/*
 * Copyright (c) 2021, 2025, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.webimage.codegen.reconstruction.stackifier;

import jdk.graal.compiler.nodes.cfg.HIRBlock;

/**
 * This class represents labeled blocks in the generated code. They are used to replace goto
 * patterns in the CFG with labeled break statements.
 *
 * For example:
 *
 * <pre>
 * label: {
 *
 * }
 */
public class LabeledBlock {
    /**
     * The first block inside the labeled block.
     */
    protected final HIRBlock start;
    /**
     * The first block after the labeled block.
     *
     * When breaking out of the labeled block, this block is reached.
     */
    protected final HIRBlock end;
    protected int label;

    protected LabeledBlock(HIRBlock start, HIRBlock end, int id) {
        this.start = start;
        this.end = end;
        this.label = id;
    }

    public HIRBlock getEnd() {
        return end;
    }

    public HIRBlock getStart() {
        return start;
    }

    public String getLabel() {
        return LabeledBlockGeneration.LabeledBlockPrefix + label;
    }

    @Override
    public String toString() {
        return getLabel() + " " + start.getId() + " -> " + end.getId();
    }
}
