/*
 * Copyright (c) 2011, 2025, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.nodes.calc;

import jdk.graal.compiler.core.common.type.ArithmeticOpTable;
import jdk.graal.compiler.core.common.type.ArithmeticOpTable.ShiftOp;
import jdk.graal.compiler.core.common.type.ArithmeticOpTable.ShiftOp.Shl;
import jdk.graal.compiler.core.common.type.IntegerStamp;
import jdk.graal.compiler.core.common.type.PrimitiveStamp;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.debug.Assertions;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.lir.gen.ArithmeticLIRGeneratorTool;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;
import jdk.vm.ci.code.CodeUtil;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.PrimitiveConstant;

@NodeInfo(shortName = "<<")
public final class LeftShiftNode extends ShiftNode<Shl> {

    public static final NodeClass<LeftShiftNode> TYPE = NodeClass.create(LeftShiftNode.class);

    public LeftShiftNode(ValueNode x, ValueNode y) {
        super(TYPE, BinaryArithmeticNode.getArithmeticOpTable(x).getShl(), x, y);
    }

    public static ValueNode create(ValueNode x, ValueNode y, NodeView view) {
        ArithmeticOpTable.ShiftOp<Shl> op = ArithmeticOpTable.forStamp(x.stamp(view)).getShl();
        Stamp stamp = op.foldStamp(x.stamp(view), y.stamp(view));
        ValueNode value = ShiftNode.canonical(op, stamp, x, y, view);
        if (value != null) {
            return value;
        }

        return canonical(null, op, stamp, x, y);
    }

    @Override
    protected ShiftOp<Shl> getOp(ArithmeticOpTable table) {
        return table.getShl();
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forX, ValueNode forY) {
        ValueNode ret = super.canonical(tool, forX, forY);
        if (ret != this) {
            return ret;
        }

        return canonical(this, getArithmeticOp(), stamp(NodeView.DEFAULT), forX, forY);
    }

    /**
     * Try to rewrite the current node to a {@linkplain MulNode} iff the
     * {@linkplain LeftShiftNode#getX()} and {@linkplain LeftShiftNode#getY()} inputs represent
     * numeric integers and {@linkplain LeftShiftNode#getY()} is a constant value. The resulting
     * {@linkplain MulNode} replaces the current node in the {@linkplain LeftShiftNode#graph()}.
     *
     * @return true iff node was replaced
     */
    public boolean tryReplaceWithMulNode() {
        MulNode mul = getEquivalentMulNode();
        if (mul != null) {
            replaceAtUsages(graph().addOrUniqueWithInputs(mul));
            return true;
        }
        return false;
    }

    /**
     * Try to compute a {@link MulNode} equivalent to this node.
     *
     * @return an equivalent {@link MulNode} or {@code null} if no such node could be built. If a
     *         node is returned, it is new, non-canonical, and not added to the graph.
     *
     * @see #tryReplaceWithMulNode()
     */
    public MulNode getEquivalentMulNode() {
        if (this.getY().isConstant() && stamp(NodeView.DEFAULT) instanceof IntegerStamp selfStamp) {
            Constant c = getY().asConstant();
            if (c instanceof PrimitiveConstant && ((PrimitiveConstant) c).getJavaKind().isNumericInteger()) {
                IntegerStamp xStamp = (IntegerStamp) getX().stamp(NodeView.DEFAULT);
                if (xStamp.getBits() == selfStamp.getBits()) {
                    long shiftAmount = ((PrimitiveConstant) c).asLong();
                    /*
                     * The shift below is done in long arithmetic, but if the underlying values are
                     * ints (or smaller), we must shift according to int semantics. So mask
                     * accordingly.
                     */
                    if (selfStamp.getBits() <= Integer.SIZE) {
                        shiftAmount &= CodeUtil.mask(CodeUtil.log2(Integer.SIZE));
                    }
                    /*
                     * If shiftAmount == 63, this will give Long.MIN_VALUE, which is negative but
                     * still correct as the multiplier. We have to do a shift here, computing this
                     * as (long) Math.pow(2, 63) would round to the wrong value.
                     */
                    long multiplier = 1L << shiftAmount;
                    return new MulNode(getX(), ConstantNode.forIntegerStamp(xStamp, multiplier));
                }
            }
        }
        return null;
    }

    private static ValueNode canonical(LeftShiftNode leftShiftNode, ArithmeticOpTable.ShiftOp<Shl> op, Stamp stamp, ValueNode forX, ValueNode forY) {
        if (forY.isConstant() && op.isNeutral(forY.asConstant())) {
            return forX;
        }

        LeftShiftNode self = leftShiftNode;
        if (forY.isJavaConstant()) {
            int amount = forY.asJavaConstant().asInt();
            int originalAmount = amount;
            int mask = op.getShiftAmountMask(stamp);
            amount &= mask;
            if (amount == 0) {
                return forX;
            }
            if (forX instanceof ShiftNode) {
                ShiftNode<?> other = (ShiftNode<?>) forX;
                if (other.getY().isConstant()) {
                    int otherAmount = other.getY().asJavaConstant().asInt() & mask;
                    if (other instanceof LeftShiftNode) {
                        int total = amount + otherAmount;
                        if (total != (total & mask)) {
                            return ConstantNode.forIntegerBits(PrimitiveStamp.getBits(stamp), 0);
                        }
                        return new LeftShiftNode(other.getX(), ConstantNode.forInt(total));
                    } else if ((other instanceof RightShiftNode || other instanceof UnsignedRightShiftNode) && otherAmount == amount) {
                        if (stamp.getStackKind() == JavaKind.Long) {
                            return new AndNode(other.getX(), ConstantNode.forLong(-1L << amount));
                        } else {
                            assert stamp.getStackKind() == JavaKind.Int : Assertions.errorMessage(leftShiftNode, op, stamp, forX, forY);
                            return new AndNode(other.getX(), ConstantNode.forInt(-1 << amount));
                        }
                    }
                }
            }
            if (originalAmount != amount) {
                return new LeftShiftNode(forX, ConstantNode.forInt(amount));
            }
        }
        if (self == null) {
            self = new LeftShiftNode(forX, forY);
        }
        return self;
    }

    @Override
    public void generate(NodeLIRBuilderTool nodeValueMap, ArithmeticLIRGeneratorTool gen) {
        nodeValueMap.setResult(this, gen.emitShl(nodeValueMap.operand(getX()), nodeValueMap.operand(getY())));
    }
}
