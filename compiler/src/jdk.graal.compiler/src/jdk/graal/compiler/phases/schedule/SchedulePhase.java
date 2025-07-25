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
package jdk.graal.compiler.phases.schedule;

import static jdk.graal.compiler.core.common.GraalOptions.GuardPriorities;
import static jdk.graal.compiler.core.common.GraalOptions.OptScheduleOutOfLoops;
import static org.graalvm.collections.Equivalence.IDENTITY;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Formatter;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;

import org.graalvm.collections.EconomicSet;
import org.graalvm.word.LocationIdentity;

import jdk.graal.compiler.core.common.SuppressFBWarnings;
import jdk.graal.compiler.core.common.cfg.AbstractControlFlowGraph;
import jdk.graal.compiler.core.common.cfg.BlockMap;
import jdk.graal.compiler.core.common.util.CompilationAlarm;
import jdk.graal.compiler.debug.Assertions;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Graph.NodeEvent;
import jdk.graal.compiler.graph.Graph.NodeEventListener;
import jdk.graal.compiler.graph.Graph.NodeEventScope;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeBitMap;
import jdk.graal.compiler.graph.NodeMap;
import jdk.graal.compiler.graph.NodeStack;
import jdk.graal.compiler.nodes.AbstractBeginNode;
import jdk.graal.compiler.nodes.AbstractEndNode;
import jdk.graal.compiler.nodes.AbstractMergeNode;
import jdk.graal.compiler.nodes.ControlSinkNode;
import jdk.graal.compiler.nodes.ControlSplitNode;
import jdk.graal.compiler.nodes.DeoptimizeNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.GraphState;
import jdk.graal.compiler.nodes.GraphState.StageFlag;
import jdk.graal.compiler.nodes.GuardNode;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.LoopBeginNode;
import jdk.graal.compiler.nodes.LoopExitNode;
import jdk.graal.compiler.nodes.PhiNode;
import jdk.graal.compiler.nodes.ProxyNode;
import jdk.graal.compiler.nodes.StartNode;
import jdk.graal.compiler.nodes.StaticDeoptimizingNode;
import jdk.graal.compiler.nodes.StaticDeoptimizingNode.GuardPriority;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.StructuredGraph.ScheduleResult;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.VirtualState;
import jdk.graal.compiler.nodes.calc.ConvertNode;
import jdk.graal.compiler.nodes.calc.IsNullNode;
import jdk.graal.compiler.nodes.cfg.ControlFlowGraph;
import jdk.graal.compiler.nodes.cfg.HIRBlock;
import jdk.graal.compiler.nodes.cfg.HIRLoop;
import jdk.graal.compiler.nodes.cfg.LocationSet;
import jdk.graal.compiler.nodes.memory.FloatingReadNode;
import jdk.graal.compiler.nodes.memory.MemoryKill;
import jdk.graal.compiler.nodes.memory.MultiMemoryKill;
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.nodes.spi.ValueProxy;
import jdk.graal.compiler.nodes.virtual.AllocatedObjectNode;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.tiers.LowTierContext;

public final class SchedulePhase extends BasePhase<CoreProviders> {

    public enum SchedulingStrategy {
        EARLIEST_WITH_GUARD_ORDER,
        EARLIEST,
        LATEST,
        LATEST_OUT_OF_LOOPS,
        LATEST_OUT_OF_LOOPS_IMPLICIT_NULL_CHECKS;

        public boolean isEarliest() {
            return this == EARLIEST || this == EARLIEST_WITH_GUARD_ORDER;
        }

        public boolean isLatest() {
            return !isEarliest();
        }

        public boolean scheduleOutOfLoops() {
            return this == LATEST_OUT_OF_LOOPS || this == LATEST_OUT_OF_LOOPS_IMPLICIT_NULL_CHECKS;
        }

        public boolean considerImplicitNullChecks() {
            return this == LATEST_OUT_OF_LOOPS_IMPLICIT_NULL_CHECKS;
        }
    }

    private final SchedulingStrategy selectedStrategy;

    private final boolean immutableGraph;

    public SchedulePhase(OptionValues options) {
        this(false, options);
    }

    public SchedulePhase(boolean immutableGraph, OptionValues options) {
        this(getDefaultStrategy(options), immutableGraph);
    }

    public SchedulePhase(SchedulingStrategy strategy) {
        this(strategy, false);
    }

    public SchedulePhase(SchedulingStrategy strategy, boolean immutableGraph) {
        this.selectedStrategy = strategy;
        this.immutableGraph = immutableGraph;
    }

    /**
     * Last schedule to be run in any phase plan in the compiler. After this no further
     * optimizations or transformations must happen that would require a re-scheduling of the graph.
     */
    public static class FinalSchedulePhase extends BasePhase<LowTierContext> {

        @Override
        public Optional<NotApplicable> notApplicableTo(GraphState graphState) {
            return NotApplicable.ifAny(
                            NotApplicable.ifApplied(this, StageFlag.FINAL_SCHEDULE, graphState),
                            NotApplicable.unlessRunAfter(this, StageFlag.ADDRESS_LOWERING, graphState));
        }

        @Override
        protected void run(StructuredGraph graph, LowTierContext context) {
            new SchedulePhase(SchedulePhase.SchedulingStrategy.LATEST_OUT_OF_LOOPS).apply(graph, context);

        }

        @Override
        public void updateGraphState(GraphState graphState) {
            super.updateGraphState(graphState);
            graphState.setAfterStage(StageFlag.FINAL_SCHEDULE);
        }

    }

    public static SchedulingStrategy getDefaultStrategy(OptionValues options) {
        return OptScheduleOutOfLoops.getValue(options) ? SchedulingStrategy.LATEST_OUT_OF_LOOPS : SchedulingStrategy.LATEST;
    }

    private NodeEventScope verifyImmutableGraph(StructuredGraph graph) {
        if (immutableGraph && Assertions.assertionsEnabled()) {
            return graph.trackNodeEvents(new NodeEventListener() {
                @Override
                public void changed(NodeEvent e, Node node) {
                    assert false : "graph changed: " + e + " on node " + node;
                }
            });
        } else {
            return null;
        }
    }

    @Override
    public Optional<NotApplicable> notApplicableTo(GraphState graphState) {
        return ALWAYS_APPLICABLE;
    }

    @Override
    public boolean shouldApply(StructuredGraph graph) {
        /*
         * Don't compute a new schedule if the schedule obtainable by
         * StructuredGraph#getLastSchedule() is still valid and uses the same SchedulingStrategy.
         */
        ScheduleResult prev = graph.getLastSchedule();
        return prev == null || !graph.isLastScheduleValid() || prev.strategy != this.selectedStrategy;
    }

    @Override
    @SuppressWarnings("try")
    protected void run(StructuredGraph graph, CoreProviders context) {
        try (NodeEventScope scope = verifyImmutableGraph(graph)) {
            Instance inst = new Instance(context.getLowerer().supportsImplicitNullChecks());
            inst.run(graph, selectedStrategy, immutableGraph);
        }
    }

