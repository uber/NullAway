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

import com.google.auto.value.AutoValue;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.fixserialization.FixSerializationConfig;
import com.uber.nullaway.fixserialization.adapters.SerializationAdapter;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * provides nullability configuration based on additional flags passed to ErrorProne via
 * "-XepOpt:[Namespace:]FlagName[=Value]". See. http://errorprone.info/docs/flags
 */
final class ErrorProneCLIFlagsConfig implements Config {

  static final String EP_FL_NAMESPACE = "NullAway";
  static final String FL_ANNOTATED_PACKAGES = EP_FL_NAMESPACE + ":AnnotatedPackages";
  static final String FL_ASSERTS_ENABLED = EP_FL_NAMESPACE + ":AssertsEnabled";
  static final String FL_UNANNOTATED_SUBPACKAGES = EP_FL_NAMESPACE + ":UnannotatedSubPackages";
  static final String FL_ONLY_NULLMARKED = EP_FL_NAMESPACE + ":OnlyNullMarked";
  static final String FL_CLASSES_TO_EXCLUDE = EP_FL_NAMESPACE + ":ExcludedClasses";
  static final String FL_EXHAUSTIVE_OVERRIDE = EP_FL_NAMESPACE + ":ExhaustiveOverride";
  static final String FL_KNOWN_INITIALIZERS = EP_FL_NAMESPACE + ":KnownInitializers";
  static final String FL_CLASS_ANNOTATIONS_TO_EXCLUDE =
      EP_FL_NAMESPACE + ":ExcludedClassAnnotations";
  static final String FL_SUGGEST_SUPPRESSIONS = EP_FL_NAMESPACE + ":SuggestSuppressions";

  static final String FL_CLASS_ANNOTATIONS_GENERATED =
      EP_FL_NAMESPACE + ":CustomGeneratedCodeAnnotations";
  static final String FL_GENERATED_UNANNOTATED = EP_FL_NAMESPACE + ":TreatGeneratedAsUnannotated";
  static final String FL_ACKNOWLEDGE_ANDROID_RECENT = EP_FL_NAMESPACE + ":AcknowledgeAndroidRecent";
  static final String FL_JSPECIFY_MODE = EP_FL_NAMESPACE + ":JSpecifyMode";
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

  static final String FL_SKIP_LIBRARY_MODELS = EP_FL_NAMESPACE + ":IgnoreLibraryModelsFor";

  static final String FL_EXTRA_FUTURES = EP_FL_NAMESPACE + ":ExtraFuturesClasses";

  /** --- JarInfer configs --- */
  static final String FL_JI_ENABLED = EP_FL_NAMESPACE + ":JarInferEnabled";

  static final String FL_ERROR_URL = EP_FL_NAMESPACE + ":ErrorURL";

  /** --- Serialization configs --- */
  static final String FL_FIX_SERIALIZATION = EP_FL_NAMESPACE + ":SerializeFixMetadata";

  static final String FL_FIX_SERIALIZATION_VERSION =
      EP_FL_NAMESPACE + ":SerializeFixMetadataVersion";

  static final String FL_FIX_SERIALIZATION_CONFIG_PATH =
      EP_FL_NAMESPACE + ":FixSerializationConfigPath";

  static final String FL_LEGACY_ANNOTATION_LOCATION =
      EP_FL_NAMESPACE + ":LegacyAnnotationLocations";

  static final String ANNOTATED_PACKAGES_ONLY_NULLMARKED_ERROR_MSG =
      "DO NOT report an issue to Error Prone for this crash!  NullAway configuration is "
          + "incorrect.  "
          + "Must either specify annotated packages, using the "
          + "-XepOpt:"
          + FL_ANNOTATED_PACKAGES
          + "=[...] flag, or pass -XepOpt:"
          + FL_ONLY_NULLMARKED
          + " (but not both).  See https://github.com/uber/NullAway/wiki/Configuration for details. "
          + "If you feel you have gotten this message in error report an issue"
          + " at https://github.com/uber/NullAway/issues.";

  private static final String DELIMITER = ",";

  static final ImmutableSet<String> DEFAULT_CLASS_ANNOTATIONS_TO_EXCLUDE =
      ImmutableSet.of("lombok.Generated");

