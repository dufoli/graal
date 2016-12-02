/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.compiler.test;

import static com.oracle.graal.compiler.common.util.CompilationAlarm.Options.CompilationExpirationPeriod;
import static com.oracle.graal.options.OptionValues.GLOBAL;

import org.junit.Test;

import com.oracle.graal.compiler.common.util.CompilationAlarm;
import com.oracle.graal.debug.GraalError;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.StructuredGraph.AllowAssumptions;
import com.oracle.graal.options.OptionValues;
import com.oracle.graal.phases.Phase;

import jdk.vm.ci.code.BailoutException;

public class CooperativePhaseTest extends GraalCompilerTest {

    public static void snippet() {
        // dummy snippet
    }

    private static class CooperativePhase extends Phase {

        @Override
        protected void run(StructuredGraph graph) {
            while (true) {
                sleep(200);
                if (CompilationAlarm.hasExpired(graph.getOptions())) {
                    return;
                }
            }
        }

    }

    private static class UnCooperativePhase extends Phase {

        @Override
        protected void run(StructuredGraph graph) {
            while (true) {
                sleep(200);
                if (CompilationAlarm.hasExpired(graph.getOptions())) {
                    throw new BailoutException("Expiring...");
                }
            }
        }

    }

    private static class ParlyCooperativePhase extends Phase {

        @Override
        protected void run(StructuredGraph graph) {
            for (int i = 0; i < 10; i++) {
                sleep(200);
                if (CompilationAlarm.hasExpired(graph.getOptions())) {
                    throw new RuntimeException("Phase must not exit in the timeout path");
                }
            }
        }
    }

    private static class CooperativePhaseWithoutAlarm extends Phase {

        @Override
        protected void run(StructuredGraph graph) {
            if (CompilationAlarm.hasExpired(graph.getOptions())) {
                throw new RuntimeException("Phase must not exit in the timeout path");
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            GraalError.shouldNotReachHere(e.getCause());
        }
    }

    @Test(timeout = 60_000)
    @SuppressWarnings("try")
    public void test01() {
        OptionValues options = new OptionValues(GLOBAL, CompilationExpirationPeriod, 1/* sec */);
        try (CompilationAlarm c1 = CompilationAlarm.trackCompilationPeriod(GLOBAL)) {
            StructuredGraph g = parseEager("snippet", AllowAssumptions.NO, options);
            new CooperativePhase().apply(g);
        }
    }

    @Test(expected = BailoutException.class, timeout = 60_000)
    @SuppressWarnings("try")
    public void test02() {
        OptionValues options = new OptionValues(GLOBAL, CompilationExpirationPeriod, 1/* sec */);
        try (CompilationAlarm c1 = CompilationAlarm.trackCompilationPeriod(GLOBAL)) {
            StructuredGraph g = parseEager("snippet", AllowAssumptions.NO, options);
            new UnCooperativePhase().apply(g);
        }
    }

    @Test(timeout = 60_000)
    @SuppressWarnings("try")
    public void test03() {
        // 0 disables alarm utility
        OptionValues options = new OptionValues(GLOBAL, CompilationExpirationPeriod, 0);
        try (CompilationAlarm c1 = CompilationAlarm.trackCompilationPeriod(GLOBAL)) {
            StructuredGraph g = parseEager("snippet", AllowAssumptions.NO, options);
            new ParlyCooperativePhase().apply(g);
        }
    }

    @Test(timeout = 60_000)
    @SuppressWarnings("try")
    public void test04() {
        StructuredGraph g = parseEager("snippet", AllowAssumptions.NO);
        new CooperativePhaseWithoutAlarm().apply(g);
    }
}