    public static void runWithoutContextOptimizations(StructuredGraph graph) {
        runWithoutContextOptimizations(graph, getDefaultStrategy(graph.getOptions()));
    }

    public static void runWithoutContextOptimizations(StructuredGraph graph, SchedulingStrategy strategy) {
        runWithoutContextOptimizations(graph, strategy, ControlFlowGraph.computeForSchedule(graph), false);
    }

    public static void runWithoutContextOptimizations(StructuredGraph graph, SchedulingStrategy strategy, boolean immutable) {
        runWithoutContextOptimizations(graph, strategy, ControlFlowGraph.computeForSchedule(graph), immutable);
    }

    private static boolean shouldApply(StructuredGraph graph, SchedulingStrategy strategy) {
        ScheduleResult prev = graph.getLastSchedule();
        return prev == null || !graph.isLastScheduleValid() || prev.strategy != strategy;
    }

    public static void runWithoutContextOptimizations(StructuredGraph graph, SchedulingStrategy strategy, ControlFlowGraph cfg, boolean immutable) {
        if (shouldApply(graph, strategy)) {
            Instance inst = new Instance(cfg, false);
            inst.run(graph, strategy, immutable);
        }
    }

    public static void run(StructuredGraph graph, SchedulingStrategy strategy, ControlFlowGraph cfg, CoreProviders context, boolean immutable) {
        if (shouldApply(graph, strategy)) {
            Instance inst = new Instance(cfg, context.getLowerer().supportsImplicitNullChecks());
            inst.run(graph, strategy, immutable);
        }
    }

    public static class Instance {

        private static final double IMPLICIT_NULL_CHECK_OPPORTUNITY_PROBABILITY_FACTOR = 2;
        /**
         * Map from blocks to the nodes in each block.
         */
        protected ControlFlowGraph cfg;
        protected BlockMap<List<Node>> blockToNodesMap;
        protected NodeMap<HIRBlock> nodeToBlockMap;
        protected boolean supportsImplicitNullChecks;

        public Instance(boolean supportsImplicitNullChecks) {
            this(null, supportsImplicitNullChecks);
        }

        public Instance(ControlFlowGraph cfg, boolean supportsImplicitNullChecks) {
            this.cfg = cfg;
            this.supportsImplicitNullChecks = supportsImplicitNullChecks;
        }

        @SuppressWarnings("try")
        public void run(StructuredGraph graph, SchedulingStrategy selectedStrategy, boolean immutableGraph) {
            if (this.cfg == null) {
                this.cfg = ControlFlowGraph.computeForSchedule(graph);
            } else {
                GraalError.guarantee(this.cfg == graph.getLastCFG() && graph.isLastCFGValid(),
                                "Cannot compute schedule for stale CFG; given: %s, graph's last CFG is %s, is valid: %s.",
                                this.cfg, graph.getLastCFG(), graph.isLastCFGValid());
            }

            NodeMap<HIRBlock> currentNodeMap = graph.createNodeMap();
            NodeBitMap visited = graph.createNodeBitMap();
            BlockMap<List<Node>> earliestBlockToNodesMap = new BlockMap<>(cfg);
            this.nodeToBlockMap = currentNodeMap;
            this.blockToNodesMap = earliestBlockToNodesMap;

            scheduleEarliestIterative(earliestBlockToNodesMap, currentNodeMap, visited, graph, immutableGraph, selectedStrategy == SchedulingStrategy.EARLIEST_WITH_GUARD_ORDER);

            if (!selectedStrategy.isEarliest()) {
                // For non-earliest schedules, we need to do a second pass.
                BlockMap<List<Node>> latestBlockToNodesMap = new BlockMap<>(cfg);
                for (HIRBlock b : cfg.getBlocks()) {
                    latestBlockToNodesMap.put(b, new ArrayList<>());
                }

                BlockMap<ArrayList<FloatingReadNode>> watchListMap = calcLatestBlocks(selectedStrategy, currentNodeMap, earliestBlockToNodesMap, visited, latestBlockToNodesMap, immutableGraph);
                sortNodesLatestWithinBlock(cfg, earliestBlockToNodesMap, latestBlockToNodesMap, currentNodeMap, watchListMap, visited, supportsImplicitNullChecks);

                assert verifySchedule(cfg, latestBlockToNodesMap, currentNodeMap);
                assert (!Assertions.detailedAssertionsEnabled(graph.getOptions())) ||
                                ScheduleVerification.check(cfg.getStartBlock(), latestBlockToNodesMap, currentNodeMap);

                this.blockToNodesMap = latestBlockToNodesMap;

            }

            graph.setLastSchedule(new ScheduleResult(this.cfg, this.nodeToBlockMap, this.blockToNodesMap, selectedStrategy));
        }

        @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE", justification = "false positive found by findbugs")
        private BlockMap<ArrayList<FloatingReadNode>> calcLatestBlocks(SchedulingStrategy strategy, NodeMap<HIRBlock> currentNodeMap, BlockMap<List<Node>> earliestBlockToNodesMap, NodeBitMap visited,
                        BlockMap<List<Node>> latestBlockToNodesMap, boolean immutableGraph) {
            BlockMap<ArrayList<FloatingReadNode>> watchListMap = new BlockMap<>(cfg);
            HIRBlock[] reversePostOrder = cfg.reversePostOrder();
            NodeBitMap moveInputsIntoDominator = cfg.graph.createNodeBitMap();
            for (int j = reversePostOrder.length - 1; j >= 0; --j) {
                HIRBlock currentBlock = reversePostOrder[j];
                List<Node> blockToNodes = earliestBlockToNodesMap.get(currentBlock);
                LocationSet killed = null;
                int previousIndex = blockToNodes.size();
                for (int i = blockToNodes.size() - 1; i >= 0; --i) {
                    Node currentNode = blockToNodes.get(i);
                    assert currentNodeMap.get(currentNode) == currentBlock : Assertions.errorMessageContext("currentNodeMap", currentNodeMap, "currentBlock", currentBlock);
                    assert !(currentNode instanceof PhiNode) && !(currentNode instanceof ProxyNode) : currentNode;
                    assert visited.isMarked(currentNode) : Assertions.errorMessageContext("visited", visited, "currentNode", currentNode);
                    if (currentNode instanceof FixedNode) {
                        // For these nodes, the earliest is at the same time the latest block.
                    } else {
                        HIRBlock latestBlock = null;

                        if (currentBlock.getFirstDominated() == null && !(currentNode instanceof VirtualState)) {
                            // This block doesn't dominate any other blocks =>
                            // node must be scheduled in earliest block.
                            latestBlock = currentBlock;
                        }

                        if (currentNode instanceof AllocatedObjectNode) {
                            latestBlock = currentBlock;
                        }

                        LocationIdentity constrainingLocation = null;
                        if (latestBlock == null && currentNode instanceof FloatingReadNode) {
                            // We are scheduling a floating read node => check memory
                            // anti-dependencies.
                            FloatingReadNode floatingReadNode = (FloatingReadNode) currentNode;
                            if (floatingReadNode.potentialAntiDependency()) {
                                // Location can be killed.
                                LocationIdentity location = floatingReadNode.getLocationIdentity();
                                constrainingLocation = location;
                                if (currentBlock.canKill(location)) {
                                    if (killed == null) {
                                        killed = new LocationSet();
                                    }
                                    fillKillSet(killed, blockToNodes.subList(i + 1, previousIndex));
                                    previousIndex = i;
                                    if (killed.contains(location)) {
                                        // Earliest block kills location => we need to stay within
                                        // earliest block.
                                        latestBlock = currentBlock;
                                    }
                                }
                            }
                        }

                        if (latestBlock == null) {
                            // We are not constraint within earliest block => calculate optimized
                            // schedule.
                            calcLatestBlock(currentBlock, strategy, currentNode, currentNodeMap, constrainingLocation, watchListMap, latestBlockToNodesMap, visited, immutableGraph,
                                            moveInputsIntoDominator);
                        } else {
                            selectLatestBlock(currentNode, currentBlock, latestBlock, currentNodeMap, watchListMap, constrainingLocation, latestBlockToNodesMap);
                        }
                    }
                }
            }
            return watchListMap;
        }

