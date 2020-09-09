// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.memberrebinding.testclasses;

import com.android.tools.r8.NeverInline;
import com.android.tools.r8.NoVerticalClassMerging;

public class MemberRebindingBridgeRemovalTestClasses {

  @NoVerticalClassMerging
  static class A {

    @NeverInline
    public void m() {
      System.out.println("Hello world!");
    }
  }

  public static class B extends A {}
}
