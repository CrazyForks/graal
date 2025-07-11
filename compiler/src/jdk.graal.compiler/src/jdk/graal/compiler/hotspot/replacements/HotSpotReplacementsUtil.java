/*
 * Copyright (c) 2012, 2025, Oracle and/or its affiliates. All rights reserved.
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

import static jdk.graal.compiler.hotspot.GraalHotSpotVMConfig.INJECTED_VMCONFIG;
import static jdk.graal.compiler.hotspot.meta.HotSpotForeignCallsProviderImpl.VERIFY_OOP;
import static jdk.graal.compiler.nodes.CompressionNode.CompressionOp.Compress;

import java.lang.ref.Reference;

import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.api.replacements.Fold;
import jdk.graal.compiler.api.replacements.Fold.InjectedParameter;
import jdk.graal.compiler.core.common.CompressEncoding;
import jdk.graal.compiler.core.common.SuppressFBWarnings;
import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.core.common.type.IntegerStamp;
import jdk.graal.compiler.core.common.type.ObjectStamp;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Node.ConstantNodeParameter;
import jdk.graal.compiler.graph.Node.NodeIntrinsic;
import jdk.graal.compiler.hotspot.GraalHotSpotVMConfig;
import jdk.graal.compiler.hotspot.meta.HotSpotLoweringProvider;
import jdk.graal.compiler.hotspot.meta.HotSpotProviders;
import jdk.graal.compiler.hotspot.nodes.HotSpotCompressionNode;
import jdk.graal.compiler.hotspot.nodes.type.KlassPointerStamp;
import jdk.graal.compiler.hotspot.word.KlassPointer;
import jdk.graal.compiler.hotspot.word.PointerCastNode;
import jdk.graal.compiler.nodes.CanonicalizableLocation;
import jdk.graal.compiler.nodes.CompressionNode;
import jdk.graal.compiler.nodes.CompressionNode.CompressionOp;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.NamedLocationIdentity;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.LeftShiftNode;
import jdk.graal.compiler.nodes.calc.ZeroExtendNode;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.nodes.extended.LoadHubNode;
import jdk.graal.compiler.nodes.extended.LoadHubOrNullNode;
import jdk.graal.compiler.nodes.extended.RawLoadNode;
import jdk.graal.compiler.nodes.extended.StoreHubNode;
import jdk.graal.compiler.nodes.memory.AddressableMemoryAccess;
import jdk.graal.compiler.nodes.memory.ReadNode;
import jdk.graal.compiler.nodes.memory.address.AddressNode;
import jdk.graal.compiler.nodes.memory.address.OffsetAddressNode;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.replacements.ReplacementsUtil;
import jdk.graal.compiler.replacements.nodes.ReadRegisterNode;
import jdk.graal.compiler.word.Word;
import jdk.vm.ci.code.CodeUtil;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime;
import jdk.vm.ci.hotspot.HotSpotMetaspaceConstant;
import jdk.vm.ci.hotspot.HotSpotResolvedObjectType;
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.Assumptions.AssumptionResult;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.UnresolvedJavaType;

//JaCoCo Exclude

/**
 * A collection of methods used in HotSpot snippets, substitutions and stubs.
 */
public class HotSpotReplacementsUtil {

    /**
     * Base class for location specific read optimizations. Many of these optimizations are normally
     * performed on high level nodes before lowering but opportunities can arise once they have been
     * lowered into reads. By examining the values involved in those reads it may be possible to
     * infer exact types.
     */
    abstract static class HotSpotOptimizingLocationIdentity extends NamedLocationIdentity implements CanonicalizableLocation {

        HotSpotOptimizingLocationIdentity(String name) {
            this(name, true);
        }

        HotSpotOptimizingLocationIdentity(String name, boolean immutable) {
            super(name, immutable);
        }

        @Override
        public abstract ValueNode canonicalizeRead(ValueNode read, ValueNode object, ValueNode location, NodeView view, CoreProviders tool);

        protected ValueNode findReadHub(ValueNode object) {
            ValueNode base = object;
            if (base instanceof CompressionNode) {
                base = ((CompressionNode) base).getValue();
            }
            if (base instanceof AddressableMemoryAccess) {
                AddressableMemoryAccess access = (AddressableMemoryAccess) base;
                if (access.getLocationIdentity().equals(HUB_LOCATION) || access.getLocationIdentity().equals(COMPRESSED_HUB_LOCATION)) {
                    AddressNode address = access.getAddress();
                    if (address instanceof OffsetAddressNode) {
                        OffsetAddressNode offset = (OffsetAddressNode) address;
                        return offset.getBase();
                    }
                }
            } else if (base instanceof LoadHubNode) {
                LoadHubNode loadhub = (LoadHubNode) base;
                return loadhub.getValue();
            }
            return null;
        }

        /**
         * Fold reads that convert from Class -> Hub -> Class or vice versa.
         *
         * @param read
         * @param object
         * @param otherLocation
         * @return an earlier read or the original {@code read}
         */
        protected static ValueNode foldIndirection(ValueNode read, ValueNode object, LocationIdentity otherLocation) {
            if (object instanceof AddressableMemoryAccess) {
                AddressableMemoryAccess access = (AddressableMemoryAccess) object;
                if (access.getLocationIdentity().equals(otherLocation)) {
                    AddressNode address = access.getAddress();
                    if (address instanceof OffsetAddressNode) {
                        OffsetAddressNode offset = (OffsetAddressNode) address;
                        assert offset.getBase().stamp(NodeView.DEFAULT).isCompatible(read.stamp(NodeView.DEFAULT));
                        return offset.getBase();
                    }
                }
            }
            return read;
        }
    }

