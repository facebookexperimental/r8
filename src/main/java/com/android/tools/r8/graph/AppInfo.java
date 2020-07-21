// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.graph.ResolutionResult.ArrayCloneMethodResult;
import com.android.tools.r8.graph.ResolutionResult.ClassNotFoundResult;
import com.android.tools.r8.graph.ResolutionResult.IncompatibleClassResult;
import com.android.tools.r8.graph.ResolutionResult.MultiResult;
import com.android.tools.r8.graph.ResolutionResult.NoSuchMethodResult;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AppInfo implements DexDefinitionSupplier {

  private final DexApplication app;
  private final DexItemFactory dexItemFactory;
  private final ConcurrentHashMap<DexType, Map<Descriptor<?,?>, KeyedDexItem<?>>> definitions =
      new ConcurrentHashMap<>();
  // For some optimizations, e.g. optimizing synthetic classes, we may need to resolve the current
  // class being optimized.
  final ConcurrentHashMap<DexType, DexProgramClass> synthesizedClasses = new ConcurrentHashMap<>();
  private final String bucketId;

  // Set when a new AppInfo replaces a previous one. All public methods should verify that the
  // current instance is not obsolete, to ensure that we almost use the most recent AppInfo.
  private boolean obsolete;

  public AppInfo(DexApplication application, String bucketId) {
    this.app = application;
    this.dexItemFactory = app.dexItemFactory;
    this.bucketId = bucketId;
  }

  public AppInfo(DexApplication application) {
      this(application, "");
  }

  protected AppInfo(AppInfo previous) {
    assert !previous.isObsolete();
    this.app = previous.app;
    this.dexItemFactory = app.dexItemFactory;
    this.bucketId = previous.bucketId;
    this.definitions.putAll(previous.definitions);
    copyMetadataFromPrevious(previous);
  }

  public void copyMetadataFromPrevious(AppInfo previous) {
    this.synthesizedClasses.putAll(previous.synthesizedClasses);
  }

  public boolean isObsolete() {
    return obsolete;
  }

  public void markObsolete() {
    obsolete = true;
  }

  public void unsetObsolete() {
    obsolete = false;
  }

  public boolean checkIfObsolete() {
    assert !isObsolete();
    return true;
  }

  public DexApplication app() {
    assert checkIfObsolete();
    return app;
  }

  @Override
  public DexItemFactory dexItemFactory() {
    assert checkIfObsolete();
    return dexItemFactory;
  }

  public void addSynthesizedClass(DexProgramClass clazz) {
    assert checkIfObsolete();
    assert clazz.type.isD8R8SynthesizedClassType();
    DexProgramClass previous = synthesizedClasses.put(clazz.type, clazz);
    assert previous == null || previous == clazz;
  }

  public Collection<DexProgramClass> getSynthesizedClassesForSanityCheck() {
    assert checkIfObsolete();
    return Collections.unmodifiableCollection(synthesizedClasses.values());
  }

  private Map<Descriptor<?,?>, KeyedDexItem<?>> computeDefinitions(DexType type) {
    Builder<Descriptor<?,?>, KeyedDexItem<?>> builder = ImmutableMap.builder();
    DexClass clazz = app.definitionFor(type);
    if (clazz != null) {
      clazz.forEachMethod(method -> builder.put(method.getKey(), method));
      clazz.forEachField(field -> builder.put(field.getKey(), field));
    }
    return builder.build();
  }

  public Iterable<DexProgramClass> classes() {
    assert checkIfObsolete();
    return app.classes();
  }

  public Iterable<DexProgramClass> classesWithDeterministicOrder() {
    assert checkIfObsolete();
    return app.classesWithDeterministicOrder();
  }

  @Override
  public DexDefinition definitionFor(DexReference reference) {
    assert checkIfObsolete();
    if (reference.isDexType()) {
      return definitionFor(reference.asDexType());
    }
    if (reference.isDexMethod()) {
      return definitionFor(reference.asDexMethod());
    }
    assert reference.isDexField();
    return definitionFor(reference.asDexField());
  }

  @Override
  public DexClass definitionFor(DexType type) {
    assert checkIfObsolete();
    DexProgramClass cached = synthesizedClasses.get(type);
    if (cached != null) {
      assert app.definitionFor(type) == null;
      return cached;
    }
    return app.definitionFor(type);
  }

  @Override
  public DexProgramClass definitionForProgramType(DexType type) {
    return app.programDefinitionFor(type);
  }

  public Origin originFor(DexType type) {
    assert checkIfObsolete();
    DexClass definition = app.definitionFor(type);
    return definition == null ? Origin.unknown() : definition.origin;
  }

  @Override
  public DexEncodedMethod definitionFor(DexMethod method) {
    assert checkIfObsolete();
    DexType holderType = method.holder;
    DexEncodedMethod cached = (DexEncodedMethod) getDefinitions(holderType).get(method);
    if (cached != null && cached.isObsolete()) {
      definitions.remove(holderType);
      cached = (DexEncodedMethod) getDefinitions(holderType).get(method);
    }
    return cached;
  }

  @Override
  public DexEncodedField definitionFor(DexField field) {
    assert checkIfObsolete();
    return (DexEncodedField) getDefinitions(field.holder).get(field);
  }

  private Map<Descriptor<?,?>, KeyedDexItem<?>> getDefinitions(DexType type) {
    Map<Descriptor<?,?>, KeyedDexItem<?>> typeDefinitions = definitions.get(type);
    if (typeDefinitions != null) {
      return typeDefinitions;
    }

    typeDefinitions = computeDefinitions(type);
    Map<Descriptor<?,?>, KeyedDexItem<?>> existing = definitions.putIfAbsent(type, typeDefinitions);
    return existing != null ? existing : typeDefinitions;
  }

  /**
   * Get unique bucket id.
   */
  public String getBucketId() {
    return bucketId;
  }

  /**
   * Lookup static method following the super chain from the holder of {@code method}.
   * <p>
   * This method will resolve the method on the holder of {@code method} and only return a non-null
   * value if the result of resolution was a static, non-abstract method.
   *
   * @param method the method to lookup
   * @return The actual target for {@code method} or {@code null} if none found.
   */
  public DexEncodedMethod lookupStaticTarget(DexMethod method) {
    assert checkIfObsolete();
    ResolutionResult resolutionResult = resolveMethod(method.holder, method);
    DexEncodedMethod target = resolutionResult.asSingleTarget();
    return target == null || target.isStatic() ? target : null;
  }

  /**
   * Lookup super method following the super chain from the holder of {@code method}.
   *
   * <p>This method will resolve the method on the holder of {@code method} and only return a
   * non-null value if the result of resolution was an instance (i.e. non-static) method.
   *
   * @param method the method to lookup
   * @param invocationContext the class the invoke is contained in, i.e., the holder of the caller.
   * @return The actual target for {@code method} or {@code null} if none found.
   */
  public DexEncodedMethod lookupSuperTarget(DexMethod method, DexType invocationContext) {
    assert checkIfObsolete();
    // Make sure we are not chasing NotFoundError.
    ResolutionResult resolutionResult = resolveMethod(method.holder, method);
    if (resolutionResult.asListOfTargets().isEmpty()) {
      return null;
    }
    // According to
    // https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.invokespecial, use
    // the "symbolic reference" if the "symbolic reference" does not name a class.
    if (definitionFor(method.holder).isInterface()) {
      return resolveMethodOnInterface(method.holder, method).asSingleTarget();
    }
    // Then, resume on the search, but this time, starting from the holder of the caller.
    DexClass contextClass = definitionFor(invocationContext);
    if (contextClass == null || contextClass.superType == null) {
      return null;
    }
    resolutionResult = resolveMethod(contextClass.superType, method);
    DexEncodedMethod target = resolutionResult.asSingleTarget();
    return target == null || !target.isStatic() ? target : null;
  }

  /**
   * Lookup direct method following the super chain from the holder of {@code method}.
   * <p>
   * This method will lookup private and constructor methods.
   *
   * @param method the method to lookup
   * @return The actual target for {@code method} or {@code null} if none found.
   */
  public DexEncodedMethod lookupDirectTarget(DexMethod method) {
    assert checkIfObsolete();
    ResolutionResult resolutionResult = resolveMethod(method.holder, method);
    DexEncodedMethod target = resolutionResult.asSingleTarget();
    return target == null || target.isDirectMethod() ? target : null;
  }

  /**
   * Lookup virtual method starting in type and following the super chain.
   * <p>
   * This method will resolve the method on the holder of {@code method} and only return a
   * non-null value if the result of resolution was a non-static, non-private method.
   */
  public DexEncodedMethod lookupVirtualTarget(DexType type, DexMethod method) {
    assert checkIfObsolete();
    assert type.isClassType() || type.isArrayType();
    ResolutionResult resolutionResult = resolveMethod(type, method);
    DexEncodedMethod target = resolutionResult.asSingleTarget();
    return target == null || target.isVirtualMethod() ? target : null;
  }

  /**
   * Implements resolution of a method descriptor against a target type.
   *
   * <p>This method will query the definition of the holder to decide on which resolution to use. If
   * the holder is an interface, it delegates to {@link #resolveMethodOnInterface(DexType,
   * DexMethod)}, otherwise {@link #resolveMethodOnClass(DexType, DexMethod)} is used.
   *
   * <p>This is to overcome the shortcoming of the DEX file format that does not allow to encode the
   * kind of a method reference.
   */
  public ResolutionResult resolveMethod(DexType holder, DexMethod method) {
    assert checkIfObsolete();
    if (holder.isArrayType()) {
      return resolveMethodOnArray(holder, method);
    }
    DexClass definition = definitionFor(holder);
    if (definition == null) {
      return ClassNotFoundResult.INSTANCE;
    }
    return resolveMethod(definition, method);
  }

  public ResolutionResult resolveMethod(DexClass holder, DexMethod method) {
    return holder.isInterface()
        ? resolveMethodOnInterface(holder, method)
        : resolveMethodOnClass(holder, method);
  }

  /**
   * Implements resolution of a method descriptor against a target type.
   *
   * <p>The boolean isInterface parameter denotes if the method reference is an interface method
   * reference, and if so method resolution is done according to interface method resolution.
   *
   * @param holder Type at which to initiate the resolution.
   * @param method Method descriptor for resolution (the field method.holder is ignored).
   * @param isInterface Indicates if resolution is to be done according to class or interface.
   * @return The result of resolution.
   */
  public ResolutionResult resolveMethod(DexType holder, DexMethod method, boolean isInterface) {
    assert checkIfObsolete();
    return isInterface
        ? resolveMethodOnInterface(holder, method)
        : resolveMethodOnClass(holder, method);
  }

  /**
   * Implements resolution of a method descriptor against an array type.
   *
   * <p>See <a href="https://docs.oracle.com/javase/specs/jls/se8/html/jls-10.html#jls-10.7">Section
   * 10.7 of the Java Language Specification</a>. All invokations will have target java.lang.Object
   * except clone which has no target.
   */
  private ResolutionResult resolveMethodOnArray(DexType holder, DexMethod method) {
    assert checkIfObsolete();
    assert holder.isArrayType();
    if (method.name == dexItemFactory.cloneMethodName) {
      return ArrayCloneMethodResult.INSTANCE;
    } else {
      return resolveMethodOnClass(dexItemFactory.objectType, method);
    }
  }

  /**
   * Implements resolution of a method descriptor against a class type.
   * <p>
   * See <a href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.3">
   * Section 5.4.3.3 of the JVM Spec</a>.
   * <p>
   * The resolved method is not the method that will actually be invoked. Which methods gets
   * invoked depends on the invoke instruction used. However, it is always safe to rewrite
   * any invoke on the given descriptor to a corresponding invoke on the resolved descriptor, as the
   * resolved method is used as basis for dispatch.
   */
  public ResolutionResult resolveMethodOnClass(DexType holder, DexMethod method) {
    assert checkIfObsolete();
    if (holder.isArrayType()) {
      return resolveMethodOnArray(holder, method);
    }
    DexClass clazz = definitionFor(holder);
    if (clazz == null) {
      return ClassNotFoundResult.INSTANCE;
    }
    // Step 1: If holder is an interface, resolution fails with an ICCE. We return null.
    if (clazz.isInterface()) {
      return IncompatibleClassResult.INSTANCE;
    }
    return resolveMethodOnClass(clazz, method);
  }

  public ResolutionResult resolveMethodOnClass(DexClass clazz, DexMethod method) {
    assert checkIfObsolete();
    assert !clazz.isInterface();
    // Step 2:
    DexEncodedMethod singleTarget = resolveMethodOnClassStep2(clazz, method);
    if (singleTarget != null) {
      return singleTarget;
    }
    // Finally Step 3:
    return resolveMethodStep3(clazz, method);
  }

  /**
   * Implements step 2 of method resolution on classes as per
   * <a href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.3">
   * Section 5.4.3.3 of the JVM Spec</a>.
   */
  private DexEncodedMethod resolveMethodOnClassStep2(DexClass clazz, DexMethod method) {
    // Pt. 1: Signature polymorphic method check. Those are only allowed on
    //        java.lang.invoke.MethodHandle, so we only need to look for it if we are looking at
    //        that type.
    // See also <a href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-2.html#jvms-2.9">
    // Section 2.9 of the JVM Spec</a>.
    if (clazz.type == dexItemFactory.methodHandleType) {
      DexMethod signaturePolymorphic = dexItemFactory.createMethod(clazz.type,
          dexItemFactory.createProto(
              dexItemFactory.objectType, dexItemFactory.objectArrayType),
          method.name);
      DexEncodedMethod result = clazz.lookupMethod(signaturePolymorphic);
      // Check we found a result and that it has the required access flags for signature polymorphic
      // functions.
      if (result != null && result.accessFlags.isNative() && result.accessFlags.isVarargs()) {
        return result;
      }
    }
    // Pt 2: Find a method that matches the descriptor.
    DexEncodedMethod result = clazz.lookupMethod(method);
    if (result != null) {
      return result;
    }
    // Pt 3: Apply step two to direct superclass of holder.
    if (clazz.superType != null) {
      DexClass superClass = definitionFor(clazz.superType);
      if (superClass != null) {
        return resolveMethodOnClassStep2(superClass, method);
      }
    }
    return null;
  }

  /**
   * Implements step 3 of
   * <a href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.3">
   * Section 5.4.3.3 of the JVM Spec</a>. As this is the same for interfaces and classes, we share
   * one implementation.
   * <p>
   * This method will return all maximally specific default methods if there is more than one. If
   * there is no default method, any of the found methods is returned.
   */
  private ResolutionResult resolveMethodStep3(DexClass clazz, DexMethod method) {
    MultiResultBuilder builder = new MultiResultBuilder();
    DexEncodedMethod anyTarget = resolveMethodStep3Helper(clazz, method, builder);
    ResolutionResult result = builder.build();
    if (result != null) {
      // We have found default methods, return them.
      return result;
    }
    // Return any of the non-default methods.
    return anyTarget == null ? NoSuchMethodResult.INSTANCE : anyTarget;
  }

  /**
   * Helper method that performs the actual search and adds all maximally specific default methods
   * to the builder. Additionally, one of the maximally specific default methods or, if none exist,
   * any of the found methods, is returned.
   */
  private DexEncodedMethod resolveMethodStep3Helper(DexClass clazz, DexMethod method,
      MultiResultBuilder builder) {
    // We are looking for the maximally-specific superinterfaces that have a
    // non-abstract method or any of the abstract methods.
    DexEncodedMethod result = null;
    for (DexType iface : clazz.interfaces.values) {
      DexClass definiton = definitionFor(iface);
      if (definiton == null) {
        // Ignore missing interface definitions.
        continue;
      }
      DexEncodedMethod localResult = definiton.lookupMethod(method);
      // Remember the result, if any, as local result.
      result = selectCandidate(result, localResult);
      if (localResult != null && localResult.isNonAbstractVirtualMethod()) {
        // We have found a default method in this class. Remember it and stop the search.
        builder.add(localResult);
      } else {
        // Look at the super-interfaces of this class and keep searching.
        localResult = resolveMethodStep3Helper(definiton, method, builder);
        result = selectCandidate(result, localResult);
      }
    }
    // Now look at indirect super interfaces.
    if (clazz.superType != null) {
      DexClass superClass = definitionFor(clazz.superType);
      if (superClass != null) {
        DexEncodedMethod superResult = resolveMethodStep3Helper(superClass, method, builder);
        result = selectCandidate(result, superResult);
      }
    }
    return result;
  }

  /**
   * Implements resolution of a method descriptor against an interface type.
   * <p>
   * See <a href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.3">
   * Section 5.4.3.4 of the JVM Spec</a>.
   * <p>
   * The resolved method is not the method that will actually be invoked. Which methods gets
   * invoked depends on the invoke instruction used. However, it is always save to rewrite
   * any invoke on the given descriptor to a corresponding invoke on the resolved descriptor, as the
   * resolved method is used as basis for dispatch.
   */
  public ResolutionResult resolveMethodOnInterface(DexType holder, DexMethod desc) {
    assert checkIfObsolete();
    if (holder.isArrayType()) {
      return IncompatibleClassResult.INSTANCE;
    }
    // Step 1: Lookup interface.
    DexClass definition = definitionFor(holder);
    // If the definition is not an interface, resolution fails with an ICCE. We just return the
    // empty result here.
    if (definition == null) {
      return ClassNotFoundResult.INSTANCE;
    }
    if (!definition.isInterface()) {
      return IncompatibleClassResult.INSTANCE;
    }
    return resolveMethodOnInterface(definition, desc);
  }

  public ResolutionResult resolveMethodOnInterface(DexClass definition, DexMethod desc) {
    assert checkIfObsolete();
    assert definition.isInterface();
    // Step 2: Look for exact method on interface.
    DexEncodedMethod result = definition.lookupMethod(desc);
    if (result != null) {
      return result;
    }
    // Step 3: Look for matching method on object class.
    DexClass objectClass = definitionFor(dexItemFactory.objectType);
    if (objectClass == null) {
      return ClassNotFoundResult.INSTANCE;
    }
    result = objectClass.lookupMethod(desc);
    if (result != null && result.accessFlags.isPublic() && !result.accessFlags.isAbstract()) {
      return result;
    }
    // Step 3: Look for maximally-specific superinterface methods or any interface definition.
    //         This is the same for classes and interfaces.
    return resolveMethodStep3(definition, desc);
  }

  /**
   * Lookup instance field starting in type and following the interface and super chain.
   * <p>
   * The result is the field that will be hit at runtime, if such field is known. A result
   * of null indicates that the field is either undefined or not an instance field.
   */
  public DexEncodedField lookupInstanceTarget(DexType type, DexField field) {
    assert checkIfObsolete();
    assert type.isClassType();
    DexEncodedField result = resolveFieldOn(type, field);
    return result == null || result.accessFlags.isStatic() ? null : result;
  }

  /**
   * Lookup static field starting in type and following the interface and super chain.
   * <p>
   * The result is the field that will be hit at runtime, if such field is known. A result
   * of null indicates that the field is either undefined or not a static field.
   */
  public DexEncodedField lookupStaticTarget(DexType type, DexField field) {
    assert checkIfObsolete();
    assert type.isClassType();
    DexEncodedField result = resolveFieldOn(type, field);
    return result == null || !result.accessFlags.isStatic() ? null : result;
  }

  /**
   * Implements resolution of a field descriptor against the holder of the field. See also {@link
   * #resolveFieldOn}.
   */
  public DexEncodedField resolveField(DexField field) {
    assert checkIfObsolete();
    return resolveFieldOn(field.holder, field);
  }

  /**
   * Implements resolution of a field descriptor against a type.
   * <p>
   * See <a href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.2">
   * Section 5.4.3.2 of the JVM Spec</a>.
   */
  public DexEncodedField resolveFieldOn(DexType type, DexField desc) {
    assert checkIfObsolete();
    DexClass holder = definitionFor(type);
    if (holder == null) {
      return null;
    }
    // Step 1: Class declares the field.
    DexEncodedField result = holder.lookupField(desc);
    if (result != null) {
      return result;
    }
    // Step 2: Apply recursively to direct superinterfaces. First match succeeds.
    for (DexType iface : holder.interfaces.values) {
      result = resolveFieldOn(iface, desc);
      if (result != null) {
        return result;
      }
    }
    // Step 3: Apply recursively to superclass.
    if (holder.superType != null) {
      result = resolveFieldOn(holder.superType, desc);
      if (result != null) {
        return result;
      }
    }
    return null;
  }

  /**
   * Implements the dispatch logic for a static invoke operation.
   * <p>
   * The only requirement is that the method is indeed static.
   */
  public DexEncodedMethod dispatchStaticInvoke(ResolutionResult resolvedMethod) {
    assert checkIfObsolete();
    DexEncodedMethod target = resolvedMethod.asSingleTarget();
    if (target != null && target.accessFlags.isStatic()) {
      return target;
    }
    return null;
  }

  /**
   * Implements the dispatch logic for the direct parts of a invokespecial instruction.
   * <p>
   * The only requirement is that the method is not static.
   */
  public DexEncodedMethod dispatchDirectInvoke(ResolutionResult resolvedMethod) {
    assert checkIfObsolete();
    DexEncodedMethod target = resolvedMethod.asSingleTarget();
    if (target != null && !target.accessFlags.isStatic()) {
      return target;
    }
    return null;
  }

  /**
   * If previous is non-null, selects previous. If current is non-null and a non-private,
   * non-static method, current is selected. Otherwise null is returned.
   */
  private DexEncodedMethod selectCandidate(DexEncodedMethod previous, DexEncodedMethod current) {
    if (previous != null) {
      assert !previous.accessFlags.isPrivate();
      assert !previous.accessFlags.isStatic();
      return previous;
    }
    if (current != null && !current.accessFlags.isPrivate() && !current.accessFlags.isStatic()) {
      return current;
    }
    return null;
  }

  public boolean hasSubtyping() {
    assert checkIfObsolete();
    return false;
  }

  public AppInfoWithSubtyping withSubtyping() {
    assert checkIfObsolete();
    return null;
  }

  public boolean hasLiveness() {
    assert checkIfObsolete();
    return false;
  }

  public AppInfoWithLiveness withLiveness() {
    assert checkIfObsolete();
    return null;
  }

  public void registerNewType(DexType newType, DexType superType) {
    // We do not track subtyping relationships in the basic AppInfo. So do nothing.
    assert checkIfObsolete();
  }

  public boolean isInMainDexList(DexType type) {
    assert checkIfObsolete();
    return app.mainDexList.contains(type);
  }

  private static class MultiResultBuilder {

    private ImmutableSet.Builder<DexEncodedMethod> builder;
    private DexEncodedMethod singleResult;

    void add(DexEncodedMethod result) {
      if (builder != null) {
        builder.add(result);
      } else if (singleResult != null && !singleResult.equals(result)) {
        builder = ImmutableSet.builder();
        builder.add(singleResult, result);
        singleResult = null;
      } else {
        singleResult = result;
      }
    }

    ResolutionResult build() {
      if (builder != null) {
        return new MultiResult(builder.build().asList());
      } else {
        return singleResult;
      }
    }
  }

}
