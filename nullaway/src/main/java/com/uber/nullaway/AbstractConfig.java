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
import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.fixserialization.FixSerializationConfig;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** abstract base class for null checker {@link Config} implementations */
@SuppressWarnings("NullAway") // TODO: get rid of this class to avoid suppression
public abstract class AbstractConfig implements Config {

  /**
   * Packages that we assume have appropriate nullability annotations.
   *
   * <p>When we see an invocation to a method of a class outside these packages, we optimistically
   * assume all parameters are @Nullable and the return value is @NonNull
   */
  protected Pattern annotatedPackages;

  /**
   * Sub-packages without appropriate nullability annotations.
   *
   * <p>Used to exclude a particular package that contains unannotated code within a larger,
   * properly annotated, package.
   */
  protected Pattern unannotatedSubPackages;

  /** Source code in these classes will not be analyzed for nullability issues */
  @Nullable protected ImmutableSet<String> sourceClassesToExclude;

  /**
   * these classes will be treated as unannotated (don't analyze *and* treat methods as unannotated)
   */
  @Nullable protected ImmutableSet<String> unannotatedClasses;

  protected Pattern fieldAnnotPattern;

  protected boolean isExhaustiveOverride;

  protected boolean isSuggestSuppressions;

  protected boolean isAcknowledgeRestrictive;

  protected boolean checkOptionalEmptiness;

  protected boolean checkContracts;

  protected boolean handleTestAssertionLibraries;

  protected ImmutableSet<String> optionalClassPaths;

  protected boolean assertsEnabled;

  protected boolean treatGeneratedAsUnannotated;

  protected boolean acknowledgeAndroidRecent;

  protected boolean jspecifyMode;

  protected ImmutableSet<MethodClassAndName> knownInitializers;

  protected ImmutableSet<String> excludedClassAnnotations;

  protected ImmutableSet<String> generatedCodeAnnotations;

  protected ImmutableSet<String> initializerAnnotations;

  protected ImmutableSet<String> externalInitAnnotations;

  protected ImmutableSet<String> contractAnnotations;

  @Nullable protected String castToNonNullMethod;

  protected String autofixSuppressionComment;

  protected ImmutableSet<String> skippedLibraryModels;

  /** --- JarInfer configs --- */
  protected boolean jarInferEnabled;

  protected boolean jarInferUseReturnAnnotations;

  protected String jarInferRegexStripModelJarName;
  protected String jarInferRegexStripCodeJarName;

  protected String errorURL;

  /** --- Fully qualified names of custom nonnull/nullable annotation --- */
  protected ImmutableSet<String> customNonnullAnnotations;

  protected ImmutableSet<String> customNullableAnnotations;

  /**
   * If active, NullAway will write all reporting errors in output directory. The output directory
   * along with the activation status of other serialization features are stored in {@link
   * FixSerializationConfig}.
   */
  protected boolean serializationActivationFlag;

  protected FixSerializationConfig fixSerializationConfig;

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

  protected static Pattern getPackagePattern(ImmutableSet<String> packagePrefixes) {
    // noinspection ConstantConditions
    String choiceRegexp =
        Joiner.on("|")
            .join(Iterables.transform(packagePrefixes, input -> input.replaceAll("\\.", "\\\\.")));
    return Pattern.compile("^(?:" + choiceRegexp + ")(?:\\..*)?");
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
  @Nullable
  public String getCastToNonNullMethod() {
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

  @AutoValue
  abstract static class MethodClassAndName {

    static MethodClassAndName create(String enclosingClass, String methodName) {
      return new AutoValue_AbstractConfig_MethodClassAndName(enclosingClass, methodName);
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

  /** --- JarInfer configs --- */
  @Override
  public boolean isJarInferEnabled() {
    return jarInferEnabled;
  }

  @Override
  public boolean isJarInferUseReturnAnnotations() {
    return jarInferUseReturnAnnotations;
  }

  @Override
  public String getJarInferRegexStripModelJarName() {
    return jarInferRegexStripModelJarName;
  }

  @Override
  public String getJarInferRegexStripCodeJarName() {
    return jarInferRegexStripCodeJarName;
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
}
