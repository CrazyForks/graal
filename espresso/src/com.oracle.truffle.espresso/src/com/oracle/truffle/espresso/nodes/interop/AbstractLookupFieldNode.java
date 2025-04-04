/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.oracle.truffle.espresso.nodes.interop;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.espresso.classfile.descriptors.Name;
import com.oracle.truffle.espresso.classfile.descriptors.Symbol;
import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.nodes.EspressoNode;

public abstract class AbstractLookupFieldNode extends EspressoNode {

    public enum FieldLookupKind {
        Instance,
        Static,
        All
    }

    protected abstract Field[] getFieldArray(Klass klass);

    @TruffleBoundary
    protected Field doLookup(Klass klass, String name, boolean publicOnly, FieldLookupKind kind) {
        Field[] table = getFieldArray(klass);
        if (table.length == 0) {
            return null;
        }
        Symbol<Name> nameSymbol = klass.getNames().lookup(name);
        if (nameSymbol == null) {
            return null;
        }
        for (int i = table.length - 1; i >= 0; i--) {
            Field f = table[i];
            if (!f.isRemoved() && f.getName() == nameSymbol && (f.isPublic() || !publicOnly) &&
                            ((kind == FieldLookupKind.All) ||
                                            (kind == FieldLookupKind.Static && f.isStatic()) ||
                                            (kind == FieldLookupKind.Instance && !f.isStatic()))) {
                return f;
            }
        }
        return null;
    }

}
