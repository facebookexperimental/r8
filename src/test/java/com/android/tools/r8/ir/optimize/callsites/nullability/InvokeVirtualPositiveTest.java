// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.optimize.callsites.nullability;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.NeverClassInline;
import com.android.tools.r8.NeverInline;
import com.android.tools.r8.NeverMerge;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.codeinspector.InstructionSubject;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class InvokeVirtualPositiveTest extends TestBase {
  private static final Class<?> MAIN = Main.class;

  @Parameterized.Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimes().build();
  }

  private final TestParameters parameters;

  public InvokeVirtualPositiveTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void testR8() throws Exception {
    testForR8(parameters.getBackend())
        .addInnerClasses(InvokeVirtualPositiveTest.class)
        .addKeepMainRule(MAIN)
        .enableMergeAnnotations()
        .enableClassInliningAnnotations()
        .enableInliningAnnotations()
        .addOptionsModification(InternalOptions::enableCallSiteOptimizationInfoPropagation)
        .setMinApi(parameters.getRuntime())
        .run(parameters.getRuntime(), MAIN)
        .assertSuccessWithOutputLines("A", "B")
        .inspect(this::inspect);
  }

  private void inspect(CodeInspector inspector) {
    ClassSubject a = inspector.clazz(A.class);
    assertThat(a, isPresent());

    MethodSubject a_m = a.uniqueMethodWithName("m");
    assertThat(a_m, isPresent());
    // Can optimize branches since `arg` is definitely not null.
    assertTrue(a_m.streamInstructions().noneMatch(InstructionSubject::isIf));

    ClassSubject b = inspector.clazz(B.class);
    assertThat(b, isPresent());

    MethodSubject b_m = b.uniqueMethodWithName("m");
    assertThat(b_m, isPresent());
    // Can optimize branches since `arg` is definitely not null.
    assertTrue(b_m.streamInstructions().noneMatch(InstructionSubject::isIf));
  }

  @NeverMerge
  @NeverClassInline
  static class A {
    @NeverInline
    void m(Object arg) {
      // Technically same as String#valueOf.
      if (arg != null) {
        System.out.println(arg.toString());
      } else {
        System.out.println("null");
      }
    }

    @NeverInline
    @Override
    public String toString() {
      return "A";
    }
  }

  @NeverClassInline
  static class B extends A {
    @NeverInline
    @Override
    void m(Object arg) {
      // Same as A#m.
      if (arg != null) {
        System.out.println(arg.toString());
      } else {
        System.out.println("null");
      }
    }

    @NeverInline
    @Override
    public String toString() {
      return "B";
    }
  }

  static class Main {
    public static void main(String... args) {
      A a = System.currentTimeMillis() > 0 ? new A() : new B();
      a.m(a);  // calls A.m() with non-null instance.

      B b = new B();
      b.m(b);  // calls B.m() with non-null instance
    }
  }
}
