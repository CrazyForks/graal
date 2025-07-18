/*
 * Copyright (c) 2017, 2025, Oracle and/or its affiliates. All rights reserved.
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

import org.graalvm.collections.EconomicMap;
import org.junit.Assert;
import org.junit.Test;

import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.DebugContext.Scope;
import jdk.graal.compiler.debug.DebugOptions;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.StructuredGraph.AllowAssumptions;
import jdk.graal.compiler.options.OptionKey;
import jdk.graal.compiler.options.OptionValues;

public class GraphResetDebugTest extends GraalCompilerTest {

    public static void testSnippet() {
    }

    @Test
    public void test1() {
        assumeManagementLibraryIsLoadable();
        EconomicMap<OptionKey<?>, Object> map = EconomicMap.create();
        // Configure with an option that enables scopes
        map.put(DebugOptions.DumpOnError, true);
        DebugContext debug = getDebugContext(new OptionValues(map));
        StructuredGraph graph = parseEager("testSnippet", AllowAssumptions.YES, debug);
        boolean resetSucceeded = false;
        try (Scope _ = debug.scope("some scope")) {
            graph.resetDebug(DebugContext.disabled(getInitialOptions()));
            resetSucceeded = true;
        } catch (AssertionError expected) {
        }
        Assert.assertFalse(resetSucceeded);
    }
}
