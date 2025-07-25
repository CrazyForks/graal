/*
 * Copyright (c) 2014, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.annotation;

import static com.oracle.svm.core.util.VMError.intentionallyUnimplemented;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Type;

import com.oracle.graal.pointsto.infrastructure.GraphProvider;
import com.oracle.graal.pointsto.infrastructure.OriginalMethodProvider;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.ConstantPool;
import jdk.vm.ci.meta.ExceptionHandler;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.LineNumberTable;
import jdk.vm.ci.meta.LocalVariableTable;
import jdk.vm.ci.meta.ProfilingInfo;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Signature;
import jdk.vm.ci.meta.SpeculationLog;

public abstract class CustomSubstitutionMethod implements ResolvedJavaMethod, GraphProvider, OriginalMethodProvider, AnnotationWrapper {

    protected final ResolvedJavaMethod original;

    public CustomSubstitutionMethod(ResolvedJavaMethod original) {
        this.original = original;
    }

    public ResolvedJavaMethod getOriginal() {
        return original;
    }

    @Override
    public ResolvedJavaMethod unwrapTowardsOriginalMethod() {
        return original;
    }

    @Override
    public boolean allowRuntimeCompilation() {
        /*
         * The safe default for all methods with manually generated graphs is that such methods are
         * not available for runtime compilation. Note that a manually generated graph must be able
         * to provide the proper deoptimization entry points and deoptimization frame states. If a
         * subclass provides that, it can override this method and return true.
         */
        return false;
    }

    @Override
    public String getName() {
        return original.getName();
    }

    @Override
    public Signature getSignature() {
        /*
         * When the substitution method injects a different declaringClass, then type resolution
         * with that new declaringClass could fail due to class visibility differences. We try to
         * resolve with the original declaringClass, for which the resolving can then succeed.
         */
        return new Signature() {
            private final Signature wrapped = original.getSignature();

            @Override
            public int getParameterCount(boolean receiver) {
                return wrapped.getParameterCount(receiver);
            }

            @Override
            public JavaType getParameterType(int index, ResolvedJavaType accessingClass) {
                JavaType result = wrapped.getParameterType(index, accessingClass);
                if (accessingClass != null && !(result instanceof ResolvedJavaType)) {
                    result = wrapped.getParameterType(index, original.getDeclaringClass());
                }
                return result;
            }

            @Override
            public JavaType getReturnType(ResolvedJavaType accessingClass) {
                JavaType result = wrapped.getReturnType(accessingClass);
                if (accessingClass != null && !(result instanceof ResolvedJavaType)) {
                    result = wrapped.getReturnType(original.getDeclaringClass());
                }
                return result;
            }

            @Override
            public String toString() {
                return "CustomSubstitutionMethod.Signature<" + wrapped + ">";
            }
        };
    }

    @Override
    public byte[] getCode() {
        return null;
    }

    @Override
    public int getCodeSize() {
        return 0;
    }

    @Override
    public ResolvedJavaType getDeclaringClass() {
        return original.getDeclaringClass();
    }

    @Override
    public int getMaxLocals() {
        return getSignature().getParameterCount(!isStatic()) * 2;
    }

    @Override
    public int getMaxStackSize() {
        // fits a double-slot return value
        return 2;
    }

    @Override
    public int getModifiers() {
        return original.getModifiers();
    }

    @Override
    public boolean isSynthetic() {
        return original.isSynthetic();
    }

    @Override
    public boolean isVarArgs() {
        return original.isVarArgs();
    }

    @Override
    public boolean isBridge() {
        return original.isBridge();
    }

    @Override
    public boolean isDefault() {
        return original.isDefault();
    }

    @Override
    public boolean isDeclared() {
        return original.isDeclared();
    }

    @Override
    public boolean isClassInitializer() {
        return original.isClassInitializer();
    }

    @Override
    public boolean isConstructor() {
        return original.isConstructor();
    }

    @Override
    public boolean canBeStaticallyBound() {
        return original.canBeStaticallyBound();
    }

    @Override
    public ExceptionHandler[] getExceptionHandlers() {
        return new ExceptionHandler[0];
    }

    @Override
    public StackTraceElement asStackTraceElement(int bci) {
        return original.asStackTraceElement(0);
    }

    @Override
    public ProfilingInfo getProfilingInfo(boolean includeNormal, boolean includeOSR) {
        throw intentionallyUnimplemented(); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public void reprofile() {
    }

    @Override
    public ConstantPool getConstantPool() {
        /*
         * The constant pool must actually never be accessed, but this method is called and the
         * result cannot be null.
         */
        return original.getConstantPool();
    }

    @Override
    public AnnotatedElement getAnnotationRoot() {
        return original;
    }

    @Override
    public Parameter[] getParameters() {
        return original.getParameters();
    }

    @Override
    public Annotation[][] getParameterAnnotations() {
        return original.getParameterAnnotations();
    }

    @Override
    public Type[] getGenericParameterTypes() {
        return original.getGenericParameterTypes();
    }

    @Override
    public boolean canBeInlined() {
        return original.canBeInlined();
    }

    @Override
    public boolean hasNeverInlineDirective() {
        return original.hasNeverInlineDirective();
    }

    @Override
    public boolean shouldBeInlined() {
        return original.shouldBeInlined();
    }

    @Override
    public LineNumberTable getLineNumberTable() {
        return null;
    }

    @Override
    public LocalVariableTable getLocalVariableTable() {
        return null;
    }

    @Override
    public Constant getEncoding() {
        throw intentionallyUnimplemented(); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public boolean isInVirtualMethodTable(ResolvedJavaType resolved) {
        throw intentionallyUnimplemented(); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public SpeculationLog getSpeculationLog() {
        throw intentionallyUnimplemented(); // ExcludeFromJacocoGeneratedReport
    }
}