        protected static void selectLatestBlock(Node currentNode, HIRBlock currentBlock, HIRBlock latestBlock, NodeMap<HIRBlock> currentNodeMap, BlockMap<ArrayList<FloatingReadNode>> watchListMap,
                        LocationIdentity constrainingLocation, BlockMap<List<Node>> latestBlockToNodesMap) {
            if (currentBlock != latestBlock) {
                checkLatestEarliestRelation(currentNode, currentBlock, latestBlock, null);

                currentNodeMap.setAndGrow(currentNode, latestBlock);

                if (constrainingLocation != null && latestBlock.canKill(constrainingLocation)) {
                    if (watchListMap.get(latestBlock) == null) {
                        watchListMap.put(latestBlock, new ArrayList<>());
                    }
                    watchListMap.get(latestBlock).add((FloatingReadNode) currentNode);
                }
            }

            latestBlockToNodesMap.get(latestBlock).add(currentNode);
        }

        private static void checkLatestEarliestRelation(Node currentNode, HIRBlock earliestBlock, HIRBlock latestBlock, Node currentUsage) {
            GraalError.guarantee(earliestBlock.dominates(latestBlock) || (currentNode instanceof FrameState && latestBlock == earliestBlock.getDominator()),
                            "%s earliest block %s (%s) does not dominate latest block %s (%s), added usage %s", currentNode, earliestBlock, earliestBlock.getBeginNode(), latestBlock,
                            latestBlock.getBeginNode(), currentUsage);
        }

        private static boolean verifySchedule(ControlFlowGraph cfg, BlockMap<List<Node>> blockToNodesMap, NodeMap<HIRBlock> nodeMap) {
            for (HIRBlock b : cfg.getBlocks()) {
                List<Node> nodes = blockToNodesMap.get(b);
                for (Node n : nodes) {
                    assert n.isAlive();
                    assert nodeMap.get(n) == b : Assertions.errorMessage(n, b);
                    StructuredGraph g = (StructuredGraph) n.graph();
                    if (g.hasLoops() && g.getGuardsStage().areFrameStatesAtDeopts() && n instanceof DeoptimizeNode) {
                        assert b.getLoopDepth() == 0 : n;
                    }
                }
            }
            return true;
        }

        public static HIRBlock checkKillsBetween(HIRBlock earliestBlock, HIRBlock latestBlock, LocationIdentity location) {
            assert earliestBlock.strictlyDominates(latestBlock);
            HIRBlock current = latestBlock.getDominator();

            // Collect dominator chain that needs checking.
            List<HIRBlock> dominatorChain = new ArrayList<>();
            dominatorChain.add(latestBlock);
            while (current != earliestBlock) {
                // Current is an intermediate dominator between earliestBlock and latestBlock.
                assert earliestBlock.strictlyDominates(current) : Assertions.errorMessageContext("earliestBlock", earliestBlock, "current", current);
                assert current.strictlyDominates(latestBlock) : Assertions.errorMessageContext("latestBlock", latestBlock, "current", current);
                if (current.canKill(location)) {
                    dominatorChain.clear();
                }
                dominatorChain.add(current);
                current = current.getDominator();
            }

            // The first element of dominatorChain now contains the latest possible block.
            assert dominatorChain.size() >= 1 : Assertions.errorMessageContext("dominatorChain", dominatorChain);
            HIRBlock dominator = dominatorChain.get(dominatorChain.size() - 1).getDominator();
            assert dominator == earliestBlock : Assertions.errorMessageContext("dominator", dominator, "earliestBlock", earliestBlock);

            HIRBlock lastBlock = earliestBlock;
            for (int i = dominatorChain.size() - 1; i >= 0; --i) {
                HIRBlock currentBlock = dominatorChain.get(i);
                if (currentBlock.getLoopDepth() > lastBlock.getLoopDepth()) {
                    // We are entering a loop boundary. The new loops must not kill the location for
                    // the crossing to be safe.
                    if (currentBlock.getLoop() != null && ((HIRLoop) currentBlock.getLoop()).canKill(location)) {
                        break;
                    }
                }

                if (currentBlock.canKillBetweenThisAndDominator(location)) {
                    break;
                }
                lastBlock = currentBlock;
            }

            return lastBlock;
        }

        private static void fillKillSet(LocationSet killed, List<Node> subList) {
            if (!killed.isAny()) {
                for (Node n : subList) {
                    // Check if this node kills a node in the watch list.
                    if (MemoryKill.isSingleMemoryKill(n)) {
                        LocationIdentity identity = ((SingleMemoryKill) n).getKilledLocationIdentity();
                        killed.add(identity);
                        if (killed.isAny()) {
                            return;
                        }
                    } else if (MemoryKill.isMultiMemoryKill(n)) {
                        for (LocationIdentity identity : ((MultiMemoryKill) n).getKilledLocationIdentities()) {
                            killed.add(identity);
                            if (killed.isAny()) {
                                return;
                            }
                        }
                    }
                }
            }
        }

        private static void sortNodesLatestWithinBlock(ControlFlowGraph cfg, BlockMap<List<Node>> earliestBlockToNodesMap, BlockMap<List<Node>> latestBlockToNodesMap, NodeMap<HIRBlock> currentNodeMap,
                        BlockMap<ArrayList<FloatingReadNode>> watchListMap, NodeBitMap visited, boolean supportsImplicitNullChecks) {
            NodeStack nodeStack = new NodeStack();
            for (HIRBlock b : cfg.getBlocks()) {
                sortNodesLatestWithinBlock(nodeStack, b, earliestBlockToNodesMap, latestBlockToNodesMap, currentNodeMap, watchListMap, visited, supportsImplicitNullChecks);
            }
        }

