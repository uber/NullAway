/*
 * Copyright (c) 2017 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.nullaway;

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.ErrorProneFlags;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * provides nullability configuration based on additional flags passed to ErrorProne via
 * "-XepOpt:[Namespace:]FlagName[=Value]". See. http://errorprone.info/docs/flags
 */
final class ErrorProneCLIFlagsConfig extends AbstractConfig {

  static final String EP_FL_NAMESPACE = "NullAway";
  static final String FL_ANNOTATED_PACKAGES = EP_FL_NAMESPACE + ":AnnotatedPackages";
  static final String FL_UNANNOTATED_SUBPACKAGES = EP_FL_NAMESPACE + ":UnannotatedSubPackages";
  static final String FL_CLASSES_TO_EXCLUDE = EP_FL_NAMESPACE + ":ExcludedClasses";
  static final String FL_EXHAUSTIVE_OVERRIDE = EP_FL_NAMESPACE + ":ExhaustiveOverride";
  static final String FL_KNOWN_INITIALIZERS = EP_FL_NAMESPACE + ":KnownInitializers";
  static final String FL_CLASS_ANNOTATIONS_TO_EXCLUDE =
      EP_FL_NAMESPACE + ":ExcludedClassAnnotations";
  static final String FL_SUGGEST_SUPPRESSIONS = EP_FL_NAMESPACE + ":SuggestSuppressions";
  static final String FL_EXCLUDED_FIELD_ANNOT = EP_FL_NAMESPACE + ":ExcludedFieldAnnotations";
  static final String FL_INITIALIZER_ANNOT = EP_FL_NAMESPACE + ":CustomInitializerAnnotations";
  private static final String DELIMITER = ",";

  static final ImmutableSet<String> DEFAULT_KNOWN_INITIALIZERS =
      ImmutableSet.of(
          "android.view.View.onFinishInflate",
          "android.app.Service.onCreate",
          "android.app.Activity.onCreate",
          "android.app.Fragment.onCreate",
          "android.app.Application.onCreate",
          "javax.annotation.processing.Processor.init");
  static final ImmutableSet<String> DEFAULT_INITIALIZER_ANNOT =
      ImmutableSet.of(
          "org.junit.Before",
          "org.junit.BeforeClass"); // + Anything with @Initializer as its "simple name"

  ErrorProneCLIFlagsConfig(ErrorProneFlags flags) {
    if (!flags.get(FL_ANNOTATED_PACKAGES).isPresent()) {
      throw new IllegalStateException(
          "Must specify annotated packages, using the "
              + "-XepOpt:"
              + FL_ANNOTATED_PACKAGES
              + "=[...] flag.");
    }
    annotatedPackages = getPackagePattern(getFlagStringSet(flags, FL_ANNOTATED_PACKAGES));
    unannotatedSubPackages = getPackagePattern(getFlagStringSet(flags, FL_UNANNOTATED_SUBPACKAGES));
    sourceClassesToExclude = getFlagStringSet(flags, FL_CLASSES_TO_EXCLUDE);
    knownInitializers =
        getKnownInitializers(
            getFlagStringSet(flags, FL_KNOWN_INITIALIZERS, DEFAULT_KNOWN_INITIALIZERS));
    excludedClassAnnotations = getFlagStringSet(flags, FL_CLASS_ANNOTATIONS_TO_EXCLUDE);
    initializerAnnotations =
        getFlagStringSet(flags, FL_INITIALIZER_ANNOT, DEFAULT_INITIALIZER_ANNOT);
    isExhaustiveOverride = flags.getBoolean(FL_EXHAUSTIVE_OVERRIDE).orElse(false);
    isSuggestSuppressions = flags.getBoolean(FL_SUGGEST_SUPPRESSIONS).orElse(false);
    ImmutableSet<String> propStrings = getFlagStringSet(flags, FL_EXCLUDED_FIELD_ANNOT);
    fieldAnnotPattern = propStrings.isEmpty() ? null : getPackagePattern(propStrings);
  }

  private static ImmutableSet<String> getFlagStringSet(ErrorProneFlags flags, String flagName) {
    Optional<String> flagValue = flags.get(flagName);
    if (flagValue.isPresent()) {
      return ImmutableSet.copyOf(flagValue.get().split(DELIMITER));
    }
    return ImmutableSet.of();
  }

  private static ImmutableSet<String> getFlagStringSet(
      ErrorProneFlags flags, String flagName, ImmutableSet<String> defaults) {
    Set<String> combined = new LinkedHashSet<>(defaults);
    Optional<String> flagValue = flags.get(flagName);
    if (flagValue.isPresent()) {
      Collections.addAll(combined, flagValue.get().split(DELIMITER));
    }
    return ImmutableSet.copyOf(combined);
  }
}
