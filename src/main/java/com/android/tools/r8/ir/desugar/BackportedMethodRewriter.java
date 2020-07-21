// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.desugar;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.*;
import com.android.tools.r8.graph.DexApplication.Builder;
import com.android.tools.r8.graph.DexProgramClass.ChecksumSupplier;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.InvokeMethod;
import com.android.tools.r8.ir.code.InvokeStatic;
import com.android.tools.r8.ir.conversion.IRConverter;
import com.android.tools.r8.ir.desugar.backports.BackportedMethods;
import com.android.tools.r8.ir.desugar.backports.BooleanMethodRewrites;
import com.android.tools.r8.ir.desugar.backports.FloatMethodRewrites;
import com.android.tools.r8.ir.desugar.backports.ListMethodGenerators;
import com.android.tools.r8.ir.desugar.backports.ListMethodRewrites;
import com.android.tools.r8.ir.desugar.backports.LongMethodRewrites;
import com.android.tools.r8.ir.desugar.backports.NumericMethodRewrites;
import com.android.tools.r8.ir.desugar.backports.ObjectsMethodRewrites;
import com.android.tools.r8.origin.SynthesizedOrigin;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.StringDiagnostic;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

public final class BackportedMethodRewriter {

  public static final String UTILITY_CLASS_NAME_PREFIX = "$r8$backportedMethods$utility";
  private static final String UTILITY_CLASS_DESCRIPTOR_PREFIX = "L" + UTILITY_CLASS_NAME_PREFIX;

  private final AppView<?> appView;
  private final IRConverter converter;
  private final DexItemFactory factory;
  private final RewritableMethods rewritableMethods;

  private final Set<DexType> holders = Sets.newConcurrentHashSet();
  private final Map<DexMethod, MethodProvider> methodProviders = new ConcurrentHashMap<>();

  public BackportedMethodRewriter(AppView<?> appView, IRConverter converter) {
    this.appView = appView;
    this.converter = converter;
    this.factory = appView.dexItemFactory();
    this.rewritableMethods = new RewritableMethods(appView.options(), appView);
  }

  public static List<DexMethod> generateListOfBackportedMethods(AndroidApiLevel apiLevel) {
    List<DexMethod> methods = new ArrayList<>();
    InternalOptions options = new InternalOptions();
    options.minApiLevel = apiLevel.getLevel();
    AppView<?> appView = AppView.createForD8(null, options);
    BackportedMethodRewriter.RewritableMethods rewritableMethods =
        new BackportedMethodRewriter.RewritableMethods(options, appView);
    rewritableMethods.visit(methods::add);
    return methods;
  }

  public void desugar(IRCode code) {
    if (rewritableMethods.isEmpty()) {
      return; // Nothing to do!
    }

    InstructionListIterator iterator = code.instructionListIterator();
    while (iterator.hasNext()) {
      Instruction instruction = iterator.next();
      if (!instruction.isInvokeMethod()) {
        continue;
      }

      InvokeMethod invoke = instruction.asInvokeMethod();
      MethodProvider provider = getMethodProviderOrNull(invoke.getInvokedMethod());
      if (provider == null) {
        continue;
      }

      provider.rewriteInvoke(invoke, iterator, code, appView);

      if (provider.requiresGenerationOfCode()) {
        DexMethod newMethod = provider.provideMethod(appView);
        methodProviders.putIfAbsent(newMethod, provider);
        holders.add(code.method.method.holder);
      }
    }
  }

  private Collection<DexProgramClass> findSynthesizedFrom(Builder<?> builder, DexType holder) {
    for (DexProgramClass synthesizedClass : builder.getSynthesizedClasses()) {
      if (holder == synthesizedClass.getType()) {
        return synthesizedClass.getSynthesizedFrom();
      }
    }
    return null;
  }

  public static boolean hasRewrittenMethodPrefix(DexType clazz) {
    return clazz.descriptor.toString().startsWith(UTILITY_CLASS_DESCRIPTOR_PREFIX);
  }

  public void synthesizeUtilityClass(
      Builder<?> builder, ExecutorService executorService, InternalOptions options)
      throws ExecutionException {
    if (holders.isEmpty()) {
      return;
    }
    // Compute referencing classes ignoring references in-between utility classes.
    Set<DexProgramClass> referencingClasses = Sets.newConcurrentHashSet();
    for (DexType holder : holders) {
      DexClass definitionFor = appView.definitionFor(holder);
      if (definitionFor == null) {
        Collection<DexProgramClass> synthesizedFrom = findSynthesizedFrom(builder, holder);
        assert synthesizedFrom != null;
        referencingClasses.addAll(synthesizedFrom);
      } else {
        referencingClasses.add(definitionFor.asProgramClass());
      }
    }

    MethodAccessFlags flags = MethodAccessFlags.fromSharedAccessFlags(
        Constants.ACC_PUBLIC | Constants.ACC_STATIC | Constants.ACC_SYNTHETIC, false);
    ClassAccessFlags classAccessFlags =
        ClassAccessFlags.fromSharedAccessFlags(Constants.ACC_PUBLIC | Constants.ACC_SYNTHETIC);
    // Generate the utility classes in a loop since utility classes can require the
    // the creation of other utility classes.
    // Function multiplyExact(long, int) calls multiplyExact(long, long) for example.
    while (!methodProviders.isEmpty()) {
      DexMethod method = methodProviders.keySet().iterator().next();
      MethodProvider provider = methodProviders.remove(method);
      assert provider.requiresGenerationOfCode();
      // The utility class could have been synthesized, e.g., running R8 then D8,
      // or if already processed in this while loop.
      if (appView.definitionFor(method.holder) != null) {
        continue;
      }
      Code code = provider.generateTemplateMethod(options, method);
      DexEncodedMethod dexEncodedMethod =
          new DexEncodedMethod(
              method, flags, DexAnnotationSet.empty(), ParameterAnnotationsList.empty(), code);
      DexProgramClass utilityClass =
          new DexProgramClass(
              method.holder,
              null,
              new SynthesizedOrigin("java8 methods utility class", getClass()),
              classAccessFlags,
              factory.objectType,
              DexTypeList.empty(),
              null,
              null,
              Collections.emptyList(),
              null,
              Collections.emptyList(),
              DexAnnotationSet.empty(),
              DexEncodedField.EMPTY_ARRAY,
              DexEncodedField.EMPTY_ARRAY,
              new DexEncodedMethod[] {dexEncodedMethod},
              DexEncodedMethod.EMPTY_ARRAY,
              factory.getSkipNameValidationForTesting(),
              getChecksumSupplier(dexEncodedMethod),
              referencingClasses);
      boolean addToMainDexList =
          referencingClasses.stream()
              .anyMatch(clazz -> appView.appInfo().isInMainDexList(clazz.type));
      appView.appInfo().addSynthesizedClass(utilityClass);
      builder.addSynthesizedClass(utilityClass, addToMainDexList);
      // The following may add elements to methodsProviders.
      converter.optimizeSynthesizedClass(utilityClass, executorService);
    }
  }

