/*
 * Copyright (c) 2023, 2023, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.espresso.impl.generics.tree;

import com.oracle.truffle.espresso.impl.generics.visitor.TypeTreeVisitor;

public final class SimpleClassTypeSignature implements FieldTypeSignature {
    private final boolean dollar;
    private final String name;
    private final TypeArgument[] typeArgs;

    private SimpleClassTypeSignature(String n, boolean dollar, TypeArgument[] tas) {
        name = n;
        this.dollar = dollar;
        typeArgs = tas;
    }

    public static SimpleClassTypeSignature make(String n,
                    boolean dollar,
                    TypeArgument[] tas) {
        return new SimpleClassTypeSignature(n, dollar, tas);
    }

    /*
     * Should a '$' be used instead of '.' to separate this component of the name from the previous
     * one when composing a string to pass to Class.forName; in other words, is this a transition to
     * a nested class.
     */
    public boolean getDollar() {
        return dollar;
    }

    public String getName() {
        return name;
    }

    public TypeArgument[] getTypeArguments() {
        return typeArgs;
    }

    public void accept(TypeTreeVisitor<?> v) {
        v.visitSimpleClassTypeSignature(this);
    }
}
