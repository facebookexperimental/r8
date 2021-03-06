// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.info;

import com.android.tools.r8.graph.DexEncodedMethod.ClassInlinerEligibility;
import com.android.tools.r8.graph.DexEncodedMethod.TrivialInitializer;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.analysis.type.ClassTypeLatticeElement;
import com.android.tools.r8.ir.analysis.type.TypeLatticeElement;
import com.android.tools.r8.ir.optimize.info.ParameterUsagesInfo.ParameterUsage;
import com.google.common.collect.ImmutableSet;
import java.util.BitSet;
import java.util.Set;

public class DefaultMethodOptimizationInfo implements MethodOptimizationInfo {

  public static final MethodOptimizationInfo DEFAULT_INSTANCE = new DefaultMethodOptimizationInfo();

  static Set<DexType> UNKNOWN_INITIALIZED_CLASSES_ON_NORMAL_EXIT = ImmutableSet.of();
  static int UNKNOWN_RETURNED_ARGUMENT = -1;
  static boolean UNKNOWN_NEVER_RETURNS_NULL = false;
  static boolean UNKNOWN_NEVER_RETURNS_NORMALLY = false;
  static boolean UNKNOWN_RETURNS_CONSTANT = false;
  static long UNKNOWN_RETURNED_CONSTANT_NUMBER = 0;
  static DexString UNKNOWN_RETURNED_CONSTANT_STRING = null;
  static TypeLatticeElement UNKNOWN_TYPE = null;
  static ClassTypeLatticeElement UNKNOWN_CLASS_TYPE = null;
  static boolean DOES_NOT_USE_IDNETIFIER_NAME_STRING = false;
  static boolean UNKNOWN_CHECKS_NULL_RECEIVER_BEFORE_ANY_SIDE_EFFECT = false;
  static boolean UNKNOWN_TRIGGERS_CLASS_INIT_BEFORE_ANY_SIDE_EFFECT = false;
  static ClassInlinerEligibility UNKNOWN_CLASS_INLINER_ELIGIBILITY = null;
  static TrivialInitializer UNKNOWN_TRIVIAL_INITIALIZER = null;
  static boolean UNKNOWN_INITIALIZER_ENABLING_JAVA_ASSERTIONS = false;
  static ParameterUsagesInfo UNKNOWN_PARAMETER_USAGE_INFO = null;
  static boolean UNKNOWN_MAY_HAVE_SIDE_EFFECTS = true;
  static boolean UNKNOWN_RETURN_VALUE_ONLY_DEPENDS_ON_ARGUMENTS = false;
  private static BitSet NO_NULL_PARAMETER_OR_THROW_FACTS = null;
  private static BitSet NO_NULL_PARAMETER_ON_NORMAL_EXITS_FACTS = null;

  private DefaultMethodOptimizationInfo() {}

  @Override
  public boolean isDefaultMethodOptimizationInfo() {
    return true;
  }

  @Override
  public boolean isUpdatableMethodOptimizationInfo() {
    return false;
  }

  @Override
  public UpdatableMethodOptimizationInfo asUpdatableMethodOptimizationInfo() {
    return null;
  }

  @Override
  public boolean cannotBeKept() {
    return false;
  }

  @Override
  public boolean classInitializerMayBePostponed() {
    return false;
  }

  @Override
  public TypeLatticeElement getDynamicReturnType() {
    return UNKNOWN_TYPE;
  }

  @Override
  public ClassTypeLatticeElement getDynamicLowerBoundType() {
    return UNKNOWN_CLASS_TYPE;
  }

  @Override
  public Set<DexType> getInitializedClassesOnNormalExit() {
    return UNKNOWN_INITIALIZED_CLASSES_ON_NORMAL_EXIT;
  }

  @Override
  public TrivialInitializer getTrivialInitializerInfo() {
    return UNKNOWN_TRIVIAL_INITIALIZER;
  }

  @Override
  public ParameterUsage getParameterUsages(int parameter) {
    assert UNKNOWN_PARAMETER_USAGE_INFO == null;
    return null;
  }

  @Override
  public BitSet getNonNullParamOrThrow() {
    return NO_NULL_PARAMETER_OR_THROW_FACTS;
  }

  @Override
  public BitSet getNonNullParamOnNormalExits() {
    return NO_NULL_PARAMETER_ON_NORMAL_EXITS_FACTS;
  }

  @Override
  public boolean hasBeenInlinedIntoSingleCallSite() {
    return false;
  }

  @Override
  public boolean isReachabilitySensitive() {
    return false;
  }

  @Override
  public boolean returnsArgument() {
    return false;
  }

  @Override
  public int getReturnedArgument() {
    assert returnsArgument();
    return UNKNOWN_RETURNED_ARGUMENT;
  }

  @Override
  public boolean neverReturnsNull() {
    return UNKNOWN_NEVER_RETURNS_NULL;
  }

  @Override
  public boolean neverReturnsNormally() {
    return UNKNOWN_NEVER_RETURNS_NORMALLY;
  }

  @Override
  public boolean returnsConstant() {
    return UNKNOWN_RETURNS_CONSTANT;
  }

  @Override
  public boolean returnsConstantNumber() {
    return UNKNOWN_RETURNS_CONSTANT;
  }

  @Override
  public boolean returnsConstantString() {
    return UNKNOWN_RETURNS_CONSTANT;
  }

  @Override
  public ClassInlinerEligibility getClassInlinerEligibility() {
    return UNKNOWN_CLASS_INLINER_ELIGIBILITY;
  }

  @Override
  public long getReturnedConstantNumber() {
    assert returnsConstantNumber();
    return UNKNOWN_RETURNED_CONSTANT_NUMBER;
  }

  @Override
  public DexString getReturnedConstantString() {
    assert returnsConstantString();
    return UNKNOWN_RETURNED_CONSTANT_STRING;
  }

  @Override
  public boolean isInitializerEnablingJavaAssertions() {
    return UNKNOWN_INITIALIZER_ENABLING_JAVA_ASSERTIONS;
  }

  @Override
  public boolean useIdentifierNameString() {
    return DOES_NOT_USE_IDNETIFIER_NAME_STRING;
  }

  @Override
  public boolean forceInline() {
    return false;
  }

  @Override
  public boolean neverInline() {
    return false;
  }

  @Override
  public boolean checksNullReceiverBeforeAnySideEffect() {
    return UNKNOWN_CHECKS_NULL_RECEIVER_BEFORE_ANY_SIDE_EFFECT;
  }

  @Override
  public boolean triggersClassInitBeforeAnySideEffect() {
    return UNKNOWN_TRIGGERS_CLASS_INIT_BEFORE_ANY_SIDE_EFFECT;
  }

  @Override
  public boolean mayHaveSideEffects() {
    return UNKNOWN_MAY_HAVE_SIDE_EFFECTS;
  }

  @Override
  public boolean returnValueOnlyDependsOnArguments() {
    return UNKNOWN_RETURN_VALUE_ONLY_DEPENDS_ON_ARGUMENTS;
  }

  @Override
  public boolean returnValueHasBeenPropagated() {
    return false;
  }

  @Override
  public UpdatableMethodOptimizationInfo mutableCopy() {
    return new UpdatableMethodOptimizationInfo();
  }
}
