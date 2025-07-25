/*
 * Copyright (c) 2013, 2025, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.hotspot.test;

import static jdk.graal.compiler.core.GraalCompilerOptions.CompilationBailoutAsFailure;
import static jdk.graal.compiler.core.GraalCompilerOptions.CompilationFailureAction;

import jdk.graal.compiler.core.test.GraalCompilerTest;
import jdk.graal.compiler.core.CompilationWrapper.ExceptionAction;
import jdk.graal.compiler.core.phases.HighTier;
import jdk.graal.compiler.debug.DebugOptions;
import jdk.graal.compiler.debug.TTY;
import jdk.graal.compiler.hotspot.HotSpotGraalCompiler;
import jdk.graal.compiler.options.OptionValues;
import org.junit.Test;

import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime;

/**
 * Tests {@link CompileTheWorld} functionality.
 */
public class CompileTheWorldTest extends GraalCompilerTest {

    @Test
    public void testJDK() throws Throwable {
        boolean originalBailoutAction = CompilationBailoutAsFailure.getValue(getInitialOptions());
        ExceptionAction originalFailureAction = CompilationFailureAction.getValue(getInitialOptions());
        // Compile a couple classes in rt.jar
        HotSpotJVMCIRuntime runtime = HotSpotJVMCIRuntime.runtime();
        System.setProperty("CompileTheWorld.LimitModules", "java.base");
        OptionValues initialOptions = getInitialOptions();
        OptionValues harnessOptions = CompileTheWorld.loadHarnessOptions();
        int startAt = 1;
        int stopAt = 5;
        int maxClasses = Integer.MAX_VALUE;
        String methodFilters = null;
        String excludeMethodFilters = null;
        String scratchDir = "";
        boolean verbose = false;
        try (AutoCloseable _ = new TTY.Filter()) {
            CompileTheWorld ctw = new CompileTheWorld(runtime,
                            (HotSpotGraalCompiler) runtime.getCompiler(),
                            CompileTheWorld.SUN_BOOT_CLASS_PATH,
                            startAt,
                            stopAt,
                            maxClasses,
                            methodFilters,
                            excludeMethodFilters,
                            scratchDir,
                            verbose,
                            harnessOptions,
                            new OptionValues(initialOptions, HighTier.Options.Inline, false,
                                            DebugOptions.DisableIntercept, true,
                                            CompilationFailureAction, ExceptionAction.Silent));
            ctw.compile();
        }
        assert CompilationBailoutAsFailure.getValue(initialOptions) == originalBailoutAction;
        assert CompilationFailureAction.getValue(initialOptions) == originalFailureAction;
    }
}
