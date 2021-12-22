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

  private static final String BASENAME_REGEX = ".*/([^/]+)\\.[ja]ar$";

  static final String EP_FL_NAMESPACE = "NullAway";
  static final String FL_ANNOTATED_PACKAGES = EP_FL_NAMESPACE + ":AnnotatedPackages";
  static final String FL_ASSERTS_ENABLED = EP_FL_NAMESPACE + ":AssertsEnabled";
  static final String FL_UNANNOTATED_SUBPACKAGES = EP_FL_NAMESPACE + ":UnannotatedSubPackages";
  static final String FL_CLASSES_TO_EXCLUDE = EP_FL_NAMESPACE + ":ExcludedClasses";
  static final String FL_EXHAUSTIVE_OVERRIDE = EP_FL_NAMESPACE + ":ExhaustiveOverride";
  static final String FL_KNOWN_INITIALIZERS = EP_FL_NAMESPACE + ":KnownInitializers";
  static final String FL_CLASS_ANNOTATIONS_TO_EXCLUDE =
      EP_FL_NAMESPACE + ":ExcludedClassAnnotations";
  static final String FL_SUGGEST_SUPPRESSIONS = EP_FL_NAMESPACE + ":SuggestSuppressions";
  static final String FL_GENERATED_UNANNOTATED = EP_FL_NAMESPACE + ":TreatGeneratedAsUnannotated";
  static final String FL_ACKNOWLEDGE_ANDROID_RECENT = EP_FL_NAMESPACE + ":AcknowledgeAndroidRecent";
  static final String FL_EXCLUDED_FIELD_ANNOT = EP_FL_NAMESPACE + ":ExcludedFieldAnnotations";
  static final String FL_INITIALIZER_ANNOT = EP_FL_NAMESPACE + ":CustomInitializerAnnotations";
  static final String FL_NULLABLE_ANNOT = EP_FL_NAMESPACE + ":CustomNullableAnnotations";
  static final String FL_NONNULL_ANNOT = EP_FL_NAMESPACE + ":CustomNonnullAnnotations";
  static final String FL_CTNN_METHOD = EP_FL_NAMESPACE + ":CastToNonNullMethod";
  static final String FL_EXTERNAL_INIT_ANNOT = EP_FL_NAMESPACE + ":ExternalInitAnnotations";
  static final String FL_CONTRACT_ANNOT = EP_FL_NAMESPACE + ":CustomContractAnnotations";
  static final String FL_UNANNOTATED_CLASSES = EP_FL_NAMESPACE + ":UnannotatedClasses";
  static final String FL_ACKNOWLEDGE_RESTRICTIVE =
      EP_FL_NAMESPACE + ":AcknowledgeRestrictiveAnnotations";
  static final String FL_CHECK_OPTIONAL_EMPTINESS = EP_FL_NAMESPACE + ":CheckOptionalEmptiness";
  static final String FL_CHECK_CONTRACTS = EP_FL_NAMESPACE + ":CheckContracts";
  static final String FL_HANDLE_TEST_ASSERTION_LIBRARIES =
      EP_FL_NAMESPACE + ":HandleTestAssertionLibraries";
  static final String FL_OPTIONAL_CLASS_PATHS =
      EP_FL_NAMESPACE + ":CheckOptionalEmptinessCustomClasses";
  static final String FL_SUPPRESS_COMMENT = EP_FL_NAMESPACE + ":AutoFixSuppressionComment";
  /** --- JarInfer configs --- */
  static final String FL_JI_ENABLED = EP_FL_NAMESPACE + ":JarInferEnabled";

  static final String FL_JI_USE_RETURN = EP_FL_NAMESPACE + ":JarInferUseReturnAnnotations";

  static final String FL_JI_REGEX_MODEL_PATH = EP_FL_NAMESPACE + ":JarInferRegexStripModelJar";
  static final String FL_JI_REGEX_CODE_PATH = EP_FL_NAMESPACE + ":JarInferRegexStripCodeJar";
  static final String FL_ERROR_URL = EP_FL_NAMESPACE + ":ErrorURL";

  private static final String DELIMITER = ",";

  static final ImmutableSet<String> DEFAULT_CLASS_ANNOTATIONS_TO_EXCLUDE =
      ImmutableSet.of("lombok.Generated");

  static final ImmutableSet<String> DEFAULT_KNOWN_INITIALIZERS =
      ImmutableSet.of(
          "android.view.View.onFinishInflate",
          "android.app.Service.onCreate",
          "android.app.Activity.onCreate",
          "android.app.Fragment.onCreate",
          "android.app.Fragment.onAttach",
          "android.app.Fragment.onCreateView",
          "android.app.Fragment.onViewCreated",
          "android.app.Application.onCreate",
          "javax.annotation.processing.Processor.init",
          // Support Library v4 - can be removed once AndroidX becomes more popular
          "android.support.v4.app.ActivityCompat.onCreate",
          "android.support.v4.app.Fragment.onCreate",
          "android.support.v4.app.Fragment.onAttach",
          "android.support.v4.app.Fragment.onCreateView",
          "android.support.v4.app.Fragment.onViewCreated",
          // Support Library v4 - can be removed once AndroidX becomes more popular
          "androidx.core.app.ActivityCompat.onCreate",
          "androidx.fragment.app.Fragment.onCreate",
          "androidx.fragment.app.Fragment.onAttach",
          "androidx.fragment.app.Fragment.onCreateView",
          "androidx.fragment.app.Fragment.onActivityCreated",
          "androidx.fragment.app.Fragment.onViewCreated",
          // Multidex app
          "android.support.multidex.Application.onCreate");

  static final ImmutableSet<String> DEFAULT_INITIALIZER_ANNOT =
      ImmutableSet.of(
          "org.junit.Before",
          "org.junit.BeforeClass",
          "org.junit.jupiter.api.BeforeAll",
          "org.junit.jupiter.api.BeforeEach",
          "org.springframework.beans.factory.annotation.Autowired");
  // + Anything with @Initializer as its "simple name"

  static final ImmutableSet<String> DEFAULT_EXTERNAL_INIT_ANNOT = ImmutableSet.of("lombok.Builder");

  static final ImmutableSet<String> DEFAULT_CONTRACT_ANNOT =
      ImmutableSet.of("org.jetbrains.annotations.Contract");

  static final ImmutableSet<String> DEFAULT_EXCLUDED_FIELD_ANNOT =
      ImmutableSet.of(
          "jakarta.inject.Inject", // no explicit initialization when there is dependency injection
          "javax.inject.Inject", // no explicit initialization when there is dependency injection
          "com.google.errorprone.annotations.concurrent.LazyInit",
          "org.checkerframework.checker.nullness.qual.MonotonicNonNull",
          "org.springframework.beans.factory.annotation.Autowired");

  private static final String DEFAULT_URL = "http://t.uber.com/nullaway";

  ErrorProneCLIFlagsConfig(ErrorProneFlags flags) {
    if (!flags.get(FL_ANNOTATED_PACKAGES).isPresent()) {
      throw new IllegalStateException(
          "DO NOT report an issue to Error Prone for this crash!  NullAway configuration is "
              + "incorrect.  "
              + "Must specify annotated packages, using the "
              + "-XepOpt:"
              + FL_ANNOTATED_PACKAGES
              + "=[...] flag.  If you feel you have gotten this message in error report an issue"
              + " at https://github.com/uber/NullAway/issues.");
    }
    annotatedPackages = getPackagePattern(getFlagStringSet(flags, FL_ANNOTATED_PACKAGES));
    unannotatedSubPackages = getPackagePattern(getFlagStringSet(flags, FL_UNANNOTATED_SUBPACKAGES));
    sourceClassesToExclude = getFlagStringSet(flags, FL_CLASSES_TO_EXCLUDE);
    unannotatedClasses = getFlagStringSet(flags, FL_UNANNOTATED_CLASSES);
    knownInitializers =
        getKnownInitializers(
            getFlagStringSet(flags, FL_KNOWN_INITIALIZERS, DEFAULT_KNOWN_INITIALIZERS));
    excludedClassAnnotations =
        getFlagStringSet(
            flags, FL_CLASS_ANNOTATIONS_TO_EXCLUDE, DEFAULT_CLASS_ANNOTATIONS_TO_EXCLUDE);
    initializerAnnotations =
        getFlagStringSet(flags, FL_INITIALIZER_ANNOT, DEFAULT_INITIALIZER_ANNOT);
    customNullableAnnotations = getFlagStringSet(flags, FL_NULLABLE_ANNOT, ImmutableSet.of());
    customNonnullAnnotations = getFlagStringSet(flags, FL_NONNULL_ANNOT, ImmutableSet.of());
    externalInitAnnotations =
        getFlagStringSet(flags, FL_EXTERNAL_INIT_ANNOT, DEFAULT_EXTERNAL_INIT_ANNOT);
    contractAnnotations = getFlagStringSet(flags, FL_CONTRACT_ANNOT, DEFAULT_CONTRACT_ANNOT);
    isExhaustiveOverride = flags.getBoolean(FL_EXHAUSTIVE_OVERRIDE).orElse(false);
    isSuggestSuppressions = flags.getBoolean(FL_SUGGEST_SUPPRESSIONS).orElse(false);
    isAcknowledgeRestrictive = flags.getBoolean(FL_ACKNOWLEDGE_RESTRICTIVE).orElse(false);
    checkOptionalEmptiness = flags.getBoolean(FL_CHECK_OPTIONAL_EMPTINESS).orElse(false);
    checkContracts = flags.getBoolean(FL_CHECK_CONTRACTS).orElse(false);
    handleTestAssertionLibraries =
        flags.getBoolean(FL_HANDLE_TEST_ASSERTION_LIBRARIES).orElse(false);
    treatGeneratedAsUnannotated = flags.getBoolean(FL_GENERATED_UNANNOTATED).orElse(false);
    acknowledgeAndroidRecent = flags.getBoolean(FL_ACKNOWLEDGE_ANDROID_RECENT).orElse(false);
    assertsEnabled = flags.getBoolean(FL_ASSERTS_ENABLED).orElse(false);
    fieldAnnotPattern =
        getPackagePattern(
            getFlagStringSet(flags, FL_EXCLUDED_FIELD_ANNOT, DEFAULT_EXCLUDED_FIELD_ANNOT));
    castToNonNullMethod = flags.get(FL_CTNN_METHOD).orElse(null);
    autofixSuppressionComment = flags.get(FL_SUPPRESS_COMMENT).orElse("");
    optionalClassPaths =
        new ImmutableSet.Builder<String>()
            .addAll(getFlagStringSet(flags, FL_OPTIONAL_CLASS_PATHS))
            .add("java.util.Optional")
            .build();
    if (autofixSuppressionComment.contains("\n")) {
      throw new IllegalStateException(
          "Invalid -XepOpt:" + FL_SUPPRESS_COMMENT + " value. Comment must be single line.");
    }
    /** --- JarInfer configs --- */
    jarInferEnabled = flags.getBoolean(FL_JI_ENABLED).orElse(false);
    jarInferUseReturnAnnotations = flags.getBoolean(FL_JI_USE_RETURN).orElse(false);
    // The defaults of these two options translate to: remove .aar/.jar from the file name, and also
    // implicitly mean that NullAway will search for jarinfer models in the same jar which contains
    // the analyzed classes.
    jarInferRegexStripModelJarName = flags.get(FL_JI_REGEX_MODEL_PATH).orElse(BASENAME_REGEX);
    jarInferRegexStripCodeJarName = flags.get(FL_JI_REGEX_CODE_PATH).orElse(BASENAME_REGEX);
    errorURL = flags.get(FL_ERROR_URL).orElse(DEFAULT_URL);
    if (acknowledgeAndroidRecent && !isAcknowledgeRestrictive) {
      throw new IllegalStateException(
          "-XepOpt:"
              + FL_ACKNOWLEDGE_ANDROID_RECENT
              + " should only be set when -XepOpt:"
              + FL_ACKNOWLEDGE_RESTRICTIVE
              + " is also set");
    }
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