    public static ResolvedJavaType getType(ResolvedJavaType accessingClass, String typeName) {
        try {
            return UnresolvedJavaType.create(typeName).resolve(accessingClass);
        } catch (LinkageError e) {
            throw new GraalError(e);
        }
    }

    @Fold
    public static int getFieldOffset(ResolvedJavaType type, String fieldName) {
        return getField(type, fieldName).getOffset();
    }

    public static ResolvedJavaField getField(ResolvedJavaType type, String fieldName) {
        for (ResolvedJavaField field : type.getInstanceFields(true)) {
            if (field.getName().equals(fieldName)) {
                return field;
            }
        }
        throw new GraalError("missing field " + fieldName + " in type " + type);
    }

    public static HotSpotJVMCIRuntime runtime() {
        return HotSpotJVMCIRuntime.runtime();
    }

    @Fold
    public static int getHeapWordSize(@InjectedParameter GraalHotSpotVMConfig injectedVMConfig) {
        return injectedVMConfig.heapWordSize;
    }

    @Fold
    public static int klassLayoutHelperNeutralValue(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.klassLayoutHelperNeutralValue;
    }

    @Fold
    public static boolean useTLAB(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.useTLAB;
    }

    @Fold
    public static boolean useG1GC(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.useG1GC();
    }

    @Fold
    public static boolean useSerialGC(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.useSerialGC();
    }

