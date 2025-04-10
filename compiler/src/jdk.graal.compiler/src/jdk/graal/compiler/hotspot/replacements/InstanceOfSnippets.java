/*
 * Copyright (c) 2012, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.graal.compiler.hotspot.replacements;

import static jdk.graal.compiler.core.common.GraalOptions.TypeCheckMaxHints;
import static jdk.graal.compiler.core.common.GraalOptions.TypeCheckMinProfileHitProbability;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.OPTIMIZING_PRIMARY_SUPERS_LOCATION;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.PRIMARY_SUPERS_LOCATION;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.SECONDARY_SUPER_CACHE_LOCATION;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadHubIntrinsic;
import static jdk.graal.compiler.hotspot.replacements.HotSpotReplacementsUtil.loadHubOrNullIntrinsic;
import static jdk.graal.compiler.hotspot.replacements.TypeCheckSnippetUtils.checkSecondarySubType;
import static jdk.graal.compiler.hotspot.replacements.TypeCheckSnippetUtils.checkUnknownSubType;
import static jdk.graal.compiler.hotspot.replacements.TypeCheckSnippetUtils.createHints;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.FAST_PATH_PROBABILITY;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.LIKELY_PROBABILITY;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.NOT_FREQUENT_PROBABILITY;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.NOT_LIKELY_PROBABILITY;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.probability;
import static jdk.graal.compiler.nodes.extended.BranchProbabilityNode.unknownProbability;
import static jdk.vm.ci.meta.DeoptimizationAction.InvalidateReprofile;
import static jdk.vm.ci.meta.DeoptimizationReason.OptimizedTypeCheckViolated;

import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.api.replacements.Snippet.ConstantParameter;
import jdk.graal.compiler.api.replacements.Snippet.NonNullParameter;
import jdk.graal.compiler.api.replacements.Snippet.VarargsParameter;
import jdk.graal.compiler.core.common.type.ObjectStamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.hotspot.meta.HotSpotProviders;
import jdk.graal.compiler.hotspot.nodes.type.KlassPointerStamp;
import jdk.graal.compiler.hotspot.replacements.TypeCheckSnippetUtils.Counters;
import jdk.graal.compiler.hotspot.replacements.TypeCheckSnippetUtils.Hints;
import jdk.graal.compiler.hotspot.word.KlassPointer;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.DeoptimizeNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.PiNode;
import jdk.graal.compiler.nodes.SnippetAnchorNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.TypeCheckHints;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.extended.GuardingNode;
import jdk.graal.compiler.nodes.java.ClassIsAssignableFromNode;
import jdk.graal.compiler.nodes.java.InstanceOfDynamicNode;
import jdk.graal.compiler.nodes.java.InstanceOfNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.replacements.InstanceOfSnippetsTemplates;
import jdk.graal.compiler.replacements.SnippetCounter;
import jdk.graal.compiler.replacements.SnippetTemplate.Arguments;
import jdk.graal.compiler.replacements.SnippetTemplate.SnippetInfo;
import jdk.graal.compiler.replacements.Snippets;
import jdk.graal.compiler.replacements.nodes.ExplodeLoopNode;
import jdk.vm.ci.hotspot.HotSpotResolvedObjectType;
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaTypeProfile;
import jdk.vm.ci.meta.TriState;

/**
 * Snippets used for implementing the type test of an instanceof instruction. Since instanceof is a
 * floating node, it is lowered separately for each of its usages.
 * <p>
 * The type tests implemented are described in the paper
 * <a href="http://dl.acm.org/citation.cfm?id=583821"> Fast subtype checking in the HotSpot JVM</a>
 * by Cliff Click and John Rose, with the adaption on the secondary supers hashed lookup algorithm
 * described in JDK-8180450 (see comments in {@link TypeCheckSnippetUtils#checkSecondarySubType}).
 */
public class InstanceOfSnippets implements Snippets {

