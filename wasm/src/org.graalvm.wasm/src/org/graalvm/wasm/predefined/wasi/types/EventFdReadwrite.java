/*
 * Copyright (c) 2021, 2025, Oracle and/or its affiliates. All rights reserved.
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

/*
 * This file has been automatically generated from wasi_snapshot_preview1.witx.
 */

package org.graalvm.wasm.predefined.wasi.types;

import com.oracle.truffle.api.nodes.Node;
import org.graalvm.wasm.memory.WasmMemory;
import org.graalvm.wasm.memory.WasmMemoryLibrary;

/**
 * The contents of an {@code event} when type is {@code eventtype::fd_read} or
 * {@code eventtype::fd_write}.
 */
public final class EventFdReadwrite {

    /** Static methods only; don't let anyone instantiate this class. */
    private EventFdReadwrite() {
    }

    /** Size of this structure, in bytes. */
    public static final int BYTES = 16;

    /** Reads the number of bytes available for reading or writing. */
    public static long readNbytes(Node node, WasmMemoryLibrary memoryLib, WasmMemory memory, int address) {
        return memoryLib.load_i64(memory, node, address + 0);
    }

    /** Writes the number of bytes available for reading or writing. */
    public static void writeNbytes(Node node, WasmMemoryLibrary memoryLib, WasmMemory memory, int address, long value) {
        memoryLib.store_i64(memory, node, address + 0, value);
    }

    /** Reads the state of the file descriptor. */
    public static short readFlags(Node node, WasmMemoryLibrary memoryLib, WasmMemory memory, int address) {
        return (short) memoryLib.load_i32_16u(memory, node, address + 8);
    }

    /** Writes the state of the file descriptor. */
    public static void writeFlags(Node node, WasmMemoryLibrary memoryLib, WasmMemory memory, int address, short value) {
        memoryLib.store_i32_16(memory, node, address + 8, value);
    }

}
