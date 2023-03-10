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
import com.uber.nullaway.fixserialization.FixSerializationConfig;
import java.util.Set;
import javax.annotation.Nullable;

/** Provides configuration parameters for the nullability checker. */
public interface Config {

  /**
   * Checks if Serialization feature is active.
   *
   * @return true, if Fix Serialization feature is active.
   */
  boolean serializationIsActive();

  /**
   * Getter method for {@link FixSerializationConfig}.
   *
   * <p>Fix Serialization feature must be activated, otherwise calling this method will fail the
   * execution.
   *
   * @return {@link FixSerializationConfig} instance in Config.
   */
  FixSerializationConfig getSerializationConfig();

  /**
   * Checks if a class comes from an explicitly annotated package.
   *
   * @param className fully qualified name for class
   * @return true if the class is from a package that is explicitly configured to be treated as
   *     properly annotated according to our convention (every possibly null parameter / return /
   *     field annotated @Nullable), false otherwise
   */
  boolean fromExplicitlyAnnotatedPackage(String className);

  /**
   * Checks if a class comes from an explicitly unannotated (sub-)package.
   *
   * @param className fully qualified name for class
   * @return true if the class is from a package that is explicitly configured to be treated as
   *     unannotated (even if it is a subpackage of a package configured to be explicitly annotated
   *     or if it's marked @NullMarked), false otherwise
   */
  boolean fromExplicitlyUnannotatedPackage(String className);

  /**
   * Checks if (tool) generated code should be considered always unannoatated.
   *
   * @return true if code marked as generated code should be treated as unannotated, even if it
   *     comes from a package otherwise configured as annotated.
   */
  boolean treatGeneratedAsUnannotated();

  /**
   * Checks if a class should be excluded.
   *
   * @param className fully-qualified class name
   * @return true if the source file for the class should be excluded from nullability analysis,
   *     false otherwise
   */
  boolean isExcludedClass(String className);

  /**
   * Checks if a class should be treated as unannotated.
   *
   * @param symbol symbol for class
   * @return true if the class should be treated as unannotated (in spite of being in an annotated
   *     package)
   */
  boolean isUnannotatedClass(Symbol.ClassSymbol symbol);

  /**
   * Gets the list of excluded class annotations.
   *
   * @return class annotations that should exclude a class from nullability analysis
   */
  ImmutableSet<String> getExcludedClassAnnotations();

  ImmutableSet<String> getGeneratedCodeAnnotations();

  /**
   * Checks if the annotation is an @Initializer annotation.
   *
   * @param annotationName fully-qualified annotation name
   * @return true if a method with this annotation should be considered an initializer method, false
   *     otherwise.
   */
  boolean isInitializerMethodAnnotation(String annotationName);

  /**
   * Checks if the annotation is a custom @Nullable annotation.
   *
   * @param annotationName fully-qualified annotation name
   * @return true if annotation should be considered as a custom nullable annotation.
   */
  boolean isCustomNullableAnnotation(String annotationName);

  /**
   * Checks if the annotation is a custom @Nonnull annotation.
   *
   * @param annotationName fully-qualified annotation name
   * @return true if annotation should be considered as a custom nonnull annotation.
   */
  boolean isCustomNonnullAnnotation(String annotationName);

  /**
   * Checks if the annotation is an excluded field annotation.
   *
   * @param annotationName fully-qualified annotation name
   * @return true if a field with this annotation should not be checked for proper initialization,
   *     false otherwise
   */
  boolean isExcludedFieldAnnotation(String annotationName);

  /**
   * Checks whether the analysis can assume that @Override annotations are enforced on the codebase.
   *
   * @return true if the analysis can assume that all overriding methods (including implementations
   *     of interface methods) are annotated with @Override, false otherwise
   */
  boolean exhaustiveOverride();

  /**
   * Checks if a method is a known initializer.
   *
   * @param methodSymbol the method
   * @return true if the method is a known initializer
   */
  boolean isKnownInitializerMethod(Symbol.MethodSymbol methodSymbol);

  /**
   * Checks if annotation marks an "external-init class," i.e., a class where some external
   * framework initializes object fields after invoking the zero-argument constructor.
   *
   * <p>Note that this annotation can be on the class itself, or on the zero-arguments constructor,
   * but will be ignored anywhere else.
   *
   * @param annotationName fully-qualified annotation name
   * @return true if classes with the annotation are external-init
   */
  boolean isExternalInitClassAnnotation(String annotationName);

  /**
   * Checks if annotation is a "Contract". A Contract annotation has a String value following the
   * format of Jetbrains Contract annotations to define nullability properties of the method.
   *
   * @param annotationName fully-qualified annotation name
   * @return true if the annotation is a Contract
   */
  boolean isContractAnnotation(String annotationName);

