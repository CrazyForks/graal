/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2020, 2020, Red Hat Inc. All rights reserved.
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

package com.oracle.objectfile.pecoff.cv;

import com.oracle.objectfile.debugentry.FieldEntry;
import com.oracle.objectfile.debugentry.MethodEntry;
import com.oracle.objectfile.debugentry.TypeEntry;

final class CVNames {

    static String typeNameToCodeViewName(String typeName) {
        return typeName.replace('.', '_').replace("[]", "_array");
    }

    static String typeNameToCodeViewName(TypeEntry typeEntry) {
        return typeNameToCodeViewName(typeEntry.getTypeName());
    }

    static String methodNameToCodeViewName(MethodEntry memberEntry) {
        return typeNameToCodeViewName(memberEntry.getOwnerType()) + "::" + memberEntry.getMethodName();
    }

    static String fieldNameToCodeViewName(FieldEntry memberEntry) {
        return typeNameToCodeViewName(memberEntry.getOwnerType()) + "::" + memberEntry.fieldName();
    }
}