        private static void sortNodesLatestWithinBlock(NodeStack nodeStack, HIRBlock b, BlockMap<List<Node>> earliestBlockToNodesMap, BlockMap<List<Node>> latestBlockToNodesMap,
                        NodeMap<HIRBlock> nodeMap,
                        BlockMap<ArrayList<FloatingReadNode>> watchListMap, NodeBitMap unprocessed, boolean supportsImplicitNullChecks) {
            List<Node> earliestSorting = earliestBlockToNodesMap.get(b);
            ArrayList<Node> result = new ArrayList<>(earliestSorting.size());
            ArrayList<FloatingReadNode> watchList = null;
            if (watchListMap != null) {
                watchList = watchListMap.get(b);
                assert watchList == null || !b.getKillLocations().isEmpty();
            }
            AbstractBeginNode beginNode = b.getBeginNode();
            if (beginNode instanceof LoopExitNode) {
                LoopExitNode loopExitNode = (LoopExitNode) beginNode;
                for (ProxyNode proxy : loopExitNode.proxies()) {
                    unprocessed.clear(proxy);
                    ValueNode value = proxy.value();
                    // if multiple proxies reference the same value, schedule the value of a
                    // proxy once
                    if (value != null && nodeMap.get(value) == b && unprocessed.isMarked(value)) {
                        sortIntoList(nodeStack, value, b, result, nodeMap, unprocessed);
                    }
                }
            }
            FixedNode endNode = b.getEndNode();
            FixedNode fixedEndNode = null;
            if (isFixedEnd(endNode)) {
                // Only if the end node is either a control split or an end node, we need to force
                // it to be the last node in the schedule.
                fixedEndNode = endNode;
                unprocessed.clear(fixedEndNode);
            }
            for (Node n : earliestSorting) {
                if (n != fixedEndNode) {
                    if (n instanceof FixedNode) {
                        assert nodeMap.get(n) == b : Assertions.errorMessageContext("n", n, "b", b);
                        checkWatchList(nodeStack, b, nodeMap, unprocessed, result, watchList, n);
                        sortIntoList(nodeStack, n, b, result, nodeMap, unprocessed);
                    } else if (nodeMap.get(n) == b && n instanceof FloatingReadNode) {
                        FloatingReadNode floatingReadNode = (FloatingReadNode) n;
                        if (isImplicitNullOpportunity(floatingReadNode, b, supportsImplicitNullChecks)) {
                            // Schedule at the beginning of the block.
                            sortIntoList(nodeStack, floatingReadNode, b, result, nodeMap, unprocessed);
                        } else {
                            LocationIdentity location = floatingReadNode.getLocationIdentity();
                            if (b.canKill(location)) {
                                // This read can be killed in this block, add to watch list.
                                if (watchList == null) {
                                    watchList = new ArrayList<>();
                                }
                                watchList.add(floatingReadNode);
                            }
                        }
                    }
                }
            }

            for (Node n : latestBlockToNodesMap.get(b)) {
                assert nodeMap.get(n) == b : n;
                assert !(n instanceof FixedNode) : n;
                if (unprocessed.isMarked(n)) {
                    sortIntoList(nodeStack, n, b, result, nodeMap, unprocessed);
                }
            }

            if (fixedEndNode != null) {
                result.add(fixedEndNode);
            } else if (endNode != null && unprocessed.isMarked(endNode)) {
                sortIntoList(nodeStack, endNode, b, result, nodeMap, unprocessed);
            }

            latestBlockToNodesMap.put(b, result);
        }

        private static void checkWatchList(NodeStack nodeStack, HIRBlock b, NodeMap<HIRBlock> nodeMap, NodeBitMap unprocessed, ArrayList<Node> result, ArrayList<FloatingReadNode> watchList, Node n) {
            if (watchList != null && !watchList.isEmpty()) {
                // Check if this node kills a node in the watch list.
                if (MemoryKill.isSingleMemoryKill(n)) {
                    LocationIdentity identity = ((SingleMemoryKill) n).getKilledLocationIdentity();
                    checkWatchList(nodeStack, watchList, identity, b, result, nodeMap, unprocessed);
                } else if (MemoryKill.isMultiMemoryKill(n)) {
                    for (LocationIdentity identity : ((MultiMemoryKill) n).getKilledLocationIdentities()) {
                        checkWatchList(nodeStack, watchList, identity, b, result, nodeMap, unprocessed);
                    }
                }
            }
        }

        private static void checkWatchList(NodeStack nodeStack, ArrayList<FloatingReadNode> watchList, LocationIdentity identity, HIRBlock b, ArrayList<Node> result, NodeMap<HIRBlock> nodeMap,
                        NodeBitMap unprocessed) {
            if (identity.isImmutable()) {
                // Nothing to do. This can happen for an initialization write.
            } else if (identity.isAny()) {
                for (FloatingReadNode r : watchList) {
                    if (unprocessed.isMarked(r)) {
                        sortIntoList(nodeStack, r, b, result, nodeMap, unprocessed);
                    }
                }
                watchList.clear();
            } else {
                int index = 0;
                while (index < watchList.size()) {
                    FloatingReadNode r = watchList.get(index);
                    LocationIdentity locationIdentity = r.getLocationIdentity();
                    assert locationIdentity.isMutable();
                    if (unprocessed.isMarked(r)) {
                        if (identity.overlaps(locationIdentity)) {
                            sortIntoList(nodeStack, r, b, result, nodeMap, unprocessed);
                        } else {
                            ++index;
                            continue;
                        }
                    }
                    int lastIndex = watchList.size() - 1;
                    watchList.set(index, watchList.get(lastIndex));
                    watchList.remove(lastIndex);
                }
            }
        }

        private static void sortIntoList(NodeStack stack, Node n, HIRBlock b, ArrayList<Node> result, NodeMap<HIRBlock> nodeMap, NodeBitMap unprocessed) {
            assert unprocessed.isMarked(n) : Assertions.errorMessage(n);
            assert nodeMap.get(n) == b : Assertions.errorMessage(n);
            assert stack.isEmpty() : "Node stack must be pre-allocated, but empty.";
            assert !(n instanceof PhiNode) : "Phi nodes will never be sorted into the list.";
            assert !(n instanceof ProxyNode) : "Proxy nodes will never be sorted into the list.";

            unprocessed.clear(n);

            /*
             * Schedule all unprocessed transitive inputs. This uses an explicit stack instead of
             * recursion to avoid overflowing the call stack.
             */
            pushUnprocessedInputs(n, b, nodeMap, unprocessed, stack);
            while (!stack.isEmpty()) {
                Node top = stack.peek();
                int added = pushUnprocessedInputs(top, b, nodeMap, unprocessed, stack);
                if (added == 0) {
                    if (unprocessed.isMarked(top)) {
                        result.add(top);
                        unprocessed.clear(top);
                    }
                    stack.pop();
                }
            }
            result.add(n);
        }