    /**
     * A test against a set of hints derived from a profile with 100% precise coverage of seen
     * types. This snippet deoptimizes on hint miss paths.
     */
    @Snippet
    public static Object instanceofWithProfile(Object object, @VarargsParameter KlassPointer[] hints, @VarargsParameter boolean[] hintIsPositive, Object trueValue, Object falseValue,
                    @ConstantParameter boolean nullSeen, @ConstantParameter Counters counters) {
        if (probability(NOT_FREQUENT_PROBABILITY, object == null)) {
            counters.isNull.inc();
            if (!nullSeen) {
                // See comment below for other deoptimization path; the
                // same reasoning applies here.
                DeoptimizeNode.deopt(InvalidateReprofile, OptimizedTypeCheckViolated);
            }
            return falseValue;
        }
        GuardingNode anchorNode = SnippetAnchorNode.anchor();
        KlassPointer objectHub = loadHubIntrinsic(PiNode.piCastNonNull(object, anchorNode));
        // if we get an exact match: succeed immediately
        ExplodeLoopNode.explodeLoop();
        for (int i = 0; i < hints.length; i++) {
            KlassPointer hintHub = hints[i];
            boolean positive = hintIsPositive[i];
            if (probability(LIKELY_PROBABILITY, hintHub.equal(objectHub))) {
                counters.hintsHit.inc();
                return unknownProbability(positive) ? trueValue : falseValue;
            }
            counters.hintsMiss.inc();
        }
        // This maybe just be a rare event but it might also indicate a phase change
        // in the application. Ideally we want to use DeoptimizationAction.None for
        // the former but the cost is too high if indeed it is the latter. As such,
        // we defensively opt for InvalidateReprofile.
        DeoptimizeNode.deopt(DeoptimizationAction.InvalidateReprofile, OptimizedTypeCheckViolated);
        return falseValue;
    }

    /**
     * A test against a final type.
     */
    @Snippet
    public static Object instanceofExact(Object object, KlassPointer exactHub, Object trueValue, Object falseValue, @ConstantParameter Counters counters) {
        KlassPointer objectHub = loadHubOrNullIntrinsic(object);
        if (probability(LIKELY_PROBABILITY, objectHub.notEqual(exactHub))) {
            counters.exactMiss.inc();
            return falseValue;
        }
        counters.exactHit.inc();
        return trueValue;
    }

    /**
     * A test against a primary type.
     */
    @Snippet
    public static Object instanceofPrimary(KlassPointer hub, Object object, @ConstantParameter int superCheckOffset, Object trueValue, Object falseValue, @ConstantParameter Counters counters) {
        if (probability(NOT_FREQUENT_PROBABILITY, object == null)) {
            counters.isNull.inc();
            return falseValue;
        }
        GuardingNode anchorNode = SnippetAnchorNode.anchor();
        KlassPointer objectHub = loadHubIntrinsic(PiNode.piCastNonNull(object, anchorNode));
        if (probability(NOT_LIKELY_PROBABILITY, objectHub.readKlassPointer(superCheckOffset, PRIMARY_SUPERS_LOCATION).notEqual(hub))) {
            counters.displayMiss.inc();
            return falseValue;
        }
        counters.displayHit.inc();
        return trueValue;
    }

    /**
     * A test against a restricted secondary type.
     */
    @Snippet(allowMissingProbabilities = true)
    public static Object instanceofSecondary(KlassPointer hub, Object object, @VarargsParameter KlassPointer[] hints, @VarargsParameter boolean[] hintIsPositive,
                    @ConstantParameter boolean isHubAbstract, Object trueValue, Object falseValue, @ConstantParameter Counters counters) {
        if (probability(NOT_FREQUENT_PROBABILITY, object == null)) {
            counters.isNull.inc();
            return falseValue;
        }
        GuardingNode anchorNode = SnippetAnchorNode.anchor();
        KlassPointer objectHub = loadHubIntrinsic(PiNode.piCastNonNull(object, anchorNode));
        // if we get an exact match: succeed immediately
        ExplodeLoopNode.explodeLoop();
        for (int i = 0; i < hints.length; i++) {
            KlassPointer hintHub = hints[i];
            boolean positive = hintIsPositive[i];
            if (probability(NOT_FREQUENT_PROBABILITY, hintHub.equal(objectHub))) {
                counters.hintsHit.inc();
                return positive ? trueValue : falseValue;
            }
        }
        counters.hintsMiss.inc();
        if (!checkSecondarySubType(hub, objectHub, isHubAbstract, counters)) {
            return falseValue;
        }
        return trueValue;
    }