    /**
     * @see GraalHotSpotVMConfig#doingUnsafeAccessOffset
     */
    @Fold
    public static int doingUnsafeAccessOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.doingUnsafeAccessOffset;
    }

    public static final LocationIdentity EXCEPTION_OOP_LOCATION = NamedLocationIdentity.mutable("ExceptionOop");

    /**
     * @see GraalHotSpotVMConfig#threadExceptionOopOffset
     */
    @Fold
    public static int threadExceptionOopOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.threadExceptionOopOffset;
    }

    public static final LocationIdentity EXCEPTION_PC_LOCATION = NamedLocationIdentity.mutable("ExceptionPc");

    @Fold
    public static int threadExceptionPcOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.threadExceptionPcOffset;
    }

    public static final LocationIdentity TLAB_TOP_LOCATION = new HotSpotOptimizingLocationIdentity("TlabTop", false) {
        @Override
        public ValueNode canonicalizeRead(ValueNode read, ValueNode object, ValueNode location, NodeView view, CoreProviders tool) {
            // The TLAB top is always aligned to -XX:ObjectAlignment, reflect this in its stamp.
            if (read instanceof ReadNode readNode) {
                IntegerStamp readStamp = (IntegerStamp) readNode.stamp(view);
                GraalHotSpotVMConfig config = ((HotSpotProviders) tool.getReplacements().getProviders()).getConfig();
                long alignmentMask = -config.objectAlignment;
                IntegerStamp alignedStamp = IntegerStamp.stampForMask(readStamp.getBits(), readStamp.mustBeSet(), alignmentMask);
                readNode.setStamp(readStamp.join(alignedStamp));
            }
            return read;
        }
    };

    @Fold
    public static int threadTlabTopOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.threadTlabTopOffset();
    }

    public static final LocationIdentity TLAB_END_LOCATION = NamedLocationIdentity.mutable("TlabEnd");

    @Fold
    static int threadTlabEndOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.threadTlabEndOffset();
    }

    public static final LocationIdentity PENDING_EXCEPTION_LOCATION = NamedLocationIdentity.mutable("PendingException");

    /**
     * @see GraalHotSpotVMConfig#pendingExceptionOffset
     */
    @Fold
    static int threadPendingExceptionOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.pendingExceptionOffset;
    }

    /**
     * @see GraalHotSpotVMConfig#threadExceptionOopOffset
     */
    public static Object readExceptionOop(Word thread) {
        return thread.readObject(threadExceptionOopOffset(INJECTED_VMCONFIG), EXCEPTION_OOP_LOCATION);
    }

    public static Word readExceptionPc(Word thread) {
        return thread.readWord(threadExceptionPcOffset(INJECTED_VMCONFIG), EXCEPTION_PC_LOCATION);
    }

    /**
     * @see GraalHotSpotVMConfig#threadExceptionOopOffset
     */
    public static void writeExceptionOop(Word thread, Object value) {
        thread.writeObject(threadExceptionOopOffset(INJECTED_VMCONFIG), value, EXCEPTION_OOP_LOCATION);
    }

    public static void writeExceptionPc(Word thread, Word value) {
        thread.writeWord(threadExceptionPcOffset(INJECTED_VMCONFIG), value, EXCEPTION_PC_LOCATION);
    }

    public static Word readTlabTop(Word thread) {
        return thread.readWord(threadTlabTopOffset(INJECTED_VMCONFIG), TLAB_TOP_LOCATION);
    }

    public static Word readTlabEnd(Word thread) {
        return thread.readWord(threadTlabEndOffset(INJECTED_VMCONFIG), TLAB_END_LOCATION);
    }

    public static void writeTlabTop(Word thread, Word top) {
        thread.writeWord(threadTlabTopOffset(INJECTED_VMCONFIG), top, TLAB_TOP_LOCATION);
    }

    /**
     * Clears the pending exception for the given thread.
     *
     * @return the pending exception, or null if there was none
     */
    @SuppressFBWarnings(value = "NP_NULL_PARAM_DEREF_NONVIRTUAL", justification = "foldable method parameters are injected")
    public static Object clearPendingException(Word thread) {
        Object result = thread.readObject(threadPendingExceptionOffset(INJECTED_VMCONFIG), PENDING_EXCEPTION_LOCATION);
        thread.writeObject(threadPendingExceptionOffset(INJECTED_VMCONFIG), null, PENDING_EXCEPTION_LOCATION);
        return result;
    }

    /**
     * Gets the pending exception for the given thread.
     *
     * @return the pending exception, or null if there was none
     */
    @SuppressFBWarnings(value = "NP_NULL_PARAM_DEREF_NONVIRTUAL", justification = "foldable method parameters are injected")
    public static Object getPendingException(Word thread) {
        return thread.readObject(threadPendingExceptionOffset(INJECTED_VMCONFIG), PENDING_EXCEPTION_LOCATION);
    }

    /**
     * The location identity for the {@code JavaThread} field containing the reference to the
     * current thread. As far as Java code without virtual threads is concerned this can be
     * considered immutable: it is set just after the JavaThread is created, before it is published.
     * After that, it is never changed. In the presence of virtual threads from JDK 19 onwards, this
     * value can change when a virtual thread is unmounted and then mounted again.
     */
    public static final LocationIdentity JAVA_THREAD_CURRENT_THREAD_OBJECT_LOCATION = NamedLocationIdentity.mutable("JavaThread::_vthread");

    public static final LocationIdentity JAVA_THREAD_CARRIER_THREAD_OBJECT_LOCATION = NamedLocationIdentity.mutable("JavaThread::_threadObj");

    public static final LocationIdentity JAVA_THREAD_MONITOR_OWNER_ID_LOCATION = NamedLocationIdentity.mutable("JavaThread::_monitor_owner_id");

    public static final LocationIdentity JAVA_THREAD_HOLD_MONITOR_COUNT_LOCATION = NamedLocationIdentity.mutable("JavaThread::_held_monitor_count");

    public static final LocationIdentity JAVA_THREAD_SCOPED_VALUE_CACHE_LOCATION = NamedLocationIdentity.immutable("JavaThread::_scopedValueCache");

    public static final LocationIdentity JAVA_THREAD_LOCK_STACK_TOP_LOCATION = NamedLocationIdentity.mutable("LockStack::_top");

    public static final LocationIdentity JAVA_THREAD_LOCK_STACK_LOCATION = NamedLocationIdentity.mutable("JavaThread::_lock_stack");

    public static final LocationIdentity JAVA_THREAD_OM_CACHE_LOCATION = NamedLocationIdentity.mutable("JavaThread::_om_cache");

    public static final LocationIdentity JAVA_THREAD_UNLOCKED_INFLATED_MONITOR_LOCATION = NamedLocationIdentity.mutable("JavaThread::_unlocked_inflated_monitor");

    @Fold
    public static JavaKind getWordKind() {
        return runtime().getHostJVMCIBackend().getCodeCache().getTarget().wordJavaKind;
    }

    @Fold
    public static int wordSize() {
        return runtime().getHostJVMCIBackend().getCodeCache().getTarget().wordSize;
    }

    public static final LocationIdentity PROTOTYPE_MARK_WORD_LOCATION = NamedLocationIdentity.mutable("PrototypeMarkWord");

    public static final LocationIdentity KLASS_ACCESS_FLAGS_LOCATION = NamedLocationIdentity.immutable("Klass::_access_flags");
    public static final LocationIdentity KLASS_MISC_FLAGS_LOCATION = NamedLocationIdentity.immutable("Klass::_misc_flags");

    @Fold
    public static int klassMiscFlagsOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.klassMiscFlagsOffset;
    }

    @Fold
    public static int jvmAccHasFinalizer(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.jvmAccHasFinalizer;
    }

    public static final LocationIdentity KLASS_LAYOUT_HELPER_LOCATION = new HotSpotOptimizingLocationIdentity("Klass::_layout_helper") {
        @Override
        public ValueNode canonicalizeRead(ValueNode read, ValueNode object, ValueNode location, NodeView view, CoreProviders tool) {
            ValueNode javaObject = findReadHub(object);
            if (javaObject != null) {
                if (javaObject.stamp(NodeView.DEFAULT) instanceof ObjectStamp) {
                    ObjectStamp stamp = (ObjectStamp) javaObject.stamp(NodeView.DEFAULT);
                    HotSpotResolvedObjectType type = (HotSpotResolvedObjectType) stamp.javaType(tool.getMetaAccess());
                    if (type.isArray() && !type.getComponentType().isPrimitive()) {
                        int layout = type.layoutHelper();
                        return ConstantNode.forInt(layout);
                    }
                }
            }
            return read;
        }
    };

    @Fold
    public static int allocatePrefetchStyle(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.allocatePrefetchStyle;
    }

    @Fold
    public static int allocatePrefetchLines(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.allocatePrefetchLines;
    }

    @Fold
    public static int allocatePrefetchDistance(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.allocatePrefetchDistance;
    }

    @Fold
    public static int allocateInstancePrefetchLines(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.allocateInstancePrefetchLines;
    }

    @Fold
    public static int allocatePrefetchStepSize(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.allocatePrefetchStepSize;
    }

    @NodeIntrinsic(value = KlassLayoutHelperNode.class)
    public static native int readLayoutHelper(KlassPointer object);

    /**
     * Checks if class {@code klass} is an array.
     *
     * See: Klass::layout_helper_is_array
     *
     * @param klassNonNull the class to be checked
     * @return true if klassNonNull is an array, false otherwise
     */
    public static boolean klassIsArray(KlassPointer klassNonNull) {
        /*
         * The less-than check only works if both values are ints. We use local variables to make
         * sure these are still ints and haven't changed.
         */
        final int layoutHelper = readLayoutHelper(klassNonNull);
        final int layoutHelperNeutralValue = klassLayoutHelperNeutralValue(INJECTED_VMCONFIG);
        return layoutHelper < layoutHelperNeutralValue;
    }

    public static final LocationIdentity ARRAY_KLASS_COMPONENT_MIRROR = NamedLocationIdentity.immutable("ArrayKlass::_component_mirror");

    public static final LocationIdentity KLASS_SUPER_KLASS_LOCATION = NamedLocationIdentity.immutable("Klass::_super");

    public static final LocationIdentity MARK_WORD_LOCATION = NamedLocationIdentity.mutable("MarkWord");

    @Fold
    public static int markOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.markOffset;
    }

    public static final LocationIdentity HUB_WRITE_LOCATION = NamedLocationIdentity.mutable("Hub:write");

    public static final LocationIdentity HUB_LOCATION = new HotSpotOptimizingLocationIdentity("Hub") {
        @Override
        public ValueNode canonicalizeRead(ValueNode read, ValueNode object, ValueNode location, NodeView view, CoreProviders tool) {
            TypeReference constantType = StampTool.typeReferenceOrNull(object);
            if (constantType != null && constantType.isExact()) {
                return ConstantNode.forConstant(read.stamp(NodeView.DEFAULT), tool.getConstantReflection().asObjectHub(constantType.getType()), tool.getMetaAccess());
            }
            return read;
        }
    };

    public static final LocationIdentity COMPRESSED_HUB_LOCATION = new HotSpotOptimizingLocationIdentity("CompressedHub") {
        @Override
        public ValueNode canonicalizeRead(ValueNode read, ValueNode object, ValueNode location, NodeView view, CoreProviders tool) {
            TypeReference constantType = StampTool.typeReferenceOrNull(object);
            if (constantType != null && constantType.isExact()) {
                return ConstantNode.forConstant(read.stamp(NodeView.DEFAULT), ((HotSpotMetaspaceConstant) tool.getConstantReflection().asObjectHub(constantType.getType())).compress(),
                                tool.getMetaAccess());
            }
            return read;
        }
    };

    public static final LocationIdentity COMPACT_HUB_LOCATION = new HotSpotOptimizingLocationIdentity("CompactHub") {
        @Override
        public ValueNode canonicalizeRead(ValueNode read, ValueNode object, ValueNode location, NodeView view, CoreProviders tool) {
            TypeReference constantType = StampTool.typeReferenceOrNull(object);
            if (constantType != null && constantType.isExact()) {
                GraalHotSpotVMConfig config = ((HotSpotProviders) tool.getReplacements().getProviders()).getConfig();
                KlassPointerStamp hubStamp = KlassPointerStamp.klassNonNull().compressed(config.getKlassEncoding());
                ConstantNode compressedHub = ConstantNode.forConstant(hubStamp, ((HotSpotMetaspaceConstant) tool.getConstantReflection().asObjectHub(constantType.getType())).compress(),
                                tool.getMetaAccess());
                ValueNode rawCompressedHub = PointerCastNode.create(IntegerStamp.create(32, 0, CodeUtil.mask(64 - config.markWordKlassShift)), compressedHub);
                ValueNode rawCompressedHubWordSize = ZeroExtendNode.create(rawCompressedHub, 32, 64, NodeView.DEFAULT);
                return LeftShiftNode.create(rawCompressedHubWordSize, ConstantNode.forInt(config.markWordKlassShift), NodeView.DEFAULT);
            }
            return read;
        }
    };

    @Fold
    public static boolean useCompactObjectHeaders(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.useCompactObjectHeaders;
    }

    @Fold
    public static int markWordKlassShift(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.markWordKlassShift;
    }

    @NodeIntrinsic(HotSpotCompressionNode.class)
    private static native KlassPointer compress(@ConstantNodeParameter CompressionOp op, KlassPointer hub, @ConstantNodeParameter CompressEncoding encoding);

    @Fold
    static int hubOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.hubOffset;
    }

    @Fold
    static CompressEncoding klassEncoding(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.getKlassEncoding();
    }

    public static void initializeObjectHeader(Word memory, Word markWord, KlassPointer hub) {
        if (useCompactObjectHeaders(INJECTED_VMCONFIG)) {
            Word compressedHub = Word.unsigned(compress(Compress, hub, klassEncoding(INJECTED_VMCONFIG)).asInt());
            Word hubInPlace = compressedHub.shiftLeft(markWordKlassShift(INJECTED_VMCONFIG));
            Word newMarkWord = markWord.or(hubInPlace);
            memory.writeWord(markOffset(INJECTED_VMCONFIG), newMarkWord, MARK_WORD_LOCATION);
        } else {
            memory.writeWord(markOffset(INJECTED_VMCONFIG), markWord, MARK_WORD_LOCATION);
            StoreHubNode.write(memory, hub);
        }
    }

    @Fold
    public static boolean useObjectMonitorTable(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.useObjectMonitorTable;
    }

    @Fold
    public static int unlockedValue(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.unlockedValue;
    }

    @Fold
    public static int monitorValue(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.monitorValue;
    }

    @Fold
    public static int objectMonitorOwnerOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.objectMonitorOwner;
    }

    @Fold
    public static int objectMonitorRecursionsOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.objectMonitorRecursions;
    }

    @Fold
    public static int objectMonitorEntryListOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.objectMonitorEntryList;
    }

    @Fold
    public static int objectMonitorSuccOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.objectMonitorSucc;
    }

    /**
     * Mask for a biasable, locked or unlocked mark word. It is the least significant 3 bits prior
     * to Java 18 (1 bit for biased locking and 2 bits for stack locking or heavy locking), and 2
     * bits afterwards due to elimination of the biased locking.
     */
    @Fold
    public static long markWordLockMaskInPlace(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.markWordLockMaskInPlace;
    }

    @Fold
    public static int metaspaceArrayLengthOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.metaspaceArrayLengthOffset;
    }

    @Fold
    public static int metaspaceArrayBaseOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.metaspaceArrayBaseOffset;
    }

    @Fold
    public static int arrayLengthOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.arrayLengthOffsetInBytes;
    }

    @Fold
    public static boolean verifyBeforeOrAfterGC(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.verifyBeforeGC || config.verifyAfterGC;
    }

    /**
     * Idiom for making {@link GraalHotSpotVMConfig} a constant.
     */
    @Fold
    public static int objectAlignment(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.objectAlignment;
    }

    @Fold
    public static int instanceHeaderSize(@InjectedParameter GraalHotSpotVMConfig config) {
        if (config.useCompactObjectHeaders) {
            return wordSize();
        }
        return config.useCompressedClassPointers ? (2 * wordSize()) - 4 : 2 * wordSize();
    }

    @Fold
    public static boolean supportsG1LowLatencyBarriers(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.g1LowLatencyPostWriteBarrierSupport;
    }

    @Fold
    public static byte cleanCardValue(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.cleanCardValue;
    }

    @Fold
    public static byte dirtyCardValue(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.dirtyCardValue;
    }

    @Fold
    public static byte g1YoungCardValue(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.g1YoungCardValue;
    }

    @Fold
    public static int cardTableShift(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.cardtableShift;
    }

    @Fold
    public static long cardTableStart(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.cardtableStartAddress;
    }

    @Fold
    public static int g1CardTableBaseOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.g1CardTableBaseOffset;
    }

    @Fold
    public static int g1CardQueueIndexOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.g1CardQueueIndexOffset;
    }

    @Fold
    public static int g1CardQueueBufferOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.g1CardQueueBufferOffset;
    }

    @Fold
    public static int logOfHeapRegionGrainBytes(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.logOfHRGrainBytes;
    }

    @Fold
    public static int g1SATBQueueMarkingActiveOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.g1SATBQueueMarkingActiveOffset;
    }

    @Fold
    public static int g1SATBQueueIndexOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.g1SATBQueueIndexOffset;
    }

    @Fold
    public static int g1SATBQueueBufferOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.g1SATBQueueBufferOffset;
    }

    @Fold
    public static boolean useCondCardMark(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.useCondCardMark;
    }

    public static final LocationIdentity KLASS_SUPER_CHECK_OFFSET_LOCATION = NamedLocationIdentity.immutable("Klass::_super_check_offset");

    @Fold
    public static boolean useSecondarySupersCache(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.useSecondarySupersCache;
    }

    @Fold
    public static boolean useSecondarySupersTable(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.useSecondarySupersTable;
    }

    @Fold
    public static int superCheckOffsetOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.superCheckOffsetOffset;
    }

    public static final LocationIdentity SECONDARY_SUPER_CACHE_LOCATION = NamedLocationIdentity.mutable("SecondarySuperCache");

    @Fold
    public static int secondarySuperCacheOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.secondarySuperCacheOffset;
    }

    public static final LocationIdentity SECONDARY_SUPERS_LOCATION = NamedLocationIdentity.immutable("SecondarySupers");

    public static final LocationIdentity KLASS_HASH_SLOT_LOCATION = NamedLocationIdentity.immutable("Klass::_hash_slot");

    public static final LocationIdentity KLASS_BITMAP_LOCATION = NamedLocationIdentity.immutable("Klass::_bitmap");

    @Fold
    public static int klassHashSlotOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.klassHashSlotOffset;
    }

    @Fold
    public static int klassBitmapOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.klassBitmapOffset;
    }

    @Fold
    public static int secondarySupersOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.secondarySupersOffset;
    }

    public static final LocationIdentity BASICLOCK_METADATA_LOCATION = NamedLocationIdentity.mutable("BasicLock::_metadata");

    public static final LocationIdentity OBJECT_MONITOR_OWNER_LOCATION = NamedLocationIdentity.mutable("ObjectMonitor::_owner");

    public static final LocationIdentity OBJECT_MONITOR_RECURSION_LOCATION = NamedLocationIdentity.mutable("ObjectMonitor::_recursions");

    public static final LocationIdentity OBJECT_MONITOR_CXQ_LOCATION = NamedLocationIdentity.mutable("ObjectMonitor::_cxq");

    public static final LocationIdentity OBJECT_MONITOR_ENTRY_LIST_LOCATION = NamedLocationIdentity.mutable("ObjectMonitor::_EntryList");

    public static final LocationIdentity OBJECT_MONITOR_SUCC_LOCATION = NamedLocationIdentity.mutable("ObjectMonitor::_succ");

    public static final LocationIdentity OBJECT_MONITOR_STACK_LOCKER_LOCATION = NamedLocationIdentity.mutable("ObjectMonitor::_stack_locker");

    @Fold
    public static int lockMetadataOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.basicLockMetadataOffset;
    }

    @Fold
    static int javaThreadMonitorOwnerIDOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.javaThreadMonitorOwnerIDOffset;
    }

    @Fold
    static int javaThreadLockStackTopOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.threadLockStackOffset + config.lockStackTopOffset;
    }

    @Fold
    static int javaThreadLockStackEndOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.lockStackEndOffset;
    }

    @Fold
    static int javaThreadOomCacheOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.threadOmCacheOffset;
    }

    @Fold
    public static int javaThreadUnlockedInflatedMonitorOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.threadUnlockedInflatedMonitorOffset;
    }

    @Fold
    static int omCacheOopToOopDifference(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.omCacheOopToOopDifference;
    }

    @Fold
    static int omCacheOopToMonitorDifference(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.omCacheOopToMonitorDifference;
    }

    @Fold
    static boolean isCAssertEnabled(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.cAssertions;
    }

    @Fold
    public static boolean diagnoseSyncOnValueBasedClasses(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.diagnoseSyncOnValueBasedClasses != 0 && config.jvmAccIsValueBasedClass != 0;
    }

    @Fold
    public static int jvmAccIsValueBasedClass(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.jvmAccIsValueBasedClass;
    }

    @Fold
    public static long defaultPrototypeMarkWord(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.markWordNoHashInPlace | config.markWordNoLockInPlace;
    }

    @Fold
    static int uninitializedIdentityHashCodeValue(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.uninitializedIdentityHashCodeValue;
    }

    @Fold
    static int markWordHashCodeShift(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.markWordHashCodeShift;
    }

    @Fold
    static long markWordHashMark(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.markWordHashMask;
    }

    /**
     * Loads the hub of an object (without null checking it first).
     */
    public static KlassPointer loadHub(Object object) {
        return loadHubIntrinsic(object);
    }

    @Fold
    public static boolean verifyOops(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.verifyOops;
    }

    public static Object verifyOop(Object object) {
        if (verifyOops(INJECTED_VMCONFIG)) {
            verifyOopStub(VERIFY_OOP, object);
        }
        return object;
    }

    @Fold
    public static long verifyOopBits(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.verifyOopBits;
    }

    @Fold
    public static long verifyOopMask(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.verifyOopMask;
    }

    @Fold
    public static long verifyOopCounterAddress(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.verifyOopCounterAddress;
    }

    @NodeIntrinsic(ForeignCallNode.class)
    private static native Object verifyOopStub(@ConstantNodeParameter ForeignCallDescriptor descriptor, Object object);

    public static Word loadWordFromObject(Object object, int offset) {
        ReplacementsUtil.staticAssert(useCompactObjectHeaders(INJECTED_VMCONFIG) || offset != hubOffset(INJECTED_VMCONFIG), "Use loadHubIntrinsic instead of loadWordFromObject");
        return loadWordFromObjectIntrinsic(object, offset, LocationIdentity.any(), getWordKind());
    }

    public static Word loadWordFromObject(Object object, int offset, LocationIdentity identity) {
        ReplacementsUtil.staticAssert(useCompactObjectHeaders(INJECTED_VMCONFIG) || offset != hubOffset(INJECTED_VMCONFIG), "Use loadHubIntrinsic instead of loadWordFromObject");
        return loadWordFromObjectIntrinsic(object, offset, identity, getWordKind());
    }

    public static KlassPointer loadKlassFromObject(Object object, int offset, LocationIdentity identity) {
        ReplacementsUtil.staticAssert(useCompactObjectHeaders(INJECTED_VMCONFIG) || offset != hubOffset(INJECTED_VMCONFIG), "Use loadHubIntrinsic instead of loadKlassFromObject");
        return loadKlassFromObjectIntrinsic(object, offset, identity, getWordKind());
    }

    /**
     * Reads the value of a given register.
     *
     * @param register a register which must not be available to the register allocator
     * @return the value of {@code register} as a word
     */
    public static Word registerAsWord(@ConstantNodeParameter Register register) {
        return registerAsWord(register, true, false);
    }

    @NodeIntrinsic(value = ReadRegisterNode.class)
    public static native Word registerAsWord(@ConstantNodeParameter Register register, @ConstantNodeParameter boolean directUse, @ConstantNodeParameter boolean incoming);

    @NodeIntrinsic(value = RawLoadNode.class)
    private static native Word loadWordFromObjectIntrinsic(Object object, long offset, @ConstantNodeParameter LocationIdentity locationIdentity, @ConstantNodeParameter JavaKind wordKind);

    @NodeIntrinsic(value = RawLoadNode.class)
    private static native KlassPointer loadKlassFromObjectIntrinsic(Object object, long offset, @ConstantNodeParameter LocationIdentity locationIdentity, @ConstantNodeParameter JavaKind wordKind);

    @NodeIntrinsic(value = LoadHubNode.class)
    public static native KlassPointer loadHubIntrinsic(Object object);

    @NodeIntrinsic(value = LoadHubOrNullNode.class)
    public static native KlassPointer loadHubOrNullIntrinsic(Object object);

    static final LocationIdentity CLASS_INIT_STATE_LOCATION = NamedLocationIdentity.mutable("ClassInitState");

    static final LocationIdentity CLASS_INIT_THREAD_LOCATION = NamedLocationIdentity.mutable("ClassInitThread");

    @Fold
    static int instanceKlassInitStateOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.instanceKlassInitStateOffset;
    }

    @Fold
    static int instanceKlassInitThreadOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        assert config.instanceKlassInitThreadOffset != -1;
        return config.instanceKlassInitThreadOffset;
    }

    @Fold
    public static int instanceKlassStateFullyInitialized(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.instanceKlassStateFullyInitialized;
    }

    @Fold
    public static int instanceKlassStateBeingInitialized(@InjectedParameter GraalHotSpotVMConfig config) {
        assert config.instanceKlassStateBeingInitialized != -1;
        return config.instanceKlassStateBeingInitialized;
    }

    /**
     *
     * @param hub the hub of an InstanceKlass
     * @return true is the InstanceKlass represented by hub is fully initialized
     */
    public static boolean isInstanceKlassFullyInitialized(KlassPointer hub) {
        return readInstanceKlassInitState(hub) == instanceKlassStateFullyInitialized(INJECTED_VMCONFIG);
    }

    static byte readInstanceKlassInitState(KlassPointer hub) {
        return hub.readByteVolatile(instanceKlassInitStateOffset(INJECTED_VMCONFIG), CLASS_INIT_STATE_LOCATION);
    }

    static Word readInstanceKlassInitThread(KlassPointer hub) {
        return hub.readWord(instanceKlassInitThreadOffset(INJECTED_VMCONFIG), CLASS_INIT_THREAD_LOCATION);
    }

    public static final LocationIdentity KLASS_MODIFIER_FLAGS_LOCATION = NamedLocationIdentity.immutable("Klass::_modifier_flags");

    public static final LocationIdentity CLASS_KLASS_LOCATION = new HotSpotOptimizingLocationIdentity("Class._klass") {
        @Override
        public ValueNode canonicalizeRead(ValueNode read, ValueNode object, ValueNode location, NodeView view, CoreProviders tool) {
            return foldIndirection(read, object, CLASS_MIRROR_LOCATION);
        }
    };

    public static final LocationIdentity CLASS_ARRAY_KLASS_LOCATION = new HotSpotOptimizingLocationIdentity("Class._array_klass") {
        @Override
        public ValueNode canonicalizeRead(ValueNode read, ValueNode object, ValueNode location, NodeView view, CoreProviders tool) {
            return foldIndirection(read, object, ARRAY_KLASS_COMPONENT_MIRROR);
        }
    };

    @Fold
    public static int arrayKlassOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.arrayKlassOffset;
    }

    /**
     * HotSpot oop handle memory locations.
     */
    public static class OopHandleLocationIdentity extends NamedLocationIdentity {
        public OopHandleLocationIdentity(String name, boolean immutable) {
            super(name, immutable);
        }

        /**
         * @see NamedLocationIdentity#immutable(String)
         */
        public static NamedLocationIdentity immutable(String name) {
            return new OopHandleLocationIdentity(name, true);
        }

        /**
         * @see NamedLocationIdentity#mutable(String)
         */
        public static NamedLocationIdentity mutable(String name) {
            return new OopHandleLocationIdentity(name, false);
        }
    }

    public static final LocationIdentity CLASS_MIRROR_LOCATION = NamedLocationIdentity.immutable("Klass::_java_mirror");

    /**
     * This represents the contents of OopHandles used for some internal fields.
     */
    public static final LocationIdentity HOTSPOT_OOP_HANDLE_LOCATION = OopHandleLocationIdentity.immutable("OopHandle contents");

    /**
     * This represents the contents of the OopHandle used to store the current thread. Virtual
     * thread support makes this mutable.
     */
    public static final LocationIdentity HOTSPOT_CURRENT_THREAD_OOP_HANDLE_LOCATION = OopHandleLocationIdentity.mutable("_vthread OopHandle contents");

    public static final LocationIdentity HOTSPOT_CARRIER_THREAD_OOP_HANDLE_LOCATION = OopHandleLocationIdentity.mutable("_threadObj OopHandle contents");

    public static final LocationIdentity HOTSPOT_JAVA_THREAD_SCOPED_VALUE_CACHE_HANDLE_LOCATION = OopHandleLocationIdentity.mutable("_scopedValueCache OopHandle contents");

    public static final LocationIdentity HOTSPOT_VTMS_NOTIFY_JVMTI_EVENTS = NamedLocationIdentity.mutable("JvmtiVTMSTransitionDisabler::_VTMS_notify_jvmti_events");
    public static final LocationIdentity HOTSPOT_JAVA_THREAD_IS_IN_VTMS_TRANSITION = NamedLocationIdentity.mutable("JavaThread::_is_in_VTMS_transition");
    public static final LocationIdentity HOTSPOT_JAVA_THREAD_IS_IN_TMP_VTMS_TRANSITION = NamedLocationIdentity.mutable("JavaThread::_is_in_tmp_VTMS_transition");
    public static final LocationIdentity HOTSPOT_JAVA_THREAD_IS_DISABLE_SUSPEND = NamedLocationIdentity.mutable("JavaThread::_is_disable_suspend");
    public static final LocationIdentity HOTSPOT_JAVA_LANG_THREAD_IS_IN_VTMS_TRANSITION = NamedLocationIdentity.mutable("Thread::_is_in_VTMS_transition");
    public static final LocationIdentity HOTSPOT_JAVA_THREAD_CONT_ENTRY_LOCATION = NamedLocationIdentity.mutable("JavaThread::_cont_entry");

    public static final LocationIdentity HOTSPOT_CONTINUATION_ENTRY_PIN_COUNT_LOCATION = NamedLocationIdentity.mutable("ContinuationEntry::_pin_count");

    @Fold
    public static int layoutHelperHeaderSizeShift(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.layoutHelperHeaderSizeShift;
    }

    @Fold
    public static int layoutHelperHeaderSizeMask(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.layoutHelperHeaderSizeMask;
    }

    @Fold
    public static int layoutHelperLog2ElementSizeShift(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.layoutHelperLog2ElementSizeShift;
    }

    @Fold
    public static int layoutHelperLog2ElementSizeMask(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.layoutHelperLog2ElementSizeMask;
    }

    @NodeIntrinsic(ForeignCallNode.class)
    public static native int identityHashCode(@ConstantNodeParameter ForeignCallDescriptor descriptor, Object object);

    @Fold
    public static long gcTotalCollectionsAddress(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.gcTotalCollectionsAddress();
    }

    public static String referentFieldName() {
        return "referent";
    }

    public static long referentOffset(@InjectedParameter MetaAccessProvider metaAccessProvider) {
        return referentField(metaAccessProvider).getOffset();
    }

    public static ResolvedJavaField referentField(@InjectedParameter MetaAccessProvider metaAccessProvider) {
        return getField(metaAccessProvider.lookupJavaType(Reference.class), referentFieldName());
    }

    public static final LocationIdentity OBJ_ARRAY_KLASS_ELEMENT_KLASS_LOCATION = new HotSpotOptimizingLocationIdentity("ObjArrayKlass::_element_klass") {
        @Override
        public ValueNode canonicalizeRead(ValueNode read, ValueNode object, ValueNode location, NodeView view, CoreProviders tool) {
            ValueNode javaObject = findReadHub(object);
            if (javaObject != null) {
                ResolvedJavaType type = StampTool.typeOrNull(javaObject);
                if (type != null && type.isArray()) {
                    ResolvedJavaType element = type.getComponentType();
                    if (element != null && !element.isPrimitive() && !element.getElementalType().isInterface()) {
                        Assumptions assumptions = object.graph().getAssumptions();
                        AssumptionResult<ResolvedJavaType> leafType = element.findLeafConcreteSubtype();
                        if (leafType != null && leafType.canRecordTo(assumptions)) {
                            leafType.recordTo(assumptions);
                            return ConstantNode.forConstant(read.stamp(NodeView.DEFAULT), tool.getConstantReflection().asObjectHub(leafType.getResult()), tool.getMetaAccess());
                        }
                    }
                }
            }
            return read;
        }
    };

    @Fold
    public static int arrayClassElementOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.arrayClassElementOffset;
    }

    @Fold
    public static int threadCarrierThreadOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.javaThreadThreadObjOffset;
    }

    public static boolean supportsVirtualThreadUpdateJFR(GraalHotSpotVMConfig config) {
        return config.threadJFRThreadLocalOffset != -1 && config.jfrThreadLocalVthreadIDOffset != -1 && config.jfrThreadLocalVthreadEpochOffset != -1 &&
                        config.jfrThreadLocalVthreadExcludedOffset != -1 && config.jfrThreadLocalVthreadOffset != -1 && config.javaLangThreadJFREpochOffset != -1 &&
                        config.javaLangThreadTIDOffset != -1;
    }

    @Fold
    public static int jfrThreadLocalVthreadIDOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.threadJFRThreadLocalOffset + config.jfrThreadLocalVthreadIDOffset;
    }

    @Fold
    public static int jfrThreadLocalVthreadEpochOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.threadJFRThreadLocalOffset + config.jfrThreadLocalVthreadEpochOffset;
    }

    @Fold
    public static int jfrThreadLocalVthreadExcludedOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.threadJFRThreadLocalOffset + config.jfrThreadLocalVthreadExcludedOffset;
    }

    @Fold
    public static int jfrThreadLocalVthreadOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.threadJFRThreadLocalOffset + config.jfrThreadLocalVthreadOffset;
    }

    @Fold
    public static int javaLangThreadJFREpochOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.javaLangThreadJFREpochOffset;
    }

    @Fold
    public static int javaLangThreadTIDOffset(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.javaLangThreadTIDOffset;
    }

    public static final LocationIdentity PRIMARY_SUPERS_LOCATION = NamedLocationIdentity.immutable("PrimarySupers");

    /**
     * This location identity is intended for accesses to {@code Klass::_primary_supers}, which is
     * immutable. However, in {@link TypeCheckSnippetUtils#checkUnknownSubType}, it is possible to
     * trigger context insensitive constant folding of the corresponding read in the dead code where
     * the read's displacement is {@link GraalHotSpotVMConfig#secondarySuperCacheOffset}, i.e.,
     * pointing to the mutable {@code Klass::_secondary_super_cache}. Hence, we only fold
     * corresponding reads when the displacement is not
     * {@link GraalHotSpotVMConfig#secondarySuperCacheOffset}.
     */
    public static final LocationIdentity OPTIMIZING_PRIMARY_SUPERS_LOCATION = new HotSpotOptimizingLocationIdentity("PrimarySupersOrSecondarySuperCache", false) {
        @Override
        public ValueNode canonicalizeRead(ValueNode read, ValueNode object, ValueNode offset, NodeView view, CoreProviders tool) {
            int secondarySuperCacheOffset = ((HotSpotLoweringProvider) tool.getLowerer()).getVMConfig().secondarySuperCacheOffset;

            if (object instanceof ConstantNode && offset instanceof ConstantNode) {
                long displacement = offset.asJavaConstant().asLong();
                if (displacement != secondarySuperCacheOffset) {
                    Stamp accessStamp = read.stamp(view);
                    Constant constant = accessStamp.readConstant(tool.getConstantReflection().getMemoryAccessProvider(), object.asConstant(), displacement, accessStamp);
                    return ConstantNode.forConstant(accessStamp, constant, 0, false, tool.getMetaAccess());
                }
            }
            return read;
        }
    };

    public static final LocationIdentity METASPACE_ARRAY_LENGTH_LOCATION = NamedLocationIdentity.immutable("MetaspaceArrayLength");

    public static final LocationIdentity SECONDARY_SUPERS_ELEMENT_LOCATION = NamedLocationIdentity.immutable("SecondarySupersElement");

}
