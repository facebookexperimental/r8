// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.naming.NamingLens;

public interface PresortedComparable<T> extends Comparable<T> {

  int slowCompareTo(T other, NamingLens namingLens);
}
