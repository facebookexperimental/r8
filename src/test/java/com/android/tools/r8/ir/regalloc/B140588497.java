// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.regalloc;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.InstructionSubject;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import java.util.Iterator;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class B140588497 extends TestBase {

  @Parameterized.Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withDexRuntimes().build();
  }

  private final TestParameters parameters;

  public B140588497(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void testD8() throws Exception {
    testForD8()
        .addInnerClasses(B140588497.class)
        .setMinApi(parameters.getRuntime())
        .compile()
        .inspect(inspector -> {
          ClassSubject c = inspector.clazz(TestClass.class);
          assertThat(c, isPresent());

          MethodSubject m = c.uniqueMethodWithName("invokeRangeTest");
          assertThat(m, isPresent());
          long prev;
          long curr = -1;
          Iterator<InstructionSubject> it =
              m.iterateInstructions(InstructionSubject::isConstNumber);
          while (it.hasNext()) {
            InstructionSubject instr = it.next();
            prev = curr;
            curr = instr.getConstNumber();
            assertTrue(prev < curr);
          }
        });
  }

  static class TestClass {
    public static void invokeRangeTest() {
      consumeManyLongs(0, 1, 2, 3, 4, 5);
    }
    public static void consumeManyLongs(long a, long b, long c, long d, long e, long f) {}
  }

}
