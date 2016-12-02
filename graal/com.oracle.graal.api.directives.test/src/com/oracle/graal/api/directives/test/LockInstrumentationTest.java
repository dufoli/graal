/*
 * Copyright (c) 2016, 2016, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.api.directives.test;

import static com.oracle.graal.compiler.common.GraalOptions.UseGraalInstrumentation;
import static com.oracle.graal.options.OptionValues.GLOBAL;

import java.io.IOException;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.graal.api.directives.GraalDirectives;
import com.oracle.graal.compiler.test.GraalCompilerTest;
import com.oracle.graal.options.OptionValues;

import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaMethod;

@SuppressWarnings("try")
public class LockInstrumentationTest extends GraalCompilerTest {

    private TinyInstrumentor instrumentor;

    public LockInstrumentationTest() {
        HotSpotResolvedJavaMethod method = (HotSpotResolvedJavaMethod) getResolvedJavaMethod(ClassA.class, "notInlinedMethod");
        method.setNotInlineable();

        try {
            instrumentor = new TinyInstrumentor(LockInstrumentationTest.class, "instrumentation");
        } catch (IOException e) {
            Assert.fail("unable to initialize the instrumentor: " + e);
        }
    }

    public static class ClassA {

        // This method should be marked as not inlineable
        public void notInlinedMethod() {
        }

    }

    public static final Object lock = new Object();
    public static boolean lockAfterCheckPoint;
    public static boolean checkpoint;

    public void resetFlags() {
        lockAfterCheckPoint = false;
        checkpoint = false;
    }

    static void instrumentation() {
        GraalDirectives.instrumentationBeginForPredecessor();
        lockAfterCheckPoint = checkpoint;
        GraalDirectives.instrumentationEnd();
    }

    public static void lockSnippet() {
        synchronized (lock) {
            checkpoint = true;
            ClassA a = new ClassA();
            a.notInlinedMethod();
        }
    }

    /**
     * Tests that the effect of instrumenting {@link #lockSnippet()} is as shown below.
     *
     * <pre>
     *     synchronized (lock) {
     *         lockAfterCheckPoint = checkpoint;  <--- instrumentation
     *         checkpoint = true;
     *         ClassA a = new ClassA();
     *         a.notInlinedMethod();
     *     }
     * </pre>
     */
    @Test
    public void testLock() {
        try {
            Class<?> clazz = instrumentor.instrument(LockInstrumentationTest.class, "lockSnippet", Opcodes.MONITORENTER);
            ResolvedJavaMethod method = getResolvedJavaMethod(clazz, "lockSnippet");
            executeExpected(method, null); // ensure the method is fully resolved
            resetFlags();
            OptionValues options = new OptionValues(GLOBAL, UseGraalInstrumentation, true);
            InstalledCode code = getCode(method, options);
            code.executeVarargs();
            Assert.assertFalse("expected lock was performed before checkpoint", lockAfterCheckPoint);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static void postponeLockSnippet() {
        ClassA a = new ClassA();

        synchronized (a) {
            checkpoint = true;
            a.notInlinedMethod();
        }
    }

    /**
     * Tests that the effect of instrumenting of {@link #postponeLockSnippet()} is as shown below.
     *
     * <pre>
     *     ClassA a = new ClassA();
     *     synchronized (lock) {
     *         checkpoint = true;
     *         lockAfterCheckPoint = checkpoint;  <--- instrumentation
     *         a.notInlinedMethod();
     *     }
     * </pre>
     */
    @Test
    public void testNonEscapeLock() {
        try {
            Class<?> clazz = instrumentor.instrument(LockInstrumentationTest.class, "postponeLockSnippet", Opcodes.MONITORENTER);
            ResolvedJavaMethod method = getResolvedJavaMethod(clazz, "postponeLockSnippet");
            executeExpected(method, null); // ensure the method is fully resolved
            resetFlags();
            // The lock in the snippet will be relocated before the invocation to
            // notInlinedMethod(), i.e., after the checkpoint. We expect the instrumentation follows
            // and flag will be set to true.
            OptionValues options = new OptionValues(GLOBAL, UseGraalInstrumentation, true);
            InstalledCode code = getCode(method, options);
            code.executeVarargs();
            Assert.assertTrue("expected lock was performed after checkpoint", lockAfterCheckPoint);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
