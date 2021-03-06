// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.desugar.annotations.version3;

import com.android.tools.r8.ir.desugar.annotations.A;

public class Client {
  public static void main(String[] args) {
    A a = new A().method();
    B b = new B().method();
    C c = new C().method();

    System.out.println("a=" + a.getClass().getSimpleName());
    System.out.println("b=" + b.getClass().getSimpleName());
    System.out.println("c=" + c.getClass().getSimpleName());
  }
}
