// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.kotlin;

import com.android.tools.r8.ToolHelper.KotlinTargetVersion;
import com.android.tools.r8.utils.InternalOptions;
import java.util.function.Consumer;
import org.junit.Test;

public class KotlinLambdaMergingWithReprocessingTest extends AbstractR8KotlinTestBase {
  private Consumer<InternalOptions> optionsModifier =
    o -> {
      o.enableInlining = true;
      o.enableClassInlining = true;
      o.enableLambdaMerging = true;
    };

  public KotlinLambdaMergingWithReprocessingTest(
      KotlinTargetVersion targetVersion, boolean allowAccessModification) {
    super(targetVersion, allowAccessModification);
  }

  @Test
  public void testMergingKStyleLambdasAndReprocessing() throws Exception {
    final String mainClassName = "reprocess_merged_lambdas_kstyle.MainKt";
    runTest("reprocess_merged_lambdas_kstyle", mainClassName, optionsModifier, null);
  }
}
