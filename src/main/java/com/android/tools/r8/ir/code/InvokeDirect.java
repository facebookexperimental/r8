// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.cf.code.CfInvoke;
import com.android.tools.r8.code.InvokeDirectRange;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.analysis.ClassInitializationAnalysis;
import com.android.tools.r8.ir.analysis.ClassInitializationAnalysis.AnalysisAssumption;
import com.android.tools.r8.ir.analysis.ClassInitializationAnalysis.Query;
import com.android.tools.r8.ir.analysis.fieldvalueanalysis.AbstractFieldSet;
import com.android.tools.r8.ir.analysis.modeling.LibraryMethodReadSetModeling;
import com.android.tools.r8.ir.conversion.CfBuilder;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.ConstraintWithTarget;
import com.android.tools.r8.ir.optimize.InliningConstraints;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class InvokeDirect extends InvokeMethodWithReceiver {

  private final boolean itf;

  public InvokeDirect(DexMethod target, Value result, List<Value> arguments) {
    this(target, result, arguments, false);
  }

  public InvokeDirect(DexMethod target, Value result, List<Value> arguments, boolean itf) {
    super(target, result, arguments);
    this.itf = itf;
    // invoke-direct <init> should have no out value.
    assert !target.name.toString().equals(Constants.INSTANCE_INITIALIZER_NAME)
        || result == null;
  }

  @Override
  public int opcode() {
    return Opcodes.INVOKE_DIRECT;
  }

  public boolean isInterface() {
    return itf;
  }

  @Override
  public <T> T accept(InstructionVisitor<T> visitor) {
    return visitor.visit(this);
  }

  @Override
  public Type getType() {
    return Type.DIRECT;
  }

  @Override
  protected String getTypeString() {
    return "Direct";
  }

  @Override
  public void buildDex(DexBuilder builder) {
    com.android.tools.r8.code.Instruction instruction;
    int argumentRegisters = requiredArgumentRegisters();
    builder.requestOutgoingRegisters(argumentRegisters);
    if (needsRangedInvoke(builder)) {
      assert argumentsConsecutive(builder);
      int firstRegister = argumentRegisterValue(0, builder);
      instruction = new InvokeDirectRange(firstRegister, argumentRegisters, getInvokedMethod());
    } else {
      int[] individualArgumentRegisters = new int[5];
      int argumentRegistersCount = fillArgumentRegisters(builder, individualArgumentRegisters);
      instruction = new com.android.tools.r8.code.InvokeDirect(
          argumentRegistersCount,
          getInvokedMethod(),
          individualArgumentRegisters[0],  // C
          individualArgumentRegisters[1],  // D
          individualArgumentRegisters[2],  // E
          individualArgumentRegisters[3],  // F
          individualArgumentRegisters[4]); // G
    }
    addInvokeAndMoveResult(instruction, builder);
  }

  /**
   * Two invokes of a constructor are only allowed to be considered equal if the object
   * they are initializing is the same. Art rejects code that has objects created by
   * different new-instance instructions flow to one constructor invoke.
   */
  public boolean sameConstructorReceiverValue(Invoke other) {
    if (!getInvokedMethod().name.toString().equals(Constants.INSTANCE_INITIALIZER_NAME)) {
      return true;
    }
    return inValues.get(0) == other.inValues.get(0);
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.isInvokeDirect() && super.identicalNonValueNonPositionParts(other);
  }

  @Override
  public boolean isInvokeDirect() {
    return true;
  }

  @Override
  public InvokeDirect asInvokeDirect() {
    return this;
  }

  @Override
  public DexEncodedMethod lookupSingleTarget(
      AppView<?> appView, ProgramMethod context, Value receiver) {
    DexMethod invokedMethod = getInvokedMethod();
    if (appView.appInfo().hasLiveness()) {
      AppInfoWithLiveness appInfo = appView.appInfo().withLiveness();
      DexEncodedMethod result = appInfo.lookupDirectTarget(invokedMethod, context);
      assert verifyD8LookupResult(
          result, appView.appInfo().lookupDirectTargetOnItself(invokedMethod, context));
      return result;
    }
    // In D8, we can treat invoke-direct instructions as having a single target if the invoke is
    // targeting a method in the enclosing class.
    return appView.appInfo().lookupDirectTargetOnItself(invokedMethod, context);
  }

  @Override
  public ConstraintWithTarget inliningConstraint(
      InliningConstraints inliningConstraints, ProgramMethod context) {
    return inliningConstraints.forInvokeDirect(getInvokedMethod(), context.getHolder());
  }

  @Override
  public void buildCf(CfBuilder builder) {
    builder.add(new CfInvoke(org.objectweb.asm.Opcodes.INVOKESPECIAL, getInvokedMethod(), itf));
  }

  @Override
  public boolean definitelyTriggersClassInitialization(
      DexType clazz,
      ProgramMethod context,
      AppView<AppInfoWithLiveness> appView,
      Query mode,
      AnalysisAssumption assumption) {
    return ClassInitializationAnalysis.InstructionUtils.forInvokeDirect(
        this, clazz, appView, mode, assumption);
  }

  @Override
  public boolean canBeDeadCode(AppView<?> appView, IRCode code) {
    ProgramMethod context = code.context();
    if (instructionMayHaveSideEffects(appView, context)) {
      return false;
    }

    if (appView.dexItemFactory().isConstructor(getInvokedMethod())) {
      // If it is a constructor call that initializes an uninitialized object, then the
      // uninitialized object must be dead. This is the case if all the constructor calls cannot
      // have side effects and the instance is dead except for the constructor calls.
      List<Instruction> otherInitCalls = null;
      for (Instruction user : getReceiver().uniqueUsers()) {
        if (user == this) {
          continue;
        }
        if (user.isInvokeDirect()) {
          InvokeDirect invoke = user.asInvokeDirect();
          if (appView.dexItemFactory().isConstructor(invoke.getInvokedMethod())
              && invoke.getReceiver() == getReceiver()) {
            // If another constructor call than `this` is found, then it must not have side effects.
            if (invoke.instructionMayHaveSideEffects(appView, context)) {
              return false;
            }
            if (otherInitCalls == null) {
              otherInitCalls = new ArrayList<>();
            }
            otherInitCalls.add(invoke);
          }
        }
      }

      // Now check that the instance is dead except for the constructor calls.
      final List<Instruction> finalOtherInitCalls = otherInitCalls;
      Predicate<Instruction> ignoreConstructorCalls =
          instruction ->
              instruction == this
                  || (finalOtherInitCalls != null && finalOtherInitCalls.contains(instruction));
      if (!getReceiver().isDead(appView, code, ignoreConstructorCalls)) {
        return false;
      }

      // Verify that it is not a super-constructor call (these cannot be removed).
      if (getReceiver().getAliasedValue() == code.getThis()) {
        return false;
      }
    }

    return true;
  }

  @Override
  public AbstractFieldSet readSet(AppView<AppInfoWithLiveness> appView, ProgramMethod context) {
    DexMethod invokedMethod = getInvokedMethod();

    // Trivial instance initializers do not read any fields.
    if (appView.dexItemFactory().isConstructor(invokedMethod)) {
      DexEncodedMethod singleTarget = lookupSingleTarget(appView, context);

      // If we have a single target in the program, then use the computed initializer info.
      // If we have a single target in the library, then fallthrough to the library modeling below.
      if (singleTarget != null && singleTarget.isProgramMethod(appView)) {
        return singleTarget.getOptimizationInfo().getInstanceInitializerInfo().readSet();
      }
    }

    return LibraryMethodReadSetModeling.getModeledReadSetOrUnknown(this, appView.dexItemFactory());
  }
}