        private static int pushUnprocessedInputs(Node n, HIRBlock b, NodeMap<HIRBlock> nodeMap, NodeBitMap unprocessed, NodeStack stack) {
            int pushCount = 0;
            for (Node input : n.inputs()) {
                if (nodeMap.get(input) == b && unprocessed.isMarked(input)) {
                    assert !(input instanceof PhiNode) : "Phi nodes will always be already unmarked in the bitmap.";
                    assert !(input instanceof ProxyNode) : "Proxy nodes will always be already unmarked in the bitmap.";
                    stack.push(input);
                    pushCount++;
                }
            }

            /*
             * Nodes on top of the stack are scheduled first. Pushing inputs left to right would
             * therefore mean scheduling them right to left. We observe the best performance when
             * scheduling inputs left to right, therefore we push them in reverse order. We could
             * explore more elaborate scheduling policies, like scheduling for reduced register
             * pressure using Sethi-Ullman numbering (GR-34624).
             */
            stack.reverseTopElements(pushCount);
            return pushCount;
        }

        protected void calcLatestBlock(HIRBlock earliestBlock, SchedulingStrategy strategy, Node currentNode, NodeMap<HIRBlock> currentNodeMap, LocationIdentity constrainingLocation,
                        BlockMap<ArrayList<FloatingReadNode>> watchListMap, BlockMap<List<Node>> latestBlockToNodesMap, NodeBitMap visited, boolean immutableGraph,
                        NodeBitMap moveInputsIntoDominator) {
            HIRBlock latestBlock = null;
            if (!currentNode.hasUsages()) {
                assert currentNode instanceof GuardNode : Assertions.errorMessage(currentNode);
                latestBlock = earliestBlock;
            } else {
                assert currentNode.hasUsages();
                for (Node usage : currentNode.usages()) {
                    if (immutableGraph && !visited.contains(usage)) {
                        /*
                         * Normally, dead nodes are deleted by the scheduler before we reach this
                         * point. Only when the scheduler is asked to not modify a graph, we can see
                         * dead nodes here.
                         */
                        continue;
                    }
                    latestBlock = calcLatestBlockForUsage(currentNode, usage, earliestBlock, latestBlock, currentNodeMap, moveInputsIntoDominator);
                    checkLatestEarliestRelation(currentNode, earliestBlock, latestBlock, usage);
                }

                assert latestBlock != null : currentNode;

                if (strategy.scheduleOutOfLoops()) {
                    HIRBlock currentBlock = latestBlock;
                    while (currentBlock.getLoopDepth() > earliestBlock.getLoopDepth() && currentBlock != earliestBlock.getDominator()) {
                        HIRBlock previousCurrentBlock = currentBlock;
                        currentBlock = currentBlock.getDominator();
                        if (previousCurrentBlock.isLoopHeader()) {
                            if (compareRelativeFrequencies(currentBlock.getRelativeFrequency(), latestBlock.getRelativeFrequency()) <= 0 ||
                                            ((StructuredGraph) currentNode.graph()).isBeforeStage(StageFlag.VALUE_PROXY_REMOVAL)) {
                                // Only assign new latest block if frequency is actually lower or if
                                // loop proxies would be required otherwise.
                                latestBlock = currentBlock;
                            }
                        }
                    }
                }

                if (latestBlock != earliestBlock && latestBlock != earliestBlock.getDominator() && constrainingLocation != null) {
                    latestBlock = checkKillsBetween(earliestBlock, latestBlock, constrainingLocation);
                }
            }

            if (latestBlock != earliestBlock && strategy.considerImplicitNullChecks() && isImplicitNullOpportunity(currentNode, earliestBlock, supportsImplicitNullChecks) &&
                            earliestBlock.getRelativeFrequency() < latestBlock.getRelativeFrequency() * IMPLICIT_NULL_CHECK_OPPORTUNITY_PROBABILITY_FACTOR) {
                latestBlock = earliestBlock;
            }

            // Check if inputs of this virtual state node need to be scheduled in the dominator of
            // the node's latest block. This can happen when the node or any of its usages
            // transitively is an input to the abstract begin node of the selected latest block.
            if (currentNode instanceof VirtualState) {
                for (Node usage : currentNode.usages()) {
                    if ((!moveInputsIntoDominator.isNew(usage) && moveInputsIntoDominator.isMarked(usage)) || (usage instanceof AbstractBeginNode && !(usage instanceof StartNode))) {
                        if (currentNodeMap.get(usage) == latestBlock) {
                            moveInputsIntoDominator.mark(currentNode);
                            break;
                        }
                    }
                }
            }

            selectLatestBlock(currentNode, earliestBlock, latestBlock, currentNodeMap, watchListMap, constrainingLocation, latestBlockToNodesMap);
        }

        public static int compareRelativeFrequencies(double f1, double f2) {
            double delta = 0.01D;
            double res = f1 - f2;
            if (res > delta) {
                return 1;
            }
            if (res < -delta) {
                return -1;
            }
            return 0;
        }

        protected static boolean isImplicitNullOpportunity(Node currentNode, HIRBlock block, boolean supportsImplicitNullChecks) {
            if (!supportsImplicitNullChecks) {
                return false;
            }
            if (currentNode instanceof FloatingReadNode floatingReadNode) {
                Node pred = block.getBeginNode().predecessor();
                if (pred instanceof IfNode ifNode) {
                    if (ifNode.condition() instanceof IsNullNode isNullNode && ifNode.getTrueSuccessorProbability() == 0.0) {
                        ValueNode base = floatingReadNode.getAddress().getBase();
                        return base != null && getUnproxifiedUncompressed(base) == getUnproxifiedUncompressed(isNullNode.getValue());
                    }
                }
            }
            return false;
        }

