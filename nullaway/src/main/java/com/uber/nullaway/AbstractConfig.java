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
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.autofix.AutoFixConfig;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** abstract base class for null checker {@link Config} implementations */
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

  protected Set<String> optionalClassPaths;

  protected boolean assertsEnabled;

  /**
   * if true, {@link #fromAnnotatedPackage(Symbol.ClassSymbol)} will return false for any class
   * annotated with {@link javax.annotation.Generated}
   */
  protected boolean treatGeneratedAsUnannotated;

  protected boolean acknowledgeAndroidRecent;

  protected Set<MethodClassAndName> knownInitializers;

  protected Set<String> excludedClassAnnotations;

  protected Set<String> initializerAnnotations;

  protected Set<String> externalInitAnnotations;

  protected Set<String> contractAnnotations;

  @Nullable protected String castToNonNullMethod;

  @Nullable protected String customNullableAnnotation;

  protected String autofixSuppressionComment;

  /** --- JarInfer configs --- */
  protected boolean jarInferEnabled;

  protected boolean jarInferUseReturnAnnotations;

  protected String jarInferRegexStripModelJarName;
  protected String jarInferRegexStripCodeJarName;

  protected String errorURL;

  protected boolean autoFixFlag;
  protected AutoFixConfig autoFixConfig;

  @Override
  public boolean autofixIsEnabled() {
    return autoFixFlag;
  }

  @Override
  public AutoFixConfig getAutoFixConfig() {
    return autoFixConfig;
  }

  protected static Pattern getPackagePattern(Set<String> packagePrefixes) {
    // noinspection ConstantConditions
    String choiceRegexp =
        Joiner.on("|")
            .join(Iterables.transform(packagePrefixes, input -> input.replaceAll("\\.", "\\\\.")));
    return Pattern.compile("^(?:" + choiceRegexp + ")(?:\\..*)?");
  }

  @Override
  public boolean fromAnnotatedPackage(Symbol.ClassSymbol symbol) {
    String className = symbol.getQualifiedName().toString();
    return annotatedPackages.matcher(className).matches()
        && !unannotatedSubPackages.matcher(className).matches()
        && (!treatGeneratedAsUnannotated
            || !ASTHelpers.hasDirectAnnotationWithSimpleName(symbol, "Generated"));
  }

  @Override
  public boolean isExcludedClass(String className) {
    if (sourceClassesToExclude != null) {
      for (String classPrefix : sourceClassesToExclude) {
        if (className.startsWith(classPrefix)) {
          return true;
        }
      }
    }
    if (autoFixFlag) {
      return autoFixConfig.isOutOfScope(className);
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
    return ImmutableSet.copyOf(excludedClassAnnotations);
  }

  @Override
  public boolean isInitializerMethodAnnotation(String annotationName) {
    return initializerAnnotations.contains(annotationName);
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
  public Set<String> getOptionalClassPaths() {
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
  @Nullable
  public String getCustomNullableAnnotation() {
    return customNullableAnnotation;
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

  protected Set<MethodClassAndName> getKnownInitializers(Set<String> qualifiedNames) {
    Set<MethodClassAndName> result = new LinkedHashSet<>();
    for (String name : qualifiedNames) {
      int lastDot = name.lastIndexOf('.');
      String methodName = name.substring(lastDot + 1);
      String className = name.substring(0, lastDot);
      result.add(MethodClassAndName.create(className, methodName));
    }
    return result;
  }

  @AutoValue
  abstract static class MethodClassAndName {

    static MethodClassAndName create(String enclosingClass, String methodName) {
      return new AutoValue_AbstractConfig_MethodClassAndName(enclosingClass, methodName);
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
  public boolean treatGeneratedAsUnannotated() {
    return treatGeneratedAsUnannotated;
  }

  @Override
  public boolean acknowledgeAndroidRecent() {
    return acknowledgeAndroidRecent;
  }
}
