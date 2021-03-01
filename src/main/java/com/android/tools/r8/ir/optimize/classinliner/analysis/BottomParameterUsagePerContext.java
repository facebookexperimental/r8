// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.classinliner.analysis;

import java.util.function.BiFunction;

class BottomParameterUsagePerContext extends ParameterUsagePerContext {

  private static final BottomParameterUsagePerContext INSTANCE =
      new BottomParameterUsagePerContext();

  private BottomParameterUsagePerContext() {}

  static BottomParameterUsagePerContext getInstance() {
    return INSTANCE;
  }

  @Override
  public ParameterUsage get(AnalysisContext context) {
    return ParameterUsage.bottom();
  }

  @Override
  public boolean isBottom() {
    return true;
  }

  @Override
  ParameterUsagePerContext rebuild(
      BiFunction<AnalysisContext, ParameterUsage, ParameterUsage> transformation) {
    return this;
  }
}