        private static Node getUnproxifiedUncompressed(Node node) {
            Node result = node;
            while (true) { // TERMINATION ARGUMENT: unproxifying inputs
                CompilationAlarm.checkProgress(node.graph());
                if (result instanceof ValueProxy) {
                    ValueProxy valueProxy = (ValueProxy) result;
                    result = valueProxy.getOriginalNode();
                } else if (result instanceof ConvertNode) {
                    ConvertNode convertNode = (ConvertNode) result;
                    if (convertNode.mayNullCheckSkipConversion()) {
                        result = convertNode.getValue();
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
            return result;
        }

        private static HIRBlock calcLatestBlockForUsage(Node node, Node usage, HIRBlock earliestBlock, HIRBlock initialLatestBlock, NodeMap<HIRBlock> currentNodeMap,
                        NodeBitMap moveInputsToDominator) {
            assert !(node instanceof PhiNode) : node;
            HIRBlock currentBlock = initialLatestBlock;
            if (usage instanceof PhiNode) {
                // An input to a PhiNode is used at the end of the predecessor block that
                // corresponds to the PhiNode input. One PhiNode can use an input multiple times.
                PhiNode phi = (PhiNode) usage;
                AbstractMergeNode merge = phi.merge();
                HIRBlock mergeBlock = currentNodeMap.get(merge);
                for (int i = 0; i < phi.valueCount(); ++i) {
                    if (phi.valueAt(i) == node) {
                        HIRBlock otherBlock = mergeBlock.getPredecessorAt(i);
                        currentBlock = AbstractControlFlowGraph.commonDominatorTyped(currentBlock, otherBlock);
                    }
                }
            } else {
                // All other types of usages: Put the input into the same block as the usage.
                HIRBlock otherBlock = currentNodeMap.get(usage);
                if (usage instanceof ProxyNode) {
                    ProxyNode proxyNode = (ProxyNode) usage;
                    otherBlock = currentNodeMap.get(proxyNode.proxyPoint());
                }

                if (!(node instanceof VirtualState) && !moveInputsToDominator.isNew(usage) && moveInputsToDominator.isMarked(usage)) {
                    /*
                     * The usage is marked as forcing its inputs into the dominator. Respect that if
                     * we can, but the dominator might not be a legal position for the node. This is
                     * the case for loop-variant floating nodes between a loop phi and a virtual
                     * state on the loop begin.
                     */
                    HIRBlock dominator = otherBlock.getDominator();
                    if (AbstractControlFlowGraph.dominates(earliestBlock, dominator)) {
                        otherBlock = dominator;
                    }
                    GraalError.guarantee(otherBlock != null, "Dominators need to be computed in the CFG");
                }

                currentBlock = AbstractControlFlowGraph.commonDominatorTyped(currentBlock, otherBlock);
            }
            return currentBlock;
        }

        /**
         * Micro block that is allocated for each fixed node and captures all floating nodes that
         * need to be scheduled immediately after the corresponding fixed node.
         */
        private static class MicroBlock {
            private final int id;
            private int nodeCount;
            private NodeEntry head;
            private NodeEntry tail;

            MicroBlock(int id) {
                this.id = id;
            }

            /**
             * Adds a new floating node into the micro block.
             */
            public void add(Node node) {
                assert !(node instanceof FixedNode) : node;
                NodeEntry newTail = new NodeEntry(node);
                if (tail == null) {
                    tail = head = newTail;
                } else {
                    tail.next = newTail;
                    tail = newTail;
                }
                nodeCount++;
            }

            /**
             * Number of nodes in this micro block.
             */
            public int getNodeCount() {
                return nodeCount;
            }

            /**
             * The id of the micro block, with a block always associated with a lower id than its
             * successors.
             */
            public int getId() {
                return id;
            }

            /**
             * First node of the linked list of nodes of this micro block.
             */
            public NodeEntry getFirstNode() {
                return head;
            }

            /**
             * Takes all nodes in this micro blocks and prepends them to the nodes of the given
             * parameter.
             *
             * @param newBlock the new block for the nodes
             */
            public void prependChildrenTo(MicroBlock newBlock) {
                if (tail != null) {
                    assert head != null;
                    tail.next = newBlock.head;
                    newBlock.head = head;
                    head = tail = null;
                    newBlock.nodeCount += nodeCount;
                    nodeCount = 0;
                }
            }

            @Override
            public String toString() {
                return String.format("MicroBlock[id=%d]", id);
            }

            @Override
            public int hashCode() {
                return id;
            }
        }

        /**
         * Entry in the linked list of nodes.
         */
        private static class NodeEntry {
            private final Node node;
            private NodeEntry next;

            NodeEntry(Node node) {
                this.node = node;
                this.next = null;
            }

            public NodeEntry getNext() {
                return next;
            }

            public Node getNode() {
                return node;
            }
        }

        private void scheduleEarliestIterative(BlockMap<List<Node>> blockToNodes, NodeMap<HIRBlock> nodeToBlock, NodeBitMap visited, StructuredGraph graph, boolean immutableGraph,
                        boolean withGuardOrder) {

            NodeMap<MicroBlock> entries = graph.createNodeMap();
            NodeStack stack = new NodeStack();

            // Initialize with fixed nodes.
            MicroBlock startBlock = null;
            int nextId = 1;
            for (HIRBlock b : cfg.reversePostOrder()) {
                for (FixedNode current : b.getBeginNode().getBlockNodes()) {
                    MicroBlock microBlock = new MicroBlock(nextId++);
                    entries.set(current, microBlock);
                    boolean isNew = visited.checkAndMarkInc(current);
                    assert isNew;
                    if (startBlock == null) {
                        startBlock = microBlock;
                    }
                }
            }

            if (graph.getGuardsStage().allowsFloatingGuards() && graph.hasNode(GuardNode.TYPE)) {
                // Now process guards.
                if (GuardPriorities.getValue(graph.getOptions()) && withGuardOrder) {
                    EnumMap<GuardPriority, List<GuardNode>> guardsByPriority = new EnumMap<>(GuardPriority.class);
                    for (GuardNode guard : graph.getNodes(GuardNode.TYPE)) {
                        guardsByPriority.computeIfAbsent(guard.computePriority(), p -> new ArrayList<>()).add(guard);
                    }
                    // `EnumMap.values` returns values in "natural" key order
                    for (List<GuardNode> guards : guardsByPriority.values()) {
                        processNodes(visited, entries, stack, startBlock, guards);
                    }
                    GuardOrder.resortGuards(graph, entries, stack);
                } else {
                    processNodes(visited, entries, stack, startBlock, graph.getNodes(GuardNode.TYPE));
                }
            }

            // Now process inputs of fixed nodes.
            for (HIRBlock b : cfg.reversePostOrder()) {
                for (FixedNode current : b.getBeginNode().getBlockNodes()) {
                    processNodes(visited, entries, stack, startBlock, current.inputs());
                }
            }

            if (visited.getCounter() < graph.getNodeCount()) {
                // Visit back input edges of loop phis.
                boolean changed;
                boolean unmarkedPhi;
                do {
                    changed = false;
                    unmarkedPhi = false;
                    for (LoopBeginNode loopBegin : graph.getNodes(LoopBeginNode.TYPE)) {
                        for (PhiNode phi : loopBegin.phis()) {
                            if (visited.isMarked(phi)) {
                                for (int i = 0; i < loopBegin.getLoopEndCount(); ++i) {
                                    Node node = phi.valueAt(i + loopBegin.forwardEndCount());
                                    if (node != null && entries.get(node) == null) {
                                        changed = true;
                                        processStack(node, startBlock, entries, visited, stack);
                                    }
                                }
                            } else {
                                unmarkedPhi = true;
                            }
                        }
                    }

                    /*
                     * the processing of one loop phi could have marked a previously checked loop
                     * phi, therefore this needs to be iterative.
                     */
                } while (unmarkedPhi && changed);
            }

            // Check for dead nodes.
            if (!immutableGraph && visited.getCounter() < graph.getNodeCount()) {
                for (Node n : graph.getNodes()) {
                    if (!visited.isMarked(n)) {
                        n.clearInputs();
                        n.markDeleted();
                    }
                }
            }

            for (HIRBlock b : cfg.reversePostOrder()) {
                FixedNode fixedNode = b.getEndNode();
                if (fixedNode instanceof ControlSplitNode) {
                    ControlSplitNode controlSplitNode = (ControlSplitNode) fixedNode;
                    MicroBlock endBlock = entries.get(fixedNode);
                    AbstractBeginNode primarySuccessor = controlSplitNode.getPrimarySuccessor();
                    if (primarySuccessor != null) {
                        endBlock.prependChildrenTo(entries.get(primarySuccessor));
                    } else {
                        assert endBlock.tail == null;
                    }
                }
            }

            // Create lists for each block
            for (HIRBlock b : cfg.reversePostOrder()) {
                // Count nodes in block
                int totalCount = 0;
                for (FixedNode current : b.getBeginNode().getBlockNodes()) {
                    MicroBlock microBlock = entries.get(current);
                    totalCount += microBlock.getNodeCount() + 1;
                }

                // Initialize with begin node, it is always the first node.
                ArrayList<Node> nodes = new ArrayList<>(totalCount);
                blockToNodes.put(b, nodes);

                for (FixedNode current : b.getBeginNode().getBlockNodes()) {
                    MicroBlock microBlock = entries.get(current);
                    nodeToBlock.set(current, b);
                    nodes.add(current);
                    NodeEntry next = microBlock.getFirstNode();
                    while (next != null) {
                        Node nextNode = next.getNode();
                        nodeToBlock.set(nextNode, b);
                        nodes.add(nextNode);
                        next = next.getNext();
                    }
                }
            }

            assert (!Assertions.detailedAssertionsEnabled(cfg.graph.getOptions())) || ScheduleVerification.check(cfg.getStartBlock(), blockToNodes, nodeToBlock);
        }

        private static void processNodes(NodeBitMap visited, NodeMap<MicroBlock> entries, NodeStack stack, MicroBlock startBlock, Iterable<? extends Node> nodes) {
            for (Node node : nodes) {
                if (entries.get(node) == null) {
                    processStack(node, startBlock, entries, visited, stack);
                }
            }
        }

        private static void processStackPhi(NodeStack stack, PhiNode phiNode, NodeMap<MicroBlock> nodeToBlock, NodeBitMap visited) {
            stack.pop();
            if (visited.checkAndMarkInc(phiNode)) {
                MicroBlock mergeBlock = nodeToBlock.get(phiNode.merge());
                assert mergeBlock != null : phiNode;
                nodeToBlock.set(phiNode, mergeBlock);
                AbstractMergeNode merge = phiNode.merge();
                for (int i = 0; i < merge.forwardEndCount(); ++i) {
                    Node input = phiNode.valueAt(i);
                    if (input != null && nodeToBlock.get(input) == null) {
                        stack.push(input);
                    }
                }
            }
        }

        private static void processStackProxy(NodeStack stack, ProxyNode proxyNode, NodeMap<MicroBlock> nodeToBlock, NodeBitMap visited) {
            stack.pop();
            if (visited.checkAndMarkInc(proxyNode)) {
                nodeToBlock.set(proxyNode, nodeToBlock.get(proxyNode.proxyPoint()));
                Node input = proxyNode.value();
                if (input != null && nodeToBlock.get(input) == null) {
                    stack.push(input);
                }
            }
        }

        private static void processStack(Node first, MicroBlock startBlock, NodeMap<MicroBlock> nodeToMicroBlock, NodeBitMap visited, NodeStack stack) {
            assert stack.isEmpty();
            assert !visited.isMarked(first);
            stack.push(first);
            Node current = first;
            while (true) { // TERMINATION ARGUMENT: processing stack until empty
                CompilationAlarm.checkProgress(first.graph());
                if (current instanceof PhiNode) {
                    processStackPhi(stack, (PhiNode) current, nodeToMicroBlock, visited);
                } else if (current instanceof ProxyNode) {
                    processStackProxy(stack, (ProxyNode) current, nodeToMicroBlock, visited);
                } else {
                    MicroBlock currentBlock = nodeToMicroBlock.get(current);
                    if (currentBlock == null) {
                        MicroBlock earliestBlock = processInputs(nodeToMicroBlock, stack, startBlock, current);
                        if (earliestBlock == null) {
                            // We need to delay until inputs are processed.
                        } else {
                            // Can immediately process and pop.
                            stack.pop();
                            visited.checkAndMarkInc(current);
                            nodeToMicroBlock.set(current, earliestBlock);
                            earliestBlock.add(current);
                        }
                    } else {
                        stack.pop();
                    }
                }

                if (stack.isEmpty()) {
                    break;
                }
                current = stack.peek();
            }
        }

        private static final class GuardOrder {
            /**
             * After an earliest schedule, this will re-sort guards to honor their
             * {@linkplain StaticDeoptimizingNode#computePriority() priority}.
             *
             * Note that this only changes the order of nodes within {@linkplain MicroBlock
             * micro-blocks}, nodes will not be moved from one micro-block to another.
             */
            private static void resortGuards(StructuredGraph graph, NodeMap<MicroBlock> entries, NodeStack stack) {
                assert stack.isEmpty();
                EconomicSet<MicroBlock> blocksWithGuards = EconomicSet.create(IDENTITY);
                for (GuardNode guard : graph.getNodes(GuardNode.TYPE)) {
                    MicroBlock block = entries.get(guard);
                    assert block != null : guard + "should already be scheduled to a micro-block";
                    blocksWithGuards.add(block);
                }
                assert !blocksWithGuards.isEmpty();
                NodeMap<GuardPriority> priorities = graph.createNodeMap();
                NodeBitMap blockNodes = graph.createNodeBitMap();
                for (MicroBlock block : blocksWithGuards) {
                    MicroBlock newBlock = resortGuards(block, stack, blockNodes, priorities);
                    assert stack.isEmpty();
                    assert blockNodes.isEmpty();
                    if (newBlock != null) {
                        assert block.getNodeCount() == newBlock.getNodeCount() : Assertions.errorMessage(block, newBlock);
                        block.head = newBlock.head;
                        block.tail = newBlock.tail;
                    }
                }
            }

            /**
             * This resorts guards within one micro-block.
             *
             * {@code stack}, {@code blockNodes} and {@code priorities} are just temporary
             * data-structures which are allocated once by the callers of this method. They should
             * be in their "initial"/"empty" state when calling this method and when it returns.
             */
            private static MicroBlock resortGuards(MicroBlock block, NodeStack stack, NodeBitMap blockNodes, NodeMap<GuardPriority> priorities) {
                if (!propagatePriority(block, stack, priorities, blockNodes)) {
                    return null;
                }

                Function<GuardNode, GuardPriority> transitiveGuardPriorityGetter = priorities::get;
                Comparator<GuardNode> globalGuardPriorityComparator = Comparator.comparing(transitiveGuardPriorityGetter).thenComparing(GuardNode::computePriority).thenComparingInt(Node::hashCode);

                SortedSet<GuardNode> availableGuards = new TreeSet<>(globalGuardPriorityComparator);
                MicroBlock newBlock = new MicroBlock(block.getId());

                NodeBitMap sorted = blockNodes;
                sorted.invert();

                for (NodeEntry e = block.head; e != null; e = e.next) {
                    checkIfAvailable(e.node, stack, sorted, newBlock, availableGuards, false);
                }
                do {
                    while (!stack.isEmpty()) {
                        checkIfAvailable(stack.pop(), stack, sorted, newBlock, availableGuards, true);
                    }
                    Iterator<GuardNode> iterator = availableGuards.iterator();
                    if (iterator.hasNext()) {
                        addNodeToResort(iterator.next(), stack, sorted, newBlock, true);
                        iterator.remove();
                    }
                } while (!stack.isEmpty() || !availableGuards.isEmpty());

                blockNodes.clearAll();
                return newBlock;
            }

            /**
             * This checks if {@code n} can be scheduled, if it is the case, it schedules it now by
             * calling {@link #addNodeToResort(Node, NodeStack, NodeBitMap, MicroBlock, boolean)}.
             */
            private static void checkIfAvailable(Node n, NodeStack stack, NodeBitMap sorted, Instance.MicroBlock newBlock, SortedSet<GuardNode> availableGuardNodes, boolean pushUsages) {
                if (sorted.isMarked(n)) {
                    return;
                }
                for (Node in : n.inputs()) {
                    if (!sorted.isMarked(in)) {
                        return;
                    }
                }
                if (n instanceof GuardNode) {
                    availableGuardNodes.add((GuardNode) n);
                } else {
                    addNodeToResort(n, stack, sorted, newBlock, pushUsages);
                }
            }

            /**
             * Add a node to the re-sorted micro-block. This also pushes nodes that need to be
             * (re-)examined on the stack.
             */
            private static void addNodeToResort(Node n, NodeStack stack, NodeBitMap sorted, MicroBlock newBlock, boolean pushUsages) {
                sorted.mark(n);
                newBlock.add(n);
                if (pushUsages) {
                    for (Node u : n.usages()) {
                        if (!sorted.isMarked(u)) {
                            stack.push(u);
                        }
                    }
                }
            }

            /**
             * This fills in a map of transitive priorities ({@code priorities}). It also marks the
             * nodes from this micro-block in {@code blockNodes}.
             *
             * The transitive priority of a guard is the highest of its priority and the priority of
             * the guards that depend on it (transitively).
             *
             * This method returns {@code false} if no re-ordering is necessary in this micro-block.
             */
            private static boolean propagatePriority(MicroBlock block, NodeStack stack, NodeMap<GuardPriority> priorities, NodeBitMap blockNodes) {
                assert stack.isEmpty();
                assert blockNodes.isEmpty();
                GuardPriority lowestPriority = GuardPriority.highest();
                for (NodeEntry e = block.head; e != null; e = e.next) {
                    blockNodes.mark(e.node);
                    if (e.node instanceof GuardNode) {
                        GuardNode guard = (GuardNode) e.node;
                        GuardPriority priority = guard.computePriority();
                        if (lowestPriority != null) {
                            if (priority.isLowerPriorityThan(lowestPriority)) {
                                lowestPriority = priority;
                            } else if (priority.isHigherPriorityThan(lowestPriority)) {
                                lowestPriority = null;
                            }
                        }
                        stack.push(guard);
                        priorities.set(guard, priority);
                    }
                }
                if (lowestPriority != null) {
                    stack.clear();
                    blockNodes.clearAll();
                    return false;
                }

                do {
                    Node current = stack.pop();
                    assert blockNodes.isMarked(current);
                    GuardPriority priority = priorities.get(current);
                    for (Node input : current.inputs()) {
                        if (!blockNodes.isMarked(input)) {
                            continue;
                        }
                        GuardPriority inputPriority = priorities.get(input);
                        if (inputPriority == null || inputPriority.isLowerPriorityThan(priority)) {
                            priorities.set(input, priority);
                            stack.push(input);
                        }
                    }
                } while (!stack.isEmpty());
                return true;
            }
        }

        /**
         * Processes the inputs of given block. Pushes unprocessed inputs onto the stack. Returns
         * null if there were still unprocessed inputs, otherwise returns the earliest block given
         * node can be scheduled in.
         */
        private static MicroBlock processInputs(NodeMap<MicroBlock> nodeToBlock, NodeStack stack, MicroBlock startBlock, Node current) {
            if (current.getNodeClass().isLeafNode()) {
                return startBlock;
            }

            MicroBlock earliestBlock = startBlock;
            for (Node input : current.inputs()) {
                GraalError.guarantee(input != current, "Cannot have direct cycles on schedulable nodes %s", current);
                MicroBlock inputBlock = nodeToBlock.get(input);
                if (inputBlock == null) {
                    earliestBlock = null;
                    stack.push(input);
                } else if (earliestBlock != null && inputBlock.getId() > earliestBlock.getId()) {
                    earliestBlock = inputBlock;
                }
            }
            return earliestBlock;
        }

        private static boolean isFixedEnd(FixedNode endNode) {
            return endNode instanceof ControlSplitNode || endNode instanceof ControlSinkNode || endNode instanceof AbstractEndNode;
        }

        public String printScheduleHelper(String desc) {
            Formatter buf = new Formatter();
            buf.format("=== %s / %s ===%n", getCFG().getStartBlock().getBeginNode().graph(), desc);
            for (HIRBlock b : getCFG().getBlocks()) {
                buf.format("==== b: %s (loopDepth: %s). ", b, b.getLoopDepth());
                buf.format("dom: %s. ", b.getDominator());

                int predCount = b.getPredecessorCount();
                HIRBlock[] pred = new HIRBlock[predCount];
                for (int i = 0; i < predCount; i++) {
                    pred[i] = b.getPredecessorAt(i);
                }
                int succCount = b.getSuccessorCount();
                HIRBlock[] succ = new HIRBlock[succCount];
                for (int i = 0; i < succCount; i++) {
                    succ[i] = b.getSuccessorAt(i);
                }
                buf.format("preds: %s. ", Arrays.toString(pred));
                buf.format("succs: %s ====%n", Arrays.toString(succ));

                if (blockToNodesMap.get(b) != null) {
                    for (Node n : nodesFor(b)) {
                        printNode(n);
                    }
                } else {
                    for (Node n : b.getNodes()) {
                        printNode(n);
                    }
                }
            }
            buf.format("%n");
            return buf.toString();
        }

        private static void printNode(Node n) {
            Formatter buf = new Formatter();
            buf.format("%s", n);
            if (MemoryKill.isSingleMemoryKill(n)) {
                buf.format(" // kills %s", ((SingleMemoryKill) n).getKilledLocationIdentity());
            } else if (MemoryKill.isMultiMemoryKill(n)) {
                buf.format(" // kills ");
                for (LocationIdentity locid : ((MultiMemoryKill) n).getKilledLocationIdentities()) {
                    buf.format("%s, ", locid);
                }
            } else if (n instanceof FloatingReadNode) {
                FloatingReadNode frn = (FloatingReadNode) n;
                buf.format(" // from %s", frn.getLocationIdentity());
                buf.format(", lastAccess: %s", frn.getLastLocationAccess());
                buf.format(", address: %s", frn.getAddress());
            } else if (n instanceof GuardNode) {
                buf.format(", anchor: %s", ((GuardNode) n).getAnchor());
            }
            n.getDebug().log("%s", buf);
        }

        public ControlFlowGraph getCFG() {
            return cfg;
        }

        /**
         * Gets the nodes in a given block.
         */
        public List<Node> nodesFor(HIRBlock block) {
            return blockToNodesMap.get(block);
        }
    }

}
