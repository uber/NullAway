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
import com.sun.tools.javac.code.Symbol;
import java.util.Set;
import javax.annotation.Nullable;

/** Provides configuration parameters for the nullability checker. */
public interface Config {

  /**
   * @param symbol symbol for class
   * @return true if the class is from a package that should be treated as properly annotated
   *     according to our convention (every possibly null parameter / return / field
   *     annotated @Nullable), false otherwise
   */
  boolean fromAnnotatedPackage(Symbol.ClassSymbol symbol);

  /**
   * @param className fully-qualified class name
   * @return true if the source file for the class should be excluded from nullability analysis,
   *     false otherwise
   */
  boolean isExcludedClass(String className);

  /**
   * @param symbol symbol for class
   * @return true if the class should be treated as unannotated (in spite of being in an annotated
   *     package)
   */
  boolean isUnannotatedClass(Symbol.ClassSymbol symbol);

  /** @return class annotations that should exclude a class from nullability analysis */
  ImmutableSet<String> getExcludedClassAnnotations();

  /**
   * @param annotationName fully-qualified annotation name
   * @return true if a method with this annotation should be considered an initializer method, false
   *     otherwise.
   */
  boolean isInitializerMethodAnnotation(String annotationName);

  /**
   * @param annotationName fully-qualified annotation name
   * @return true if a field with this annotation should not be checked for proper initialization,
   *     false otherwise
   */
  boolean isExcludedFieldAnnotation(String annotationName);

  /**
   * @return true if the analysis can assume that all overriding methods (including implementations
   *     of interface methods) are annotated with @Override, false otherwise
   */
  boolean exhaustiveOverride();

  /**
   * @param methodSymbol the method
   * @return true if the method is a known initializer
   */
  boolean isKnownInitializerMethod(Symbol.MethodSymbol methodSymbol);

  /**
   * Checks if annotation marks an "external-init class," i.e., a class where some external
   * framework initializes object fields after invoking the zero-argument constructor.
   *
   * @param annotationName fully-qualified annotation name
   * @return true if classes with the annotation are external-init
   */
  boolean isExternalInitClassAnnotation(String annotationName);

  /**
   * @return true if the null checker should suggest adding warning suppressions. Only useful for
   *     suppressing all warnings in a large code base.
   */
  boolean suggestSuppressions();

  /** @return true if the assert support is enabled. */
  boolean assertsEnabled();

  /**
   * @return true if the null checker should acknowledge stricter nullability annotations whenever
   *     they are available in unannotated code, defaulting to optimistic defaults only when
   *     explicit annotations are missing. false if any annotations in code not explicitly marked as
   *     annotated should be ignored completely and unannotated code should always be treated
   *     optimistically.
   */
  boolean acknowledgeRestrictiveAnnotations();

  /**
   * @return true if Optional Emptiness Handler is to be used. When Optional.get() method is called
   *     on an empty optional, program will crash with an exception. This handler warns on possible
   *     cases where Optional.get() call is made on an empty optional. Nullaway determines if an
   *     optional is non-empty based on Optional.isPresent() call.
   */
  boolean checkOptionalEmptiness();

  /**
   * @return true if AssertionHandler should be enabled. In the absence of this handler, checks in
   *     tests using assertion libraries are ignored. So, any deference of an object that follows
   *     such checks are still considered a potential dereference of null. When this handler is
   *     enabled, it understands checks using assertion libraries and reasons about the following
   *     code such that any deference of objects that have been checked will never be null.
   */
  boolean handleTestAssertionLibraries();

  /**
   * @return the paths for Optional class. The list always contains the path of {@link
   *     java.util.Optional}.
   */
  Set<String> getOptionalClassPaths();

  /**
   * @return the fully qualified name of a method which will take a @Nullable version of a value and
   *     return an @NonNull copy (likely through an unsafe downcast, but performing runtime checking
   *     and logging)
   */
  @Nullable
  String getCastToNonNullMethod();

  /**
   * @return the comment to add to @SuppressWarnings annotations inserted into fix suggestions
   *     and/or auto-fix runs.
   */
  String getAutofixSuppressionComment();

  // --- JarInfer configs ---

  /** @return true if JarInfer should be enabled */
  boolean isJarInferEnabled();

  /**
   * @return true if NullAway should use the @Nullable return value annotations inferred by
   *     JarInfer.
   */
  boolean isJarInferUseReturnAnnotations();
  /** @return the regex to extract jar name from the JarInfer model jar's path. */
  String getJarInferRegexStripModelJarName();
  /** @return the regex to extract jar name from the classfile jar's path. */
  String getJarInferRegexStripCodeJarName();

  /** @return the URL to show with NullAway error messages */
  String getErrorURL();

  /** @return true if generated code should be treated as unannotated */
  boolean treatGeneratedAsUnannotated();

  /**
   * @return true if Android's {@code @RecentlyNullable} should be treated as {@code @Nullable}, and
   *     similarly for {@code @RecentlyNonNull}
   */
  boolean handleAndroidRecent();
}
