// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import static com.android.tools.r8.graph.FieldAccessInfoImpl.MISSING_FIELD_ACCESS_INFO;
import static com.android.tools.r8.naming.IdentifierNameStringUtils.identifyIdentifier;
import static com.android.tools.r8.naming.IdentifierNameStringUtils.isReflectionMethod;
import static com.android.tools.r8.shaking.AnnotationRemover.shouldKeepAnnotation;
import static com.android.tools.r8.shaking.EnqueuerUtils.toImmutableSortedMap;
import static com.google.common.base.Predicates.not;

import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.errors.Unimplemented;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.experimental.graphinfo.AnnotationGraphNode;
import com.android.tools.r8.experimental.graphinfo.ClassGraphNode;
import com.android.tools.r8.experimental.graphinfo.FieldGraphNode;
import com.android.tools.r8.experimental.graphinfo.GraphConsumer;
import com.android.tools.r8.experimental.graphinfo.GraphEdgeInfo;
import com.android.tools.r8.experimental.graphinfo.GraphEdgeInfo.EdgeKind;
import com.android.tools.r8.experimental.graphinfo.GraphNode;
import com.android.tools.r8.experimental.graphinfo.KeepRuleGraphNode;
import com.android.tools.r8.experimental.graphinfo.MethodGraphNode;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.Descriptor;
import com.android.tools.r8.graph.DexAnnotation;
import com.android.tools.r8.graph.DexCallSite;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexDefinition;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItem;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexLibraryClass;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexMethodHandle;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexReference;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.FieldAccessInfoCollectionImpl;
import com.android.tools.r8.graph.FieldAccessInfoImpl;
import com.android.tools.r8.graph.KeyedDexItem;
import com.android.tools.r8.graph.PresortedComparable;
import com.android.tools.r8.graph.ResolutionResult;
import com.android.tools.r8.graph.analysis.EnqueuerAnalysis;
import com.android.tools.r8.ir.analysis.proto.schema.ProtoEnqueuerExtension;
import com.android.tools.r8.ir.code.ArrayPut;
import com.android.tools.r8.ir.code.ConstantValueUtils;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionIterator;
import com.android.tools.r8.ir.code.InvokeMethod;
import com.android.tools.r8.ir.code.InvokeVirtual;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.desugar.LambdaDescriptor;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.references.Reference;
import com.android.tools.r8.references.TypeReference;
import com.android.tools.r8.shaking.EnqueuerWorklist.Action;
import com.android.tools.r8.shaking.RootSetBuilder.ConsequentRootSet;
import com.android.tools.r8.shaking.RootSetBuilder.RootSet;
import com.android.tools.r8.shaking.ScopedDexMethodSet.AddMethodIfMoreVisibleResult;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.SetUtils;
import com.android.tools.r8.utils.StringDiagnostic;
import com.android.tools.r8.utils.Timing;
import com.google.common.base.Equivalence.Wrapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import it.unimi.dsi.fastutil.objects.Object2BooleanArrayMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
import java.lang.reflect.InvocationHandler;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Approximates the runtime dependencies for the given set of roots.
 * <p>
 * <p>The implementation filters the static call-graph with liveness information on classes to
 * remove virtual methods that are reachable by their static type but are unreachable at runtime as
 * they are not visible from any instance.
 * <p>
 * <p>As result of the analysis, an instance of {@link AppInfoWithLiveness} is returned. See the
 * field descriptions for details.
 */
public class Enqueuer {

  public enum Mode {
    INITIAL_TREE_SHAKING,
    FINAL_TREE_SHAKING,
    MAIN_DEX_TRACING,
    WHY_ARE_YOU_KEEPING;

    public boolean isInitialTreeShaking() {
      return this == INITIAL_TREE_SHAKING;
    }

    public boolean isFinalTreeShaking() {
      return this == FINAL_TREE_SHAKING;
    }

    public boolean isInitialOrFinalTreeShaking() {
      return isInitialTreeShaking() || isFinalTreeShaking();
    }

    public boolean isTracingMainDex() {
      return this == MAIN_DEX_TRACING;
    }
  }

  private final boolean forceProguardCompatibility;
  private final Mode mode;

  private Set<EnqueuerAnalysis> analyses = Sets.newIdentityHashSet();
  private final AppInfoWithSubtyping appInfo;
  private final AppView<? extends AppInfoWithSubtyping> appView;
  private final InternalOptions options;
  private RootSet rootSet;
  private ProguardClassFilter dontWarnPatterns;

  private final Map<DexMethod, Set<DexEncodedMethod>> virtualInvokes = new IdentityHashMap<>();
  private final Map<DexMethod, Set<DexEncodedMethod>> interfaceInvokes = new IdentityHashMap<>();
  private final Map<DexMethod, Set<DexEncodedMethod>> superInvokes = new IdentityHashMap<>();
  private final Map<DexMethod, Set<DexEncodedMethod>> directInvokes = new IdentityHashMap<>();
  private final Map<DexMethod, Set<DexEncodedMethod>> staticInvokes = new IdentityHashMap<>();
  private final FieldAccessInfoCollectionImpl fieldAccessInfoCollection =
      new FieldAccessInfoCollectionImpl();
  private final Set<DexField> instanceFieldsWrittenOutsideEnclosingInstanceInitializers =
      Sets.newIdentityHashSet();
  private final Set<DexField> staticFieldsWrittenOutsideEnclosingStaticInitializer =
      Sets.newIdentityHashSet();
  private final Set<DexCallSite> callSites = Sets.newIdentityHashSet();

  private final Set<DexReference> identifierNameStrings = Sets.newIdentityHashSet();

  // Canonicalization of external graph-nodes and edge info.
  private final Map<DexItem, AnnotationGraphNode> annotationNodes = new IdentityHashMap<>();
  private final Map<DexType, ClassGraphNode> classNodes = new IdentityHashMap<>();
  private final Map<DexMethod, MethodGraphNode> methodNodes = new IdentityHashMap<>();
  private final Map<DexField, FieldGraphNode> fieldNodes = new IdentityHashMap<>();
  private final Map<ProguardKeepRuleBase, KeepRuleGraphNode> ruleNodes = new IdentityHashMap<>();
  private final Map<EdgeKind, GraphEdgeInfo> reasonInfo = new IdentityHashMap<>();

  /**
   * Set of method signatures used in invoke-super instructions that either cannot be resolved or
   * resolve to a private method (leading to an IllegalAccessError).
   */
  private final Set<DexMethod> brokenSuperInvokes = Sets.newIdentityHashSet();
  /**
   * This map keeps a view of all virtual methods that are reachable from virtual invokes. A method
   * is reachable even if no live subtypes exist, so this is not sufficient for inclusion in the
   * live set.
   */
  private final Map<DexProgramClass, SetWithStoredReason<DexEncodedMethod>>
      reachableVirtualMethods = Maps.newIdentityHashMap();

  /**
   * Tracks the dependency between a method and the super-method it calls, if any. Used to make
   * super methods become live when they become reachable from a live sub-method.
   */
  private final Map<DexEncodedMethod, Set<DexEncodedMethod>> superInvokeDependencies = Maps
      .newIdentityHashMap();
  /** Set of instance fields that can be reached by read/write operations. */
  private final Map<DexProgramClass, SetWithReason<DexEncodedField>> reachableInstanceFields =
      Maps.newIdentityHashMap();

  /**
   * Set of types that are mentioned in the program. We at least need an empty abstract class item
   * for these.
   */
  private final SetWithReason<DexProgramClass> liveTypes = new SetWithReason<>(this::registerClass);

  /** Set of live types defined in the library and classpath. Used to avoid duplicate tracing. */
  private final Set<DexClass> liveNonProgramTypes = Sets.newIdentityHashSet();

  /**
   * Set of proto extension types that are technically live, but which we have not traced because
   * they are dead according to the generated extension registry shrinker.
   *
   * <p>Only used if {@link InternalOptions#enableGeneratedExtensionRegistryShrinking} is set.
   */
  private final Set<DexType> skippedProtoExtensionTypes = Sets.newIdentityHashSet();

  /** Set of annotation types that are instantiated. */
  private final SetWithReason<DexAnnotation> liveAnnotations =
      new SetWithReason<>(this::registerAnnotation);

  /** Set of types that are actually instantiated. These cannot be abstract. */
  private final SetWithReason<DexProgramClass> instantiatedTypes =
      new SetWithReason<>(this::registerClass);

  /** Set of all types that are instantiated, directly or indirectly, thus may be abstract. */
  private final Set<DexProgramClass> directAndIndirectlyInstantiatedTypes =
      Sets.newIdentityHashSet();

  /**
   * Set of methods that are the immediate target of an invoke. They might not actually be live but
   * are required so that invokes can find the method. If a method is only a target but not live,
   * its implementation may be removed and it may be marked abstract.
   */
  private final SetWithReason<DexEncodedMethod> targetedMethods =
      new SetWithReason<>(this::registerMethod);
  /**
   * Set of program methods that are used as the bootstrap method for an invoke-dynamic instruction.
   */
  private final Set<DexMethod> bootstrapMethods = Sets.newIdentityHashSet();
  /**
   * Set of direct methods that are the immediate target of an invoke-dynamic.
   */
  private final Set<DexMethod> methodsTargetedByInvokeDynamic = Sets.newIdentityHashSet();
  /**
   * Set of direct lambda methods that are the immediate target of an invoke-dynamic.
   */
  private final Set<DexMethod> lambdaMethodsTargetedByInvokeDynamic = Sets.newIdentityHashSet();
  /**
   * Set of virtual methods that are the immediate target of an invoke-direct.
   * */
  private final Set<DexMethod> virtualMethodsTargetedByInvokeDirect = Sets.newIdentityHashSet();
  /**
   * Set of methods that belong to live classes and can be reached by invokes. These need to be
   * kept.
   */
  private final SetWithReason<DexEncodedMethod> liveMethods =
      new SetWithReason<>(this::registerMethod);

  /**
   * Set of fields that belong to live classes and can be reached by invokes. These need to be kept.
   */
  private final SetWithReason<DexEncodedField> liveFields =
      new SetWithReason<>(this::registerField);

  /**
   * Set of service types (from META-INF/services/) that may have been instantiated reflectively via
   * ServiceLoader.load() or ServiceLoader.loadInstalled().
   */
  private final Set<DexType> instantiatedAppServices = Sets.newIdentityHashSet();

  /**
   * Set of interface types for which there may be instantiations, such as lambda expressions or
   * explicit keep rules.
   */
  private final SetWithReason<DexProgramClass> instantiatedInterfaceTypes =
      new SetWithReason<>(this::registerInterface);

  /** A queue of items that need processing. Different items trigger different actions. */
  private final EnqueuerWorklist workList;

  /** A queue of items that have been added to try to keep Proguard compatibility. */
  private final EnqueuerWorklist proguardCompatibilityWorkList;

  /**
   * A set of methods that need code inspection for Java reflection in use.
   */
  private final Set<DexEncodedMethod> pendingReflectiveUses = Sets.newLinkedHashSet();

  /**
   * A cache for DexMethod that have been marked reachable.
   */
  private final Set<DexMethod> virtualTargetsMarkedAsReachable = Sets.newIdentityHashSet();

  /**
   * A set of references we have reported missing to dedupe warnings.
   */
  private final Set<DexReference> reportedMissing = Sets.newIdentityHashSet();

  /**
   * A set of references that we are keeping due to keep rules. This may differ from the root set
   * due to dependent keep rules.
   */
  private final Set<DexReference> pinnedItems = Sets.newIdentityHashSet();

  /**
   * A set of seen const-class references that both serve as an initial lock-candidate set and will
   * prevent statically merging the classes referenced.
   */
  private final Set<DexType> constClassReferences = Sets.newIdentityHashSet();

  /**
   * A map from classes to annotations that need to be processed should the classes ever become
   * live.
   */
  private final Map<DexType, Set<DexAnnotation>> deferredAnnotations = new IdentityHashMap<>();

  /**
   * Set of keep rules generated for Proguard compatibility in Proguard compatibility mode.
   */
  private final ProguardConfiguration.Builder compatibility;

  /** Map of active if rules to speed up aapt2 generated keep rules. */
  private Map<Wrapper<ProguardIfRule>, Set<ProguardIfRule>> activeIfRules;

  /**
   * A cache of ScopedDexMethodSet for each live type used for determining that virtual methods that
   * cannot be removed because they are widening access for another virtual method defined earlier
   * in the type hierarchy. See b/136698023 for more information.
   */
  private final Map<DexType, ScopedDexMethodSet> scopedMethodsForLiveTypes =
      new IdentityHashMap<>();

  private final GraphReporter graphReporter;
  private final GraphConsumer keptGraphConsumer;

  Enqueuer(
      AppView<? extends AppInfoWithSubtyping> appView,
      GraphConsumer keptGraphConsumer,
      ProguardConfiguration.Builder compatibility,
      Mode mode) {
    assert appView.appServices() != null;
    InternalOptions options = appView.options();
    this.appInfo = appView.appInfo();
    this.appView = appView;
    this.compatibility = compatibility;
    this.forceProguardCompatibility = options.forceProguardCompatibility;
    this.graphReporter = new GraphReporter();
    this.keptGraphConsumer = keptGraphConsumer;
    this.mode = mode;
    this.options = options;
    this.workList = EnqueuerWorklist.createWorklist(appView);
    this.proguardCompatibilityWorkList =
        EnqueuerWorklist.createProguardCompatibilityWorklist(appView);

    if (options.enableGeneratedMessageLiteShrinking && mode.isInitialOrFinalTreeShaking()) {
      registerAnalysis(new ProtoEnqueuerExtension(appView));
    }
  }

  public Mode getMode() {
    return mode;
  }

  public Enqueuer registerAnalysis(EnqueuerAnalysis analysis) {
    this.analyses.add(analysis);
    return this;
  }

  private boolean isProgramClass(DexType type) {
    return getProgramClassOrNull(type) != null;
  }

  private DexProgramClass getProgramClassOrNull(DexType type) {
    DexClass clazz = appView.definitionFor(type);
    if (clazz != null) {
      if (clazz.isProgramClass()) {
        return clazz.asProgramClass();
      }
      if (liveNonProgramTypes.add(clazz) && clazz.isLibraryClass()) {
        ensureMethodsContinueToWidenAccess(clazz);
        warnIfLibraryTypeInheritsFromProgramType(clazz.asLibraryClass());
      }
    }
    reportMissingClass(type);
    return null;
  }

  private void warnIfLibraryTypeInheritsFromProgramType(DexLibraryClass clazz) {
    if (clazz.superType != null) {
      ensureFromLibraryOrThrow(clazz.superType, clazz);
    }
    for (DexType iface : clazz.interfaces.values) {
      ensureFromLibraryOrThrow(iface, clazz);
    }
  }

  private Set<DexField> instanceFieldsWrittenOnlyInEnclosingInstanceInitializers() {
    Set<DexField> result = getNonPinnedWrittenFields(not(DexEncodedField::isStatic));
    result.removeAll(instanceFieldsWrittenOutsideEnclosingInstanceInitializers);
    return result;
  }

  private Set<DexField> staticFieldsWrittenOnlyInEnclosingStaticInitializer() {
    Set<DexField> result = getNonPinnedWrittenFields(DexEncodedField::isStatic);
    result.removeAll(staticFieldsWrittenOutsideEnclosingStaticInitializer);
    return result;
  }

  private Set<DexField> getNonPinnedWrittenFields(Predicate<DexEncodedField> predicate) {
    Set<DexField> result = Sets.newIdentityHashSet();
    fieldAccessInfoCollection.forEach(
        info -> {
          if (info == MISSING_FIELD_ACCESS_INFO) {
            return;
          }
          // Note that it is safe to use definitionFor() here, and not lookupField(), since the
          // field held by `info` is a direct reference to the definition of the field.
          DexEncodedField encodedField = appView.definitionFor(info.getField());
          if (encodedField == null) {
            assert false;
            return;
          }
          if (encodedField.isProgramField(appInfo)
              && info.isWritten()
              && predicate.test(encodedField)) {
            result.add(encodedField.field);
          }
        });
    result.removeAll(
        pinnedItems.stream()
            .filter(DexReference::isDexField)
            .map(DexReference::asDexField)
            .collect(Collectors.toSet()));
    return result;
  }