  // Annotations with simple name ".Generated" need not be manually listed, and are always matched
  // by default
  // TODO: org.apache.avro.specific.AvroGenerated should go here, but we are skipping it for the
  // next release to better test the effect of this feature (users can always manually configure
  // it).
  static final ImmutableSet<String> DEFAULT_CLASS_ANNOTATIONS_GENERATED = ImmutableSet.of();

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
          "android.support.multidex.Application.onCreate",
          // Apache Flink
          // See docs:
          // https://nightlies.apache.org/flink/flink-docs-master/api/java/org/apache/flink/api/common/functions/RichFunction.html#open-org.apache.flink.api.common.functions.OpenContext-
          "org.apache.flink.api.common.functions.RichFunction.open");

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
          "org.springframework.beans.factory.annotation.Autowired",
          "org.springframework.boot.test.mock.mockito.MockBean",
          "org.springframework.boot.test.mock.mockito.SpyBean");

  private static final String DEFAULT_URL = "http://t.uber.com/nullaway";

  /**
   * Packages that we assume have appropriate nullability annotations.
   *
   * <p>When we see an invocation to a method of a class outside these packages, we optimistically
   * assume all parameters are @Nullable and the return value is @NonNull
   */
  private final Pattern annotatedPackages;

  /**
   * Sub-packages without appropriate nullability annotations.
   *
   * <p>Used to exclude a particular package that contains unannotated code within a larger,
   * properly annotated, package.
   */
  private final Pattern unannotatedSubPackages;

  /** Source code in these classes will not be analyzed for nullability issues */
  private final @Nullable ImmutableSet<String> sourceClassesToExclude;

  /**
   * these classes will be treated as unannotated (don't analyze *and* treat methods as unannotated)
   */
  private final @Nullable ImmutableSet<String> unannotatedClasses;

  private final Pattern fieldAnnotPattern;
  private final boolean isExhaustiveOverride;
  private final boolean isSuggestSuppressions;
  private final boolean isAcknowledgeRestrictive;
  private final boolean checkOptionalEmptiness;
  private final boolean checkContracts;
  private final boolean handleTestAssertionLibraries;
  private final ImmutableSet<String> optionalClassPaths;
  private final boolean assertsEnabled;
  private final boolean treatGeneratedAsUnannotated;
  private final boolean acknowledgeAndroidRecent;
  private final boolean jspecifyMode;
  private final boolean legacyAnnotationLocation;
  private final ImmutableSet<MethodClassAndName> knownInitializers;
  private final ImmutableSet<String> excludedClassAnnotations;
  private final ImmutableSet<String> generatedCodeAnnotations;
  private final ImmutableSet<String> initializerAnnotations;
  private final ImmutableSet<String> externalInitAnnotations;
  private final ImmutableSet<String> contractAnnotations;
  private final @Nullable String castToNonNullMethod;
  private final String autofixSuppressionComment;
  private final ImmutableSet<String> skippedLibraryModels;
  private final ImmutableSet<String> extraFuturesClasses;

  /** --- JarInfer configs --- */
  private final boolean jarInferEnabled;

  private final String errorURL;

  /** --- Fully qualified names of custom nonnull/nullable annotation --- */
  private final ImmutableSet<String> customNonnullAnnotations;

  private final ImmutableSet<String> customNullableAnnotations;

  /**
   * If active, NullAway will write all reporting errors in output directory. The output directory
   * along with the activation status of other serialization features are stored in {@link
   * FixSerializationConfig}.
   */
  private final boolean serializationActivationFlag;

  private final FixSerializationConfig fixSerializationConfig;

  ErrorProneCLIFlagsConfig(ErrorProneFlags flags) {
    boolean annotatedPackagesPassed = flags.get(FL_ANNOTATED_PACKAGES).isPresent();
    boolean onlyNullMarked = flags.getBoolean(FL_ONLY_NULLMARKED).orElse(false);
    // exactly one of AnnotatedPackages or OnlyNullMarked should be passed in
    if ((!annotatedPackagesPassed && !onlyNullMarked)
        || (annotatedPackagesPassed && onlyNullMarked)) {
      throw new IllegalStateException(ANNOTATED_PACKAGES_ONLY_NULLMARKED_ERROR_MSG);
    }
    annotatedPackages = getPackagePattern(getFlagStringSet(flags, FL_ANNOTATED_PACKAGES));
    unannotatedSubPackages = getPackagePattern(getFlagStringSet(flags, FL_UNANNOTATED_SUBPACKAGES));
    sourceClassesToExclude = getFlagStringSet(flags, FL_CLASSES_TO_EXCLUDE);
    unannotatedClasses = getFlagStringSet(flags, FL_UNANNOTATED_CLASSES);
    knownInitializers =
        getFlagStringSet(flags, FL_KNOWN_INITIALIZERS, DEFAULT_KNOWN_INITIALIZERS).stream()
            .map(MethodClassAndName::fromClassDotMethod)
            .collect(ImmutableSet.toImmutableSet());
    excludedClassAnnotations =
        getFlagStringSet(
            flags, FL_CLASS_ANNOTATIONS_TO_EXCLUDE, DEFAULT_CLASS_ANNOTATIONS_TO_EXCLUDE);
    generatedCodeAnnotations =
        getFlagStringSet(
            flags, FL_CLASS_ANNOTATIONS_GENERATED, DEFAULT_CLASS_ANNOTATIONS_GENERATED);
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
    jspecifyMode = flags.getBoolean(FL_JSPECIFY_MODE).orElse(false);
    assertsEnabled = flags.getBoolean(FL_ASSERTS_ENABLED).orElse(false);
    fieldAnnotPattern =
        getPackagePattern(
            getFlagStringSet(flags, FL_EXCLUDED_FIELD_ANNOT, DEFAULT_EXCLUDED_FIELD_ANNOT));
    castToNonNullMethod = flags.get(FL_CTNN_METHOD).orElse(null);
    legacyAnnotationLocation = flags.getBoolean(FL_LEGACY_ANNOTATION_LOCATION).orElse(false);
    if (legacyAnnotationLocation && jspecifyMode) {
      throw new IllegalStateException(
          "-XepOpt:"
              + FL_LEGACY_ANNOTATION_LOCATION
              + " cannot be used when "
              + FL_JSPECIFY_MODE
              + " is set ");
    }
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
    skippedLibraryModels = getFlagStringSet(flags, FL_SKIP_LIBRARY_MODELS);
    extraFuturesClasses = getFlagStringSet(flags, FL_EXTRA_FUTURES);

    /* --- JarInfer configs --- */
    jarInferEnabled = flags.getBoolean(FL_JI_ENABLED).orElse(false);
    errorURL = flags.get(FL_ERROR_URL).orElse(DEFAULT_URL);
    if (acknowledgeAndroidRecent && !isAcknowledgeRestrictive) {
      throw new IllegalStateException(
          "-XepOpt:"
              + FL_ACKNOWLEDGE_ANDROID_RECENT
              + " should only be set when -XepOpt:"
              + FL_ACKNOWLEDGE_RESTRICTIVE
              + " is also set");
    }
    serializationActivationFlag = flags.getBoolean(FL_FIX_SERIALIZATION).orElse(false);
    Optional<String> fixSerializationConfigPath = flags.get(FL_FIX_SERIALIZATION_CONFIG_PATH);
    if (serializationActivationFlag && !fixSerializationConfigPath.isPresent()) {
      throw new IllegalStateException(
          "DO NOT report an issue to Error Prone for this crash!  NullAway Fix Serialization configuration is "
              + "incorrect.  "
              + "Must specify AutoFixer Output Directory, using the "
              + "-XepOpt:"
              + FL_FIX_SERIALIZATION_CONFIG_PATH
              + " flag.  If you feel you have gotten this message in error report an issue"
              + " at https://github.com/uber/NullAway/issues.");
    }
    int serializationVersion =
        flags.getInteger(FL_FIX_SERIALIZATION_VERSION).orElse(SerializationAdapter.LATEST_VERSION);
    /*
     * if fixSerializationActivationFlag is false, the default constructor is invoked for
     * creating FixSerializationConfig which all features are deactivated.  This lets the
     * field be @Nonnull, allowing us to avoid null checks in various places.
     */
    fixSerializationConfig =
        serializationActivationFlag && fixSerializationConfigPath.isPresent()
            ? new FixSerializationConfig(fixSerializationConfigPath.get(), serializationVersion)
            : new FixSerializationConfig();
    if (serializationActivationFlag && isSuggestSuppressions) {
      throw new IllegalStateException(
          "In order to activate Fix Serialization mode ("
              + FL_FIX_SERIALIZATION
              + "), Suggest Suppressions mode must be deactivated ("
              + FL_SUGGEST_SUPPRESSIONS
              + ")");
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

  private static final Pattern getPackagePattern(ImmutableSet<String> packagePrefixes) {
    // noinspection ConstantConditions
    String choiceRegexp =
        Joiner.on("|")
            .join(Iterables.transform(packagePrefixes, input -> input.replaceAll("\\.", "\\\\.")));
    return Pattern.compile("^(?:" + choiceRegexp + ")(?:\\..*)?");
  }

  @Override
  public boolean serializationIsActive() {
    return serializationActivationFlag;
  }

  @Override
  public FixSerializationConfig getSerializationConfig() {
    Preconditions.checkArgument(
        serializationActivationFlag, "Fix Serialization is not active, cannot access it's config.");
    return fixSerializationConfig;
  }

  @Override
  public boolean fromExplicitlyAnnotatedPackage(String className) {
    return annotatedPackages.matcher(className).matches();
  }

  @Override
  public boolean fromExplicitlyUnannotatedPackage(String className) {
    return unannotatedSubPackages.matcher(className).matches();
  }

  @Override
  public boolean treatGeneratedAsUnannotated() {
    return treatGeneratedAsUnannotated;
  }

  @Override
  public boolean isExcludedClass(String className) {
    if (sourceClassesToExclude == null) {
      return false;
    }
    for (String classPrefix : sourceClassesToExclude) {
      if (className.startsWith(classPrefix)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean isUnannotatedClass(Symbol.ClassSymbol symbol) {
    if (unannotatedClasses == null) {
      return false;
    }
    String className = symbol.getQualifiedName().toString();
    for (String classPrefix : unannotatedClasses) {
      if (className.startsWith(classPrefix)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public ImmutableSet<String> getExcludedClassAnnotations() {
    return excludedClassAnnotations;
  }

  @Override
  public ImmutableSet<String> getGeneratedCodeAnnotations() {
    return generatedCodeAnnotations;
  }

  @Override
  public boolean isInitializerMethodAnnotation(String annotationName) {
    return initializerAnnotations.contains(annotationName);
  }

  @Override
  public boolean isCustomNullableAnnotation(String annotationName) {
    return customNullableAnnotations.contains(annotationName);
  }

  @Override
  public boolean isCustomNonnullAnnotation(String annotationName) {
    return customNonnullAnnotations.contains(annotationName);
  }

  @Override
  public boolean exhaustiveOverride() {
    return isExhaustiveOverride;
  }

  @Override
  public boolean isKnownInitializerMethod(Symbol.MethodSymbol methodSymbol) {
    Symbol.ClassSymbol enclosingClass = ASTHelpers.enclosingClass(methodSymbol);
    if (enclosingClass == null) {
      return false;
    }
    MethodClassAndName classAndName =
        MethodClassAndName.create(
            enclosingClass.getQualifiedName().toString(), methodSymbol.getSimpleName().toString());
    return knownInitializers.contains(classAndName);
  }

  @Override
  public boolean isExcludedFieldAnnotation(String annotationName) {
    return Nullness.isNullableAnnotation(annotationName, this)
        || (fieldAnnotPattern != null && fieldAnnotPattern.matcher(annotationName).matches());
  }

  @Override
  public boolean suggestSuppressions() {
    return isSuggestSuppressions;
  }

  @Override
  public boolean acknowledgeRestrictiveAnnotations() {
    return isAcknowledgeRestrictive;
  }

  @Override
  public boolean checkOptionalEmptiness() {
    return checkOptionalEmptiness;
  }

  @Override
  public boolean checkContracts() {
    return checkContracts;
  }

  @Override
  public boolean handleTestAssertionLibraries() {
    return handleTestAssertionLibraries;
  }

  @Override
  public ImmutableSet<String> getOptionalClassPaths() {
    return optionalClassPaths;
  }

  @Override
  public boolean assertsEnabled() {
    return assertsEnabled;
  }

  @Override
  public @Nullable String getCastToNonNullMethod() {
    return castToNonNullMethod;
  }

  @Override
  public String getAutofixSuppressionComment() {
    if (autofixSuppressionComment.trim().length() > 0) {
      return "/* " + autofixSuppressionComment + " */ ";
    } else {
      return "";
    }
  }

  @Override
  public boolean isExternalInitClassAnnotation(String annotationName) {
    return externalInitAnnotations.contains(annotationName);
  }

  @Override
  public boolean isContractAnnotation(String annotationName) {
    return contractAnnotations.contains(annotationName);
  }

  @Override
  public boolean isSkippedLibraryModel(String classDotMethod) {
    return skippedLibraryModels.contains(classDotMethod);
  }

  @Override
  public ImmutableSet<String> getExtraFuturesClasses() {
    return extraFuturesClasses;
  }

  /** --- JarInfer configs --- */
  @Override
  public boolean isJarInferEnabled() {
    return jarInferEnabled;
  }

  @Override
  public String getErrorURL() {
    return errorURL;
  }

  @Override
  public boolean acknowledgeAndroidRecent() {
    return acknowledgeAndroidRecent;
  }

  @Override
  public boolean isJSpecifyMode() {
    return jspecifyMode;
  }

  @Override
  public boolean isLegacyAnnotationLocation() {
    return legacyAnnotationLocation;
  }

  @AutoValue
  abstract static class MethodClassAndName {

    static MethodClassAndName create(String enclosingClass, String methodName) {
      return new AutoValue_ErrorProneCLIFlagsConfig_MethodClassAndName(enclosingClass, methodName);
    }

    static MethodClassAndName fromClassDotMethod(String classDotMethod) {
      int lastDot = classDotMethod.lastIndexOf('.');
      String methodName = classDotMethod.substring(lastDot + 1);
      String className = classDotMethod.substring(0, lastDot);
      return MethodClassAndName.create(className, methodName);
    }

    abstract String enclosingClass();

    abstract String methodName();
  }
}