  /**
   * Checks if the checker should suggest adding warning suppressions instead of fixes.
   *
   * @return true if the null checker should suggest adding warning suppressions. Only useful for
   *     suppressing all warnings in a large code base.
   */
  boolean suggestSuppressions();

  /**
   * Checks if assert support is enabled.
   *
   * @return true if the assert support is enabled.
   */
  boolean assertsEnabled();

  /**
   * Checks if acknowledging restrictive annotations is enabled.
   *
   * @return true if the null checker should acknowledge stricter nullability annotations whenever
   *     they are available in unannotated code, defaulting to optimistic defaults only when
   *     explicit annotations are missing. false if any annotations in code not explicitly marked as
   *     annotated should be ignored completely and unannotated code should always be treated
   *     optimistically.
   */
  boolean acknowledgeRestrictiveAnnotations();

  /**
   * Checks if optional emptiness checking is enabled.
   *
   * @return true if Optional Emptiness Handler is to be used. When Optional.get() method is called
   *     on an empty optional, program will crash with an exception. This handler warns on possible
   *     cases where Optional.get() call is made on an empty optional. Nullaway determines if an
   *     optional is non-empty based on Optional.isPresent() call.
   */
  boolean checkOptionalEmptiness();

  boolean checkContracts();

  /**
   * Checks if test assertion library handling is enabled.
   *
   * @return true if AssertionHandler should be enabled. In the absence of this handler, checks in
   *     tests using assertion libraries are ignored. So, any deference of an object that follows
   *     such checks are still considered a potential dereference of null. When this handler is
   *     enabled, it understands checks using assertion libraries and reasons about the following
   *     code such that any deference of objects that have been checked will never be null.
   */
  boolean handleTestAssertionLibraries();

  /**
   * Gets the list of Optional classes (e.g. {@link java.util.Optional})
   *
   * @return the paths for Optional class. The list always contains the path of {@link
   *     java.util.Optional}.
   */
  Set<String> getOptionalClassPaths();

  /**
   * Gets the fully qualified name of the <code>castToNonNull()</code> method, if any.
   *
   * @return the fully qualified name of a method which will take a @Nullable version of a value and
   *     return an @NonNull copy (likely through an unsafe downcast, but performing runtime checking
   *     and logging)
   */
  @Nullable
  String getCastToNonNullMethod();

  /**
   * Gets an optional comment to add to auto-fix suppressions.
   *
   * @return the comment to add to @SuppressWarnings annotations inserted into fix suggestions
   *     and/or auto-fix runs.
   */
  String getAutofixSuppressionComment();

  /**
   * Checks if the given library model should be skipped/ignored.
   *
   * <p>For ease of configuration in the command line, this works at the level of the (class, method
   * name) pair, meaning it applies for all methods with the same name in the same class, even if
   * they have different signatures, and to all library models applicable to that method (i.e. on
   * the method's return, arguments, etc).
   *
   * @param classDotMethod The method from the model, in [fully_qualified_class_name].[method_name]
   *     format (no args)
   * @return True if the library model should be skipped.
   */
  boolean isSkippedLibraryModel(String classDotMethod);

  // --- JarInfer configs ---

  /**
   * Checks if JarInfer should be enabled.
   *
   * @return true if JarInfer should be enabled
   */
  boolean isJarInferEnabled();

  /**
   * Checks if NullAway should use @Nullable return value annotations inferred by JarInfer.
   *
   * @return true if NullAway should use the @Nullable return value annotations inferred by
   *     JarInfer.
   */
  boolean isJarInferUseReturnAnnotations();

  /**
   * Used by JarInfer
   *
   * @return the regex to extract jar name from the JarInfer model jar's path.
   */
  String getJarInferRegexStripModelJarName();

  /**
   * Used by JarInfer.
   *
   * @return the regex to extract jar name from the classfile jar's path.
   */
  String getJarInferRegexStripCodeJarName();

  /**
   * Gets the URL to show with NullAway error messages.
   *
   * @return the URL to show with NullAway error messages
   */
  String getErrorURL();

  /**
   * Checks if acknowledging {@code @RecentlyNullable} and {@code @RecentlyNonNull} annotations is
   * enabled.
   *
   * @return true if Android's {@code @RecentlyNullable} should be treated as {@code @Nullable}, and
   *     similarly for {@code @RecentlyNonNull}
   */
  boolean acknowledgeAndroidRecent();

  /** Should new checks based on JSpecify (like checks for generic types) be enabled? */
  boolean isJSpecifyMode();
}