  private static <T> SetWithReason<T> newSetWithoutReasonReporter() {
    return new SetWithReason<>((f, r) -> {});
  }

  private void enqueueRootItems(Map<DexReference, Set<ProguardKeepRuleBase>> items) {
    items.entrySet().forEach(this::enqueueRootItem);
  }

  private void enqueueRootItem(Entry<DexReference, Set<ProguardKeepRuleBase>> root) {
    DexDefinition item = appView.definitionFor(root.getKey());
    if (item != null) {
      enqueueRootItem(item, root.getValue());
    } else {
      // TODO(b/123923324): Verify that root items are present.
      // assert false : "Expected root item `" + root.getKey().toSourceString() + "` to be present";
    }
  }

  private void enqueueRootItem(DexDefinition item, Set<ProguardKeepRuleBase> rules) {
    internalEnqueueRootItem(item, rules, null);
  }

  private void internalEnqueueRootItem(
      DexDefinition item, Set<ProguardKeepRuleBase> rules, DexDefinition precondition) {
    if (item.isDexClass()) {
      DexProgramClass clazz = item.asDexClass().asProgramClass();
      KeepReasonWitness witness = graphReporter.reportKeepClass(precondition, rules, clazz);
      if (clazz.isInterface() && !clazz.accessFlags.isAnnotation()) {
        markInterfaceAsInstantiated(clazz, witness);
      } else {
        workList.enqueueMarkInstantiatedAction(clazz, witness);
        if (clazz.hasDefaultInitializer()) {
          if (forceProguardCompatibility) {
            ProguardKeepRule compatRule =
                ProguardConfigurationUtils.buildDefaultInitializerKeepRule(clazz);
            proguardCompatibilityWorkList.enqueueMarkMethodKeptAction(
                clazz.getDefaultInitializer(),
                KeepReason.dueToProguardCompatibilityKeepRule(compatRule));
          }
          if (clazz.isExternalizable(appView)) {
            enqueueMarkMethodLiveAction(clazz, clazz.getDefaultInitializer(), witness);
          }
        }
      }
    } else if (item.isDexEncodedField()) {
      KeepReasonWitness witness =
          graphReporter.reportKeepField(precondition, rules, item.asDexEncodedField());
      workList.enqueueMarkFieldKeptAction(item.asDexEncodedField(), witness);
    } else if (item.isDexEncodedMethod()) {
      KeepReasonWitness witness =
          graphReporter.reportKeepMethod(precondition, rules, item.asDexEncodedMethod());
      workList.enqueueMarkMethodKeptAction(item.asDexEncodedMethod(), witness);
    } else {
      throw new IllegalArgumentException(item.toString());
    }
    pinnedItems.add(item.toReference());
  }

  private void markInterfaceAsInstantiated(DexProgramClass clazz, KeepReason reason) {
    assert clazz.isInterface() && !clazz.accessFlags.isAnnotation();

    if (!instantiatedInterfaceTypes.add(clazz, reason)) {
      return;
    }
    populateInstantiatedTypesCache(clazz);
    markTypeAsLive(clazz, reason);
  }

  private void enqueueFirstNonSerializableClassInitializer(
      DexProgramClass clazz, KeepReason reason) {
    assert clazz.isSerializable(appView);
    // Climb up the class hierarchy. Break out if the definition is not found, or hit the library
    // classes which are kept by definition, or encounter the first non-serializable class.
    while (clazz.isSerializable(appView)) {
      DexProgramClass superClass = getProgramClassOrNull(clazz.superType);
      if (superClass == null) {
        return;
      }
      clazz = superClass;
    }
    if (clazz.hasDefaultInitializer()) {
      enqueueMarkMethodLiveAction(clazz, clazz.getDefaultInitializer(), reason);
    }
  }

  // Utility to avoid adding to the worklist if already live.
  private boolean enqueueMarkMethodLiveAction(
      DexProgramClass clazz, DexEncodedMethod method, KeepReason reason) {
    assert method.method.holder == clazz.type;
    if (liveMethods.add(method, reason)) {
      workList.enqueueMarkMethodLiveAction(clazz, method, reason);
      return true;
    }
    return false;
  }

  private void compatEnqueueHolderIfDependentNonStaticMember(
      DexClass holder, Set<ProguardKeepRuleBase> compatRules) {
    if (!forceProguardCompatibility || compatRules == null) {
      return;
    }
    enqueueRootItem(holder, compatRules);
  }

  //
  // Things to do with registering events. This is essentially the interface for byte-code
  // traversals.
  //

  private boolean registerMethodWithTargetAndContext(
      Map<DexMethod, Set<DexEncodedMethod>> seen, DexMethod method, DexEncodedMethod context) {
    DexType baseHolder = method.holder.toBaseType(appView.dexItemFactory());
    if (baseHolder.isClassType()) {
      markTypeAsLive(baseHolder, clazz -> graphReporter.reportClassReferencedFrom(clazz, context));
      return seen.computeIfAbsent(method, ignore -> Sets.newIdentityHashSet()).add(context);
    }
    return false;
  }

  public boolean registerFieldRead(DexField field, DexEncodedMethod context) {
    return registerFieldAccess(field, context, true);
  }

  public boolean registerFieldWrite(DexField field, DexEncodedMethod context) {
    return registerFieldAccess(field, context, false);
  }

  public boolean registerFieldAccess(DexField field, DexEncodedMethod context) {
    boolean changed = registerFieldAccess(field, context, true);
    changed |= registerFieldAccess(field, context, false);
    return changed;
  }

  private boolean registerFieldAccess(DexField field, DexEncodedMethod context, boolean isRead) {
    FieldAccessInfoImpl info = fieldAccessInfoCollection.get(field);
    if (info == null) {
      DexEncodedField encodedField = appInfo.resolveField(field);

      // If the field does not exist, then record this in the mapping, such that we don't have to
      // resolve the field the next time.
      if (encodedField == null) {
        fieldAccessInfoCollection.extend(field, MISSING_FIELD_ACCESS_INFO);
        return true;
      }

      // Check if we have previously created a FieldAccessInfo object for the field definition.
      info = fieldAccessInfoCollection.get(encodedField.field);

      // If not, we must create one.
      if (info == null) {
        info = new FieldAccessInfoImpl(encodedField.field);
        fieldAccessInfoCollection.extend(encodedField.field, info);
      }

      // If `field` is an indirect reference, then create a mapping for it, such that we don't have
      // to resolve the field the next time we see the reference.
      if (field != encodedField.field) {
        fieldAccessInfoCollection.extend(field, info);
      }
    } else if (info == MISSING_FIELD_ACCESS_INFO) {
      return false;
    }
    return isRead ? info.recordRead(field, context) : info.recordWrite(field, context);
  }

  private class UseRegistry extends com.android.tools.r8.graph.UseRegistry {

    private final DexEncodedMethod currentMethod;

    private UseRegistry(DexItemFactory factory, DexEncodedMethod currentMethod) {
      super(factory);
      this.currentMethod = currentMethod;
    }

    @Override
    public boolean registerInvokeVirtual(DexMethod method) {
      return registerInvokeVirtual(method, KeepReason.invokedFrom(currentMethod));
    }

    boolean registerInvokeVirtual(DexMethod method, KeepReason keepReason) {
      if (method == appView.dexItemFactory().classMethods.newInstance
          || method == appView.dexItemFactory().constructorMethods.newInstance) {
        pendingReflectiveUses.add(currentMethod);
      } else if (appView.dexItemFactory().classMethods.isReflectiveMemberLookup(method)) {
        // Implicitly add -identifiernamestring rule for the Java reflection in use.
        identifierNameStrings.add(method);
        // Revisit the current method to implicitly add -keep rule for items with reflective access.
        pendingReflectiveUses.add(currentMethod);
      }
      if (!registerMethodWithTargetAndContext(virtualInvokes, method, currentMethod)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register invokeVirtual `%s`.", method);
      }
      workList.enqueueMarkReachableVirtualAction(method, keepReason);
      return true;
    }

    @Override
    public boolean registerInvokeDirect(DexMethod method) {
      return registerInvokeDirect(method, KeepReason.invokedFrom(currentMethod));
    }

    boolean registerInvokeDirect(DexMethod method, KeepReason keepReason) {
      if (!registerMethodWithTargetAndContext(directInvokes, method, currentMethod)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register invokeDirect `%s`.", method);
      }
      handleInvokeOfDirectTarget(method, keepReason);
      return true;
    }

    @Override
    public boolean registerInvokeStatic(DexMethod method) {
      return registerInvokeStatic(method, KeepReason.invokedFrom(currentMethod));
    }

    boolean registerInvokeStatic(DexMethod method, KeepReason keepReason) {
      DexItemFactory dexItemFactory = appView.dexItemFactory();
      if (method == dexItemFactory.classMethods.forName
          || dexItemFactory.atomicFieldUpdaterMethods.isFieldUpdater(method)) {
        // Implicitly add -identifiernamestring rule for the Java reflection in use.
        identifierNameStrings.add(method);
        // Revisit the current method to implicitly add -keep rule for items with reflective access.
        pendingReflectiveUses.add(currentMethod);
      }
      // See comment in handleJavaLangEnumValueOf.
      if (method == dexItemFactory.enumMethods.valueOf) {
        pendingReflectiveUses.add(currentMethod);
      }
      // Handling of application services.
      if (dexItemFactory.serviceLoaderMethods.isLoadMethod(method)) {
        pendingReflectiveUses.add(currentMethod);
      }
      if (method == dexItemFactory.proxyMethods.newProxyInstance) {
        pendingReflectiveUses.add(currentMethod);
      }
      if (!registerMethodWithTargetAndContext(staticInvokes, method, currentMethod)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register invokeStatic `%s`.", method);
      }
      handleInvokeOfStaticTarget(method, keepReason);
      return true;
    }

    @Override
    public boolean registerInvokeInterface(DexMethod method) {
      return registerInvokeInterface(method, KeepReason.invokedFrom(currentMethod));
    }

    boolean registerInvokeInterface(DexMethod method, KeepReason keepReason) {
      if (!registerMethodWithTargetAndContext(interfaceInvokes, method, currentMethod)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register invokeInterface `%s`.", method);
      }
      workList.enqueueMarkReachableInterfaceAction(method, keepReason);
      return true;
    }

    @Override
    public boolean registerInvokeSuper(DexMethod method) {
      // We have to revisit super invokes based on the context they are found in. The same
      // method descriptor will hit different targets, depending on the context it is used in.
      DexMethod actualTarget = getInvokeSuperTarget(method, currentMethod);
      if (!registerMethodWithTargetAndContext(superInvokes, method, currentMethod)) {
        return false;
      }
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register invokeSuper `%s`.", actualTarget);
      }
      workList.enqueueMarkReachableSuperAction(method, currentMethod);
      return true;
    }

    @Override
    public boolean registerInstanceFieldWrite(DexField field) {
      if (!registerFieldWrite(field, currentMethod)) {
        return false;
      }

      // Must mark the field as targeted even if it does not exist.
      markFieldAsTargeted(field, currentMethod);

      DexEncodedField encodedField = appInfo.resolveField(field);
      if (encodedField == null) {
        reportMissingField(field);
        return false;
      }

      DexProgramClass clazz = getProgramClassOrNull(encodedField.field.holder);
      if (clazz == null) {
        return false;
      }

      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register Iput `%s`.", field);
      }

      // If it is written outside of the <init>s of its enclosing class, record it.
      boolean isWrittenOutsideEnclosingInstanceInitializers =
          currentMethod.method.holder != encodedField.field.holder
              || !currentMethod.isInstanceInitializer();
      if (isWrittenOutsideEnclosingInstanceInitializers) {
        instanceFieldsWrittenOutsideEnclosingInstanceInitializers.add(encodedField.field);
      }

      // If unused interface removal is enabled, then we won't necessarily mark the actual holder of
      // the field as live, if the holder is an interface.
      if (appView.options().enableUnusedInterfaceRemoval) {
        if (encodedField.field != field) {
          markTypeAsLive(clazz, graphReporter.reportClassReferencedFrom(clazz, currentMethod));
          markTypeAsLive(
              encodedField.field.type,
              type -> graphReporter.reportClassReferencedFrom(type, currentMethod));
        }
      }

