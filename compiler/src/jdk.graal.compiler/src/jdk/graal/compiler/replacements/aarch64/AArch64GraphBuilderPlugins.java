/*
 * Copyright (c) 2015, 2025, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.replacements.aarch64;

import java.lang.reflect.Type;
import java.util.Arrays;

import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.core.common.Stride;
import jdk.graal.compiler.core.common.calc.Condition;
import jdk.graal.compiler.core.common.memory.BarrierType;
import jdk.graal.compiler.core.common.memory.MemoryOrderMode;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.NamedLocationIdentity;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.CopySignNode;
import jdk.graal.compiler.nodes.calc.FusedMultiplyAddNode;
import jdk.graal.compiler.nodes.calc.LeftShiftNode;
import jdk.graal.compiler.nodes.calc.MaxNode;
import jdk.graal.compiler.nodes.calc.MinNode;
import jdk.graal.compiler.nodes.calc.NarrowNode;
import jdk.graal.compiler.nodes.calc.ReinterpretNode;
import jdk.graal.compiler.nodes.calc.RoundFloatToIntegerNode;
import jdk.graal.compiler.nodes.calc.ZeroExtendNode;
import jdk.graal.compiler.nodes.extended.JavaReadNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration.Plugins;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugins;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugins.Registration;
import jdk.graal.compiler.nodes.java.ArrayLengthNode;
import jdk.graal.compiler.nodes.memory.address.IndexAddressNode;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.replacements.InvocationPluginHelper;
import jdk.graal.compiler.replacements.SnippetSubstitutionInvocationPlugin;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.StringLatin1InflateNode;
import jdk.graal.compiler.replacements.StringLatin1Snippets;
import jdk.graal.compiler.replacements.StringUTF16CompressNode;
import jdk.graal.compiler.replacements.StringUTF16Snippets;
import jdk.graal.compiler.replacements.TargetGraphBuilderPlugins;
import jdk.graal.compiler.replacements.nodes.ArrayCompareToNode;
import jdk.graal.compiler.replacements.nodes.ArrayFillNode;
import jdk.graal.compiler.replacements.nodes.ArrayIndexOfNode;
import jdk.graal.compiler.replacements.nodes.FloatToHalfFloatNode;
import jdk.graal.compiler.replacements.nodes.HalfFloatToFloatNode;
import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;

public class AArch64GraphBuilderPlugins implements TargetGraphBuilderPlugins {
    @Override
    public void registerPlugins(Plugins plugins, OptionValues options) {
        register(plugins, options);
    }

    public static void register(Plugins plugins, OptionValues options) {
        InvocationPlugins invocationPlugins = plugins.getInvocationPlugins();
        invocationPlugins.defer(new Runnable() {
            @Override
            public void run() {
                registerFloatPlugins(invocationPlugins);
                registerMathPlugins(invocationPlugins);
                registerStrictMathPlugins(invocationPlugins);
                registerArraysPlugins(invocationPlugins);

                if (GraalOptions.EmitStringSubstitutions.getValue(options)) {
                    registerStringLatin1Plugins(invocationPlugins);
                    registerStringUTF16Plugins(invocationPlugins);
                }
            }
        });
    }

    private static void registerFloatPlugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, Float.class);

        r.register(new InvocationPlugin("float16ToFloat", short.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode value) {
                b.push(JavaKind.Float, b.append(new HalfFloatToFloatNode(value)));
                return true;
            }
        });
        r.register(new InvocationPlugin("floatToFloat16", float.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode value) {
                b.push(JavaKind.Short, b.append(new FloatToHalfFloatNode(value)));
                return true;
            }
        });
    }

    private static void registerMathPlugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, Math.class);
        registerFMA(r);
        registerMinMax(r);

        r.register(new InvocationPlugin("copySign", float.class, float.class) {

            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode magnitude, ValueNode sign) {
                b.addPush(JavaKind.Float, new CopySignNode(magnitude, sign));
                return true;
            }
        });
        r.register(new InvocationPlugin("copySign", double.class, double.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode magnitude, ValueNode sign) {
                b.addPush(JavaKind.Double, new CopySignNode(magnitude, sign));
                return true;
            }
        });
        r.register(new InvocationPlugin("round", float.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode input) {
                b.addPush(JavaKind.Int, new RoundFloatToIntegerNode(input));
                return true;
            }
        });
        r.register(new InvocationPlugin("round", double.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode input) {
                b.addPush(JavaKind.Long, new RoundFloatToIntegerNode(input));
                return true;
            }
        });
    }

    private static void registerFMA(Registration r) {
        r.register(new InvocationPlugin("fma", double.class, double.class, double.class) {
            @Override
            public boolean apply(GraphBuilderContext b,
                            ResolvedJavaMethod targetMethod,
                            Receiver receiver,
                            ValueNode na,
                            ValueNode nb,
                            ValueNode nc) {
                b.push(JavaKind.Double, b.append(new FusedMultiplyAddNode(na, nb, nc)));
                return true;
            }
        });
        r.register(new InvocationPlugin("fma", float.class, float.class, float.class) {
            @Override
            public boolean apply(GraphBuilderContext b,
                            ResolvedJavaMethod targetMethod,
                            Receiver receiver,
                            ValueNode na,
                            ValueNode nb,
                            ValueNode nc) {
                b.push(JavaKind.Float, b.append(new FusedMultiplyAddNode(na, nb, nc)));
                return true;
            }
        });
    }

    private static void registerMinMax(Registration r) {
        JavaKind[] supportedKinds = {JavaKind.Float, JavaKind.Double};

        for (JavaKind kind : supportedKinds) {
            r.register(new InvocationPlugin("max", kind.toJavaClass(), kind.toJavaClass()) {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode x, ValueNode y) {
                    b.push(kind, b.append(MaxNode.create(x, y, NodeView.DEFAULT)));
                    return true;
                }
            });
            r.register(new InvocationPlugin("min", kind.toJavaClass(), kind.toJavaClass()) {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode x, ValueNode y) {
                    b.push(kind, b.append(MinNode.create(x, y, NodeView.DEFAULT)));
                    return true;
                }
            });
        }
    }

    private static void registerStrictMathPlugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, StrictMath.class);
        registerMinMax(r);
    }

    private static final class ArrayCompareToPlugin extends InvocationPlugin {
        private final Stride strideA;
        private final Stride strideB;
        private final boolean swapped;

        private ArrayCompareToPlugin(Stride strideA, Stride strideB, boolean swapped, String name, Type... argumentTypes) {
            super(name, argumentTypes);
            this.strideA = strideA;
            this.strideB = strideB;
            this.swapped = swapped;
        }

        private ArrayCompareToPlugin(Stride strideA, Stride strideB, String name, Type... argumentTypes) {
            this(strideA, strideB, false, name, argumentTypes);
        }

        @Override
        public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode arrayA, ValueNode arrayB) {
            try (InvocationPluginHelper helper = new InvocationPluginHelper(b, targetMethod)) {
                ValueNode nonNullA = b.nullCheckedValue(arrayA);
                ValueNode nonNullB = b.nullCheckedValue(arrayB);

                ValueNode lengthA = b.add(new ArrayLengthNode(nonNullA));
                ValueNode lengthB = b.add(new ArrayLengthNode(nonNullB));

                ValueNode startA = helper.arrayStart(nonNullA, JavaKind.Byte);
                ValueNode startB = helper.arrayStart(nonNullB, JavaKind.Byte);
                if (swapped) {
                    /*
                     * Swapping array arguments because intrinsic expects order to be byte[]/char[]
                     * but kind arguments stay in original order.
                     */
                    b.addPush(JavaKind.Int, new ArrayCompareToNode(startB, lengthB, startA, lengthA, strideA, strideB));
                } else {
                    b.addPush(JavaKind.Int, new ArrayCompareToNode(startA, lengthA, startB, lengthB, strideA, strideB));
                }
            }
            return true;
        }
    }

    private static void registerStringLatin1Plugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, "java.lang.StringLatin1");
        r.setAllowOverwrite(true);
        r.register(new ArrayCompareToPlugin(Stride.S1, Stride.S1, "compareTo", byte[].class, byte[].class));
        r.register(new ArrayCompareToPlugin(Stride.S1, Stride.S2, "compareToUTF16", byte[].class, byte[].class));
        r.register(new InvocationPlugin("inflate", byte[].class, int.class, byte[].class, int.class, int.class) {
            @SuppressWarnings("try")
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode src, ValueNode srcIndex, ValueNode dest, ValueNode destIndex, ValueNode len) {
                // @formatter:off
                //         if (injectBranchProbability(SLOWPATH_PROBABILITY, len < 0) ||
                //                        injectBranchProbability(SLOWPATH_PROBABILITY, srcIndex < 0) ||
                //                        injectBranchProbability(SLOWPATH_PROBABILITY, srcIndex + len |>| src.length) ||
                //                        injectBranchProbability(SLOWPATH_PROBABILITY, destIndex < 0) ||
                //                        injectBranchProbability(SLOWPATH_PROBABILITY, destIndex + len |>| dest.length >> 1)) {
                //            DeoptimizeNode.deopt(DeoptimizationAction.None, DeoptimizationReason.BoundsCheckException);
                //        }
                //
                //        // Offset calc. outside of the actual intrinsic.
                //        Pointer srcPointer = Word.objectToTrackedPointer(src).add(byteArrayBaseOffset(INJECTED)).add(srcIndex * byteArrayIndexScale(INJECTED));
                //        Pointer destPointer = Word.objectToTrackedPointer(dest).add(byteArrayBaseOffset(INJECTED)).add(destIndex * 2 * byteArrayIndexScale(INJECTED));
                //        StringLatin1InflateNode.inflate(srcPointer, destPointer, len, JavaKind.Byte);
                // @formatter:on
                try (InvocationPluginHelper helper = new InvocationPluginHelper(b, targetMethod)) {
                    helper.intrinsicRangeCheck(len, Condition.LT, ConstantNode.forInt(0));
                    helper.intrinsicArrayRangeCheck(src, srcIndex, len);
                    helper.intrinsicArrayRangeCheckScaled(dest, destIndex, len);

                    ValueNode srcPointer = helper.arrayElementPointer(src, JavaKind.Byte, srcIndex);
                    ValueNode destPointer = helper.arrayElementPointerScaled(dest, JavaKind.Char, destIndex);
                    b.add(new StringLatin1InflateNode(srcPointer, destPointer, len, JavaKind.Byte));
                }
                return true;
            }
        });
        r.register(new InvocationPlugin("inflate", byte[].class, int.class, char[].class, int.class, int.class) {
            @SuppressWarnings("try")
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode src, ValueNode srcIndex, ValueNode dest, ValueNode destIndex, ValueNode len) {
                // @formatter:off
                //         if (injectBranchProbability(SLOWPATH_PROBABILITY, len < 0) ||
                //                        injectBranchProbability(SLOWPATH_PROBABILITY, srcIndex < 0) ||
                //                        injectBranchProbability(SLOWPATH_PROBABILITY, srcIndex + len |>| src.length) ||
                //                        injectBranchProbability(SLOWPATH_PROBABILITY, destIndex < 0) ||
                //                        injectBranchProbability(SLOWPATH_PROBABILITY, destIndex + len |>| dest.length)) {
                //            DeoptimizeNode.deopt(DeoptimizationAction.None, DeoptimizationReason.BoundsCheckException);
                //        }
                //
                //        // Offset calc. outside of the actual intrinsic.
                //        Pointer srcPointer = Word.objectToTrackedPointer(src).add(byteArrayBaseOffset(INJECTED)).add(srcIndex * byteArrayIndexScale(INJECTED));
                //        Pointer destPointer = Word.objectToTrackedPointer(dest).add(charArrayBaseOffset(INJECTED)).add(destIndex * charArrayIndexScale(INJECTED));
                //        StringLatin1InflateNode.inflate(srcPointer, destPointer, len, JavaKind.Char);
                // @formatter:on
                try (InvocationPluginHelper helper = new InvocationPluginHelper(b, targetMethod)) {
                    helper.intrinsicRangeCheck(len, Condition.LT, ConstantNode.forInt(0));
                    helper.intrinsicArrayRangeCheck(src, srcIndex, len);
                    helper.intrinsicArrayRangeCheck(dest, destIndex, len);

                    ValueNode srcPointer = helper.arrayElementPointer(src, JavaKind.Byte, srcIndex);
                    ValueNode destPointer = helper.arrayElementPointer(dest, JavaKind.Char, destIndex);
                    b.add(new StringLatin1InflateNode(srcPointer, destPointer, len, JavaKind.Char));
                }
                return true;
            }
        });

        r.register(new SnippetSubstitutionInvocationPlugin<>(StringLatin1Snippets.Templates.class,
                        "indexOf", byte[].class, int.class, byte[].class, int.class, int.class) {
            @Override
            public SnippetTemplate.SnippetInfo getSnippet(StringLatin1Snippets.Templates templates) {
                return templates.indexOf;
            }
        });
        r.register(new InvocationPlugin("indexOfChar", byte[].class, int.class, int.class, int.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode value, ValueNode ch, ValueNode fromIndex, ValueNode max) {
                b.addPush(JavaKind.Int, ArrayIndexOfNode.createIndexOfSingle(b, JavaKind.Byte, Stride.S1, value, max, fromIndex, ch));
                return true;
            }
        });
    }

    private static void registerStringUTF16Plugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, "java.lang.StringUTF16");
        r.setAllowOverwrite(true);
        r.register(new ArrayCompareToPlugin(Stride.S2, Stride.S2, "compareTo", byte[].class, byte[].class));
        r.register(new ArrayCompareToPlugin(Stride.S2, Stride.S1, true, "compareToLatin1", byte[].class, byte[].class));
        r.register(new InvocationPlugin("compress", byte[].class, int.class, byte[].class, int.class, int.class) {
            @SuppressWarnings("try")
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode src, ValueNode srcIndex, ValueNode dest, ValueNode destIndex, ValueNode len) {
                // @formatter:off
                //         if (injectBranchProbability(SLOWPATH_PROBABILITY, len < 0) ||
                //                        injectBranchProbability(SLOWPATH_PROBABILITY, srcIndex < 0) ||
                //                        injectBranchProbability(SLOWPATH_PROBABILITY, srcIndex + len |>| src.length >> 1) ||
                //                        injectBranchProbability(SLOWPATH_PROBABILITY, destIndex < 0) ||
                //                        injectBranchProbability(SLOWPATH_PROBABILITY, destIndex + len |>| dest.length)) {
                //            DeoptimizeNode.deopt(DeoptimizationAction.None, DeoptimizationReason.BoundsCheckException);
                //        }
                //
                //        Pointer srcPointer = Word.objectToTrackedPointer(src).add(byteArrayBaseOffset(INJECTED)).add(srcIndex * charArrayIndexScale(INJECTED));
                //        Pointer destPointer = Word.objectToTrackedPointer(dest).add(byteArrayBaseOffset(INJECTED)).add(destIndex * byteArrayIndexScale(INJECTED));
                //        return StringUTF16CompressNode.compress(srcPointer, destPointer, len, JavaKind.Byte);
                // @formatter:on

                try (InvocationPluginHelper helper = new InvocationPluginHelper(b, targetMethod)) {
                    helper.intrinsicRangeCheck(len, Condition.LT, ConstantNode.forInt(0));
                    helper.intrinsicArrayRangeCheckScaled(src, srcIndex, len);
                    helper.intrinsicArrayRangeCheck(dest, destIndex, len);

                    ValueNode srcPointer = helper.arrayElementPointerScaled(src, JavaKind.Char, srcIndex);
                    ValueNode destPointer = helper.arrayElementPointer(dest, JavaKind.Byte, destIndex);
                    b.addPush(JavaKind.Int, new StringUTF16CompressNode(srcPointer, destPointer, len, JavaKind.Byte));
                }
                return true;
            }
        });
        r.register(new InvocationPlugin("compress", char[].class, int.class, byte[].class, int.class, int.class) {
            @SuppressWarnings("try")
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode src, ValueNode srcIndex, ValueNode dest, ValueNode destIndex, ValueNode len) {
                // @formatter:off
                //         if (injectBranchProbability(SLOWPATH_PROBABILITY, len < 0) ||
                //                        injectBranchProbability(SLOWPATH_PROBABILITY, srcIndex < 0) ||
                //                        injectBranchProbability(SLOWPATH_PROBABILITY, srcIndex + len |>| src.length) ||
                //                        injectBranchProbability(SLOWPATH_PROBABILITY, destIndex < 0) ||
                //                        injectBranchProbability(SLOWPATH_PROBABILITY, destIndex + len |>| dest.length)) {
                //            DeoptimizeNode.deopt(DeoptimizationAction.None, DeoptimizationReason.BoundsCheckException);
                //        }
                //
                //        Pointer srcPointer = Word.objectToTrackedPointer(src).add(charArrayBaseOffset(INJECTED)).add(srcIndex * charArrayIndexScale(INJECTED));
                //        Pointer destPointer = Word.objectToTrackedPointer(dest).add(byteArrayBaseOffset(INJECTED)).add(destIndex * byteArrayIndexScale(INJECTED));
                //        return StringUTF16CompressNode.compress(srcPointer, destPointer, len, JavaKind.Char);
                // @formatter:on
                try (InvocationPluginHelper helper = new InvocationPluginHelper(b, targetMethod)) {
                    helper.intrinsicRangeCheck(len, Condition.LT, ConstantNode.forInt(0));
                    helper.intrinsicArrayRangeCheck(src, srcIndex, len);
                    helper.intrinsicArrayRangeCheck(dest, destIndex, len);

                    ValueNode srcPointer = helper.arrayElementPointer(src, JavaKind.Char, srcIndex);
                    ValueNode destPointer = helper.arrayElementPointer(dest, JavaKind.Byte, destIndex);
                    b.addPush(JavaKind.Int, new StringUTF16CompressNode(srcPointer, destPointer, len, JavaKind.Char));
                }
                return true;
            }
        });

        r.register(new SnippetSubstitutionInvocationPlugin<>(StringUTF16Snippets.Templates.class,
                        "indexOfUnsafe", byte[].class, int.class, byte[].class, int.class, int.class) {
            @Override
            public SnippetTemplate.SnippetInfo getSnippet(StringUTF16Snippets.Templates templates) {
                return templates.indexOfUnsafe;
            }
        });
        r.register(new InvocationPlugin("indexOfChar", byte[].class, int.class, int.class, int.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode value, ValueNode ch, ValueNode fromIndex, ValueNode max) {
                ZeroExtendNode toChar = b.add(new ZeroExtendNode(b.add(new NarrowNode(ch, JavaKind.Char.getBitCount())), JavaKind.Int.getBitCount()));
                b.addPush(JavaKind.Int, ArrayIndexOfNode.createIndexOfSingle(b, JavaKind.Byte, Stride.S2, value, max, fromIndex, toChar));
                return true;
            }
        });
        Registration r2 = new Registration(plugins, StringUTF16Snippets.class);
        r2.register(new InvocationPlugin("getChar", byte[].class, int.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode arg1, ValueNode arg2) {
                b.addPush(JavaKind.Char, new JavaReadNode(JavaKind.Char,
                                new IndexAddressNode(arg1, new LeftShiftNode(arg2, ConstantNode.forInt(1)), JavaKind.Byte),
                                NamedLocationIdentity.getArrayLocation(JavaKind.Byte), BarrierType.NONE, MemoryOrderMode.PLAIN, false));
                return true;
            }
        });
    }

    public static class ArrayFillInvocationPlugin extends InvocationPlugin {
        private final JavaKind kind;

        public ArrayFillInvocationPlugin(JavaKind kind, Type... argumentTypes) {
            super("fill", argumentTypes);
            this.kind = kind;
        }

        @SuppressWarnings("try")
        @Override
        public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode array, ValueNode value) {
            ConstantNode arrayBaseOffset = ConstantNode.forLong(b.getMetaAccess().getArrayBaseOffset(this.kind), b.getGraph());
            ValueNode nonNullArray = b.nullCheckedValue(array, DeoptimizationAction.None);
            ValueNode arrayLength = b.add(new ArrayLengthNode(nonNullArray));
            ValueNode castedValue = value;
            if (this.kind == JavaKind.Float) {
                castedValue = ReinterpretNode.create(JavaKind.Int, value, NodeView.DEFAULT);
            } else if (this.kind == JavaKind.Double) {
                castedValue = ReinterpretNode.create(JavaKind.Long, value, NodeView.DEFAULT);
            }
            b.add(new ArrayFillNode(nonNullArray, arrayBaseOffset, arrayLength, castedValue, this.kind));
            return true;
        }
    }

    private static void registerArraysPlugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, Arrays.class);
        r.register(new ArrayFillInvocationPlugin(JavaKind.Boolean, boolean[].class, boolean.class));
        r.register(new ArrayFillInvocationPlugin(JavaKind.Byte, byte[].class, byte.class));
        r.register(new ArrayFillInvocationPlugin(JavaKind.Char, char[].class, char.class));
        r.register(new ArrayFillInvocationPlugin(JavaKind.Short, short[].class, short.class));
        r.register(new ArrayFillInvocationPlugin(JavaKind.Int, int[].class, int.class));
        r.register(new ArrayFillInvocationPlugin(JavaKind.Float, float[].class, float.class));
        r.register(new ArrayFillInvocationPlugin(JavaKind.Long, long[].class, long.class));
        r.register(new ArrayFillInvocationPlugin(JavaKind.Double, double[].class, double.class));
    }
}
