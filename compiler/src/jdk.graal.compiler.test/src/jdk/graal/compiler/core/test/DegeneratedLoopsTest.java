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
package jdk.graal.compiler.core.test;

import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.DebugDumpScope;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.StructuredGraph.AllowAssumptions;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import org.junit.Test;

/**
 * In the following tests, the usages of local variable "a" are replaced with the integer constant
 * 0. Then canonicalization is applied and it is verified that the resulting graph is equal to the
 * graph of the method that just has a "return 1" statement in it.
 */
public class DegeneratedLoopsTest extends GraalCompilerTest {

    private static final String REFERENCE_SNIPPET = "referenceSnippet";

    @SuppressWarnings("all")
    public static int referenceSnippet(int a) {
        return a;
    }

    @Test
    public void test1() {
        test("test1Snippet");
    }

    private static final class UnresolvedException extends RuntimeException {

        private static final long serialVersionUID = 5215434338750728440L;

        static {
            if (true) {
                throw new UnsupportedOperationException("this class may never be initialized");
            }
        }
    }

    @SuppressWarnings("all")
    public static int test1Snippet(int a) {
        for (;;) {
            try {
                test();
                break;
            } catch (UnresolvedException e) {
            }
        }
        return a;
    }

    private static void test() {

    }

    private void test(final String snippet) {
        DebugContext debug = getDebugContext();
        try (DebugContext.Scope _ = debug.scope("DegeneratedLoopsTest", new DebugDumpScope(snippet))) {
            StructuredGraph graph = parseEager(snippet, AllowAssumptions.YES);
            HighTierContext context = getDefaultHighTierContext();
            createInliningPhase().apply(graph, context);
            createCanonicalizerPhase().apply(graph, context);
            debug.dump(DebugContext.BASIC_LEVEL, graph, "Graph");
            StructuredGraph referenceGraph = parseEager(REFERENCE_SNIPPET, AllowAssumptions.YES);
            debug.dump(DebugContext.BASIC_LEVEL, referenceGraph, "ReferenceGraph");
            assertEquals(referenceGraph, graph);
        } catch (Throwable e) {
            throw debug.handle(e);
        }
    }
}