      KeepReason reason = KeepReason.fieldReferencedIn(currentMethod);
      workList.enqueueMarkReachableFieldAction(clazz, encodedField, reason);
      return true;
    }

    @Override
    public boolean registerInstanceFieldRead(DexField field) {
      if (!registerFieldRead(field, currentMethod)) {
        return false;
      }

      // Must mark the field as targeted even if it does not exist.
      markFieldAsTargeted(field, currentMethod);

      DexEncodedField encodedField = appInfo.resolveField(field);
      if (encodedField == null) {
        reportMissingField(field);
        return false;
      }

      DexProgramClass clazz = getProgramClassOrNull(encodedField.field.holder);
      if (clazz == null) {
        return false;
      }

      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register Iget `%s`.", field);
      }

      // If unused interface removal is enabled, then we won't necessarily mark the actual holder of
      // the field as live, if the holder is an interface.
      if (appView.options().enableUnusedInterfaceRemoval) {
        if (encodedField.field != field) {
          markTypeAsLive(clazz, graphReporter.reportClassReferencedFrom(clazz, currentMethod));
          markTypeAsLive(
              encodedField.field.type,
              type -> graphReporter.reportClassReferencedFrom(type, currentMethod));
        }
      }

      workList.enqueueMarkReachableFieldAction(
          clazz, encodedField, KeepReason.fieldReferencedIn(currentMethod));
      return true;
    }

    @Override
    public boolean registerNewInstance(DexType type) {
      return registerNewInstance(type, KeepReason.instantiatedIn(currentMethod));
    }

    public boolean registerNewInstance(DexType type, KeepReason keepReason) {
      DexProgramClass clazz = getProgramClassOrNull(type);
      if (clazz != null) {
        if (clazz.isInterface()) {
          markTypeAsLive(clazz, keepReason);
        } else {
          markInstantiated(clazz, keepReason);
        }
      }
      return true;
    }

    @Override
    public boolean registerStaticFieldRead(DexField field) {
      if (!registerFieldRead(field, currentMethod)) {
        return false;
      }

      DexEncodedField encodedField = appInfo.resolveField(field);
      if (encodedField == null) {
        // Must mark the field as targeted even if it does not exist.
        markFieldAsTargeted(field, currentMethod);
        reportMissingField(field);
        return false;
      }

      if (!isProgramClass(encodedField.field.holder)) {
        // No need to trace into the non-program code.
        return false;
      }

      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register Sget `%s`.", field);
      }

      if (appView.options().enableGeneratedExtensionRegistryShrinking) {
        // If it is a dead proto extension field, don't trace onwards.
        boolean skipTracing =
            appView.withGeneratedExtensionRegistryShrinker(
                shrinker ->
                    shrinker.isDeadProtoExtensionField(encodedField, fieldAccessInfoCollection),
                false);
        if (skipTracing) {
          return false;
        }
      }

      if (encodedField.field != field) {
        // Mark the non-rebound field access as targeted. Note that this should only be done if the
        // field is not a dead proto field (in which case we bail-out above).
        markFieldAsTargeted(field, currentMethod);
      }

      markStaticFieldAsLive(encodedField, KeepReason.fieldReferencedIn(currentMethod));
      return true;
    }

    @Override
    public boolean registerStaticFieldWrite(DexField field) {
      if (!registerFieldWrite(field, currentMethod)) {
        return false;
      }

      DexEncodedField encodedField = appInfo.resolveField(field);
      if (encodedField == null) {
        // Must mark the field as targeted even if it does not exist.
        markFieldAsTargeted(field, currentMethod);
        reportMissingField(field);
        return false;
      }

      if (!isProgramClass(encodedField.field.holder)) {
        // No need to trace into the non-program code.
        return false;
      }

      if (Log.ENABLED) {
        Log.verbose(getClass(), "Register Sput `%s`.", field);
      }

      if (appView.options().enableGeneratedExtensionRegistryShrinking) {
        // If it is a dead proto extension field, don't trace onwards.
        boolean skipTracing =
            appView.withGeneratedExtensionRegistryShrinker(
                shrinker ->
                    shrinker.isDeadProtoExtensionField(encodedField, fieldAccessInfoCollection),
                false);
        if (skipTracing) {
          return false;
        }
      }

      // If it is written outside of the <clinit> of its enclosing class, record it.
      boolean isWrittenOutsideEnclosingStaticInitializer =
          currentMethod.method.holder != encodedField.field.holder
              || !currentMethod.isClassInitializer();
      if (isWrittenOutsideEnclosingStaticInitializer) {
        staticFieldsWrittenOutsideEnclosingStaticInitializer.add(encodedField.field);
      }

      if (encodedField.field != field) {
        // Mark the non-rebound field access as targeted. Note that this should only be done if the
        // field is not a dead proto field (in which case we bail-out above).
        markFieldAsTargeted(field, currentMethod);
      }

      markStaticFieldAsLive(encodedField, KeepReason.fieldReferencedIn(currentMethod));
      return true;
    }

    @Override
    public boolean registerConstClass(DexType type) {
      // We conservatively group T.class and T[].class to ensure that we do not merge T with S if
      // potential locks on T[].class and S[].class exists.
      DexType baseType = type.toBaseType(appView.dexItemFactory());
      if (baseType.isClassType()) {
        DexProgramClass baseClass = getProgramClassOrNull(baseType);
        if (baseClass != null) {
          constClassReferences.add(baseType);
        }
      }
      return registerConstClassOrCheckCast(type);
    }

    @Override
    public boolean registerCheckCast(DexType type) {
      return registerConstClassOrCheckCast(type);
    }

    @Override
    public boolean registerTypeReference(DexType type) {
      markTypeAsLive(type, clazz -> graphReporter.reportClassReferencedFrom(clazz, currentMethod));
      return true;
    }

    @Override
    public void registerMethodHandle(DexMethodHandle methodHandle, MethodHandleUse use) {
      super.registerMethodHandle(methodHandle, use);
      // If a method handle is not an argument to a lambda metafactory it could flow to a
      // MethodHandle.invokeExact invocation. For that to work, the receiver type cannot have
      // changed and therefore we cannot perform member rebinding. For these handles, we maintain
      // the receiver for the method handle. Therefore, we have to make sure that the receiver
      // stays in the output (and is not class merged). To ensure that we treat the receiver
      // as instantiated.
      if (methodHandle.isMethodHandle() && use != MethodHandleUse.ARGUMENT_TO_LAMBDA_METAFACTORY) {
        DexType type = methodHandle.asMethod().holder;
        DexProgramClass clazz = getProgramClassOrNull(type);
        if (clazz != null) {
          KeepReason reason = KeepReason.methodHandleReferencedIn(currentMethod);
          if (clazz.isInterface() && !clazz.accessFlags.isAnnotation()) {
            markInterfaceAsInstantiated(clazz, reason);
          } else {
            markInstantiated(clazz, reason);
          }
        }
      }
    }

    @Override
    public void registerCallSite(DexCallSite callSite) {
      callSites.add(callSite);
      super.registerCallSite(callSite);

      List<DexType> directInterfaces = LambdaDescriptor.getInterfaces(callSite, appInfo);
      if (directInterfaces != null) {
        for (DexType lambdaInstantiatedInterface : directInterfaces) {
          markLambdaInstantiated(lambdaInstantiatedInterface, currentMethod);
        }
      } else {
        if (!appInfo.isStringConcat(callSite.bootstrapMethod)) {
          if (options.reporter != null) {
            Diagnostic message =
                new StringDiagnostic(
                    "Unknown bootstrap method " + callSite.bootstrapMethod,
                    appInfo.originFor(currentMethod.method.holder));
            options.reporter.warning(message);
          }
        }
      }

      DexProgramClass bootstrapClass =
          getProgramClassOrNull(callSite.bootstrapMethod.asMethod().holder);
      if (bootstrapClass != null) {
        bootstrapMethods.add(callSite.bootstrapMethod.asMethod());
      }

      LambdaDescriptor descriptor = LambdaDescriptor.tryInfer(callSite, appInfo);
      if (descriptor == null) {
        return;
      }

      // For call sites representing a lambda, we link the targeted method
      // or field as if it were referenced from the current method.

      DexMethodHandle implHandle = descriptor.implHandle;
      assert implHandle != null;

      DexMethod method = implHandle.asMethod();
      if (descriptor.delegatesToLambdaImplMethod()) {
        lambdaMethodsTargetedByInvokeDynamic.add(method);
      }

      if (!methodsTargetedByInvokeDynamic.add(method)) {
        return;
      }

      switch (implHandle.type) {
        case INVOKE_STATIC:
          registerInvokeStatic(method, KeepReason.invokedFromLambdaCreatedIn(currentMethod));
          break;
        case INVOKE_INTERFACE:
          registerInvokeInterface(method, KeepReason.invokedFromLambdaCreatedIn(currentMethod));
          break;
        case INVOKE_INSTANCE:
          registerInvokeVirtual(method, KeepReason.invokedFromLambdaCreatedIn(currentMethod));
          break;
        case INVOKE_DIRECT:
          registerInvokeDirect(method, KeepReason.invokedFromLambdaCreatedIn(currentMethod));
          break;
        case INVOKE_CONSTRUCTOR:
          registerNewInstance(method.holder, KeepReason.invokedFromLambdaCreatedIn(currentMethod));
          break;
        default:
          throw new Unreachable();
      }

      // In similar way as what transitionMethodsForInstantiatedClass does for existing
      // classes we need to process classes dynamically created by runtime for lambdas.
      // We make an assumption that such classes are inherited directly from java.lang.Object
      // and implement all lambda interfaces.

      if (directInterfaces == null) {
        return;
      }

      // The set now contains all virtual methods on the type and its supertype that are reachable.
      // In a second step, we now look at interfaces. We have to do this in this order due to JVM
      // semantics for default methods. A default method is only reachable if it is not overridden
      // in any superclass. Also, it is not defined which default method is chosen if multiple
      // interfaces define the same default method. Hence, for every interface (direct or indirect),
      // we have to look at the interface chain and mark default methods as reachable, not taking
      // the shadowing of other interface chains into account.
      // See https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.3
      ScopedDexMethodSet seen = new ScopedDexMethodSet();
      for (DexType iface : directInterfaces) {
        DexProgramClass ifaceClazz = getProgramClassOrNull(iface);
        if (ifaceClazz != null) {
          transitionDefaultMethodsForInstantiatedClass(iface, seen);
        }
      }
    }

    private boolean registerConstClassOrCheckCast(DexType type) {
      if (!forceProguardCompatibility) {
        return registerTypeReference(type);
      }
      DexType baseType = type.toBaseType(appView.dexItemFactory());
      if (baseType.isClassType()) {
        DexProgramClass baseClass = getProgramClassOrNull(baseType);
        if (baseClass != null) {
          // Don't require any constructor, see b/112386012.
          markClassAsInstantiatedWithCompatRule(baseClass);
        } else {
          // This also handles reporting of missing classes.
          markTypeAsLive(
              baseType, clazz -> graphReporter.reportClassReferencedFrom(clazz, currentMethod));
        }
        return true;
      }
      return false;
    }
  }

  private void transitionReachableVirtualMethods(DexProgramClass clazz, ScopedDexMethodSet seen) {
    SetWithStoredReason<DexEncodedMethod> reachableMethods = reachableVirtualMethods.get(clazz);
    if (reachableMethods != null) {
      transitionNonAbstractMethodsToLiveAndShadow(clazz, reachableMethods, seen);
    }
  }

  private DexMethod getInvokeSuperTarget(DexMethod method, DexEncodedMethod currentMethod) {
    DexClass methodHolderClass = appView.definitionFor(method.holder);
    if (methodHolderClass != null && methodHolderClass.isInterface()) {
      return method;
    }
    DexClass holderClass = appView.definitionFor(currentMethod.method.holder);
    if (holderClass == null || holderClass.superType == null || holderClass.isInterface()) {
      // We do not know better or this call is made from an interface.
      return method;
    }
    // Return the invoked method on the supertype.
    return appView.dexItemFactory().createMethod(holderClass.superType, method.proto, method.name);
  }

  //
  // Actual actions performed.
  //

  private boolean verifyMethodIsTargeted(DexEncodedMethod method) {
    assert !method.isClassInitializer() : "Class initializers are never targeted";
    assert targetedMethods.contains(method);
    return true;
  }

  private boolean verifyTypeIsLive(DexProgramClass clazz) {
    assert liveTypes.contains(clazz);
    return true;
  }

  private void markTypeAsLive(DexType type, KeepReason reason) {
    if (type.isArrayType()) {
      markTypeAsLive(type.toBaseType(appView.dexItemFactory()), reason);
      return;
    }
    if (!type.isClassType()) {
      // Ignore primitive types.
      return;
    }
    DexProgramClass holder = getProgramClassOrNull(type);
    if (holder == null) {
      return;
    }
    markTypeAsLive(
        holder,
        scopedMethodsForLiveTypes.computeIfAbsent(type, ignore -> new ScopedDexMethodSet()),
        reason);
  }

  private void markTypeAsLive(DexType type, Function<DexProgramClass, KeepReason> reason) {
    if (type.isArrayType()) {
      markTypeAsLive(type.toBaseType(appView.dexItemFactory()), reason);
      return;
    }
    if (!type.isClassType()) {
      // Ignore primitive types.
      return;
    }
    DexProgramClass holder = getProgramClassOrNull(type);
    if (holder == null) {
      return;
    }
    markTypeAsLive(
        holder,
        scopedMethodsForLiveTypes.computeIfAbsent(type, ignore -> new ScopedDexMethodSet()),
        reason.apply(holder));
  }

  private void markTypeAsLive(DexProgramClass clazz, KeepReason reason) {
    markTypeAsLive(
        clazz,
        scopedMethodsForLiveTypes.computeIfAbsent(clazz.type, ignore -> new ScopedDexMethodSet()),
        reason);
  }

  private void markTypeAsLive(
      DexProgramClass holder, ScopedDexMethodSet seen, KeepReason reasonForType) {
    if (!liveTypes.add(holder, reasonForType)) {
      return;
    }

    if (Log.ENABLED) {
      Log.verbose(getClass(), "Type `%s` has become live.", holder.type);
    }

    KeepReason reason = KeepReason.reachableFromLiveType(holder.type);

    for (DexType iface : holder.interfaces.values) {
      markInterfaceTypeAsLiveViaInheritanceClause(iface, reason);
    }

    if (holder.superType != null) {
      ScopedDexMethodSet seenForSuper =
          scopedMethodsForLiveTypes.computeIfAbsent(
              holder.superType, ignore -> new ScopedDexMethodSet());
      seen.setParent(seenForSuper);
      markTypeAsLive(holder.superType, reason);
    }


    // We cannot remove virtual methods defined earlier in the type hierarchy if it is widening
    // access and is defined in an interface:
    //
    // public interface I {
    //   void clone();
    // }
    //
    // class Model implements I {
    //   public void clone() { ... } <-- this cannot be removed
    // }
    //
    // Any class loading of Model with Model.clone() removed will result in an illegal access
    // error because their exists an existing implementation (here it is Object.clone()). This is
    // only a problem in the DEX VM. We have to make this check no matter the output because
    // CF libraries can be used by Android apps. See b/136698023 for more information.
    ensureMethodsContinueToWidenAccess(holder, seen, reason);

    // We also need to add the corresponding <clinit> to the set of live methods, as otherwise
    // static field initialization (and other class-load-time sideeffects) will not happen.
    if (holder.hasClassInitializer()) {
      DexEncodedMethod clinit = holder.getClassInitializer();
      if (clinit != null && clinit.getOptimizationInfo().mayHaveSideEffects()) {
        assert clinit.method.holder == holder.type;
        markDirectStaticOrConstructorMethodAsLive(holder, clinit, reason);
      }
    }

    if (holder.isSerializable(appView)) {
      enqueueFirstNonSerializableClassInitializer(holder, reason);
    }

    if (!holder.annotations.isEmpty()) {
      processAnnotations(holder, holder.annotations.annotations);
    }
    // If this type has deferred annotations, we have to process those now, too.
    Set<DexAnnotation> annotations = deferredAnnotations.remove(holder.type);
    if (annotations != null && !annotations.isEmpty()) {
      assert holder.accessFlags.isAnnotation();
      assert annotations.stream().allMatch(a -> a.annotation.type == holder.type);
      annotations.forEach(annotation -> handleAnnotation(holder, annotation));
      }

    rootSet.forEachDependentStaticMember(holder, appView, this::enqueueDependentItem);
    compatEnqueueHolderIfDependentNonStaticMember(
        holder, rootSet.getDependentKeepClassCompatRule(holder.getType()));
  }

  private void ensureMethodsContinueToWidenAccess(DexClass clazz) {
    assert !clazz.isProgramClass();
    ScopedDexMethodSet seen =
        scopedMethodsForLiveTypes.computeIfAbsent(clazz.type, ignore -> new ScopedDexMethodSet());
    clazz.virtualMethods().forEach(seen::addMethodIfMoreVisible);
  }

  private void ensureMethodsContinueToWidenAccess(
      DexProgramClass clazz, ScopedDexMethodSet seen, KeepReason reason) {
    for (DexEncodedMethod method : clazz.virtualMethods()) {
      if (seen.addMethodIfMoreVisible(method) == AddMethodIfMoreVisibleResult.ADDED_MORE_VISIBLE
          && clazz.isProgramClass()
          && appView.appInfo().methodDefinedInInterfaces(method, clazz.type)) {
        markMethodAsTargeted(clazz, method, reason);
      }
    }
  }

  private void markInterfaceTypeAsLiveViaInheritanceClause(DexType type, KeepReason reason) {
    if (appView.options().enableUnusedInterfaceRemoval && !mode.isTracingMainDex()) {
      DexProgramClass clazz = getProgramClassOrNull(type);
      if (clazz == null) {
        return;
      }

      assert clazz.isInterface();

      if (!clazz.interfaces.isEmpty()) {
        markTypeAsLive(type, reason);
        return;
      }

      for (DexEncodedMethod method : clazz.virtualMethods()) {
        if (!method.accessFlags.isAbstract()) {
          markTypeAsLive(type, reason);
          return;
        }
      }

      // No need to mark the type as live. If an interface type is only reachable via the
      // inheritance clause of another type, and the interface only has abstract methods, it can
      // simply be removed from the inheritance clause.
    } else {
      markTypeAsLive(type, reason);
    }
  }

  private void enqueueDependentItem(
      DexDefinition precondition, DexDefinition consequent, Set<ProguardKeepRuleBase> reasons) {
    internalEnqueueRootItem(consequent, reasons, precondition);
  }

  private void processAnnotations(DexDefinition holder, DexAnnotation[] annotations) {
    for (DexAnnotation annotation : annotations) {
      processAnnotation(holder, annotation);
    }
  }

  private void processAnnotation(DexDefinition holder, DexAnnotation annotation) {
    handleAnnotation(holder, annotation);
  }

  private void handleAnnotation(DexDefinition holder, DexAnnotation annotation) {
    assert !holder.isDexClass() || holder.asDexClass().isProgramClass();
    DexType type = annotation.annotation.type;
    DexClass clazz = appView.definitionFor(type);
    boolean annotationTypeIsLibraryClass = clazz == null || clazz.isNotProgramClass();
    boolean isLive = annotationTypeIsLibraryClass || liveTypes.contains(clazz.asProgramClass());
    if (!shouldKeepAnnotation(annotation, isLive, appView.dexItemFactory(), options)) {
      // Remember this annotation for later.
      if (!annotationTypeIsLibraryClass) {
        deferredAnnotations.computeIfAbsent(type, ignore -> new HashSet<>()).add(annotation);
      }
      return;
    }
    KeepReason reason = KeepReason.annotatedOn(holder);
    liveAnnotations.add(annotation, reason);
    AnnotationReferenceMarker referenceMarker =
        new AnnotationReferenceMarker(annotation.annotation.type, appView.dexItemFactory(), reason);
    annotation.annotation.collectIndexedItems(referenceMarker);
  }

  private void handleInvokeOfStaticTarget(DexMethod method, KeepReason reason) {
    ResolutionResult resolutionResult = appInfo.resolveMethod(method.holder, method);
    if (resolutionResult == null) {
      reportMissingMethod(method);
      return;
    }
    DexEncodedMethod encodedMethod = resolutionResult.asSingleTarget();
    if (encodedMethod == null) {
      // Note: should this be reported too? Or is this unreachable?
      return;
    }
    DexProgramClass clazz = getProgramClassOrNull(encodedMethod.method.holder);
    if (clazz == null) {
      return;
    }

    // We have to mark the resolved method as targeted even if it cannot actually be invoked
    // to make sure the invocation will keep failing in the appropriate way.
    markMethodAsTargeted(clazz, encodedMethod, reason);

    // Only mark methods for which invocation will succeed at runtime live.
    if (encodedMethod.isStatic()) {
      registerClassInitializer(clazz, reason);
      markDirectStaticOrConstructorMethodAsLive(clazz, encodedMethod, reason);
    }
  }

  private void registerClassInitializer(DexProgramClass definition, KeepReason reason) {
    if (definition.hasClassInitializer()) {
      registerMethod(definition.getClassInitializer(), reason);
    }
  }

  private void markNonStaticDirectMethodAsReachable(DexMethod method, KeepReason reason) {
    handleInvokeOfDirectTarget(method, reason);
  }

  private void handleInvokeOfDirectTarget(DexMethod method, KeepReason reason) {
    DexType holder = method.holder;
    DexProgramClass clazz = getProgramClassOrNull(holder);
    if (clazz == null) {
      return;
    }
    // TODO(zerny): Is it ok that we lookup in both the direct and virtual pool here?
    DexEncodedMethod encodedMethod = clazz.lookupMethod(method);
    if (encodedMethod == null) {
      reportMissingMethod(method);
      return;
    }

    // We have to mark the resolved method as targeted even if it cannot actually be invoked
    // to make sure the invocation will keep failing in the appropriate way.
    markMethodAsTargeted(clazz, encodedMethod, reason);

    // Only mark methods for which invocation will succeed at runtime live.
    if (encodedMethod.isStatic()) {
      return;
    }

    markDirectStaticOrConstructorMethodAsLive(clazz, encodedMethod, reason);

    // It is valid to have an invoke-direct instruction in a default interface method that
    // targets another default method in the same interface (see testInvokeSpecialToDefault-
    // Method). In a class, that would lead to a verification error.
    if (encodedMethod.isVirtualMethod()
        && virtualMethodsTargetedByInvokeDirect.add(encodedMethod.method)) {
      enqueueMarkMethodLiveAction(clazz, encodedMethod, reason);
    }
  }

  private void ensureFromLibraryOrThrow(DexType type, DexClass context) {
    if (!mode.isInitialTreeShaking()) {
      // b/72312389: android.jar contains parts of JUnit and most developers include JUnit in
      // their programs. This leads to library classes extending program classes. When tracing
      // main dex lists we allow this.
      return;
    }

    if (dontWarnPatterns.matches(context.type)) {
      // Ignore.
      return;
    }

    DexClass holder = appView.definitionFor(type);
    if (holder != null && !holder.isLibraryClass()) {
      Diagnostic message =
          new StringDiagnostic(
              "Library class "
                  + context.type.toSourceString()
                  + (holder.isInterface() ? " implements " : " extends ")
                  + "program class "
                  + type.toSourceString());
      if (forceProguardCompatibility) {
        options.reporter.warning(message);
      } else {
        options.reporter.error(message);
      }
    }
  }

  private void reportMissingClass(DexType clazz) {
    if (Log.ENABLED && reportedMissing.add(clazz)) {
      Log.verbose(Enqueuer.class, "Class `%s` is missing.", clazz);
    }
  }

  private void reportMissingMethod(DexMethod method) {
    if (Log.ENABLED && reportedMissing.add(method)) {
      Log.verbose(Enqueuer.class, "Method `%s` is missing.", method);
    }
  }

  private void reportMissingField(DexField field) {
    if (Log.ENABLED && reportedMissing.add(field)) {
      Log.verbose(Enqueuer.class, "Field `%s` is missing.", field);
    }
  }

  private void markMethodAsTargeted(
      DexProgramClass clazz, DexEncodedMethod method, KeepReason reason) {
    assert method.method.holder == clazz.type;
    if (!targetedMethods.add(method, reason)) {
      // Already targeted.
      return;
    }
    markTypeAsLive(method.method.holder,
        holder -> graphReporter.reportClassReferencedFrom(holder, method));
    markParameterAndReturnTypesAsLive(method);
    processAnnotations(method, method.annotations.annotations);
    method.parameterAnnotationsList.forEachAnnotation(
        annotation -> processAnnotation(method, annotation));

    if (Log.ENABLED) {
      Log.verbose(getClass(), "Method `%s` is targeted.", method.method);
    }
    if (forceProguardCompatibility) {
      // Keep targeted default methods in compatibility mode. The tree pruner will otherwise make
      // these methods abstract, whereas Proguard does not (seem to) touch their code.
      if (!method.accessFlags.isAbstract() && clazz.isInterface()) {
        markMethodAsLiveWithCompatRule(clazz, method);
      }
    }
  }

  /**
   * Adds the class to the set of instantiated classes and marks its fields and methods live
   * depending on the currently seen invokes and field reads.
   */
  private void processNewlyInstantiatedClass(DexProgramClass clazz, KeepReason reason) {
    assert !clazz.isInterface() || clazz.accessFlags.isAnnotation();
    // Notify analyses. This is done even if `clazz` has already been marked as instantiated,
    // because each analysis may depend on seeing all the (clazz, reason) pairs. Thus, not doing so
    // could lead to nondeterminism.
    analyses.forEach(
        analysis -> analysis.processNewlyInstantiatedClass(clazz.asProgramClass(), reason));

    if (!instantiatedTypes.add(clazz, reason)) {
      return;
    }

    populateInstantiatedTypesCache(clazz);

    collectProguardCompatibilityRule(reason);
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Class `%s` is instantiated, processing...", clazz);
    }
    // This class becomes live, so it and all its supertypes become live types.
    markTypeAsLive(clazz, reason);
    // For all methods of the class, if we have seen a call, mark the method live.
    // We only do this for virtual calls, as the other ones will be done directly.
    transitionMethodsForInstantiatedClass(clazz);
    // For all instance fields visible from the class, mark them live if we have seen a read.
    transitionFieldsForInstantiatedClass(clazz);
    // Add all dependent instance members to the workqueue.
    transitionDependentItemsForInstantiatedClass(clazz);
  }

  private void populateInstantiatedTypesCache(DexProgramClass clazz) {
    if (!directAndIndirectlyInstantiatedTypes.add(clazz)) {
      return;
    }
    if (clazz.superType != null) {
      DexProgramClass superClass = getProgramClassOrNull(clazz.superType);
      if (superClass != null) {
        populateInstantiatedTypesCache(superClass);
      }
    }
    for (DexType iface : clazz.interfaces.values) {
      DexProgramClass ifaceClass = getProgramClassOrNull(iface);
      if (ifaceClass != null) {
        populateInstantiatedTypesCache(ifaceClass);
      }
    }
  }

  /**
   * Marks all methods live that can be reached by calls previously seen.
   *
   * <p>This should only be invoked if the given type newly becomes instantiated. In essence, this
   * method replays all the invokes we have seen so far that could apply to this type and marks the
   * corresponding methods live.
   *
   * <p>Only methods that are visible in this type are considered. That is, only those methods that
   * are either defined directly on this type or that are defined on a supertype but are not
   * shadowed by another inherited method. Furthermore, default methods from implemented interfaces
   * that are not otherwise shadowed are considered, too.
   *
   * <p>Finally all methods on library types that resolve starting at the instantiated type are
   * marked live.
   */
  private void transitionMethodsForInstantiatedClass(DexProgramClass instantiatedClass) {
    ScopedDexMethodSet seen = new ScopedDexMethodSet();
    Set<DexType> interfaces = Sets.newIdentityHashSet();
    DexProgramClass current = instantiatedClass;
    do {
      // We only have to look at virtual methods here, as only those can actually be executed at
      // runtime. Illegal dispatch situations and the corresponding exceptions are already handled
      // by the reachability logic.
      transitionReachableVirtualMethods(current, seen);
      Collections.addAll(interfaces, current.interfaces.values);
      current = getProgramClassOrNull(current.superType);
    } while (current != null && !instantiatedTypes.contains(current));

    // The set now contains all virtual methods on the type and its supertype that are reachable.
    // In a second step, we now look at interfaces. We have to do this in this order due to JVM
    // semantics for default methods. A default method is only reachable if it is not overridden in
    // any superclass. Also, it is not defined which default method is chosen if multiple
    // interfaces define the same default method. Hence, for every interface (direct or indirect),
    // we have to look at the interface chain and mark default methods as reachable, not taking
    // the shadowing of other interface chains into account.
    // See https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.3
    for (DexType iface : interfaces) {
      DexClass clazz = appView.definitionFor(iface);
      if (clazz == null) {
        reportMissingClass(iface);
        // TODO(herhut): In essence, our subtyping chain is broken here. Handle that case better.
        break;
      }
      transitionDefaultMethodsForInstantiatedClass(iface, seen);
    }

    // When tracing the main-dex content, library roots must be specified, thus there are no
    // implicit edges from library methods.
    if (getMode().isTracingMainDex()) {
      return;
    }

    // When a type becomes live, all library methods on that type become live too.
    // This is done by searching the library supertypes and then resolving each method defined by
    // such a library type from the point of the instantiated type. If the resolved targets are in
    // the program, i.e., the instantiated type has a method overidding a library method, then the
    // program method is live.
    Deque<DexClass> librarySearchItems = new ArrayDeque<>();
    librarySearchItems.add(instantiatedClass);
    while (!librarySearchItems.isEmpty()) {
      DexClass clazz = librarySearchItems.pop();
      if (clazz.isNotProgramClass()) {
        markLibraryAndClasspathMethodOverriddesAsLive(clazz, instantiatedClass);
      }
      if (clazz.superType != null) {
        DexClass superClass = appView.definitionFor(clazz.superType);
        if (superClass != null) {
          librarySearchItems.add(superClass);
        }
      }
      for (DexType iface : clazz.interfaces.values) {
        DexClass ifaceClass = appView.definitionFor(iface);
        if (ifaceClass != null) {
          librarySearchItems.add(ifaceClass);
        }
      }
    }
  }

  private void markLibraryAndClasspathMethodOverriddesAsLive(
      DexClass libraryClass, DexProgramClass instantiatedClass) {
    assert libraryClass.isNotProgramClass();
    assert !instantiatedClass.isInterface() || instantiatedClass.accessFlags.isAnnotation();
    for (DexEncodedMethod method : libraryClass.virtualMethods()) {
      // Note: it may be worthwhile to add a resolution cache here. If so, it must till ensure
      // that all library override edges are reported to the kept-graph consumer.
      ResolutionResult resolution =
          appView.appInfo().resolveMethod(instantiatedClass, method.method);
      if (resolution.isValidVirtualTarget(options)) {
        resolution.forEachTarget(
            target -> {
              if (!target.isAbstract()) {
                DexClass targetHolder = appView.definitionFor(target.method.holder);
                if (targetHolder != null && targetHolder.isProgramClass()) {
                  DexProgramClass programClass = targetHolder.asProgramClass();
                  if (shouldMarkLibraryMethodOverrideAsReachable(programClass, target)) {
                    target.setLibraryMethodOverride();
                    markVirtualMethodAsLive(
                        programClass,
                        target,
                        KeepReason.isLibraryMethod(programClass, libraryClass.type));
                  }
                }
              }
            });
      }
    }
  }

  private void transitionDefaultMethodsForInstantiatedClass(
      DexType iface, ScopedDexMethodSet seen) {
    DexProgramClass clazz = getProgramClassOrNull(iface);
    if (clazz == null) {
      return;
    }
    assert clazz.accessFlags.isInterface();
    transitionReachableVirtualMethods(clazz, seen.newNestedScope());
    for (DexType subInterface : clazz.interfaces.values) {
      transitionDefaultMethodsForInstantiatedClass(subInterface, seen);
    }
  }

  private void transitionNonAbstractMethodsToLiveAndShadow(
      DexProgramClass clazz,
      SetWithStoredReason<DexEncodedMethod> reachable,
      ScopedDexMethodSet seen) {
    for (DexEncodedMethod encodedMethod : reachable.getItems()) {
      if (seen.addMethod(encodedMethod)) {
        // Abstract methods do shadow implementations but they cannot be live, as they have no code.
        if (!encodedMethod.accessFlags.isAbstract()) {
          markVirtualMethodAsLive(
              clazz,
              encodedMethod,
              registerMethod(encodedMethod, reachable.getReasons(encodedMethod)));
        }
      }
    }
  }

  /**
   * Marks all fields live that can be reached by a read assuming that the given type or one of its
   * subtypes is instantiated.
   */
  private void transitionFieldsForInstantiatedClass(DexProgramClass clazz) {
    do {
      SetWithReason<DexEncodedField> reachableFields = reachableInstanceFields.get(clazz);
      if (reachableFields != null) {
        for (DexEncodedField field : reachableFields.getItems()) {
          // TODO(b/120959039): Should the reason this field is reachable come from the set?
          markInstanceFieldAsLive(field, KeepReason.reachableFromLiveType(clazz.type));
        }
      }
      clazz = getProgramClassOrNull(clazz.superType);
    } while (clazz != null && !instantiatedTypes.contains(clazz));
  }

  private void transitionDependentItemsForInstantiatedClass(DexClass clazz) {
    DexClass current = clazz;
    do {
      // Handle keep rules that are dependent on the class being instantiated.
      rootSet.forEachDependentNonStaticMember(current, appView, this::enqueueDependentItem);

      // Visit the super type.
      current = current.superType != null ? appView.definitionFor(current.superType) : null;
    } while (current != null
        && current.isProgramClass()
        && !instantiatedTypes.contains(current.asProgramClass()));
  }

  private void markFieldAsTargeted(DexField field, DexEncodedMethod context) {
    markTypeAsLive(field.type, clazz -> graphReporter.reportClassReferencedFrom(clazz, context));
    markTypeAsLive(field.holder, clazz -> graphReporter.reportClassReferencedFrom(clazz, context));
  }

  private void markStaticFieldAsLive(DexEncodedField encodedField, KeepReason reason) {
    // Mark the type live here, so that the class exists at runtime.
    DexField field = encodedField.field;
    markTypeAsLive(field.holder, reason);
    markTypeAsLive(field.type, reason);

    DexProgramClass clazz = getProgramClassOrNull(field.holder);
    if (clazz == null) {
      return;
    }

    registerClassInitializer(clazz, reason);

    // This field might be an instance field reachable from a static context, e.g. a getStatic that
    // resolves to an instance field. We have to keep the instance field nonetheless, as otherwise
    // we might unmask a shadowed static field and hence change semantics.
    if (encodedField.accessFlags.isStatic()) {
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Adding static field `%s` to live set.", encodedField.field);
      }
    } else {
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Adding instance field `%s` to live set (static context).",
            encodedField.field);
      }
    }
    processAnnotations(encodedField, encodedField.annotations.annotations);
    liveFields.add(encodedField, reason);
    collectProguardCompatibilityRule(reason);

    // Add all dependent members to the workqueue.
    enqueueRootItems(rootSet.getDependentItems(encodedField));

    // Notify analyses.
    analyses.forEach(analysis -> analysis.processNewlyLiveField(encodedField));
  }

  private void markInstanceFieldAsLive(DexEncodedField field, KeepReason reason) {
    assert field != null;
    assert field.isProgramField(appView);
    markTypeAsLive(field.field.holder, reason);
    markTypeAsLive(field.field.type, reason);
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Adding instance field `%s` to live set.", field.field);
    }
    processAnnotations(field, field.annotations.annotations);
    liveFields.add(field, reason);
    collectProguardCompatibilityRule(reason);

    // Add all dependent members to the workqueue.
    enqueueRootItems(rootSet.getDependentItems(field));

    // Notify analyses.
    analyses.forEach(analysis -> analysis.processNewlyLiveField(field));
  }

  private void markInstantiated(DexProgramClass clazz, KeepReason reason) {
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Register new instantiation of `%s`.", clazz);
    }
    workList.enqueueMarkInstantiatedAction(clazz, reason);
  }

  private void markLambdaInstantiated(DexType itf, DexEncodedMethod method) {
    DexClass clazz = appView.definitionFor(itf);
    if (clazz == null) {
      StringDiagnostic message =
          new StringDiagnostic(
              "Lambda expression implements missing interface `" + itf.toSourceString() + "`",
              appInfo.originFor(method.method.holder));
      options.reporter.warning(message);
      return;
    }
    if (!clazz.isInterface()) {
      StringDiagnostic message =
          new StringDiagnostic(
              "Lambda expression expected to implement an interface, but found "
                  + "`"
                  + itf.toSourceString()
                  + "`",
              appInfo.originFor(method.method.holder));
      options.reporter.warning(message);
      return;
    }
    if (clazz.isProgramClass()) {
      KeepReason reason = KeepReason.instantiatedIn(method);
      if (instantiatedInterfaceTypes.add(clazz.asProgramClass(), reason)) {
        populateInstantiatedTypesCache(clazz.asProgramClass());
      }
    }
  }

  private void markDirectStaticOrConstructorMethodAsLive(
      DexProgramClass clazz, DexEncodedMethod encodedMethod, KeepReason reason) {
    assert encodedMethod.method.holder == clazz.type;

    if (!enqueueMarkMethodLiveAction(clazz, encodedMethod, reason)) {
      // Already marked live.
      return;
    }
    // Should already have marked the type live previously.
    DexMethod method = encodedMethod.method;
    assert encodedMethod.isClassInitializer() || verifyMethodIsTargeted(encodedMethod);
    assert verifyTypeIsLive(clazz);
    if (Log.ENABLED) {
      Log.verbose(
          getClass(), "Method `%s` has become live due to direct invoke", encodedMethod.method);
    }
  }

  private void markVirtualMethodAsLive(
      DexProgramClass clazz, DexEncodedMethod method, KeepReason reason) {
    assert method != null;
    // Only explicit keep rules or reflective use should make abstract methods live.
    assert !method.accessFlags.isAbstract()
        || reason.isDueToKeepRule()
        || reason.isDueToReflectiveUse();
    if (enqueueMarkMethodLiveAction(clazz, method, reason)) {
      if (Log.ENABLED) {
        Log.verbose(getClass(), "Adding virtual method `%s` to live set.", method.method);
      }
    }
  }

  public boolean isFieldLive(DexEncodedField field) {
    return liveFields.contains(field);
  }

  private boolean isInstantiatedOrHasInstantiatedSubtype(DexProgramClass clazz) {
    return directAndIndirectlyInstantiatedTypes.contains(clazz);
  }

  private void markInstanceFieldAsReachable(DexEncodedField encodedField, KeepReason reason) {
    DexField field = encodedField.field;
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Marking instance field `%s` as reachable.", field);
    }

    markTypeAsLive(field.holder, reason);
    markTypeAsLive(field.type, reason);

    DexProgramClass clazz = getProgramClassOrNull(field.holder);
    if (clazz == null) {
      return;
    }

    // We might have a instance field access that is dispatched to a static field. In such case,
    // we have to keep the static field, so that the dispatch fails at runtime in the same way that
    // it did before. We have to keep the field even if the receiver has no live inhabitants, as
    // field resolution happens before the receiver is inspected.
    if (encodedField.accessFlags.isStatic()) {
      markStaticFieldAsLive(encodedField, reason);
    } else {
      if (isInstantiatedOrHasInstantiatedSubtype(clazz)) {
        // We have at least one live subtype, so mark it as live.
        markInstanceFieldAsLive(encodedField, reason);
      } else {
        // Add the field to the reachable set if the type later becomes instantiated.
        reachableInstanceFields
            .computeIfAbsent(clazz, ignore -> newSetWithoutReasonReporter())
            .add(encodedField, reason);
      }
    }
  }

  private void markVirtualMethodAsReachable(
      DexMethod method, boolean interfaceInvoke, KeepReason reason) {
    markVirtualMethodAsReachable(method, interfaceInvoke, reason, (x, y) -> true);
  }

  private void markVirtualMethodAsReachable(
      DexMethod method,
      boolean interfaceInvoke,
      KeepReason reason,
      BiPredicate<DexProgramClass, DexEncodedMethod> possibleTargetsFilter) {
    if (!virtualTargetsMarkedAsReachable.add(method)) {
      return;
    }
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Marking virtual method `%s` as reachable.", method);
    }
    if (method.holder.isArrayType()) {
      // This is an array type, so the actual class will be generated at runtime. We treat this
      // like an invoke on a direct subtype of java.lang.Object that has no further subtypes.
      // As it has no subtypes, it cannot affect liveness of the program we are processing.
      // Ergo, we can ignore it. We need to make sure that the element type is available, though.
      markTypeAsLive(method.holder, reason);
      return;
    }
    DexClass holder = appView.definitionFor(method.holder);
    if (holder == null) {
      reportMissingClass(method.holder);
      return;
    }

    DexEncodedMethod resolutionTarget =
        findAndMarkResolutionTarget(method, interfaceInvoke, reason);
    if (resolutionTarget == null || !resolutionTarget.isValidVirtualTarget(options)) {
      // There is no valid resolution, so any call will lead to a runtime exception.
      return;
    }

    assert interfaceInvoke == holder.isInterface();
    Set<DexEncodedMethod> possibleTargets =
        resolutionTarget.lookupVirtualDispatchTargets(interfaceInvoke, appInfo);
    if (possibleTargets == null || possibleTargets.isEmpty()) {
      return;
    }
    KeepReason overridesReason = KeepReason.overridesMethod(resolutionTarget);
    for (DexEncodedMethod encodedPossibleTarget : possibleTargets) {
      if (encodedPossibleTarget.isAbstract()) {
        continue;
      }
      markPossibleTargetsAsReachable(
          resolutionTarget == encodedPossibleTarget ? reason : overridesReason,
          possibleTargetsFilter,
          encodedPossibleTarget);
    }
  }

  private void markPossibleTargetsAsReachable(
      KeepReason reason,
      BiPredicate<DexProgramClass, DexEncodedMethod> possibleTargetsFilter,
      DexEncodedMethod encodedPossibleTarget) {
    assert encodedPossibleTarget.isVirtualMethod() || options.canUseNestBasedAccess();
    assert !encodedPossibleTarget.isAbstract();
    DexMethod possibleTarget = encodedPossibleTarget.method;
    DexProgramClass clazz = getProgramClassOrNull(possibleTarget.holder);
    if (clazz == null) {
      return;
    }
    if (!possibleTargetsFilter.test(clazz, encodedPossibleTarget)) {
      return;
    }
    SetWithStoredReason<DexEncodedMethod> reachable =
        reachableVirtualMethods.computeIfAbsent(clazz, ignore -> SetWithStoredReason.create());
    if (!reachable.add(encodedPossibleTarget, reason)) {
      return;
    }

    // If the holder type is instantiated, the method is live. Otherwise check whether we find
    // a subtype that does not shadow this methods but is instantiated.
    // Note that library classes are always considered instantiated, as we do not know where
    // they are instantiated.
    if (!isInstantiatedOrHasInstantiatedSubtype(clazz)) {
      return;
    }

    // TODO(mkroghj): Remove pinnedItems check here.
    if (instantiatedTypes.contains(clazz)
        || instantiatedInterfaceTypes.contains(clazz)
        || pinnedItems.contains(clazz.type)) {
      markVirtualMethodAsLive(clazz, encodedPossibleTarget, reason);
    } else {
      Deque<DexType> worklist =
          new ArrayDeque<>(appInfo.allImmediateSubtypes(possibleTarget.holder));
      while (!worklist.isEmpty()) {
        DexType current = worklist.pollFirst();
        DexProgramClass currentClass = getProgramClassOrNull(current);
        // If this class overrides the virtual, abort the search. Note that, according to
        // the JVM spec, private methods cannot override a virtual method.
        if (currentClass == null || currentClass.lookupVirtualMethod(possibleTarget) != null) {
          continue;
        }
        if (instantiatedTypes.contains(currentClass)
            || instantiatedInterfaceTypes.contains(currentClass)) {
          markVirtualMethodAsLive(clazz, encodedPossibleTarget, reason);
          break;
        }
        appInfo.allImmediateSubtypes(current).forEach(worklist::addLast);
      }
    }
  }

  private DexEncodedMethod findAndMarkResolutionTarget(
      DexMethod method, boolean interfaceInvoke, KeepReason reason) {
    DexEncodedMethod resolutionTarget =
        appInfo.resolveMethod(method.holder, method, interfaceInvoke).asResultOfResolve();
    if (resolutionTarget == null) {
      reportMissingMethod(method);
      return null;
    }

    DexClass resolutionTargetClass = appInfo.definitionFor(resolutionTarget.method.holder);
    if (resolutionTargetClass == null) {
      reportMissingClass(resolutionTarget.method.holder);
      return null;
    }

    // We have to mark this as targeted, as even if this specific instance never becomes live, we
    // need at least an abstract version of it so that we have a target for the corresponding
    // invoke. This also ensures preserving the errors detailed below.
    if (resolutionTargetClass.isProgramClass()) {
      markMethodAsTargeted(resolutionTargetClass.asProgramClass(), resolutionTarget, reason);
    }

    // If the method of an invoke-virtual instruction resolves to a private or static method, then
    // the invoke fails with an IllegalAccessError or IncompatibleClassChangeError, respectively.
    //
    // Unfortunately the above is not always the case:
    // Some Art VMs do not fail with an IllegalAccessError or IncompatibleClassChangeError if the
    // method of an invoke-virtual instruction resolves to a private or static method, but instead
    // ignores private and static methods during resolution (see also NonVirtualOverrideTest).
    // Therefore, we need to continue resolution from the super type until we find a virtual method.
    if (resolutionTarget.isPrivateMethod() || resolutionTarget.isStatic()) {
      assert !interfaceInvoke || resolutionTargetClass.isInterface();
      DexEncodedMethod possiblyValidTarget =
          markPossiblyValidTarget(method, reason, resolutionTarget, resolutionTargetClass);
      if (possiblyValidTarget != null) {
        // Since some Art runtimes may actually end up targeting this method, it is returned as
        // the basis of lookup for the enqueuing of virtual dispatches. Not doing so may cause it
        // to be marked abstract, thus leading to an AbstractMethodError on said Art runtimes.
        return possiblyValidTarget;
      }
    }

    return resolutionTarget;
  }

  private DexEncodedMethod markPossiblyValidTarget(
      DexMethod method,
      KeepReason reason,
      DexEncodedMethod resolutionTarget,
      DexClass resolutionTargetClass) {
    while (resolutionTarget.isPrivateMethod() || resolutionTarget.isStatic()) {
      resolutionTarget =
          appInfo
              .resolveMethod(
                  resolutionTargetClass.superType, method, resolutionTargetClass.isInterface())
              .asResultOfResolve();
      if (resolutionTarget == null) {
        return null;
      }
      resolutionTargetClass = appInfo.definitionFor(resolutionTarget.method.holder);
      if (resolutionTargetClass == null) {
        return null;
      }
    }
    if (resolutionTargetClass.isProgramClass()) {
      markMethodAsTargeted(resolutionTargetClass.asProgramClass(), resolutionTarget, reason);
    }
    return resolutionTarget;
  }

  private DexMethod generatedEnumValuesMethod(DexClass enumClass) {
    DexType arrayOfEnumClass =
        appView
            .dexItemFactory()
            .createType(
                appView.dexItemFactory().createString("[" + enumClass.type.toDescriptorString()));
    DexProto proto = appView.dexItemFactory().createProto(arrayOfEnumClass);
    return appView
        .dexItemFactory()
        .createMethod(enumClass.type, proto, appView.dexItemFactory().createString("values"));
  }

  private void markEnumValuesAsReachable(DexClass clazz, KeepReason reason) {
    DexEncodedMethod valuesMethod = clazz.lookupMethod(generatedEnumValuesMethod(clazz));
    if (valuesMethod != null) {
      // TODO(sgjesse): Does this have to be enqueued as a root item? Right now it is done as the
      // marking for not renaming it is in the root set.
      workList.enqueueMarkMethodKeptAction(valuesMethod, reason);
      pinnedItems.add(valuesMethod.toReference());
      rootSet.shouldNotBeMinified(valuesMethod.toReference());
    }
  }

  private void markSuperMethodAsReachable(DexMethod method, DexEncodedMethod from) {
    // We have to mark the immediate target of the descriptor as targeted, as otherwise
    // the invoke super will fail in the resolution step with a NSM error.
    // See <a
    // href="https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-6.html#jvms-6.5.invokespecial">
    // the JVM spec for invoke-special.
    DexEncodedMethod resolutionTarget = appInfo.resolveMethod(method.holder, method)
        .asResultOfResolve();
    if (resolutionTarget == null) {
      brokenSuperInvokes.add(method);
      reportMissingMethod(method);
      return;
    }

    if (resolutionTarget.accessFlags.isPrivate() || resolutionTarget.accessFlags.isStatic()) {
      brokenSuperInvokes.add(method);
    }
    DexProgramClass resolutionTargetClass = getProgramClassOrNull(resolutionTarget.method.holder);
    if (resolutionTargetClass != null) {
      markMethodAsTargeted(
          resolutionTargetClass, resolutionTarget, KeepReason.targetedBySuperFrom(from));
    }

    // Now we need to compute the actual target in the context.
    DexEncodedMethod target = appInfo.lookupSuperTarget(method, from.method.holder);
    if (target == null) {
      // The actual implementation in the super class is missing.
      reportMissingMethod(method);
      return;
    }
    DexProgramClass clazz = getProgramClassOrNull(target.method.holder);
    if (clazz == null) {
      return;
    }
    if (target.accessFlags.isPrivate()) {
      brokenSuperInvokes.add(method);
    }
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Adding super constraint from `%s` to `%s`", from.method,
          target.method);
    }
    if (superInvokeDependencies.computeIfAbsent(
        from, ignore -> Sets.newIdentityHashSet()).add(target)) {
      if (liveMethods.contains(from)) {
        markMethodAsTargeted(clazz, target, KeepReason.invokedViaSuperFrom(from));
        if (!target.accessFlags.isAbstract()) {
          markVirtualMethodAsLive(clazz, target, KeepReason.invokedViaSuperFrom(from));
        }
      }
    }
  }

  // Returns the set of live types.
  public Set<DexProgramClass> traceMainDex(
      RootSet rootSet, ExecutorService executorService, Timing timing) throws ExecutionException {
    assert analyses.isEmpty();
    assert mode.isTracingMainDex();
    this.rootSet = rootSet;
    // Translate the result of root-set computation into enqueuer actions.
    enqueueRootItems(rootSet.noShrinking);
    trace(executorService, timing);
    options.reporter.failIfPendingErrors();
    return liveTypes.getItems();
  }

  public AppInfoWithLiveness traceApplication(
      RootSet rootSet,
      ProguardClassFilter dontWarnPatterns,
      ExecutorService executorService,
      Timing timing)
      throws ExecutionException {
    this.rootSet = rootSet;
    this.dontWarnPatterns = dontWarnPatterns;
    // Translate the result of root-set computation into enqueuer actions.
    enqueueRootItems(rootSet.noShrinking);
    trace(executorService, timing);
    options.reporter.failIfPendingErrors();
    analyses.forEach(EnqueuerAnalysis::done);
    return createAppInfo(appInfo);
  }

  private AppInfoWithLiveness createAppInfo(AppInfoWithSubtyping appInfo) {
    ImmutableSortedSet.Builder<DexType> builder =
        ImmutableSortedSet.orderedBy(PresortedComparable::slowCompareTo);
    liveAnnotations.items.forEach(annotation -> builder.add(annotation.annotation.type));

    // Remove the temporary mappings that have been inserted into the field access info collection
    // and verify that the mapping is then one-to-one.
    fieldAccessInfoCollection.removeIf(
        (field, info) -> field != info.getField() || info == MISSING_FIELD_ACCESS_INFO);
    assert fieldAccessInfoCollection.verifyMappingIsOneToOne();

    AppInfoWithLiveness appInfoWithLiveness =
        new AppInfoWithLiveness(
            appInfo,
            SetUtils.mapIdentityHashSet(liveTypes.getItems(), DexProgramClass::getType),
            SetUtils.mapIdentityHashSet(
                liveAnnotations.getItems(), DexAnnotation::getAnnotationType),
            Collections.unmodifiableSet(instantiatedAppServices),
            SetUtils.mapIdentityHashSet(instantiatedTypes.getItems(), DexProgramClass::getType),
            Enqueuer.toSortedDescriptorSet(targetedMethods.getItems()),
            ImmutableSortedSet.copyOf(DexMethod::slowCompareTo, bootstrapMethods),
            ImmutableSortedSet.copyOf(DexMethod::slowCompareTo, methodsTargetedByInvokeDynamic),
            ImmutableSortedSet.copyOf(
                DexMethod::slowCompareTo, virtualMethodsTargetedByInvokeDirect),
            toSortedDescriptorSet(liveMethods.getItems()),
            // Filter out library fields and pinned fields, because these are read by default.
            fieldAccessInfoCollection,
            instanceFieldsWrittenOnlyInEnclosingInstanceInitializers(),
            staticFieldsWrittenOnlyInEnclosingStaticInitializer(),
            // TODO(b/132593519): Do we require these sets to be sorted for determinism?
            toImmutableSortedMap(virtualInvokes, PresortedComparable::slowCompare),
            toImmutableSortedMap(interfaceInvokes, PresortedComparable::slowCompare),
            toImmutableSortedMap(superInvokes, PresortedComparable::slowCompare),
            toImmutableSortedMap(directInvokes, PresortedComparable::slowCompare),
            toImmutableSortedMap(staticInvokes, PresortedComparable::slowCompare),
            callSites,
            ImmutableSortedSet.copyOf(DexMethod::slowCompareTo, brokenSuperInvokes),
            pinnedItems,
            rootSet.mayHaveSideEffects,
            rootSet.noSideEffects,
            rootSet.assumedValues,
            rootSet.alwaysInline,
            rootSet.forceInline,
            rootSet.neverInline,
            rootSet.keepConstantArguments,
            rootSet.keepUnusedArguments,
            rootSet.neverClassInline,
            rootSet.neverMerge,
            rootSet.neverPropagateValue,
            joinIdentifierNameStrings(rootSet.identifierNameStrings, identifierNameStrings),
            Collections.emptySet(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            SetUtils.mapIdentityHashSet(
                instantiatedInterfaceTypes.getItems(), DexProgramClass::getType),
            constClassReferences);
    appInfo.markObsolete();
    return appInfoWithLiveness;
  }

  private static <T extends PresortedComparable<T>> SortedSet<T> toSortedDescriptorSet(
      Set<? extends KeyedDexItem<T>> set) {
    ImmutableSortedSet.Builder<T> builder =
        new ImmutableSortedSet.Builder<>(PresortedComparable::slowCompareTo);
    for (KeyedDexItem<T> item : set) {
      builder.add(item.getKey());
    }
    return builder.build();
  }

  private static Object2BooleanMap<DexReference> joinIdentifierNameStrings(
      Set<DexReference> explicit, Set<DexReference> implicit) {
    Object2BooleanMap<DexReference> result = new Object2BooleanArrayMap<>();
    for (DexReference e : explicit) {
      result.putIfAbsent(e, true);
    }
    for (DexReference i : implicit) {
      result.putIfAbsent(i, false);
    }
    return result;
  }

  private void trace(ExecutorService executorService, Timing timing) throws ExecutionException {
    timing.begin("Grow the tree.");
    try {
      while (true) {
        long numOfLiveItems = (long) liveTypes.items.size();
        numOfLiveItems += (long) liveMethods.items.size();
        numOfLiveItems += (long) liveFields.items.size();
        while (!workList.isEmpty()) {
          Action action = workList.poll();
          switch (action.kind) {
            case MARK_INSTANTIATED:
              processNewlyInstantiatedClass((DexProgramClass) action.target, action.reason);
              break;
            case MARK_REACHABLE_FIELD:
              markInstanceFieldAsReachable((DexEncodedField) action.target, action.reason);
              break;
            case MARK_REACHABLE_DIRECT:
              markNonStaticDirectMethodAsReachable((DexMethod) action.target, action.reason);
              break;
            case MARK_REACHABLE_VIRTUAL:
              markVirtualMethodAsReachable((DexMethod) action.target, false, action.reason);
              break;
            case MARK_REACHABLE_INTERFACE:
              markVirtualMethodAsReachable((DexMethod) action.target, true, action.reason);
              break;
            case MARK_REACHABLE_SUPER:
              markSuperMethodAsReachable((DexMethod) action.target,
                  (DexEncodedMethod) action.context);
              break;
            case MARK_METHOD_KEPT:
              markMethodAsKept((DexEncodedMethod) action.target, action.reason);
              break;
            case MARK_FIELD_KEPT:
              markFieldAsKept((DexEncodedField) action.target, action.reason);
              break;
            case MARK_METHOD_LIVE:
              markMethodAsLive(((DexEncodedMethod) action.target), action.reason);
              break;
            default:
              throw new IllegalArgumentException("" + action.kind);
          }
        }

        // Continue fix-point processing if -if rules are enabled by items that newly became live.
        long numOfLiveItemsAfterProcessing = (long) liveTypes.items.size();
        numOfLiveItemsAfterProcessing += (long) liveMethods.items.size();
        numOfLiveItemsAfterProcessing += (long) liveFields.items.size();
        if (numOfLiveItemsAfterProcessing > numOfLiveItems) {
          // Build the mapping of active if rules. We use a single collection of if-rules to allow
          // removing if rules that have a constant sequent keep rule when they materialize.
          if (activeIfRules == null) {
            activeIfRules = new HashMap<>();
            IfRuleClassPartEquivalence equivalence = new IfRuleClassPartEquivalence();
            for (ProguardIfRule ifRule : rootSet.ifRules) {
              Wrapper<ProguardIfRule> wrap = equivalence.wrap(ifRule);
              activeIfRules.computeIfAbsent(wrap, ignore -> new LinkedHashSet<>()).add(ifRule);
            }
          }
          RootSetBuilder consequentSetBuilder = new RootSetBuilder(appView, null);
          IfRuleEvaluator ifRuleEvaluator =
              new IfRuleEvaluator(
                  appView,
                  executorService,
                  activeIfRules,
                  liveFields.getItems(),
                  liveMethods.getItems(),
                  liveTypes.getItems(),
                  mode,
                  consequentSetBuilder,
                  targetedMethods.getItems());
          ConsequentRootSet consequentRootSet = ifRuleEvaluator.run();
          // TODO(b/132600955): This modifies the root set. Should the consequent be persistent?
          rootSet.addConsequentRootSet(consequentRootSet);
          enqueueRootItems(consequentRootSet.noShrinking);
          // TODO(b/132828740): Seems incorrect that the precondition is not always met here.
          consequentRootSet.dependentNoShrinking.forEach(
              (precondition, dependentItems) -> enqueueRootItems(dependentItems));
          // Check for compatibility rules indicating that the holder must be implicitly kept.
          if (forceProguardCompatibility) {
            consequentRootSet.dependentKeepClassCompatRule.forEach(
                (precondition, compatRules) -> {
                  assert precondition.isDexType();
                  DexClass preconditionHolder = appView.definitionFor(precondition.asDexType());
                  compatEnqueueHolderIfDependentNonStaticMember(preconditionHolder, compatRules);
                });
          }
          if (!workList.isEmpty()) {
            continue;
          }
        }

        // Continue fix-point processing while there are additional work items to ensure items that
        // are passed to Java reflections are traced.
        if (!pendingReflectiveUses.isEmpty()) {
          pendingReflectiveUses.forEach(this::handleReflectiveBehavior);
          pendingReflectiveUses.clear();
        }
        if (!proguardCompatibilityWorkList.isEmpty()) {
          proguardCompatibilityWorkList.transferTo(workList, liveMethods::add);
        }
        if (!workList.isEmpty()) {
          continue;
        }

        // Notify each analysis that a fixpoint has been reached, and give each analysis an
        // opportunity to add items to the worklist.
        analyses.forEach(analysis -> analysis.notifyFixpoint(this, workList));
        if (!workList.isEmpty()) {
          continue;
        }

        // Reached the fixpoint.
        break;
      }

      if (Log.ENABLED) {
        Set<DexEncodedMethod> allLive = Sets.newIdentityHashSet();
        for (Entry<DexProgramClass, SetWithStoredReason<DexEncodedMethod>> entry :
            reachableVirtualMethods.entrySet()) {
          allLive.addAll(entry.getValue().getItems());
        }
        Set<DexEncodedMethod> reachableNotLive = Sets.difference(allLive, liveMethods.getItems());
        Log.debug(getClass(), "%s methods are reachable but not live", reachableNotLive.size());
        Log.info(getClass(), "Only reachable: %s", reachableNotLive);
        Set<DexProgramClass> liveButNotInstantiated =
            Sets.difference(liveTypes.getItems(), instantiatedTypes.getItems());
        Log.debug(getClass(), "%s classes are live but not instantiated",
            liveButNotInstantiated.size());
        Log.info(getClass(), "Live but not instantiated: %s", liveButNotInstantiated);
        SetView<DexEncodedMethod> targetedButNotLive = Sets
            .difference(targetedMethods.getItems(), liveMethods.getItems());
        Log.debug(getClass(), "%s methods are targeted but not live", targetedButNotLive.size());
        Log.info(getClass(), "Targeted but not live: %s", targetedButNotLive);
      }
    } finally {
      timing.end();
    }
    unpinLambdaMethods();
  }

  private void unpinLambdaMethods() {
    for (DexMethod method : lambdaMethodsTargetedByInvokeDynamic) {
      pinnedItems.remove(method);
      rootSet.prune(method);
    }
    lambdaMethodsTargetedByInvokeDynamic.clear();
  }

  private void markMethodAsKept(DexEncodedMethod target, KeepReason reason) {
    DexMethod method = target.method;
    DexProgramClass holder = getProgramClassOrNull(method.holder);
    if (holder == null) {
      return;
    }
    if (target.isVirtualMethod()) {
      // A virtual method. Mark it as reachable so that subclasses, if instantiated, keep
      // their overrides. However, we don't mark it live, as a keep rule might not imply that
      // the corresponding class is live.
      if (!holder.isInterface()) {
        workList.enqueueMarkReachableVirtualAction(method, reason);
      } else {
        workList.enqueueMarkReachableInterfaceAction(method, reason);
        // Reachability for default methods is based on live subtypes in general. For keep rules,
        // we need special handling as we essentially might have live subtypes that are outside of
        // the current compilation unit. Keep either the default-method or its implementation
        // method.
        // TODO(b/120959039): Codify the kept-graph expectations for these cases in tests.
        if (target.isNonAbstractVirtualMethod()) {
          markVirtualMethodAsLive(holder, target, reason);
        } else {
          DexEncodedMethod implementation = target.getDefaultInterfaceMethodImplementation();
          if (implementation != null) {
            DexProgramClass companion = getProgramClassOrNull(implementation.method.holder);
            markTypeAsLive(companion, reason);
            markVirtualMethodAsLive(companion, implementation, reason);
          }
        }
      }
    } else {
      markMethodAsTargeted(holder, target, reason);
      markDirectStaticOrConstructorMethodAsLive(holder, target, reason);
    }
  }

  private void markFieldAsKept(DexEncodedField target, KeepReason reason) {
    DexProgramClass clazz = getProgramClassOrNull(target.field.holder);
    if (clazz == null) {
      return;
    }
    if (target.accessFlags.isStatic()) {
      markStaticFieldAsLive(target, reason);
    } else {
      markInstanceFieldAsReachable(target, reason);
    }
  }

  private boolean shouldMarkLibraryMethodOverrideAsReachable(
      DexProgramClass clazz, DexEncodedMethod method) {
    assert method.isVirtualMethod();

    if (appView.isClassEscapingIntoLibrary(clazz.type)) {
      return true;
    }

    // If there is a subtype of `clazz` that escapes into the library and does not override `method`
    // then we need to mark the method as being reachable.
    Deque<DexType> worklist = new ArrayDeque<>(appView.appInfo().allImmediateSubtypes(clazz.type));

    Set<DexType> visited = Sets.newIdentityHashSet();
    visited.addAll(worklist);

    while (!worklist.isEmpty()) {
      DexClass current = appView.definitionFor(worklist.removeFirst());
      if (current == null) {
        continue;
      }

      assert visited.contains(current.type);

      if (current.lookupVirtualMethod(method.method) != null) {
        continue;
      }

      if (appView.isClassEscapingIntoLibrary(current.type)) {
        return true;
      }

      for (DexType subtype : appView.appInfo().allImmediateSubtypes(current.type)) {
        if (visited.add(subtype)) {
          worklist.add(subtype);
        }
      }
    }

    return false;
  }

  private void markMethodAsLive(DexEncodedMethod method, KeepReason reason) {
    assert liveMethods.contains(method);

    DexProgramClass clazz = getProgramClassOrNull(method.method.holder);
    if (clazz == null) {
      return;
    }

    collectProguardCompatibilityRule(reason);

    Set<DexEncodedMethod> superCallTargets = superInvokeDependencies.get(method);
    if (superCallTargets != null) {
      for (DexEncodedMethod superCallTarget : superCallTargets) {
        if (Log.ENABLED) {
          Log.verbose(getClass(), "Found super invoke constraint on `%s`.", superCallTarget.method);
        }
        DexProgramClass targetClass = getProgramClassOrNull(superCallTarget.method.holder);
        assert targetClass != null;
        if (targetClass != null) {
          markMethodAsTargeted(
              targetClass, superCallTarget, KeepReason.invokedViaSuperFrom(method));
          markVirtualMethodAsLive(
              targetClass, superCallTarget, KeepReason.invokedViaSuperFrom(method));
        }
      }
    }
    markParameterAndReturnTypesAsLive(method);
    if (appView.definitionFor(method.method.holder).isProgramClass()) {
      processAnnotations(method, method.annotations.annotations);
      method.parameterAnnotationsList.forEachAnnotation(
          annotation -> processAnnotation(method, annotation));
    }
    method.registerCodeReferences(new UseRegistry(options.itemFactory, method));

    // Add all dependent members to the workqueue.
    enqueueRootItems(rootSet.getDependentItems(method));

    // Notify analyses.
    analyses.forEach(analysis -> analysis.processNewlyLiveMethod(method));
  }

  private void markParameterAndReturnTypesAsLive(DexEncodedMethod method) {
    for (DexType parameterType : method.method.proto.parameters.values) {
      markTypeAsLive(
          parameterType, clazz -> graphReporter.reportClassReferencedFrom(clazz, method));
    }
    markTypeAsLive(
        method.method.proto.returnType,
        clazz -> graphReporter.reportClassReferencedFrom(clazz, method));
  }

  private void collectProguardCompatibilityRule(KeepReason reason) {
    if (reason.isDueToProguardCompatibility() && compatibility != null) {
      compatibility.addRule(reason.getProguardKeepRule());
    }
  }

  private void markClassAsInstantiatedWithReason(DexProgramClass clazz, KeepReason reason) {
    workList.enqueueMarkInstantiatedAction(clazz, reason);
    if (clazz.hasDefaultInitializer()) {
      workList.enqueueMarkReachableDirectAction(clazz.getDefaultInitializer().method, reason);
    }
  }

  private void markClassAsInstantiatedWithCompatRule(DexProgramClass clazz) {
    ProguardKeepRule rule = ProguardConfigurationUtils.buildDefaultInitializerKeepRule(clazz);
    KeepReason reason = KeepReason.dueToProguardCompatibilityKeepRule(rule);
    if (clazz.isInterface() && !clazz.accessFlags.isAnnotation()) {
      markInterfaceAsInstantiated(clazz, reason);
      return;
    }
    proguardCompatibilityWorkList.enqueueMarkInstantiatedAction(clazz, reason);
    if (clazz.hasDefaultInitializer()) {
      proguardCompatibilityWorkList.enqueueMarkReachableDirectAction(
          clazz.getDefaultInitializer().method, reason);
    }
  }

  private void markMethodAsLiveWithCompatRule(DexProgramClass clazz, DexEncodedMethod method) {
    proguardCompatibilityWorkList.enqueueMarkMethodLiveAction(
        clazz,
        method,
        KeepReason.dueToProguardCompatibilityKeepRule(
            ProguardConfigurationUtils.buildMethodKeepRule(clazz, method)));
  }

  private void handleReflectiveBehavior(DexEncodedMethod method) {
    DexType originHolder = method.method.holder;
    Origin origin = appInfo.originFor(originHolder);
    IRCode code = method.buildIR(appView, origin);
    InstructionIterator iterator = code.instructionIterator();
    while (iterator.hasNext()) {
      Instruction instruction = iterator.next();
      handleReflectiveBehavior(method, instruction);
    }
  }

  private void handleReflectiveBehavior(DexEncodedMethod method, Instruction instruction) {
    if (!instruction.isInvokeMethod()) {
      return;
    }
    InvokeMethod invoke = instruction.asInvokeMethod();
    DexMethod invokedMethod = invoke.getInvokedMethod();
    DexItemFactory dexItemFactory = appView.dexItemFactory();
    if (invokedMethod == dexItemFactory.classMethods.newInstance) {
      handleJavaLangClassNewInstance(method, invoke);
      return;
    }
    if (invokedMethod == dexItemFactory.constructorMethods.newInstance) {
      handleJavaLangReflectConstructorNewInstance(method, invoke);
      return;
    }
    if (invokedMethod == dexItemFactory.enumMethods.valueOf) {
      handleJavaLangEnumValueOf(method, invoke);
      return;
    }
    if (invokedMethod == dexItemFactory.proxyMethods.newProxyInstance) {
      handleJavaLangReflectProxyNewProxyInstance(method, invoke);
      return;
    }
    if (dexItemFactory.serviceLoaderMethods.isLoadMethod(invokedMethod)) {
      handleServiceLoaderInvocation(method, invoke);
      return;
    }
    if (!isReflectionMethod(dexItemFactory, invokedMethod)) {
      return;
    }
    DexReference identifierItem = identifyIdentifier(invoke, appView);
    if (identifierItem == null) {
      return;
    }
    if (identifierItem.isDexType()) {
      DexProgramClass clazz = getProgramClassOrNull(identifierItem.asDexType());
      if (clazz == null) {
        return;
      }
      if (!clazz.isInterface()) {
        markInstantiated(clazz, KeepReason.reflectiveUseIn(method));
        if (clazz.hasDefaultInitializer()) {
          DexEncodedMethod initializer = clazz.getDefaultInitializer();
          KeepReason reason = KeepReason.reflectiveUseIn(method);
          markMethodAsTargeted(clazz, initializer, reason);
          markDirectStaticOrConstructorMethodAsLive(clazz, initializer, reason);
        }
      }
    } else if (identifierItem.isDexField()) {
      DexField field = identifierItem.asDexField();
      DexProgramClass clazz = getProgramClassOrNull(field.holder);
      if (clazz == null) {
        return;
      }
      DexEncodedField encodedField = appView.definitionFor(field);
      if (encodedField == null) {
        return;
      }
      // Normally, we generate a -keepclassmembers rule for the field, such that the field is only
      // kept if it is a static field, or if the holder or one of its subtypes are instantiated.
      // However, if the invoked method is a field updater, then we always need to keep instance
      // fields since the creation of a field updater throws a NoSuchFieldException if the field
      // is not present.
      boolean keepClass =
          !encodedField.accessFlags.isStatic()
              && dexItemFactory.atomicFieldUpdaterMethods.isFieldUpdater(invokedMethod);
      if (keepClass) {
        markInstantiated(clazz, KeepReason.reflectiveUseIn(method));
      }
      markFieldAsKept(encodedField, KeepReason.reflectiveUseIn(method));
      // Fields accessed by reflection is marked as both read and written.
      registerFieldRead(encodedField.field, method);
      registerFieldWrite(encodedField.field, method);

    } else {
      assert identifierItem.isDexMethod();
      DexMethod targetedMethod = identifierItem.asDexMethod();
      DexProgramClass clazz = getProgramClassOrNull(targetedMethod.holder);
      if (clazz == null) {
        return;
      }
      DexEncodedMethod encodedMethod = appView.definitionFor(targetedMethod);
      if (encodedMethod == null) {
        return;
      }
      KeepReason reason = KeepReason.reflectiveUseIn(method);
      if (encodedMethod.accessFlags.isStatic() || encodedMethod.accessFlags.isConstructor()) {
        markMethodAsTargeted(clazz, encodedMethod, reason);
        markDirectStaticOrConstructorMethodAsLive(clazz, encodedMethod, reason);
      } else {
        markVirtualMethodAsLive(clazz, encodedMethod, reason);
      }
    }
  }

  /** Handles reflective uses of {@link Class#newInstance()}. */
  private void handleJavaLangClassNewInstance(DexEncodedMethod method, InvokeMethod invoke) {
    if (!invoke.isInvokeVirtual()) {
      assert false;
      return;
    }

    DexType instantiatedType =
        ConstantValueUtils.getDexTypeRepresentedByValue(
            invoke.asInvokeVirtual().getReceiver(), appView);
    if (instantiatedType == null || !instantiatedType.isClassType()) {
      // Give up, we can't tell which class is being instantiated, or the type is not a class type.
      // The latter should not happen in practice.
      return;
    }

    DexProgramClass clazz = getProgramClassOrNull(instantiatedType);
    if (clazz == null) {
      return;
    }
    DexEncodedMethod defaultInitializer = clazz.getDefaultInitializer();
    if (defaultInitializer != null) {
      KeepReason reason = KeepReason.reflectiveUseIn(method);
      markClassAsInstantiatedWithReason(clazz, reason);
      markMethodAsTargeted(clazz, defaultInitializer, reason);
      markDirectStaticOrConstructorMethodAsLive(clazz, defaultInitializer, reason);
    }
  }

  /** Handles reflective uses of {@link java.lang.reflect.Constructor#newInstance(Object...)}. */
  private void handleJavaLangReflectConstructorNewInstance(
      DexEncodedMethod method, InvokeMethod invoke) {
    if (!invoke.isInvokeVirtual()) {
      assert false;
      return;
    }

    Value constructorValue = invoke.asInvokeVirtual().getReceiver().getAliasedValue();
    if (constructorValue.isPhi() || !constructorValue.definition.isInvokeVirtual()) {
      // Give up, we can't tell which class is being instantiated.
      return;
    }

    InvokeVirtual constructorDefinition = constructorValue.definition.asInvokeVirtual();
    if (constructorDefinition.getInvokedMethod()
        != appView.dexItemFactory().classMethods.getDeclaredConstructor) {
      // Give up, we can't tell which constructor is being invoked.
      return;
    }

    DexType instantiatedType =
        ConstantValueUtils.getDexTypeRepresentedByValue(
            constructorDefinition.getReceiver(), appView);
    if (instantiatedType == null || !instantiatedType.isClassType()) {
      // Give up, we can't tell which constructor is being invoked, or the type is not a class type.
      // The latter should not happen in practice.
      return;
    }

    DexProgramClass clazz = getProgramClassOrNull(instantiatedType);
    if (clazz == null) {
      return;
    }
    Value parametersValue = constructorDefinition.inValues().get(1);
    if (parametersValue.isPhi() || !parametersValue.definition.isNewArrayEmpty()) {
      // Give up, we can't tell which constructor is being invoked.
      return;
    }

    Value parametersSizeValue = parametersValue.definition.asNewArrayEmpty().size();
    if (parametersSizeValue.isPhi() || !parametersSizeValue.definition.isConstNumber()) {
      // Give up, we can't tell which constructor is being invoked.
      return;
    }

    DexEncodedMethod initializer = null;

    int parametersSize = parametersSizeValue.definition.asConstNumber().getIntValue();
    if (parametersSize == 0) {
      initializer = clazz.getDefaultInitializer();
    } else {
      DexType[] parameterTypes = new DexType[parametersSize];
      int missingIndices = parametersSize;
      for (Instruction user : parametersValue.uniqueUsers()) {
        if (user.isArrayPut()) {
          ArrayPut arrayPutInstruction = user.asArrayPut();
          if (arrayPutInstruction.array() != parametersValue) {
            return;
          }

          Value indexValue = arrayPutInstruction.index();
          if (indexValue.isPhi() || !indexValue.definition.isConstNumber()) {
            return;
          }
          int index = indexValue.definition.asConstNumber().getIntValue();
          if (index >= parametersSize) {
            return;
          }

          DexType type =
              ConstantValueUtils.getDexTypeRepresentedByValue(arrayPutInstruction.value(), appView);
          if (type == null) {
            return;
          }

          if (parameterTypes[index] == type) {
            continue;
          }
          if (parameterTypes[index] != null) {
            return;
          }
          parameterTypes[index] = type;
          missingIndices--;
        }
      }

      if (missingIndices == 0) {
        initializer = clazz.getInitializer(parameterTypes);
      }
    }

    if (initializer != null) {
      KeepReason reason = KeepReason.reflectiveUseIn(method);
      markClassAsInstantiatedWithReason(clazz, reason);
      markMethodAsTargeted(clazz, initializer, reason);
      markDirectStaticOrConstructorMethodAsLive(clazz, initializer, reason);
    }
  }

  /**
   * Handles reflective uses of {@link java.lang.reflect.Proxy#newProxyInstance(ClassLoader,
   * Class[], InvocationHandler)}.
   */
  private void handleJavaLangReflectProxyNewProxyInstance(
      DexEncodedMethod method, InvokeMethod invoke) {
    if (!invoke.isInvokeStatic()) {
      assert false;
      return;
    }

    Value interfacesValue = invoke.arguments().get(1);
    if (interfacesValue.isPhi() || !interfacesValue.definition.isNewArrayEmpty()) {
      // Give up, we can't tell which interfaces the proxy implements.
      return;
    }

    for (Instruction user : interfacesValue.uniqueUsers()) {
      if (!user.isArrayPut()) {
        continue;
      }

      ArrayPut arrayPut = user.asArrayPut();
      DexType type = ConstantValueUtils.getDexTypeRepresentedByValue(arrayPut.value(), appView);
      if (type == null || !type.isClassType()) {
        continue;
      }

      DexProgramClass clazz = getProgramClassOrNull(type);
      if (clazz != null && clazz.isInterface()) {
        // Add this interface to the set of pinned items to ensure that we do not merge the
        // interface into its subtype and to ensure that the devirtualizer does not perform illegal
        // rewritings of invoke-interface instructions into invoke-virtual instructions.
        pinnedItems.add(clazz.type);
      }
    }
  }

  private void handleJavaLangEnumValueOf(DexEncodedMethod method, InvokeMethod invoke) {
    // The use of java.lang.Enum.valueOf(java.lang.Class, java.lang.String) will indirectly
    // access the values() method of the enum class passed as the first argument. The method
    // SomeEnumClass.valueOf(java.lang.String) which is generated by javac for all enums will
    // call this method.
    if (invoke.inValues().get(0).isConstClass()) {
      DexClass clazz =
          appView.definitionFor(invoke.inValues().get(0).definition.asConstClass().getValue());
      if (clazz.accessFlags.isEnum() && clazz.superType == appView.dexItemFactory().enumType) {
        markEnumValuesAsReachable(clazz, KeepReason.invokedFrom(method));
      }
    }
  }

  private void handleServiceLoaderInvocation(DexEncodedMethod method, InvokeMethod invoke) {
    if (invoke.inValues().size() == 0) {
      // Should never happen.
      return;
    }

    Value argument = invoke.inValues().get(0).getAliasedValue();
    if (!argument.isPhi() && argument.definition.isConstClass()) {
      DexType serviceType = argument.definition.asConstClass().getValue();
      if (!appView.appServices().allServiceTypes().contains(serviceType)) {
        // Should never happen.
        if (Log.ENABLED) {
          options.reporter.warning(
              new StringDiagnostic(
                  "The type `"
                      + serviceType.toSourceString()
                      + "` is being passed to the method `"
                      + invoke.getInvokedMethod().toSourceString()
                      + "`, but was not found in `META-INF/services/`.",
                  appInfo.originFor(method.method.holder)));
        }
        return;
      }

      handleServiceInstantiation(serviceType, KeepReason.reflectiveUseIn(method));
    } else {
      KeepReason reason = KeepReason.reflectiveUseIn(method);
      for (DexType serviceType : appView.appServices().allServiceTypes()) {
        handleServiceInstantiation(serviceType, reason);
      }
    }
  }

  private void handleServiceInstantiation(DexType serviceType, KeepReason reason) {
    instantiatedAppServices.add(serviceType);

    List<DexType> serviceImplementationTypes =
        appView.appServices().serviceImplementationsFor(serviceType);
    for (DexType serviceImplementationType : serviceImplementationTypes) {
      if (!serviceImplementationType.isClassType()) {
        // Should never happen.
        continue;
      }

      DexProgramClass serviceImplementationClass = getProgramClassOrNull(serviceImplementationType);
      if (serviceImplementationClass != null && serviceImplementationClass.isProgramClass()) {
        markClassAsInstantiatedWithReason(serviceImplementationClass, reason);
      }
    }
  }

  private static class SetWithReason<T> {

    private final Set<T> items = Sets.newIdentityHashSet();

    private final BiConsumer<T, KeepReason> register;

    public SetWithReason(BiConsumer<T, KeepReason> register) {
      this.register = register;
    }

    boolean add(T item, KeepReason reason) {
      register.accept(item, reason);
      return items.add(item);
    }

    boolean contains(T item) {
      return items.contains(item);
    }

    Set<T> getItems() {
      return Collections.unmodifiableSet(items);
    }
  }

  private static class SetWithStoredReason<T> extends SetWithReason<T> {
    private final Map<T, Set<KeepReason>> reasons;

    static <T> SetWithStoredReason<T> create() {
      final Map<T, Set<KeepReason>> reasons = new IdentityHashMap<>();
      return new SetWithStoredReason<>(register(reasons), reasons);
    }

    private SetWithStoredReason(
        BiConsumer<T, KeepReason> register, Map<T, Set<KeepReason>> reasons) {
      super(register);
      this.reasons = reasons;
    }

    private static <T> BiConsumer<T, KeepReason> register(Map<T, Set<KeepReason>> reasons) {
      return (T item, KeepReason reason) ->
          reasons.computeIfAbsent(item, k -> new HashSet<>()).add(reason);
    }

    public Set<KeepReason> getReasons(T item) {
      return reasons.get(item);
    }
  }

  private static final class TargetWithContext<T extends Descriptor<?, T>> {

    private final T target;
    private final DexEncodedMethod context;

    private TargetWithContext(T target, DexEncodedMethod context) {
      this.target = target;
      this.context = context;
    }

    public T getTarget() {
      return target;
    }

    public DexEncodedMethod getContext() {
      return context;
    }

    @Override
    public int hashCode() {
      return target.hashCode() * 31 + context.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof TargetWithContext)) {
        return false;
      }
      TargetWithContext other = (TargetWithContext) obj;
      return (this.target == other.target) && (this.context == other.context);
    }
  }

  private class AnnotationReferenceMarker implements IndexedItemCollection {

    private final DexItem annotationHolder;
    private final DexItemFactory dexItemFactory;
    private final KeepReason reason;

    private AnnotationReferenceMarker(
        DexItem annotationHolder, DexItemFactory dexItemFactory, KeepReason reason) {
      this.annotationHolder = annotationHolder;
      this.dexItemFactory = dexItemFactory;
      this.reason = reason;
    }

    @Override
    public boolean addClass(DexProgramClass dexProgramClass) {
      return false;
    }

    @Override
    public boolean addField(DexField field) {
      DexClass holder = appView.definitionFor(field.holder);
      if (holder == null) {
        return false;
      }
      DexEncodedField target = holder.lookupStaticField(field);
      if (target != null) {
        // There is no dispatch on annotations, so only keep what is directly referenced.
        if (target.field == field) {
          if (!registerFieldRead(field, DexEncodedMethod.ANNOTATION_REFERENCE)) {
            return false;
          }
          markStaticFieldAsLive(target, KeepReason.referencedInAnnotation(annotationHolder));
          // When an annotation has a field of an enum type with a default value then Java VM
          // will use the values() method on that enum class.
          if (options.isGeneratingClassFiles()
              && annotationHolder == dexItemFactory.annotationDefault) {
            DexClass clazz = appView.definitionFor(field.type);
            if (clazz != null && clazz.isProgramClass() && clazz.accessFlags.isEnum()) {
              markEnumValuesAsReachable(clazz, KeepReason.referencedInAnnotation(annotationHolder));
            }
          }
        }
      } else {
        target = holder.lookupInstanceField(field);
        // There is no dispatch on annotations, so only keep what is directly referenced.
        if (target != null && target.field != field) {
          markInstanceFieldAsReachable(target, KeepReason.referencedInAnnotation(annotationHolder));
        }
      }
      return false;
    }

    @Override
    public boolean addMethod(DexMethod method) {
      DexProgramClass holder = getProgramClassOrNull(method.holder);
      if (holder == null) {
        return false;
      }
      DexEncodedMethod target = holder.lookupDirectMethod(method);
      if (target != null) {
        // There is no dispatch on annotations, so only keep what is directly referenced.
        if (target.method == method) {
          markDirectStaticOrConstructorMethodAsLive(
              holder, target, KeepReason.referencedInAnnotation(annotationHolder));
        }
      } else {
        target = holder.lookupVirtualMethod(method);
        // There is no dispatch on annotations, so only keep what is directly referenced.
        if (target != null && target.method == method) {
          markMethodAsTargeted(holder, target, KeepReason.referencedInAnnotation(annotationHolder));
        }
      }
      return false;
    }

    @Override
    public boolean addString(DexString string) {
      return false;
    }

    @Override
    public boolean addProto(DexProto proto) {
      return false;
    }

    @Override
    public boolean addCallSite(DexCallSite callSite) {
      return false;
    }

    @Override
    public boolean addMethodHandle(DexMethodHandle methodHandle) {
      return false;
    }

    @Override
    public boolean addType(DexType type) {
      // Annotations can also contain the void type, which is not a class type, so filter it out
      // here.
      if (type != dexItemFactory.voidType) {
        markTypeAsLive(type, reason);
      }
      return false;
    }
  }

  class GraphReporter {

    private EdgeKind reportPrecondition(KeepRuleGraphNode keepRuleGraphNode) {
      if (keepRuleGraphNode.getPreconditions().isEmpty()) {
        return EdgeKind.KeepRule;
      }
      for (GraphNode precondition : keepRuleGraphNode.getPreconditions()) {
        reportEdge(precondition, keepRuleGraphNode, EdgeKind.KeepRulePrecondition);
      }
      return EdgeKind.ConditionalKeepRule;
    }

    KeepReasonWitness reportKeepClass(
        DexDefinition precondition, ProguardKeepRuleBase rule, DexProgramClass clazz) {
      if (keptGraphConsumer == null) {
        return KeepReasonWitness.INSTANCE;
      }
      KeepRuleGraphNode ruleNode = getKeepRuleGraphNode(precondition, rule);
      EdgeKind edgeKind = reportPrecondition(ruleNode);
      return reportEdge(ruleNode, getClassGraphNode(clazz.type), edgeKind);
    }

    KeepReasonWitness reportKeepClass(
        DexDefinition precondition, Collection<ProguardKeepRuleBase> rules, DexProgramClass clazz) {
      assert !rules.isEmpty();
      if (keptGraphConsumer != null) {
        for (ProguardKeepRuleBase rule : rules) {
          reportKeepClass(precondition, rule, clazz);
        }
      }
      return KeepReasonWitness.INSTANCE;
    }

    KeepReasonWitness reportKeepMethod(
        DexDefinition precondition, ProguardKeepRuleBase rule, DexEncodedMethod method) {
      if (keptGraphConsumer == null) {
        return KeepReasonWitness.INSTANCE;
      }
      KeepRuleGraphNode ruleNode = getKeepRuleGraphNode(precondition, rule);
      EdgeKind edgeKind = reportPrecondition(ruleNode);
      return reportEdge(ruleNode, getMethodGraphNode(method.method), edgeKind);
    }

    KeepReasonWitness reportKeepMethod(
        DexDefinition precondition,
        Collection<ProguardKeepRuleBase> rules,
        DexEncodedMethod method) {
      assert !rules.isEmpty();
      if (keptGraphConsumer != null) {
        for (ProguardKeepRuleBase rule : rules) {
          reportKeepMethod(precondition, rule, method);
        }
      }
      return KeepReasonWitness.INSTANCE;
    }

    KeepReasonWitness reportKeepField(
        DexDefinition precondition, ProguardKeepRuleBase rule, DexEncodedField field) {
      if (keptGraphConsumer == null) {
        return KeepReasonWitness.INSTANCE;
      }
      KeepRuleGraphNode ruleNode = getKeepRuleGraphNode(precondition, rule);
      EdgeKind edgeKind = reportPrecondition(ruleNode);
      return reportEdge(ruleNode, getFieldGraphNode(field.field), edgeKind);
    }

    KeepReasonWitness reportKeepField(
        DexDefinition precondition, Collection<ProguardKeepRuleBase> rules, DexEncodedField field) {
      assert !rules.isEmpty();
      if (keptGraphConsumer != null) {
        for (ProguardKeepRuleBase rule : rules) {
          reportKeepField(precondition, rule, field);
        }
      }
      return KeepReasonWitness.INSTANCE;
    }

    public KeepReasonWitness reportClassReferencedFrom(
        DexProgramClass clazz, DexEncodedMethod method) {
      if (keptGraphConsumer != null) {
        MethodGraphNode source = getMethodGraphNode(method.method);
        ClassGraphNode target = getClassGraphNode(clazz.type);
        return reportEdge(source, target, EdgeKind.ReferencedFrom);
      }
      return KeepReasonWitness.INSTANCE;
    }

    private KeepReasonWitness reportEdge(
        GraphNode source, GraphNode target, GraphEdgeInfo.EdgeKind kind) {
      assert keptGraphConsumer != null;
      keptGraphConsumer.acceptEdge(source, target, getEdgeInfo(kind));
      return KeepReasonWitness.INSTANCE;
    }
  }

  /**
   * Sentinel value indicating that a keep reason has been reported.
   *
   * <p>Should only ever be returned by the register* function below.
   */
  private static class KeepReasonWitness extends KeepReason {

    private static KeepReasonWitness INSTANCE = new KeepReasonWitness();

    @Override
    public EdgeKind edgeKind() {
      throw new Unreachable();
    }

    @Override
    public GraphNode getSourceNode(Enqueuer enqueuer) {
      throw new Unreachable();
    }
  }

  private boolean skipReporting(KeepReason reason) {
    assert reason != null;
    if (reason == KeepReasonWitness.INSTANCE) {
      return true;
    }
    assert getSourceNode(reason) != null;
    return keptGraphConsumer == null;
  }

  private KeepReasonWitness registerType(DexType type, KeepReason reason) {
    if (skipReporting(reason)) {
      return KeepReasonWitness.INSTANCE;
    }
    return registerEdge(getClassGraphNode(type), reason);
  }

  private KeepReasonWitness registerInterface(DexProgramClass iface, KeepReason reason) {
    assert iface.isInterface();
    if (skipReporting(reason)) {
      return KeepReasonWitness.INSTANCE;
    }
    return registerEdge(getClassGraphNode(iface.type), reason);
  }

  private KeepReasonWitness registerClass(DexProgramClass clazz, KeepReason reason) {
    if (skipReporting(reason)) {
      return KeepReasonWitness.INSTANCE;
    }
    return registerEdge(getClassGraphNode(clazz.type), reason);
  }

  private KeepReasonWitness registerAnnotation(DexAnnotation annotation, KeepReason reason) {
    if (skipReporting(reason)) {
      return KeepReasonWitness.INSTANCE;
    }
    return registerEdge(getAnnotationGraphNode(annotation.annotation.type), reason);
  }

  private KeepReasonWitness registerMethod(
      DexEncodedMethod method, Collection<KeepReason> reasons) {
    assert !reasons.isEmpty();
    if (keptGraphConsumer != null) {
      for (KeepReason reason : reasons) {
        registerMethod(method, reason);
      }
    }
    return KeepReasonWitness.INSTANCE;
  }

  private KeepReasonWitness registerMethod(DexEncodedMethod method, KeepReason reason) {
    if (skipReporting(reason)) {
      return KeepReasonWitness.INSTANCE;
    }
    if (reason.edgeKind() == EdgeKind.IsLibraryMethod && isNonProgramClass(method.method.holder)) {
      // Don't report edges to actual library methods.
      // TODO(b/120959039): This should be dead code once no library classes are ever enqueued.
      return KeepReasonWitness.INSTANCE;
    }
    return registerEdge(getMethodGraphNode(method.method), reason);
  }

  private KeepReasonWitness registerField(DexEncodedField field, KeepReason reason) {
    if (skipReporting(reason)) {
      return KeepReasonWitness.INSTANCE;
    }
    return registerEdge(getFieldGraphNode(field.field), reason);
  }

  private KeepReasonWitness registerEdge(GraphNode target, KeepReason reason) {
    assert !skipReporting(reason);
    GraphNode sourceNode = getSourceNode(reason);
    // TODO(b/120959039): Make sure we do have edges to nodes deriving library nodes!
    if (!sourceNode.isLibraryNode()) {
      GraphEdgeInfo edgeInfo = getEdgeInfo(reason);
      keptGraphConsumer.acceptEdge(sourceNode, target, edgeInfo);
    }
    return KeepReasonWitness.INSTANCE;
  }

  private boolean isNonProgramClass(DexType type) {
    DexClass clazz = appView.definitionFor(type);
    return clazz == null || clazz.isNotProgramClass();
  }

  private GraphNode getSourceNode(KeepReason reason) {
    return reason.getSourceNode(this);
  }

  public GraphNode getGraphNode(DexReference reference) {
    if (reference.isDexType()) {
      return getClassGraphNode(reference.asDexType());
    }
    if (reference.isDexMethod()) {
      return getMethodGraphNode(reference.asDexMethod());
    }
    if (reference.isDexField()) {
      return getFieldGraphNode(reference.asDexField());
    }
    throw new Unreachable();
  }

  GraphEdgeInfo getEdgeInfo(KeepReason reason) {
    return getEdgeInfo(reason.edgeKind());
  }

  GraphEdgeInfo getEdgeInfo(GraphEdgeInfo.EdgeKind kind) {
    return reasonInfo.computeIfAbsent(kind, k -> new GraphEdgeInfo(k));
  }

  AnnotationGraphNode getAnnotationGraphNode(DexItem type) {
    return annotationNodes.computeIfAbsent(type, t -> {
      if (t instanceof DexType) {
        return new AnnotationGraphNode(getClassGraphNode(((DexType) t)));
      }
      throw new Unimplemented("Incomplete support for annotation node on item: " + type.getClass());
    });
  }

  ClassGraphNode getClassGraphNode(DexType type) {
    return classNodes.computeIfAbsent(
        type,
        t -> {
          DexClass definition = appView.definitionFor(t);
          return new ClassGraphNode(
              definition != null && definition.isNotProgramClass(),
              Reference.classFromDescriptor(t.toDescriptorString()));
        });
  }

  MethodGraphNode getMethodGraphNode(DexMethod context) {
    return methodNodes.computeIfAbsent(
        context,
        m -> {
          DexClass holderDefinition = appView.definitionFor(context.holder);
          Builder<TypeReference> builder = ImmutableList.builder();
          for (DexType param : m.proto.parameters.values) {
            builder.add(Reference.typeFromDescriptor(param.toDescriptorString()));
          }
          return new MethodGraphNode(
              holderDefinition != null && holderDefinition.isNotProgramClass(),
              Reference.method(
                  Reference.classFromDescriptor(m.holder.toDescriptorString()),
                  m.name.toString(),
                  builder.build(),
                  m.proto.returnType.isVoidType()
                      ? null
                      : Reference.typeFromDescriptor(m.proto.returnType.toDescriptorString())));
        });
  }

  FieldGraphNode getFieldGraphNode(DexField context) {
    return fieldNodes.computeIfAbsent(
        context,
        f -> {
          DexClass holderDefinition = appView.definitionFor(context.holder);
          return new FieldGraphNode(
              holderDefinition != null && holderDefinition.isNotProgramClass(),
              Reference.field(
                  Reference.classFromDescriptor(f.holder.toDescriptorString()),
                  f.name.toString(),
                  Reference.typeFromDescriptor(f.type.toDescriptorString())));
        });
  }

  // Due to the combined encoding of dependent rules, ala keepclassmembers and conditional keep
  // rules the conversion to a keep-rule graph node can be one of three forms:
  // 1. A non-dependent keep rule. In this case precondtion == null and the rule is not an if-rule.
  // 2. A dependent keep rule. In this case precondtion != null and rule is not an if-rule.
  // 3. A conditional keep rule. In this case rule is an if-rule, but precondition may or may not be
  //    null. In the non-null case, the precondition is the type the consequent may depend on,
  //    say T for the consequent "-keep T { f; }". It is *not* the precondition of the conditional
  //    rule.
  KeepRuleGraphNode getKeepRuleGraphNode(DexDefinition precondition, ProguardKeepRuleBase rule) {
    if (rule instanceof ProguardKeepRule) {
      Set<GraphNode> preconditions =
          precondition != null
              ? Collections.singleton(getGraphNode(precondition.toReference()))
              : Collections.emptySet();
      return ruleNodes.computeIfAbsent(rule, key -> new KeepRuleGraphNode(rule, preconditions));
    }
    if (rule instanceof ProguardIfRule) {
      ProguardIfRule ifRule = (ProguardIfRule) rule;
      assert !ifRule.getPreconditions().isEmpty();
      return ruleNodes.computeIfAbsent(
          ifRule,
          key -> {
            Set<GraphNode> preconditions = new HashSet<>(ifRule.getPreconditions().size());
            for (DexReference condition : ifRule.getPreconditions()) {
              preconditions.add(getGraphNode(condition));
            }
            return new KeepRuleGraphNode(ifRule, preconditions);
          });
    }
    throw new Unreachable("Unexpected type of keep rule: " + rule);
  }
}