  private ChecksumSupplier getChecksumSupplier(DexEncodedMethod method) {
    if (!appView.options().encodeChecksums) {
      return DexProgramClass::invalidChecksumRequest;
    }
    return c -> method.method.hashCode();
  }

  private MethodProvider getMethodProviderOrNull(DexMethod method) {
    DexMethod original = appView.graphLense().getOriginalMethodSignature(method);
    assert original != null;
    Map<DexType, DexType> backportCoreLibraryMembers =
        appView.options().desugaredLibraryConfiguration.getBackportCoreLibraryMember();
    if (backportCoreLibraryMembers.containsKey(original.holder)) {
      DexType newHolder = backportCoreLibraryMembers.get(original.holder);
      DexMethod newMethod =
          appView.dexItemFactory().createMethod(newHolder, original.proto, original.name);
      MethodProvider provider = rewritableMethods.getProvider(newMethod);
      if (provider != null) {
        return provider;
      }
      RetargetCoreLibraryMethodProvider extraProvider =
          new RetargetCoreLibraryMethodProvider(newHolder, original, true);
      // TODO(b/139788786): cache this entry, but without writing into a lock free structure.
      // rewritableMethods.addProvider(extraProvider);
      return extraProvider;
    }
    return rewritableMethods.getProvider(original);
  }

  private static final class RewritableMethods {

    // Map backported method to a provider for creating the actual target method (with code).
    private final Map<DexMethod, MethodProvider> rewritable = new IdentityHashMap<>();

    RewritableMethods(InternalOptions options, AppView<?> appView) {
      DexItemFactory factory = options.itemFactory;

      if (options.minApiLevel < AndroidApiLevel.K.getLevel()) {
        initializeAndroidKMethodProviders(factory);
      }
      if (options.minApiLevel < AndroidApiLevel.N.getLevel()) {
        initializeAndroidNMethodProviders(factory);
      }
      if (options.minApiLevel < AndroidApiLevel.O.getLevel()) {
        initializeAndroidOMethodProviders(factory);
      }

      if (appView.rewritePrefix.hasRewrittenType(factory.optionalType)
          || options.minApiLevel >= AndroidApiLevel.N.getLevel()) {
        // These are currently not implemented at any API level in Android.
        // They however require the Optional class to be present, either through
        // desugared libraries or natively. If Optional class is not present,
        // we do not desugar to avoid confusion in error messages.
        initializeOptionalMethodProviders(factory);
      }

      // These are currently not implemented at any API level in Android.
      initializeJava9MethodProviders(factory);
      initializeJava11MethodProviders(factory);

      if (!options.desugaredLibraryConfiguration.getRetargetCoreLibMember().isEmpty()) {
        initializeRetargetCoreLibraryMembers(appView);
      }
    }

    boolean isEmpty() {
      return rewritable.isEmpty();
    }

    public void visit(Consumer<DexMethod> consumer) {
      rewritable.keySet().forEach(consumer);
    }

    private void initializeAndroidKMethodProviders(DexItemFactory factory) {
      // Byte
      DexType type = factory.boxedByteType;
      // int Byte.compare(byte a, byte b)
      DexString name = factory.createString("compare");
      DexProto proto = factory.createProto(factory.intType, factory.byteType, factory.byteType);
      DexMethod method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::ByteMethods_compare));

