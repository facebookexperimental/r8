// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.inliner;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import com.android.tools.r8.TestBase;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.utils.BooleanUtils;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.codeinspector.InstructionSubject;
import com.android.tools.r8.utils.codeinspector.InvokeInstructionSubject;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import com.google.common.collect.ImmutableList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class InlineSynchronizedTest extends TestBase {

  private static final List<String> METHOD_NAMES =
      ImmutableList.of(
          "println",
          "normalInlinedSynchronized",
          "classInlinedSynchronized",
          "normalInlinedControl",
          "classInlinedControl");

  @Parameterized.Parameters(name = "Backend: {0}, ClassInlining: {1}")
  public static Collection data() {
    return buildParameters(ToolHelper.getBackends(), BooleanUtils.values());
  }

  private final Backend backend;
  private final boolean classInlining;

  public InlineSynchronizedTest(Backend backend, boolean classInlining) {
    this.backend = backend;
    this.classInlining = classInlining;
  }

  @Test
  public void test() throws Exception {
    CodeInspector codeInspector =
        testForR8(backend)
            .addProgramClasses(InlineSynchronizedTestClass.class)
            .addKeepMainRule(InlineSynchronizedTestClass.class)
            .addOptionsModification(o -> o.enableClassInlining = classInlining)
            .enableInliningAnnotations()
            .noMinification()
            .compile()
            .inspector();

    ClassSubject classSubject = codeInspector.clazz(InlineSynchronizedTestClass.class);
    assertThat(classSubject, isPresent());
    MethodSubject methodSubject = classSubject.mainMethod();
    Iterator<InstructionSubject> it = methodSubject.iterateInstructions();
    int[] counts = new int[METHOD_NAMES.size()];
    while (it.hasNext()) {
      InstructionSubject instruction = it.next();
      if (!instruction.isInvoke()) {
        continue;
      }
      DexString invokedName = ((InvokeInstructionSubject) instruction).invokedMethod().name;
      int idx = METHOD_NAMES.indexOf(invokedName.toString());
      if (idx >= 0) {
        ++counts[idx];
      }
    }
    // Synchronized methods can never be inlined.
    assertCount(counts, "normalInlinedSynchronized", 1);
    assertCount(counts, "classInlinedSynchronized", 1);
    // Control methods must be inlined, only the normal one or both, depending on classInlining.
    assertCount(counts, "normalInlinedControl", 0);
    assertCount(counts, "classInlinedControl", classInlining ? 0 : 1);
    // Double check the total.
    int total = 0;
    for (int i = 0; i < counts.length; ++i) {
      total += counts[i];
    }
    assertEquals(4, total);
  }

  private static void assertCount(int counts[], String methodName, int expectedCount) {
    int idx = METHOD_NAMES.indexOf(methodName);
    assert idx >= 0;
    assertEquals(expectedCount, counts[idx]);
  }
}
