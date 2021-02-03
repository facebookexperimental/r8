// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.shaking;

import static com.android.tools.r8.ir.desugar.DesugaredLibraryAPIConverter.DESCRIPTOR_VIVIFIED_PREFIX;
import static com.android.tools.r8.ir.desugar.DesugaredLibraryRetargeter.getRetargetPackageAndClassPrefixDescriptor;
import static com.android.tools.r8.ir.desugar.InterfaceMethodRewriter.EMULATE_LIBRARY_CLASS_NAME_SUFFIX;

import com.android.tools.r8.errors.dontwarn.DontWarnConfiguration;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexReference;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.synthesis.CommittedItems;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.SetUtils;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public class MissingClasses {

  private final Set<DexType> missingClasses;

  private MissingClasses(Set<DexType> missingClasses) {
    this.missingClasses = missingClasses;
  }

  public Builder builder() {
    return new Builder(missingClasses);
  }

  public static Builder builderForInitialMissingClasses() {
    return new Builder();
  }

  public static MissingClasses empty() {
    return new MissingClasses(Sets.newIdentityHashSet());
  }

  public MissingClasses commitSyntheticItems(CommittedItems committedItems) {
    return builder()
        // TODO(b/175542052): Synthetic types should not be reported as missing in the first place.
        .removeAlreadyMissingClasses(committedItems.getLegacySyntheticTypes())
        .ignoreMissingClasses();
  }

  public boolean contains(DexType type) {
    return missingClasses.contains(type);
  }

  public static class Builder {

    private final Set<DexType> alreadyMissingClasses;
    private final Map<DexType, Set<DexReference>> newMissingClasses = new IdentityHashMap<>();

    // Set of missing types that are not to be reported as missing. This does not hide reports
    // if the same type is in newMissingClasses in which case it is reported regardless.
    private final Set<DexType> newIgnoredMissingClasses = Sets.newIdentityHashSet();

    private Builder() {
      this(Sets.newIdentityHashSet());
    }

    private Builder(Set<DexType> alreadyMissingClasses) {
      this.alreadyMissingClasses = alreadyMissingClasses;
    }

    public void addNewMissingClass(DexType type, DexReference context) {
      assert context != null;
      assert context.getContextType() != type;
      if (!alreadyMissingClasses.contains(type)) {
        newMissingClasses.computeIfAbsent(type, ignore -> Sets.newIdentityHashSet()).add(context);
      }
    }

    public void legacyAddNewMissingClass(DexType type) {
      if (!alreadyMissingClasses.contains(type)) {
        // The legacy reporting is context insensitive, so therefore we use the missing classes
        // themselves as contexts.
        newMissingClasses.computeIfAbsent(type, ignore -> Sets.newIdentityHashSet()).add(type);
      }
    }

    @Deprecated
    public Builder legacyAddNewMissingClasses(Collection<DexType> types) {
      types.forEach(this::legacyAddNewMissingClass);
      return this;
    }

    public void ignoreNewMissingClass(DexType type) {
      newIgnoredMissingClasses.add(type);
    }

    public boolean contains(DexType type) {
      return alreadyMissingClasses.contains(type) || newMissingClasses.containsKey(type);
    }

    Builder removeAlreadyMissingClasses(Iterable<DexType> types) {
      for (DexType type : types) {
        alreadyMissingClasses.remove(type);
      }
      return this;
    }

    @Deprecated
    public MissingClasses ignoreMissingClasses() {
      return build();
    }

    public MissingClasses reportMissingClasses(AppView<?> appView) {
      InternalOptions options = appView.options();
      Map<DexType, Set<DexReference>> missingClassesToBeReported =
          getMissingClassesToBeReported(appView);
      if (!missingClassesToBeReported.isEmpty()) {
        MissingClassesDiagnostic diagnostic =
            new MissingClassesDiagnostic.Builder()
                .addMissingClasses(missingClassesToBeReported)
                .setFatal(!options.ignoreMissingClasses)
                .build();
        if (options.ignoreMissingClasses) {
          options.reporter.warning(diagnostic);
        } else {
          throw options.reporter.fatalError(diagnostic);
        }
      }
      return build();
    }

    private Map<DexType, Set<DexReference>> getMissingClassesToBeReported(AppView<?> appView) {
      Predicate<DexType> allowedMissingClassesPredicate =
          getIsAllowedMissingClassesPredicate(appView);
      Map<DexType, Set<DexReference>> missingClassesToBeReported =
          new IdentityHashMap<>(newMissingClasses.size());
      newMissingClasses.forEach(
          (missingClass, contexts) -> {
            // Don't report "allowed" missing classes (e.g., classes matched by -dontwarn).
            if (allowedMissingClassesPredicate.test(missingClass)) {
              return;
            }

            // Remove all contexts that are matched by a -dontwarn rule (a missing class should not
            // be reported if it os only referenced from contexts that are matched by a -dontwarn).
            contexts.removeIf(
                context -> appView.getDontWarnConfiguration().matches(context.getContextType()));

            // If there are any contexts not matched by a -dontwarn rule, then report.
            if (!contexts.isEmpty()) {
              missingClassesToBeReported.put(missingClass, contexts);
            }
          });
      return missingClassesToBeReported;
    }

    private static Predicate<DexType> getIsAllowedMissingClassesPredicate(AppView<?> appView) {
      Set<DexType> allowedMissingClasses = getAllowedMissingClasses(appView.dexItemFactory());
      Predicate<DexType> compilerSynthesizedAllowingMissingClassPredicate =
          getIsCompilerSynthesizedAllowedMissingClassesPredicate(appView);
      DontWarnConfiguration dontWarnConfiguration = appView.getDontWarnConfiguration();
      return type ->
          allowedMissingClasses.contains(type)
              || compilerSynthesizedAllowingMissingClassPredicate.test(type)
              || dontWarnConfiguration.matches(type);
    }

    private static Set<DexType> getAllowedMissingClasses(DexItemFactory dexItemFactory) {
      return ImmutableSet.<DexType>builder()
          .add(
              dexItemFactory.annotationDefault,
              dexItemFactory.annotationMethodParameters,
              dexItemFactory.annotationSourceDebugExtension,
              dexItemFactory.annotationSynthesizedClass,
              dexItemFactory.annotationSynthesizedClassMap,
              dexItemFactory.annotationThrows,
              dexItemFactory.serializedLambdaType,
              // TODO(b/176133674) StringConcatFactory is backported, but the class is reported as
              //  missing because the enqueuer runs prior to backporting and thus sees the
              //  non-desugared code.
              dexItemFactory.stringConcatFactoryType)
          .addAll(dexItemFactory.getConversionTypes())
          .build();
    }

    private static Predicate<DexType> getIsCompilerSynthesizedAllowedMissingClassesPredicate(
        AppView<?> appView) {
      DexItemFactory dexItemFactory = appView.dexItemFactory();
      InternalOptions options = appView.options();
      DexString emulatedLibraryClassNameSuffix =
          dexItemFactory.createString(EMULATE_LIBRARY_CLASS_NAME_SUFFIX + ";");
      DexString retargetPackageAndClassPrefixDescriptor =
          dexItemFactory.createString(
              getRetargetPackageAndClassPrefixDescriptor(options.desugaredLibraryConfiguration));
      DexString vivifiedClassNamePrefix = dexItemFactory.createString(DESCRIPTOR_VIVIFIED_PREFIX);
      return type -> {
        DexString descriptor = type.getDescriptor();
        return descriptor.startsWith(retargetPackageAndClassPrefixDescriptor)
            || descriptor.startsWith(vivifiedClassNamePrefix)
            || descriptor.endsWith(emulatedLibraryClassNameSuffix);
      };
    }



    /** Intentionally private, use {@link Builder#reportMissingClasses(AppView)}. */
    private MissingClasses build() {
      // Return the new set of missing classes.
      //
      // We also add newIgnoredMissingClasses to newMissingClasses to be able to assert that we have
      // a closed world after the first round of tree shaking: we should never lookup a class that
      // was not live or missing during the first round of tree shaking.
      // See also AppInfoWithLiveness.definitionFor().
      //
      // Note: At this point, all missing classes in newMissingClasses have already been reported.
      // Thus adding newIgnoredMissingClasses to newMissingClasses will not lead to reports for the
      // classes in newIgnoredMissingClasses.
      return new MissingClasses(
          SetUtils.newIdentityHashSet(
              alreadyMissingClasses, newMissingClasses.keySet(), newIgnoredMissingClasses));
    }

    public boolean wasAlreadyMissing(DexType type) {
      return alreadyMissingClasses.contains(type);
    }
  }
}
