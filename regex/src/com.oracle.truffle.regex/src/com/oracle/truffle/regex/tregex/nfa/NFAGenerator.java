/*
 * Copyright (c) 2018, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.regex.tregex.nfa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.graalvm.collections.EconomicMap;

import com.oracle.truffle.regex.charset.CodePointSet;
import com.oracle.truffle.regex.tregex.TRegexOptions;
import com.oracle.truffle.regex.tregex.automaton.StateSet;
import com.oracle.truffle.regex.tregex.automaton.TransitionBuilder;
import com.oracle.truffle.regex.tregex.automaton.TransitionConstraint;
import com.oracle.truffle.regex.tregex.automaton.TransitionOp;
import com.oracle.truffle.regex.tregex.buffer.CompilationBuffer;
import com.oracle.truffle.regex.tregex.parser.Counter;
import com.oracle.truffle.regex.tregex.parser.ast.CharacterClass;
import com.oracle.truffle.regex.tregex.parser.ast.LookBehindAssertion;
import com.oracle.truffle.regex.tregex.parser.ast.MatchFound;
import com.oracle.truffle.regex.tregex.parser.ast.PositionAssertion;
import com.oracle.truffle.regex.tregex.parser.ast.RegexAST;
import com.oracle.truffle.regex.tregex.parser.ast.RegexASTNode;
import com.oracle.truffle.regex.tregex.parser.ast.Sequence;
import com.oracle.truffle.regex.tregex.parser.ast.Term;
import com.oracle.truffle.regex.util.TBitSet;

public final class NFAGenerator {

    private final RegexAST ast;
    private final Counter.ThresholdCounter stateID = new Counter.ThresholdCounter(TRegexOptions.TRegexMaxNFASize, "NFA explosion");
    private final Counter.ThresholdCounter transitionID = new Counter.ThresholdCounter(Short.MAX_VALUE, "NFA transition explosion");
    private final NFAState dummyInitialState;
    private final NFAState[] anchoredInitialStates;
    private final NFAState[] initialStates;
    /**
     * These are like {@link #initialStates}, but with {@code mustAdvance} set to {@code false},
     * i.e. we have already advanced when we are in these states. In a regular expression with
     * {@code MustAdvance=true}, all loopback transitions end in {@link #advancedInitialState}
     * instead of {@link #initialStates}.
     */
    private final NFAState advancedInitialState;
    private final NFAState anchoredFinalState;
    private final NFAState finalState;
    private final NFAStateTransition[] anchoredEntries;
    private final NFAStateTransition[] unAnchoredEntries;
    private final NFAStateTransition anchoredReverseEntry;
    private final NFAStateTransition unAnchoredReverseEntry;
    private NFAStateTransition initialLoopBack;
    private final Deque<NFAState> expansionQueue = new ArrayDeque<>();
    private final Map<NFAStateID, NFAState> nfaStates = new HashMap<>();
    private final List<NFAState> prefixStates = new ArrayList<>();
    private final ASTStepVisitor astStepVisitor;
    private final ASTTransitionCanonicalizer astTransitionCanonicalizer;
    private final TBitSet transitionGBUpdateIndices;
    private final TBitSet transitionGBClearIndices;
    private int lastGroup = -1;
    private final ArrayList<NFAStateTransition> transitionsBuffer = new ArrayList<>();
    private final CompilationBuffer compilationBuffer;

    private NFAGenerator(RegexAST ast, CompilationBuffer compilationBuffer) {
        this.ast = ast;
        this.astStepVisitor = new ASTStepVisitor(ast);
        this.transitionGBUpdateIndices = new TBitSet(ast.getNumberOfCaptureGroups() * 2);
        this.transitionGBClearIndices = new TBitSet(ast.getNumberOfCaptureGroups() * 2);
        this.astTransitionCanonicalizer = new ASTTransitionCanonicalizer(ast, true, false, ast.getOptions().isBooleanMatch());
        this.compilationBuffer = compilationBuffer;
        dummyInitialState = new NFAState((short) stateID.inc(), StateSet.create(ast, ast.getWrappedRoot()), Collections.emptySet(), false, ast.getOptions().isMustAdvance());
        nfaStates.put(NFAStateID.create(dummyInitialState), dummyInitialState);
        anchoredFinalState = createFinalState(StateSet.create(ast, ast.getRoot().getSubTreeParent().getAnchoredFinalState()), false);
        anchoredFinalState.setAnchoredFinalState();
        finalState = createFinalState(StateSet.create(ast, ast.getRoot().getSubTreeParent().getMatchFound()), false);
        finalState.setUnAnchoredFinalState();
        assert transitionGBUpdateIndices.isEmpty() && transitionGBClearIndices.isEmpty();
        anchoredReverseEntry = createTransition(anchoredFinalState, dummyInitialState, ast.getEncoding().getFullSet(), TransitionConstraint.NO_CONSTRAINTS, TransitionOp.NO_OP);
        unAnchoredReverseEntry = createTransition(finalState, dummyInitialState, ast.getEncoding().getFullSet(), TransitionConstraint.NO_CONSTRAINTS, TransitionOp.NO_OP);
        int nEntries = ast.getWrappedPrefixLength() + 1;
        initialStates = new NFAState[nEntries];
        advancedInitialState = ast.getOptions().isMustAdvance() ? createFinalState(StateSet.create(ast, ast.getNFAUnAnchoredInitialState(0)), false) : null;
        unAnchoredEntries = new NFAStateTransition[nEntries];
        for (int i = 0; i < initialStates.length; i++) {
            initialStates[i] = createFinalState(StateSet.create(ast, ast.getNFAUnAnchoredInitialState(i)), ast.getOptions().isMustAdvance());
            initialStates[i].setUnAnchoredInitialState(true);
            unAnchoredEntries[i] = createTransition(dummyInitialState, initialStates[i], ast.getEncoding().getFullSet(), TransitionConstraint.NO_CONSTRAINTS, TransitionOp.NO_OP);
            if (i > 0) {
                initialStates[i].setHasPrefixStates(true);
            }
        }
        if (ast.getReachableCarets().isEmpty()) {
            anchoredInitialStates = initialStates;
            anchoredEntries = unAnchoredEntries;
            NFAStateTransition[] dummyInitNext = Arrays.copyOf(anchoredEntries, nEntries);
            dummyInitialState.setSuccessors(dummyInitNext, false);
        } else {
            anchoredInitialStates = new NFAState[nEntries];
            anchoredEntries = new NFAStateTransition[nEntries];
            for (int i = 0; i < anchoredInitialStates.length; i++) {
                anchoredInitialStates[i] = createFinalState(StateSet.create(ast, ast.getNFAAnchoredInitialState(i)), ast.getOptions().isMustAdvance());
                anchoredInitialStates[i].setAnchoredInitialState();
                if (i > 0) {
                    initialStates[i].setHasPrefixStates(true);
                }
                anchoredEntries[i] = createTransition(dummyInitialState, anchoredInitialStates[i], ast.getEncoding().getFullSet(), TransitionConstraint.NO_CONSTRAINTS, TransitionOp.NO_OP);
            }
            NFAStateTransition[] dummyInitNext = Arrays.copyOf(anchoredEntries, nEntries * 2);
            System.arraycopy(unAnchoredEntries, 0, dummyInitNext, nEntries, nEntries);
            dummyInitialState.setSuccessors(dummyInitNext, false);
        }
        NFAStateTransition[] dummyInitPrev = new NFAStateTransition[]{anchoredReverseEntry, unAnchoredReverseEntry};
        dummyInitialState.setPredecessors(dummyInitPrev);
    }

    public static NFA createNFA(RegexAST ast, CompilationBuffer compilationBuffer) {
        return new NFAGenerator(ast, compilationBuffer).doCreateNFA();
    }

    private NFA doCreateNFA() {
        Collections.addAll(expansionQueue, initialStates);
        if (ast.getOptions().isMustAdvance()) {
            expansionQueue.add(advancedInitialState);
        }
        if (!ast.getReachableCarets().isEmpty()) {
            Collections.addAll(expansionQueue, anchoredInitialStates);
        }

        while (!expansionQueue.isEmpty()) {
            expandNFAState(expansionQueue.pop());
        }

        assert transitionGBUpdateIndices.isEmpty() && transitionGBClearIndices.isEmpty();
        for (int i = 1; i < initialStates.length; i++) {
            addNewLoopBackTransition(initialStates[i], initialStates[i - 1]);
        }
        if (ast.getOptions().isMustAdvance()) {
            addNewLoopBackTransition(initialStates[0], advancedInitialState);
            initialLoopBack = createTransition(advancedInitialState, advancedInitialState, ast.getEncoding().getFullSet(), TransitionConstraint.NO_CONSTRAINTS, TransitionOp.NO_OP);
        } else {
            initialLoopBack = createTransition(initialStates[0], initialStates[0], ast.getEncoding().getFullSet(), TransitionConstraint.NO_CONSTRAINTS, TransitionOp.NO_OP);
        }

        for (NFAState s : nfaStates.values()) {
            if (s != dummyInitialState && needsReverseTransitions(s)) {
                s.linkPredecessors();
            }
        }

        pruneDeadStates();

        return new NFA(ast, dummyInitialState, anchoredEntries, unAnchoredEntries, anchoredReverseEntry, unAnchoredReverseEntry, nfaStates.values(), stateID, transitionID, initialLoopBack, null);
    }

    private boolean needsReverseTransitions(NFAState s) {
        return ast.getHardPrefixNodes().isDisjoint(s.getStateSet()) && !ast.getPrefixNodes().containsAll(s.getStateSet()) || ast.getFlags().isSticky();
    }

    private void pruneDeadStates() {
        ArrayList<NFAState> deadStates = new ArrayList<>();
        findDeadStates(deadStates);
        while (!deadStates.isEmpty()) {
            for (NFAState state : deadStates) {
                for (NFAStateTransition pre : state.getPredecessors()) {
                    pre.getSource().removeSuccessor(state);
                }
                // prefixStates are not reachable by prev-transitions
                for (NFAState prefixState : prefixStates) {
                    prefixState.removeSuccessor(state);
                }
                for (NFAState initialState : initialStates) {
                    initialState.removeSuccessor(state);
                }
                for (NFAState initialState : anchoredInitialStates) {
                    initialState.removeSuccessor(state);
                }
                for (int i = 0; i < unAnchoredEntries.length; i++) {
                    if (unAnchoredEntries[i] != null && unAnchoredEntries[i].getTarget() == state) {
                        unAnchoredEntries[i] = null;
                    }
                }
                for (int i = 0; i < anchoredEntries.length; i++) {
                    if (anchoredEntries[i] != null && anchoredEntries[i].getTarget() == state) {
                        anchoredEntries[i] = null;
                    }
                }
                if (initialLoopBack != null && initialLoopBack.getTarget() == state) {
                    initialLoopBack = null;
                }
                if (ast.getOptions().isMustAdvance()) {
                    advancedInitialState.removeSuccessor(state);
                }
                dummyInitialState.removeSuccessor(state);
                nfaStates.remove(NFAStateID.create(state));
            }
            deadStates.clear();
            findDeadStates(deadStates);
        }
    }

    private void findDeadStates(ArrayList<NFAState> deadStates) {
        for (NFAState state : nfaStates.values()) {
            if (state.isDead(true)) {
                deadStates.add(state);
            }
        }
    }

    private void expandNFAState(NFAState curState) {
        ASTStep nextStep = astStepVisitor.step(curState);
        boolean isPrefixState = !ast.getHardPrefixNodes().isDisjoint(curState.getStateSet()) || ast.getPrefixNodes().containsAll(curState.getStateSet());
        if (isPrefixState) {
            prefixStates.add(curState);
        }
        curState.setSuccessors(createNFATransitions(curState, nextStep), !isPrefixState || ast.getFlags().isSticky());
    }

    private NFAStateTransition[] createNFATransitions(NFAState sourceState, ASTStep nextStep) {
        transitionsBuffer.clear();
        StateSet<RegexAST, CharacterClass> stateSetCC;
        StateSet<RegexAST, LookBehindAssertion> finishedLookBehinds;
        for (ASTSuccessor successor : nextStep.getSuccessors()) {
            for (TransitionBuilder<RegexAST, Term, ASTTransition> mergeBuilder : successor.getMergedStates(astTransitionCanonicalizer, compilationBuffer)) {
                stateSetCC = null;
                finishedLookBehinds = null;
                boolean containsPositionAssertion = false;
                boolean containsMatchFound = false;
                boolean containsPrefixStates = false;
                boolean allCCInLookBehind = true;
                EconomicMap<Integer, TBitSet> matchedConditionGroupsMap = ast.getProperties().hasConditionalBackReferences() ? EconomicMap.create() : null;
                for (ASTTransition astTransition : mergeBuilder.getTransitionSet().getTransitions()) {
                    Term target = astTransition.getTarget();
                    boolean inLookBehindAssertion = target.isInLookBehindAssertion();
                    if (target instanceof CharacterClass) {
                        if (stateSetCC == null) {
                            stateSetCC = StateSet.create(ast);
                            finishedLookBehinds = StateSet.create(ast);
                        }
                        stateSetCC.add((CharacterClass) target);
                        allCCInLookBehind &= inLookBehindAssertion;
                        if (inLookBehindAssertion && target == ((Sequence) target.getParent()).getLastTerm()) {
                            finishedLookBehinds.add((LookBehindAssertion) target.getSubTreeParent());
                        }
                    } else if (target instanceof PositionAssertion) {
                        containsPositionAssertion = true;
                    } else {
                        assert target instanceof MatchFound;
                        containsMatchFound = true;
                    }
                    containsPrefixStates |= target.isPrefix();
                    astTransition.getGroupBoundaries().updateBitSets(transitionGBUpdateIndices, transitionGBClearIndices);
                    if (!target.isInLookAroundAssertion()) {
                        lastGroup = astTransition.getGroupBoundaries().getLastGroup();
                    }
                    if (ast.getProperties().hasConditionalBackReferences()) {
                        matchedConditionGroupsMap.put(target.getId(), astTransition.getMatchedConditionGroups());
                    }
                }
                if (!(sourceState.isMustAdvance() && transitionGBUpdateIndices.get(0) && transitionGBUpdateIndices.get(1))) {
                    // Here we assume the transition set to be of size one to gather the guards,
                    // which is true as long as we don't have lookaround, but in that
                    // case we just don't have guards anyway
                    long[] constraints = mapConstraintStateID(mergeBuilder.getConstraints(), sourceState.getId());
                    long[] operations = mapOpSourceAndTarget(mergeBuilder.getOperations(), sourceState.getId(), finalState.getId());

                    if (containsPositionAssertion) {
                        if (stateSetCC == null || allCCInLookBehind) {
                            changeOpTarget(operations, anchoredFinalState.getId());
                            transitionsBuffer.add(createTransition(sourceState, anchoredFinalState, ast.getEncoding().getFullSet(), constraints, operations));
                        }
                    } else if (stateSetCC == null) {
                        if (containsMatchFound && mergeBuilder.getCodePointSet().matchesEverything(ast.getEncoding())) {
                            transitionsBuffer.add(createTransition(sourceState, finalState, ast.getEncoding().getFullSet(), constraints, operations));
                            // Transitions dominated by a transition to a final state will never
                            // end up being used, so we can skip generating them and return the
                            // current list of transitions.
                            if (!sourceState.hasPrefixStates() && !(initialStates.length > 1 && sourceState == initialStates[0]) && constraints.length == 0) {
                                // the only exception to this rule is when the source state is one
                                // of those artificially inserted for look-behind merging; in this
                                // case we must preserve all transitions for the backward DFA.
                                clearGroupBoundaries();
                                return transitionsBuffer.toArray(NFAStateTransition[]::new);
                            }
                        }
                    } else {
                        if (containsMatchFound && allCCInLookBehind) {
                            // possible when a not fully matched lookbehind is still being tracked
                            // when the main expression already reached a final state.
                            transitionsBuffer.add(createTransition(sourceState, finalState, ast.getEncoding().getFullSet(), constraints, operations));
                            operations = Arrays.copyOf(operations, operations.length);
                        }
                        assert mergeBuilder.getCodePointSet().matchesSomething();
                        NFAState targetState = registerMatcherState(stateSetCC, finishedLookBehinds, containsPrefixStates,
                                        sourceState.isMustAdvance() && !ast.getHardPrefixNodes().isDisjoint(stateSetCC), matchedConditionGroupsMap);
                        changeOpTarget(operations, targetState.getId());
                        transitionsBuffer.add(createTransition(sourceState, targetState, mergeBuilder.getCodePointSet(), constraints, operations));
                    }
                }
                clearGroupBoundaries();
            }
        }
        return transitionsBuffer.toArray(NFAStateTransition[]::new);
    }

    private void clearGroupBoundaries() {
        transitionGBUpdateIndices.clear();
        transitionGBClearIndices.clear();
        lastGroup = -1;
    }

    private static long[] mapConstraintStateID(long[] constraints, int stateID) {
        long[] mapped = new long[constraints.length];
        for (int i = 0; i < constraints.length; i++) {
            mapped[i] = TransitionConstraint.setStateID(constraints[i], stateID);
        }
        return mapped;
    }

    private static long[] mapOpSourceAndTarget(long[] operations, int newSource, int newTarget) {
        long[] mapped = new long[operations.length];
        for (int i = 0; i < operations.length; i++) {
            mapped[i] = TransitionOp.setSourceAndTarget(operations[i], newSource, newTarget);
        }
        return mapped;
    }

    private static void changeOpTarget(long[] operations, int newTarget) {
        for (int i = 0; i < operations.length; i++) {
            operations[i] = TransitionOp.setTarget(operations[i], newTarget);
        }
    }

    private NFAState createFinalState(StateSet<RegexAST, ? extends RegexASTNode> stateSet, boolean mustAdvance) {
        NFAState state = new NFAState((short) stateID.inc(), stateSet, Collections.emptySet(), false, mustAdvance);
        assert !nfaStates.containsKey(NFAStateID.create(state));
        nfaStates.put(NFAStateID.create(state), state);
        return state;
    }

    private NFAStateTransition createTransition(NFAState source, NFAState target, CodePointSet codePointSet, long[] constraints, long[] operations) {
        return new NFAStateTransition((short) transitionID.inc(), source, target, codePointSet, ast.createGroupBoundaries(transitionGBUpdateIndices, transitionGBClearIndices, -1, lastGroup),
                        constraints,
                        operations);
    }

    private NFAState registerMatcherState(StateSet<RegexAST, CharacterClass> stateSetCC,
                    StateSet<RegexAST, LookBehindAssertion> finishedLookBehinds,
                    boolean containsPrefixStates,
                    boolean mustAdvance,
                    EconomicMap<Integer, TBitSet> matchedConditionGroupsMap) {
        NFAStateID nfaStateID = new NFAStateID(stateSetCC, mustAdvance, matchedConditionGroupsMap);
        if (nfaStates.containsKey(nfaStateID)) {
            return nfaStates.get(nfaStateID);
        } else {
            NFAState state = new NFAState((short) stateID.inc(), stateSetCC, finishedLookBehinds, containsPrefixStates, mustAdvance, matchedConditionGroupsMap);
            expansionQueue.push(state);
            nfaStates.put(nfaStateID, state);
            return state;
        }
    }

    private void addNewLoopBackTransition(NFAState source, NFAState target) {
        source.addLoopBackNext(createTransition(source, target, ast.getEncoding().getFullSet(), TransitionConstraint.NO_CONSTRAINTS, TransitionOp.NO_OP));
        if (needsReverseTransitions(source)) {
            target.incPredecessors();
        }
    }

    private static final class NFAStateID {

        private final StateSet<RegexAST, ? extends RegexASTNode> stateSet;
        private final boolean mustAdvance;
        private final EconomicMap<Integer, TBitSet> matchedConditionGroupsMap;

        NFAStateID(StateSet<RegexAST, ? extends RegexASTNode> stateSet, boolean mustAdvance, EconomicMap<Integer, TBitSet> matchedConditionGroupsMap) {
            this.stateSet = stateSet;
            this.mustAdvance = mustAdvance;
            this.matchedConditionGroupsMap = matchedConditionGroupsMap;
        }

        public static NFAStateID create(NFAState state) {
            return new NFAStateID(state.getStateSet(), state.isMustAdvance(), state.getMatchedConditionGroupsMap());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof NFAStateID)) {
                return false;
            }
            NFAStateID that = (NFAStateID) o;
            return mustAdvance == that.mustAdvance && stateSet.equals(that.stateSet) && mapEquals(matchedConditionGroupsMap, that.matchedConditionGroupsMap);
        }

        private static <K, V> boolean mapEquals(EconomicMap<K, V> mapA, EconomicMap<K, V> mapB) {
            if (mapA == null) {
                return mapB == null;
            }
            if (mapB == null) {
                return mapA == null;
            }
            if (mapA.size() != mapB.size()) {
                return false;
            }
            for (K key : mapA.getKeys()) {
                if (!mapB.containsKey(key) || !mapA.get(key).equals(mapB.get(key))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return Objects.hash(stateSet, mustAdvance, mapHashCode(matchedConditionGroupsMap));
        }

        private static <K, V> int mapHashCode(EconomicMap<K, V> map) {
            if (map == null) {
                return 0;
            }
            int hashCode = 0;
            for (V value : map.getValues()) {
                hashCode = 31 * hashCode + value.hashCode();
            }
            return hashCode;
        }
    }
}