    /**
     * Type test used when the type being tested against is not known at compile time.
     */
    @Snippet(allowMissingProbabilities = true)
    public static Object instanceofDynamic(KlassPointer hub, Object object, Object trueValue, Object falseValue, @ConstantParameter boolean allowNull, @ConstantParameter boolean exact,
                    @ConstantParameter Counters counters) {
        if (probability(NOT_FREQUENT_PROBABILITY, object == null)) {
            counters.isNull.inc();
            if (allowNull) {
                return trueValue;
            } else {
                return falseValue;
            }
        }
        GuardingNode anchorNode = SnippetAnchorNode.anchor();
        KlassPointer nonNullObjectHub = loadHubIntrinsic(PiNode.piCastNonNull(object, anchorNode));
        if (exact) {
            if (probability(LIKELY_PROBABILITY, nonNullObjectHub.notEqual(hub))) {
                counters.exactMiss.inc();
                return falseValue;
            }
            counters.exactHit.inc();
            return trueValue;
        }
        // The hub of a primitive type can be null => always return false in this case.
        if (probability(FAST_PATH_PROBABILITY, !hub.isNull())) {
            if (checkUnknownSubType(hub, nonNullObjectHub, counters)) {
                return trueValue;
            }
        }
        return falseValue;
    }

    @Snippet(allowMissingProbabilities = true)
    public static Object isAssignableFrom(@NonNullParameter Class<?> thisClassNonNull, @NonNullParameter Class<?> otherClassNonNull, Object trueValue, Object falseValue,
                    @ConstantParameter Counters counters) {
        if (probability(NOT_LIKELY_PROBABILITY, thisClassNonNull == otherClassNonNull)) {
            return trueValue;
        }

        KlassPointer thisHub = ClassGetHubNode.readClass(thisClassNonNull);
        KlassPointer otherHub = ClassGetHubNode.readClass(otherClassNonNull);
        if (probability(FAST_PATH_PROBABILITY, !thisHub.isNull())) {
            if (probability(FAST_PATH_PROBABILITY, !otherHub.isNull())) {
                GuardingNode guardNonNull = SnippetAnchorNode.anchor();
                KlassPointer nonNullOtherHub = ClassGetHubNode.piCastNonNull(otherHub, guardNonNull);
                if (checkUnknownSubType(thisHub, nonNullOtherHub, counters)) {
                    return trueValue;
                }
            }
        }

        // If either hub is null, one of them is a primitive type and given that the class is not
        // equal, return false.
        return falseValue;
    }

    public static class Templates extends InstanceOfSnippetsTemplates {

        private final SnippetInfo instanceofWithProfile;
        private final SnippetInfo instanceofExact;
        private final SnippetInfo instanceofPrimary;
        private final SnippetInfo instanceofSecondary;
        private final SnippetInfo instanceofDynamic;
        private final SnippetInfo isAssignableFrom;

        private final Counters counters;

        @SuppressWarnings("this-escape")
        public Templates(OptionValues options, SnippetCounter.Group.Factory factory, HotSpotProviders providers) {
            super(options, providers);

            this.instanceofWithProfile = snippet(providers, InstanceOfSnippets.class, "instanceofWithProfile");
            this.instanceofExact = snippet(providers, InstanceOfSnippets.class, "instanceofExact");
            this.instanceofPrimary = snippet(providers, InstanceOfSnippets.class, "instanceofPrimary", PRIMARY_SUPERS_LOCATION);
            this.instanceofSecondary = snippet(providers, InstanceOfSnippets.class, "instanceofSecondary", SECONDARY_SUPER_CACHE_LOCATION);
            this.instanceofDynamic = snippet(providers, InstanceOfSnippets.class, "instanceofDynamic", OPTIMIZING_PRIMARY_SUPERS_LOCATION, SECONDARY_SUPER_CACHE_LOCATION);
            this.isAssignableFrom = snippet(providers, InstanceOfSnippets.class, "isAssignableFrom", OPTIMIZING_PRIMARY_SUPERS_LOCATION, SECONDARY_SUPER_CACHE_LOCATION);

            this.counters = new Counters(factory);
        }

