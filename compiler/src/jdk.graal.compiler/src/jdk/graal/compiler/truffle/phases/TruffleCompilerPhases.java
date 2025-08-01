/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.truffle.phases;

import java.util.ListIterator;

import jdk.graal.compiler.loop.phases.LoopSafepointEliminationPhase;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.common.DeoptimizationGroupingPhase;
import jdk.graal.compiler.phases.common.LoopSafepointInsertionPhase;
import jdk.graal.compiler.phases.schedule.SchedulePhase;
import jdk.graal.compiler.phases.tiers.LowTierContext;
import jdk.graal.compiler.phases.tiers.MidTierContext;
import jdk.graal.compiler.phases.tiers.Suites;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.truffle.KnownTruffleTypes;

public final class TruffleCompilerPhases {

    private TruffleCompilerPhases() {
    }

    public static void register(KnownTruffleTypes types, Providers providers, Suites suites) {
        if (suites.isImmutable()) {
            throw new IllegalStateException("Suites are already immutable.");
        }
        // insert before to always insert safepoints consistently before host vm safepoints.
        ListIterator<BasePhase<? super MidTierContext>> loopSafepointInsertion = suites.getMidTier().findPhase(LoopSafepointInsertionPhase.class);
        loopSafepointInsertion.previous();
        loopSafepointInsertion.add(new TruffleSafepointInsertionPhase(types, providers));

        // truffle safepoints have additional requirements to get eliminated and can not just use
        // default loop safepoint elimination.
        suites.getMidTier().replaceAllPhases(LoopSafepointEliminationPhase.class, () -> new TruffleLoopSafepointEliminationPhase(types));

        ListIterator<BasePhase<? super MidTierContext>> midTierPhasesIterator = suites.getMidTier().findPhase(DeoptimizationGroupingPhase.class);
        if (midTierPhasesIterator != null) {
            BasePhase<? super MidTierContext> deoptimizationGroupingPhase = midTierPhasesIterator.previous();
            midTierPhasesIterator.set(new DynamicDeoptimizationGroupingPhase((DeoptimizationGroupingPhase) deoptimizationGroupingPhase));
        }
        ListIterator<BasePhase<? super LowTierContext>> lowTierPhasesIterator = suites.getLowTier().findPhase(SchedulePhase.FinalSchedulePhase.class);
        if (lowTierPhasesIterator != null) {
            lowTierPhasesIterator.previous();
            lowTierPhasesIterator.add(new TruffleForceDeoptSpeculationPhase());
        }
    }

}
