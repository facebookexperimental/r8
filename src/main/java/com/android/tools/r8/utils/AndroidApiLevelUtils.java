// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.utils;

import com.android.tools.r8.androidapi.AndroidApiLevelCompute;
import com.android.tools.r8.androidapi.ComputedApiLevel;
import com.android.tools.r8.graph.LibraryMethod;
import com.android.tools.r8.graph.ProgramMethod;

public class AndroidApiLevelUtils {

  public static boolean isApiSafeForInlining(
      ProgramMethod caller, ProgramMethod inlinee, InternalOptions options) {
    if (!options.apiModelingOptions().enableApiCallerIdentification) {
      return true;
    }
    if (caller.getHolderType() == inlinee.getHolderType()) {
      return true;
    }
    return caller
        .getDefinition()
        .getApiLevel()
        .isGreaterThanOrEqualTo(inlinee.getDefinition().getApiLevelForCode());
  }

  public static boolean isApiSafeForMemberRebinding(
      LibraryMethod method,
      AndroidApiLevelCompute androidApiLevelCompute,
      InternalOptions options) {
    ComputedApiLevel apiLevel =
        androidApiLevelCompute.computeApiLevelForLibraryReference(
            method.getReference(), ComputedApiLevel.unknown());
    if (apiLevel.isUnknownApiLevel()) {
      return false;
    }
    assert options.apiModelingOptions().enableApiCallerIdentification;
    return apiLevel.asKnownApiLevel().getApiLevel().isLessThanOrEqualTo(options.getMinApiLevel());
  }
}