      // Short
      type = factory.boxedShortType;
      // int Short.compare(short a, short b)
      name = factory.createString("compare");
      proto = factory.createProto(factory.intType, factory.shortType, factory.shortType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::ShortMethods_compare));

      // Integer
      type = factory.boxedIntType;
      // int Integer.compare(int a, int b)
      name = factory.createString("compare");
      proto = factory.createProto(factory.intType, factory.intType, factory.intType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::IntegerMethods_compare));

      // Long
      type = factory.boxedLongType;
      // int Long.compare(long a, long b)
      name = factory.createString("compare");
      proto = factory.createProto(factory.intType, factory.longType, factory.longType);
      method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, LongMethodRewrites::rewriteCompare));

      // Boolean
      type = factory.boxedBooleanType;
      // int Boolean.compare(boolean a, boolean b)
      name = factory.createString("compare");
      proto = factory.createProto(factory.intType, factory.booleanType, factory.booleanType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::BooleanMethods_compare));

      // Character
      type = factory.boxedCharType;
      // int Character.compare(char a, char b)
      name = factory.createString("compare");
      proto = factory.createProto(factory.intType, factory.charType, factory.charType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::CharacterMethods_compare));

      // Objects
      type = factory.objectsType;

      // int Objects.compare(T a, T b, Comparator<? super T> c)
      name = factory.createString("compare");
      proto = factory.createProto(factory.intType, factory.objectType, factory.objectType,
          factory.comparatorType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::ObjectsMethods_compare));

      // boolean Objects.deepEquals(Object a, Object b)
      name = factory.createString("deepEquals");
      proto = factory.createProto(factory.booleanType, factory.objectType, factory.objectType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::ObjectsMethods_deepEquals));

      // boolean Objects.equals(Object a, Object b)
      name = factory.createString("equals");
      proto = factory.createProto(factory.booleanType, factory.objectType, factory.objectType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::ObjectsMethods_equals));

      // int Objects.hash(Object... o)
      name = factory.createString("hash");
      proto = factory.createProto(factory.intType, factory.objectArrayType);
      method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, ObjectsMethodRewrites::rewriteToArraysHashCode));

      // int Objects.hashCode(Object o)
      name = factory.createString("hashCode");
      proto = factory.createProto(factory.intType, factory.objectType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::ObjectsMethods_hashCode));

      // T Objects.requireNonNull(T obj)
      method = factory.objectsMethods.requireNonNull;
      addProvider(new InvokeRewriter(method, ObjectsMethodRewrites::rewriteRequireNonNull));

      // T Objects.requireNonNull(T obj, String message)
      name = factory.createString("requireNonNull");
      proto = factory.createProto(factory.objectType, factory.objectType, factory.stringType);
      method = factory.createMethod(type, proto, name);
      addProvider(
          new MethodGenerator(
              method,
              BackportedMethods::ObjectsMethods_requireNonNullMessage,
              "requireNonNullMessage"));

      // String Objects.toString(Object o)
      name = factory.createString("toString");
      proto = factory.createProto(factory.stringType, factory.objectType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::ObjectsMethods_toString));

      // String Objects.toString(Object o, String nullDefault);
      name = factory.createString("toString");
      proto = factory.createProto(factory.stringType, factory.objectType, factory.stringType);
      method = factory.createMethod(type, proto, name);
      addProvider(
          new MethodGenerator(
              method, BackportedMethods::ObjectsMethods_toStringDefault, "toStringDefault"));

      // Collections
      type = factory.collectionsType;

      // Enumeration<T> Collections.emptyEnumeration();
      name = factory.createString("emptyEnumeration");
      proto = factory.createProto(factory.enumerationType);
      method = factory.createMethod(type, proto, name);
      addProvider(
          new MethodGenerator(method, BackportedMethods::CollectionsMethods_emptyEnumeration));

      // Iterator<T> Collections.emptyIterator();
      name = factory.createString("emptyIterator");
      proto = factory.createProto(factory.iteratorType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::CollectionsMethods_emptyIterator));

      // ListIterator<T> Collections.emptyListIterator();
      name = factory.createString("emptyListIterator");
      proto = factory.createProto(factory.listIteratorType);
      method = factory.createMethod(type, proto, name);
      addProvider(
          new MethodGenerator(method, BackportedMethods::CollectionsMethods_emptyListIterator));
    }

    private void initializeAndroidNMethodProviders(DexItemFactory factory) {
      // Byte
      DexType type = factory.boxedByteType;
      // int Byte.hashCode(byte i)
      DexString name = factory.createString("hashCode");
      DexProto proto = factory.createProto(factory.intType, factory.byteType);
      DexMethod method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, NumericMethodRewrites::rewriteAsIdentity));

      // Short
      type = factory.boxedShortType;
      // int Short.hashCode(short i)
      name = factory.createString("hashCode");
      proto = factory.createProto(factory.intType, factory.shortType);
      method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, NumericMethodRewrites::rewriteAsIdentity));

      // Integer
      type = factory.boxedIntType;

      // int Integer.hashCode(int i)
      name = factory.createString("hashCode");
      proto = factory.createProto(factory.intType, factory.intType);
      method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, NumericMethodRewrites::rewriteAsIdentity));

      // int Integer.max(int a, int b)
      name = factory.createString("max");
      proto = factory.createProto(factory.intType, factory.intType, factory.intType);
      method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, NumericMethodRewrites::rewriteToInvokeMath));

      // int Integer.min(int a, int b)
      name = factory.createString("min");
      proto = factory.createProto(factory.intType, factory.intType, factory.intType);
      method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, NumericMethodRewrites::rewriteToInvokeMath));

      // int Integer.sum(int a, int b)
      name = factory.createString("sum");
      proto = factory.createProto(factory.intType, factory.intType, factory.intType);
      method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, NumericMethodRewrites::rewriteToAddInstruction));

      // Double
      type = factory.boxedDoubleType;

      // int Double.hashCode(double d)
      name = factory.createString("hashCode");
      proto = factory.createProto(factory.intType, factory.doubleType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::DoubleMethods_hashCode));

      // double Double.max(double a, double b)
      name = factory.createString("max");
      proto = factory.createProto(factory.doubleType, factory.doubleType, factory.doubleType);
      method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, NumericMethodRewrites::rewriteToInvokeMath));

      // double Double.min(double a, double b)
      name = factory.createString("min");
      proto = factory.createProto(factory.doubleType, factory.doubleType, factory.doubleType);
      method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, NumericMethodRewrites::rewriteToInvokeMath));

      // double Double.sum(double a, double b)
      name = factory.createString("sum");
      proto = factory.createProto(factory.doubleType, factory.doubleType, factory.doubleType);
      method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, NumericMethodRewrites::rewriteToAddInstruction));

      // boolean Double.isFinite(double a)
      name = factory.createString("isFinite");
      proto = factory.createProto(factory.booleanType, factory.doubleType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::DoubleMethods_isFinite));

      // Float
      type = factory.boxedFloatType;

      // int Float.hashCode(float d)
      name = factory.createString("hashCode");
      proto = factory.createProto(factory.intType, factory.floatType);
      method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, FloatMethodRewrites::rewriteHashCode));

      // float Float.max(float a, float b)
      name = factory.createString("max");
      proto = factory.createProto(factory.floatType, factory.floatType, factory.floatType);
      method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, NumericMethodRewrites::rewriteToInvokeMath));

      // float Float.min(float a, float b)
      name = factory.createString("min");
      proto = factory.createProto(factory.floatType, factory.floatType, factory.floatType);
      method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, NumericMethodRewrites::rewriteToInvokeMath));

      // float Float.sum(float a, float b)
      name = factory.createString("sum");
      proto = factory.createProto(factory.floatType, factory.floatType, factory.floatType);
      method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, NumericMethodRewrites::rewriteToAddInstruction));

      // boolean Float.isFinite(float a)
      name = factory.createString("isFinite");
      proto = factory.createProto(factory.booleanType, factory.floatType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::FloatMethods_isFinite));

      // Boolean
      type = factory.boxedBooleanType;

      // int Boolean.hashCode(boolean b)
      name = factory.createString("hashCode");
      proto = factory.createProto(factory.intType, factory.booleanType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::BooleanMethods_hashCode));

      // boolean Boolean.logicalAnd(boolean a, boolean b)
      name = factory.createString("logicalAnd");
      proto = factory.createProto(factory.booleanType, factory.booleanType, factory.booleanType);
      method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, BooleanMethodRewrites::rewriteLogicalAnd));

      // boolean Boolean.logicalOr(boolean a, boolean b)
      name = factory.createString("logicalOr");
      proto = factory.createProto(factory.booleanType, factory.booleanType, factory.booleanType);
      method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, BooleanMethodRewrites::rewriteLogicalOr));

      // boolean Boolean.logicalXor(boolean a, boolean b)
      name = factory.createString("logicalXor");
      proto = factory.createProto(factory.booleanType, factory.booleanType, factory.booleanType);
      method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, BooleanMethodRewrites::rewriteLogicalXor));

      // Long
      type = factory.boxedLongType;

      // int Long.hashCode(long i)
      name = factory.createString("hashCode");
      proto = factory.createProto(factory.intType, factory.longType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::LongMethods_hashCode));

      // long Long.max(long a, long b)
      name = factory.createString("max");
      proto = factory.createProto(factory.longType, factory.longType, factory.longType);
      method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, NumericMethodRewrites::rewriteToInvokeMath));

      // long Long.min(long a, long b)
      name = factory.createString("min");
      proto = factory.createProto(factory.longType, factory.longType, factory.longType);
      method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, NumericMethodRewrites::rewriteToInvokeMath));

      // long Long.sum(long a, long b)
      name = factory.createString("sum");
      proto = factory.createProto(factory.longType, factory.longType, factory.longType);
      method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, NumericMethodRewrites::rewriteToAddInstruction));

      // Character
      type = factory.boxedCharType;

      // int Character.hashCode(char i)
      name = factory.createString("hashCode");
      proto = factory.createProto(factory.intType, factory.charType);
      method = factory.createMethod(type, proto, name);
      addProvider(new InvokeRewriter(method, NumericMethodRewrites::rewriteAsIdentity));

      // Objects
      type = factory.objectsType;

      // boolean Objects.isNull(Object o)
      name = factory.createString("isNull");
      proto = factory.createProto(factory.booleanType, factory.objectType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::ObjectsMethods_isNull));

      // boolean Objects.nonNull(Object a)
      name = factory.createString("nonNull");
      proto = factory.createProto(factory.booleanType, factory.objectType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::ObjectsMethods_nonNull));

      // Math & StrictMath, which have some symmetric, binary-compatible APIs
      DexType[] mathTypes = {factory.mathType, factory.strictMathType};
      for (int i = 0; i < mathTypes.length; i++) {
        type = mathTypes[i];

        // int {Math,StrictMath}.addExact(int, int)
        name = factory.createString("addExact");
        proto = factory.createProto(factory.intType, factory.intType, factory.intType);
        method = factory.createMethod(type, proto, name);
        addProvider(
            new MethodGenerator(method, BackportedMethods::MathMethods_addExactInt, "addExactInt"));

        // long {Math,StrictMath}.addExact(long, long)
        name = factory.createString("addExact");
        proto = factory.createProto(factory.longType, factory.longType, factory.longType);
        method = factory.createMethod(type, proto, name);
        addProvider(
            new MethodGenerator(
                method, BackportedMethods::MathMethods_addExactLong, "addExactLong"));

        // int {Math,StrictMath}.floorDiv(int, int)
        name = factory.createString("floorDiv");
        proto = factory.createProto(factory.intType, factory.intType, factory.intType);
        method = factory.createMethod(type, proto, name);
        addProvider(
            new MethodGenerator(method, BackportedMethods::MathMethods_floorDivInt, "floorDivInt"));

        // long {Math,StrictMath}.floorDiv(long, long)
        name = factory.createString("floorDiv");
        proto = factory.createProto(factory.longType, factory.longType, factory.longType);
        method = factory.createMethod(type, proto, name);
        addProvider(
            new MethodGenerator(
                method, BackportedMethods::MathMethods_floorDivLong, "floorDivLong"));

        // int {Math,StrictMath}.floorMod(int, int)
        name = factory.createString("floorMod");
        proto = factory.createProto(factory.intType, factory.intType, factory.intType);
        method = factory.createMethod(type, proto, name);
        addProvider(
            new MethodGenerator(method, BackportedMethods::MathMethods_floorModInt, "floorModInt"));

        // long {Math,StrictMath}.floorMod(long, long)
        name = factory.createString("floorMod");
        proto = factory.createProto(factory.longType, factory.longType, factory.longType);
        method = factory.createMethod(type, proto, name);
        addProvider(
            new MethodGenerator(
                method, BackportedMethods::MathMethods_floorModLong, "floorModLong"));

        // int {Math,StrictMath}.multiplyExact(int, int)
        name = factory.createString("multiplyExact");
        proto = factory.createProto(factory.intType, factory.intType, factory.intType);
        method = factory.createMethod(type, proto, name);
        addProvider(
            new MethodGenerator(
                method, BackportedMethods::MathMethods_multiplyExactInt, "multiplyExactInt"));

        // long {Math,StrictMath}.multiplyExact(long, long)
        name = factory.createString("multiplyExact");
        proto = factory.createProto(factory.longType, factory.longType, factory.longType);
        method = factory.createMethod(type, proto, name);
        addProvider(
            new MethodGenerator(
                method, BackportedMethods::MathMethods_multiplyExactLong, "multiplyExactLong"));

        // double {Math,StrictMath}.nextDown(double)
        name = factory.createString("nextDown");
        proto = factory.createProto(factory.doubleType, factory.doubleType);
        method = factory.createMethod(type, proto, name);
        addProvider(
            new MethodGenerator(
                method, BackportedMethods::MathMethods_nextDownDouble, "nextDownDouble"));

        // float {Math,StrictMath}.nextDown(float)
        name = factory.createString("nextDown");
        proto = factory.createProto(factory.floatType, factory.floatType);
        method = factory.createMethod(type, proto, name);
        addProvider(
            new MethodGenerator(
                method, BackportedMethods::MathMethods_nextDownFloat, "nextDownFloat"));

        // int {Math,StrictMath}.subtractExact(int, int)
        name = factory.createString("subtractExact");
        proto = factory.createProto(factory.intType, factory.intType, factory.intType);
        method = factory.createMethod(type, proto, name);
        addProvider(
            new MethodGenerator(
                method, BackportedMethods::MathMethods_subtractExactInt, "subtractExactInt"));

        // long {Math,StrictMath}.subtractExact(long, long)
        name = factory.createString("subtractExact");
        proto = factory.createProto(factory.longType, factory.longType, factory.longType);
        method = factory.createMethod(type, proto, name);
        addProvider(
            new MethodGenerator(
                method, BackportedMethods::MathMethods_subtractExactLong, "subtractExactLong"));

        // int {Math,StrictMath}.toIntExact(long)
        name = factory.createString("toIntExact");
        proto = factory.createProto(factory.intType, factory.longType);
        method = factory.createMethod(type, proto, name);
        addProvider(new MethodGenerator(method, BackportedMethods::MathMethods_toIntExact));
      }

      // Math (APIs which are not mirrored by StrictMath)
      type = factory.mathType;

      // int Math.decrementExact(int)
      name = factory.createString("decrementExact");
      proto = factory.createProto(factory.intType, factory.intType);
      method = factory.createMethod(type, proto, name);
      addProvider(
          new MethodGenerator(
              method, BackportedMethods::MathMethods_decrementExactInt, "decrementExactInt"));

      // long Math.decrementExact(long)
      name = factory.createString("decrementExact");
      proto = factory.createProto(factory.longType, factory.longType);
      method = factory.createMethod(type, proto, name);
      addProvider(
          new MethodGenerator(
              method, BackportedMethods::MathMethods_decrementExactLong, "decrementExactLong"));

      // int Math.incrementExact(int)
      name = factory.createString("incrementExact");
      proto = factory.createProto(factory.intType, factory.intType);
      method = factory.createMethod(type, proto, name);
      addProvider(
          new MethodGenerator(
              method, BackportedMethods::MathMethods_incrementExactInt, "incrementExactInt"));

      // long Math.incrementExact(long)
      name = factory.createString("incrementExact");
      proto = factory.createProto(factory.longType, factory.longType);
      method = factory.createMethod(type, proto, name);
      addProvider(
          new MethodGenerator(
              method, BackportedMethods::MathMethods_incrementExactLong, "incrementExactLong"));

      // int Math.negateExact(int)
      name = factory.createString("negateExact");
      proto = factory.createProto(factory.intType, factory.intType);
      method = factory.createMethod(type, proto, name);
      addProvider(
          new MethodGenerator(
              method, BackportedMethods::MathMethods_negateExactInt, "negateExactInt"));

      // long Math.negateExact(long)
      name = factory.createString("negateExact");
      proto = factory.createProto(factory.longType, factory.longType);
      method = factory.createMethod(type, proto, name);
      addProvider(
          new MethodGenerator(
              method, BackportedMethods::MathMethods_negateExactLong, "negateExactLong"));
    }

    private void initializeAndroidOMethodProviders(DexItemFactory factory) {
      // Byte
      DexType type = factory.boxedByteType;

      // int Byte.toUnsignedInt(byte value)
      DexString name = factory.createString("toUnsignedInt");
      DexProto proto = factory.createProto(factory.intType, factory.byteType);
      DexMethod method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::ByteMethods_toUnsignedInt));

      // long Byte.toUnsignedLong(byte value)
      name = factory.createString("toUnsignedLong");
      proto = factory.createProto(factory.longType, factory.byteType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::ByteMethods_toUnsignedLong));

      // Short
      type = factory.boxedShortType;

      // int Short.toUnsignedInt(short value)
      name = factory.createString("toUnsignedInt");
      proto = factory.createProto(factory.intType, factory.shortType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::ShortMethods_toUnsignedInt));

      // long Short.toUnsignedLong(short value)
      name = factory.createString("toUnsignedLong");
      proto = factory.createProto(factory.longType, factory.shortType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::ShortMethods_toUnsignedLong));

      // Integer
      type = factory.boxedIntType;

      // int Integer.divideUnsigned(int a, int b)
      name = factory.createString("divideUnsigned");
      proto = factory.createProto(factory.intType, factory.intType, factory.intType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::IntegerMethods_divideUnsigned));

      // int Integer.remainderUnsigned(int a, int b)
      name = factory.createString("remainderUnsigned");
      proto = factory.createProto(factory.intType, factory.intType, factory.intType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::IntegerMethods_remainderUnsigned));

      // int Integer.compareUnsigned(int a, int b)
      name = factory.createString("compareUnsigned");
      proto = factory.createProto(factory.intType, factory.intType, factory.intType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::IntegerMethods_compareUnsigned));

      // long Integer.toUnsignedLong(int value)
      name = factory.createString("toUnsignedLong");
      proto = factory.createProto(factory.longType, factory.intType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::IntegerMethods_toUnsignedLong));

      // int Integer.parseUnsignedInt(String value)
      name = factory.createString("parseUnsignedInt");
      proto = factory.createProto(factory.intType, factory.stringType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::IntegerMethods_parseUnsignedInt));

      // int Integer.parseUnsignedInt(String value, int radix)
      name = factory.createString("parseUnsignedInt");
      proto = factory.createProto(factory.intType, factory.stringType, factory.intType);
      method = factory.createMethod(type, proto, name);
      addProvider(
          new MethodGenerator(
              method,
              BackportedMethods::IntegerMethods_parseUnsignedIntWithRadix,
              "parseUnsignedIntWithRadix"));

      // String Integer.toUnsignedString(int value)
      name = factory.createString("toUnsignedString");
      proto = factory.createProto(factory.stringType, factory.intType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::IntegerMethods_toUnsignedString));

      // String Integer.toUnsignedString(int value, int radix)
      name = factory.createString("toUnsignedString");
      proto = factory.createProto(factory.stringType, factory.intType, factory.intType);
      method = factory.createMethod(type, proto, name);
      addProvider(
          new MethodGenerator(
              method,
              BackportedMethods::IntegerMethods_toUnsignedStringWithRadix,
              "toUnsignedStringWithRadix"));

      // Long
      type = factory.boxedLongType;

      // long Long.divideUnsigned(long a, long b)
      name = factory.createString("divideUnsigned");
      proto = factory.createProto(factory.longType, factory.longType, factory.longType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::LongMethods_divideUnsigned));

      // long Long.remainderUnsigned(long a, long b)
      name = factory.createString("remainderUnsigned");
      proto = factory.createProto(factory.longType, factory.longType, factory.longType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::LongMethods_remainderUnsigned));

      // int Long.compareUnsigned(long a, long b)
      name = factory.createString("compareUnsigned");
      proto = factory.createProto(factory.intType, factory.longType, factory.longType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::LongMethods_compareUnsigned));

      // long Long.parseUnsignedLong(String value)
      name = factory.createString("parseUnsignedLong");
      proto = factory.createProto(factory.longType, factory.stringType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::LongMethods_parseUnsignedLong));

      // long Long.parseUnsignedLong(String value, int radix)
      name = factory.createString("parseUnsignedLong");
      proto = factory.createProto(factory.longType, factory.stringType, factory.intType);
      method = factory.createMethod(type, proto, name);
      addProvider(
          new MethodGenerator(
              method,
              BackportedMethods::LongMethods_parseUnsignedLongWithRadix,
              "parseUnsignedLongWithRadix"));

      // String Long.toUnsignedString(long value)
      name = factory.createString("toUnsignedString");
      proto = factory.createProto(factory.stringType, factory.longType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::LongMethods_toUnsignedString));

      // String Long.toUnsignedString(long value, int radix)
      name = factory.createString("toUnsignedString");
      proto = factory.createProto(factory.stringType, factory.longType, factory.intType);
      method = factory.createMethod(type, proto, name);
      addProvider(
          new MethodGenerator(
              method,
              BackportedMethods::LongMethods_toUnsignedStringWithRadix,
              "toUnsignedStringWithRadix"));

      // String
      type = factory.stringType;

      // String String.join(CharSequence, CharSequence...)
      name = factory.createString("join");
      proto = factory.createProto(factory.stringType, factory.charSequenceType,
          factory.charSequenceArrayType);
      method = factory.createMethod(type, proto, name);
      addProvider(
          new MethodGenerator(method, BackportedMethods::StringMethods_joinArray, "joinArray"));

      // String String.join(CharSequence, Iterable<? extends CharSequence>)
      name = factory.createString("join");
      proto =
          factory.createProto(factory.stringType, factory.charSequenceType, factory.iterableType);
      method = factory.createMethod(type, proto, name);
      addProvider(
          new MethodGenerator(
              method, BackportedMethods::StringMethods_joinIterable, "joinIterable"));
    }

    private void initializeJava9MethodProviders(DexItemFactory factory) {
      // Math & StrictMath, which have some symmetric, binary-compatible APIs
      DexType[] mathTypes = {factory.mathType, factory.strictMathType};
      for (int i = 0; i < mathTypes.length; i++) {
        DexType type = mathTypes[i];

        // long {Math,StrictMath}.multiplyExact(long, int)
        DexString name = factory.createString("multiplyExact");
        DexProto proto = factory.createProto(factory.longType, factory.longType, factory.intType);
        DexMethod method = factory.createMethod(type, proto, name);
        addProvider(
            new MethodGenerator(
                method,
                BackportedMethods::MathMethods_multiplyExactLongInt,
                "multiplyExactLongInt"));

        // long {Math,StrictMath}.floorDiv(long, int)
        name = factory.createString("floorDiv");
        proto = factory.createProto(factory.longType, factory.longType, factory.intType);
        method = factory.createMethod(type, proto, name);
        addProvider(
            new MethodGenerator(
                method, BackportedMethods::MathMethods_floorDivLongInt, "floorDivLongInt"));

        // int {Math,StrictMath}.floorMod(long, int)
        name = factory.createString("floorMod");
        proto = factory.createProto(factory.intType, factory.longType, factory.intType);
        method = factory.createMethod(type, proto, name);
        addProvider(
            new MethodGenerator(
                method, BackportedMethods::MathMethods_floorModLongInt, "floorModLongInt"));
      }

      // Byte
      DexType type = factory.boxedByteType;

      // int Byte.compareUnsigned(byte, byte)
      DexString name = factory.createString("compareUnsigned");
      DexProto proto = factory.createProto(factory.intType, factory.byteType, factory.byteType);
      DexMethod method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::ByteMethods_compareUnsigned));

      // Short
      type = factory.boxedShortType;

      // int Short.compareUnsigned(short, short)
      name = factory.createString("compareUnsigned");
      proto = factory.createProto(factory.intType, factory.shortType, factory.shortType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::ShortMethods_compareUnsigned));

      // Objects
      type = factory.objectsType;

      // T Objects.requireNonNullElse(T, T)
      name = factory.createString("requireNonNullElse");
      proto = factory.createProto(factory.objectType, factory.objectType, factory.objectType);
      method = factory.createMethod(type, proto, name);
      addProvider(
          new MethodGenerator(method, BackportedMethods::ObjectsMethods_requireNonNullElse));

      // T Objects.requireNonNullElseGet(T, Supplier<? extends T>)
      name = factory.createString("requireNonNullElseGet");
      proto = factory.createProto(factory.objectType, factory.objectType, factory.supplierType);
      method = factory.createMethod(type, proto, name);
      addProvider(
          new MethodGenerator(method, BackportedMethods::ObjectsMethods_requireNonNullElseGet));

      // int Objects.checkIndex(int, int)
      name = factory.createString("checkIndex");
      proto = factory.createProto(factory.intType, factory.intType, factory.intType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::ObjectsMethods_checkIndex));

      // int Objects.checkFromToIndex(int, int, int)
      name = factory.createString("checkFromToIndex");
      proto =
          factory.createProto(factory.intType, factory.intType, factory.intType, factory.intType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::ObjectsMethods_checkFromToIndex));

      // int Objects.checkFromIndexSize(int, int, int)
      name = factory.createString("checkFromIndexSize");
      proto =
          factory.createProto(factory.intType, factory.intType, factory.intType, factory.intType);
      method = factory.createMethod(type, proto, name);
      addProvider(
          new MethodGenerator(method, BackportedMethods::ObjectsMethods_checkFromIndexSize));

      // List<E> List.of(<args>) for 0 to 10 arguments and List.of(E[])
      type = factory.listType;
      name = factory.createString("of");
      for (int i = 0; i <= 10; i++) {
        final int formalCount = i;
        proto = factory.createProto(factory.listType, Collections.nCopies(i, factory.objectType));
        method = factory.createMethod(type, proto, name);
        addProvider(
            i == 0
                ? new InvokeRewriter(method, ListMethodRewrites::rewriteEmptyOf)
                : new MethodGenerator(
                    method,
                    (options, methodArg, ignored) ->
                        ListMethodGenerators.generateListOf(options, methodArg, formalCount)));
      }
      proto = factory.createProto(factory.listType, factory.objectArrayType);
      method = factory.createMethod(type, proto, name);
      addProvider(new MethodGenerator(method, BackportedMethods::ListMethods_ofArray, "ofArray"));
    }

    private void initializeJava11MethodProviders(DexItemFactory factory) {
      // Character
      DexType type = factory.boxedCharType;

      // String Character.toString(int)
      DexString name = factory.createString("toString");
      DexProto proto = factory.createProto(factory.stringType, factory.intType);
      DexMethod method = factory.createMethod(type, proto, name);
      addProvider(
          new MethodGenerator(
              method, BackportedMethods::CharacterMethods_toStringCodepoint, "toStringCodepoint"));
    }

    private void initializeOptionalMethodProviders(DexItemFactory factory) {
      // Optional
      DexType optionalType = factory.optionalType;

      // Optional.or(supplier)
      DexString name = factory.createString("or");
      DexProto proto = factory.createProto(optionalType, factory.supplierType);
      DexMethod method = factory.createMethod(optionalType, proto, name);
      addProvider(
          new StatifyingMethodGenerator(
              method, BackportedMethods::OptionalMethods_or, "or", optionalType));

      // Optional.stream()
      name = factory.createString("stream");
      proto = factory.createProto(factory.createType("Ljava/util/stream/Stream;"));
      method = factory.createMethod(optionalType, proto, name);
      addProvider(
          new StatifyingMethodGenerator(
              method, BackportedMethods::OptionalMethods_stream, "stream", optionalType));

      // Optional{void,Int,Long,Double}.ifPresentOrElse(consumer,runnable)
      DexType[] optionalTypes =
          new DexType[] {
            optionalType,
            factory.createType(factory.createString("Ljava/util/OptionalDouble;")),
            factory.createType(factory.createString("Ljava/util/OptionalLong;")),
            factory.createType(factory.createString("Ljava/util/OptionalInt;"))
          };
      DexType[] consumerTypes =
          new DexType[] {
            factory.consumerType,
            factory.createType("Ljava/util/function/DoubleConsumer;"),
            factory.createType("Ljava/util/function/LongConsumer;"),
            factory.createType("Ljava/util/function/IntConsumer;")
          };
      TemplateMethodFactory[] methodFactories =
          new TemplateMethodFactory[] {
            BackportedMethods::OptionalMethods_ifPresentOrElse,
            BackportedMethods::OptionalMethods_ifPresentOrElseDouble,
            BackportedMethods::OptionalMethods_ifPresentOrElseLong,
            BackportedMethods::OptionalMethods_ifPresentOrElseInt
          };
      for (int i = 0; i < optionalTypes.length; i++) {
        DexType optional = optionalTypes[i];
        DexType consumer = consumerTypes[i];
        name = factory.createString("ifPresentOrElse");
        proto = factory.createProto(factory.voidType, consumer, factory.runnableType);
        method = factory.createMethod(optional, proto, name);
        addProvider(
            new StatifyingMethodGenerator(method, methodFactories[i], "ifPresentOrElse", optional));
      }
    }

    private void warnMissingRetargetCoreLibraryMember(DexType type, AppView<?> appView) {
      StringDiagnostic warning =
          new StringDiagnostic(
              "Cannot retarget core library member "
                  + type.getName()
                  + " because the class is missing.");
      appView.options().reporter.warning(warning);
    }

    private void initializeRetargetCoreLibraryMembers(AppView<?> appView) {
      Map<DexString, Map<DexType, DexType>> retargetCoreLibMember =
          appView.options().desugaredLibraryConfiguration.getRetargetCoreLibMember();
      for (DexString methodName : retargetCoreLibMember.keySet()) {
        for (DexType inType : retargetCoreLibMember.get(methodName).keySet()) {
          DexClass typeClass = appView.definitionFor(inType);
          if (typeClass == null) {
            warnMissingRetargetCoreLibraryMember(inType, appView);
          } else {
            DexType newHolder = retargetCoreLibMember.get(methodName).get(inType);
            List<DexEncodedMethod> found = findDexEncodedMethodsWithName(methodName, typeClass);
            for (DexEncodedMethod encodedMethod : found) {
              DexProto proto = encodedMethod.method.proto;
              DexMethod method = appView.dexItemFactory().createMethod(inType, proto, methodName);
              addProvider(
                  new RetargetCoreLibraryMethodProvider(
                      newHolder, method, encodedMethod.isStatic()));
            }
          }
        }
      }
    }

    private List<DexEncodedMethod> findDexEncodedMethodsWithName(
        DexString methodName, DexClass clazz) {
      List<DexEncodedMethod> found = new ArrayList<>();
      for (DexEncodedMethod encodedMethod : clazz.methods()) {
        if (encodedMethod.method.name == methodName) {
          found.add(encodedMethod);
        }
      }
      assert found.size() > 0 : "Should have found a method (library specifications).";
      return found;
    }

    private void addProvider(MethodProvider generator) {
      MethodProvider replaced = rewritable.put(generator.method, generator);
      assert replaced == null;
    }

    MethodProvider getProvider(DexMethod method) {
      return rewritable.get(method);
    }
  }

  public abstract static class MethodProvider {
    final DexMethod method;

    public MethodProvider(DexMethod method) {
      this.method = method;
    }

    public abstract void rewriteInvoke(InvokeMethod invoke, InstructionListIterator iterator,
        IRCode code, AppView<?> appView);

    public abstract DexMethod provideMethod(AppView<?> appView);

    public abstract Code generateTemplateMethod(InternalOptions options, DexMethod method);

    public abstract boolean requiresGenerationOfCode();
  }

  private static class RetargetCoreLibraryMethodProvider extends MethodProvider {

    private final DexType newHolder;
    private DexMethod targetMethod;
    private boolean isStatic;

    RetargetCoreLibraryMethodProvider(DexType newHolder, DexMethod method, boolean isStatic) {
      super(method);
      this.newHolder = newHolder;
      this.isStatic = isStatic;
    }

    @Override
    public void rewriteInvoke(InvokeMethod invoke, InstructionListIterator iterator, IRCode code,
        AppView<?> appView) {
      iterator.replaceCurrentInstruction(
          new InvokeStatic(provideMethod(appView), invoke.outValue(), invoke.inValues()));
    }

    @Override
    public DexMethod provideMethod(AppView<?> appView) {
      if (targetMethod != null) {
        return targetMethod;
      }
      DexItemFactory factory = appView.dexItemFactory();
      DexProto newProto =
          isStatic ? method.proto : factory.prependTypeToProto(method.holder, method.proto);
      targetMethod = factory.createMethod(newHolder, newProto, method.name);
      return targetMethod;
    }

    @Override
    public Code generateTemplateMethod(InternalOptions options, DexMethod method) {
      throw new Unreachable("Does not generate any method.");
    }

    @Override
    public boolean requiresGenerationOfCode() {
      return false;
    }
  }

  private static final class InvokeRewriter extends MethodProvider {
    private final MethodInvokeRewriter rewriter;

    InvokeRewriter(DexMethod method, MethodInvokeRewriter rewriter) {
      super(method);
      this.rewriter = rewriter;
    }

    @Override public void rewriteInvoke(InvokeMethod invoke, InstructionListIterator iterator,
        IRCode code, AppView<?> appView) {
      rewriter.rewrite(invoke, iterator, appView.dexItemFactory());
      assert code.isConsistentSSA();
    }

    @Override
    public boolean requiresGenerationOfCode() {
      return false;
    }

    @Override
    public DexMethod provideMethod(AppView<?> appView) {
      throw new Unreachable();
    }

    @Override
    public Code generateTemplateMethod(InternalOptions options, DexMethod method) {
      throw new Unreachable();
    }
  }

  private static class MethodGenerator extends MethodProvider {

    private final TemplateMethodFactory factory;
    private final String methodName;
    DexMethod generatedMethod;

    MethodGenerator(DexMethod method, TemplateMethodFactory factory) {
      this(method, factory, method.name.toString());
    }

    MethodGenerator(DexMethod method, TemplateMethodFactory factory, String methodName) {
      super(method);
      this.factory = factory;
      this.methodName = methodName;
    }

    @Override
    public void rewriteInvoke(InvokeMethod invoke, InstructionListIterator iterator, IRCode code,
        AppView<?> appView) {
      iterator.replaceCurrentInstruction(
          new InvokeStatic(provideMethod(appView), invoke.outValue(), invoke.inValues()));
    }

    @Override
    public DexMethod provideMethod(AppView<?> appView) {
      if (generatedMethod != null) {
        return generatedMethod;
      }
      AppInfo appInfo = appView.appInfo();
      DexItemFactory factory = appView.dexItemFactory();
      String unqualifiedName = method.holder.getName();
      // Avoid duplicate class names between core lib dex file and program dex files.
      String coreLibUtilitySuffix =
          appView.options().isDesugaredLibraryCompilation() ? "$corelib" : "";
      String descriptor =
          UTILITY_CLASS_DESCRIPTOR_PREFIX
              + appInfo.getBucketId()
              + '$'
              + unqualifiedName
              + '$'
              + method.proto.parameters.size()
              + coreLibUtilitySuffix
              + '$'
              + methodName
              + ';';
      DexType type = factory.createType(descriptor);
      generatedMethod = factory.createMethod(type, method.proto, method.name);
      return generatedMethod;
    }

    @Override
    public Code generateTemplateMethod(InternalOptions options, DexMethod method) {
      return factory.create(options, method, methodName);
    }

    @Override
    public boolean requiresGenerationOfCode() {
      return true;
    }
  }

  // Specific subclass to transform virtual methods into static desugared methods.
  // To be correct, the method has to be on a final class, and be implemented directly
  // on the class (no overrides).
  private static class StatifyingMethodGenerator extends MethodGenerator {

    private final DexType receiverType;

    StatifyingMethodGenerator(
        DexMethod method, TemplateMethodFactory factory, String methodName, DexType receiverType) {
      super(method, factory, methodName);
      this.receiverType = receiverType;
    }

    @Override
    public DexMethod provideMethod(AppView<?> appView) {
      if (generatedMethod != null) {
        return generatedMethod;
      }
      super.provideMethod(appView);
      assert generatedMethod != null;
      DexProto newProto = appView.dexItemFactory().prependTypeToProto(receiverType, method.proto);
      generatedMethod =
          appView.dexItemFactory().createMethod(generatedMethod.holder, newProto, method.name);
      return generatedMethod;
    }
  }

  private interface TemplateMethodFactory {

    Code create(InternalOptions options, DexMethod method, String name);
  }

  private interface MethodInvokeRewriter {
    void rewrite(InvokeMethod invoke, InstructionListIterator iterator, DexItemFactory factory);
  }
}
