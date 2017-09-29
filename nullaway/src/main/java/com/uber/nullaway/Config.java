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

import com.sun.tools.javac.code.Symbol;
import javax.annotation.Nullable;

/** Provides configuration parameters for the nullability checker. */
public interface Config {

  /**
   * @param className fully-qualified class name
   * @return true if the class is from a package that should be treated as properly annotated
   *     according to our convention (every possibly null parameter / return / field
   *     annotated @Nullable), false otherwise
   */
  boolean fromAnnotatedPackage(String className);

  /**
   * @param className fully-qualified class name
   * @return true if the source file for the class should be excluded from nullability analysis,
   *     false otherwise
   */
  boolean isExcludedClass(String className);

  /**
   * @param annotationName fully-qualified annotation name
   * @return true if a top-level class with this annotation should be excluded from nullability
   *     analysis, false otherwise
   */
  boolean isExcludedClassAnnotation(String annotationName);

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
   * @return true if the null checker should suggest adding warning suppressions. Only useful for
   *     suppressing all warnings in a large code base.
   */
  boolean suggestSuppressions();

  /**
   * @return the fully qualified name of a method which will take a @Nullable version of a value and
   *     return an @NonNull copy (likely through an unsafe downcast, but performing runtime checking
   *     and logging)
   */
  @Nullable
  String getCastToNonNullMethod();
}