        @Override
        protected Arguments makeArguments(InstanceOfUsageReplacer replacer, LoweringTool tool) {
            if (replacer.instanceOf instanceof InstanceOfNode) {
                InstanceOfNode instanceOf = (InstanceOfNode) replacer.instanceOf;
                ValueNode object = instanceOf.getValue();
                Assumptions assumptions = instanceOf.graph().getAssumptions();

                OptionValues localOptions = instanceOf.getOptions();
                JavaTypeProfile profile = instanceOf.profile();
                TypeCheckHints hintInfo = new TypeCheckHints(instanceOf.type(), profile, assumptions, TypeCheckMinProfileHitProbability.getValue(localOptions),
                                TypeCheckMaxHints.getValue(localOptions));
                final HotSpotResolvedObjectType type = (HotSpotResolvedObjectType) instanceOf.type().getType();
                ConstantNode hub = ConstantNode.forConstant(KlassPointerStamp.klassNonNull(), type.klass(), tool.getMetaAccess(), instanceOf.graph());

                Arguments args;

                StructuredGraph graph = instanceOf.graph();
                if (hintInfo.hintHitProbability >= 1.0 && hintInfo.exact == null) {
                    Hints hints = createHints(hintInfo, tool.getMetaAccess(), false, graph);
                    args = new Arguments(instanceofWithProfile, graph.getGuardsStage(), tool.getLoweringStage());
                    args.add("object", object);
                    args.addVarargs("hints", KlassPointer.class, KlassPointerStamp.klassNonNull(), hints.hubs);
                    args.addVarargs("hintIsPositive", boolean.class, StampFactory.forKind(JavaKind.Boolean), hints.isPositive);
                } else if (hintInfo.exact != null) {
                    args = new Arguments(instanceofExact, graph.getGuardsStage(), tool.getLoweringStage());
                    args.add("object", object);
                    args.add("exactHub", ConstantNode.forConstant(KlassPointerStamp.klassNonNull(), ((HotSpotResolvedObjectType) hintInfo.exact).klass(), tool.getMetaAccess(), graph));
                } else if (type.isPrimaryType()) {
                    args = new Arguments(instanceofPrimary, graph.getGuardsStage(), tool.getLoweringStage());
                    args.add("hub", hub);
                    args.add("object", object);
                    args.add("superCheckOffset", type.superCheckOffset());
                } else {
                    Hints hints = createHints(hintInfo, tool.getMetaAccess(), false, graph);
                    args = new Arguments(instanceofSecondary, graph.getGuardsStage(), tool.getLoweringStage());
                    args.add("hub", hub);
                    args.add("object", object);
                    args.addVarargs("hints", KlassPointer.class, KlassPointerStamp.klassNonNull(), hints.hubs);
                    args.addVarargs("hintIsPositive", boolean.class, StampFactory.forKind(JavaKind.Boolean), hints.isPositive);
                    args.add("isHubAbstract", !type.isArray() && (type.isAbstract() || type.isInterface()));
                }
                args.add("trueValue", replacer.trueValue);
                args.add("falseValue", replacer.falseValue);
                if (hintInfo.hintHitProbability >= 1.0 && hintInfo.exact == null) {
                    args.add("nullSeen", hintInfo.profile.getNullSeen() != TriState.FALSE);
                }
                args.add("counters", counters);
                return args;
            } else if (replacer.instanceOf instanceof InstanceOfDynamicNode) {
                InstanceOfDynamicNode instanceOf = (InstanceOfDynamicNode) replacer.instanceOf;
                ValueNode object = instanceOf.getObject();

                Arguments args = new Arguments(instanceofDynamic, instanceOf.graph().getGuardsStage(), tool.getLoweringStage());
                args.add("hub", instanceOf.getMirrorOrHub());
                args.add("object", object);
                args.add("trueValue", replacer.trueValue);
                args.add("falseValue", replacer.falseValue);
                args.add("allowNull", instanceOf.allowsNull());
                args.add("exact", instanceOf.isExact());
                args.add("counters", counters);
                return args;
            } else if (replacer.instanceOf instanceof ClassIsAssignableFromNode) {
                ClassIsAssignableFromNode isAssignable = (ClassIsAssignableFromNode) replacer.instanceOf;
                Arguments args = new Arguments(isAssignableFrom, isAssignable.graph().getGuardsStage(), tool.getLoweringStage());
                assert ((ObjectStamp) isAssignable.getThisClass().stamp(NodeView.DEFAULT)).nonNull();
                assert ((ObjectStamp) isAssignable.getOtherClass().stamp(NodeView.DEFAULT)).nonNull();
                args.add("thisClassNonNull", isAssignable.getThisClass());
                args.add("otherClassNonNull", isAssignable.getOtherClass());
                args.add("trueValue", replacer.trueValue);
                args.add("falseValue", replacer.falseValue);
                args.add("counters", counters);
                return args;
            } else {
                throw GraalError.shouldNotReachHereUnexpectedValue(replacer); // ExcludeFromJacocoGeneratedReport
            }
        }
    }
}
