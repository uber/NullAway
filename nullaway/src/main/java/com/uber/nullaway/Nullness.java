/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Based on com.google.errorprone.dataflow.nullnesspropagation.*

package com.uber.nullaway;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.util.List;
import java.util.stream.Stream;
import javax.lang.model.element.AnnotationMirror;
import org.checkerframework.nullaway.dataflow.analysis.AbstractValue;

/**
 * Represents one of the possible nullness values in our nullness analysis.
 *
 * @author deminguyen@google.com (Demi Nguyen)
 */
public enum Nullness implements AbstractValue<Nullness> {

  /**
   * The lattice for nullness looks like:
   *
   * <pre>
   *        Nullable
   *       /        \
   *   Null          Non-null
   *        \      /
   *         Bottom
   * </pre>
   */
  NULLABLE("Nullable"),
  NULL("Null"),
  NONNULL("Non-null"),
  BOTTOM("Bottom");

  private final String displayName;

  Nullness(String displayName) {
    this.displayName = displayName;
  }

  // The following leastUpperBound and greatestLowerBound methods were created by handwriting a
  // truth table and then encoding the values into these functions. A better approach would be to
  // represent the lattice directly and compute these functions from the lattice.

  @Override
  public Nullness leastUpperBound(Nullness other) {
    if (this == other) {
      return this;
    }
    // Bottom loses.
    if (this == BOTTOM) {
      return other;
    }
    if (other == BOTTOM) {
      return this;
    }
    // They disagree, and neither is bottom.
    return NULLABLE;
  }

  public Nullness greatestLowerBound(Nullness other) {
    if (this == other) {
      return this;
    }
    // Nullable loses.
    if (this == NULLABLE) {
      return other;
    }
    if (other == NULLABLE) {
      return this;
    }
    // They disagree, and neither is nullable.
    return BOTTOM;
  }

  /**
   * Returns the {@code Nullness} that corresponds to what you can deduce by knowing that some
   * expression is not equal to another expression with this {@code Nullness}.
   *
   * <p>A {@code Nullness} represents a set of possible values for a expression. Suppose you have
   * two variables {@code var1} and {@code var2}. If {@code var1 != var2}, then {@code var1} must be
   * an element of the complement of the singleton set containing the value of {@code var2}. If you
   * union these complement sets over all possible values of {@code var2}, the set that results is
   * what this method returns, assuming that {@code this} is the {@code Nullness} of {@code var2}.
   *
   * <p>Example 1: Suppose {@code nv2 == NULL}. Then {@code var2} can have exactly one value, {@code
   * null}, and {@code var1} must have a value in the set of all values except {@code null}. That
   * set is exactly {@code NONNULL}.
   *
   * <p>Example 2: Suppose {@code nv2 == NONNULL}. Then {@code var2} can have any value except
   * {@code null}. Suppose {@code var2} has value {@code "foo"}. Then {@code var1} must have a value
   * in the set of all values except {@code "foo"}. Now suppose {@code var2} has value {@code "bar"}
   * . Then {@code var1} must have a value in set of all values except {@code "bar"}. Since we don't
   * know which value in the set {@code NONNULL var2} has, we union all possible complement sets to
   * get the set of all values, or {@code NULLABLE}.
   */
  public Nullness deducedValueWhenNotEqual() {
    switch (this) {
      case NULLABLE:
        return NULLABLE;
      case NONNULL:
        return NULLABLE;
      case NULL:
        return NONNULL;
      case BOTTOM:
        return BOTTOM;
      default:
        throw new AssertionError("Inverse of " + this + " not defined");
    }
  }

  @Override
  public String toString() {
    return displayName;
  }

  public static boolean hasNullableAnnotation(
      Stream<? extends AnnotationMirror> annotations, Config config) {
    return annotations
        .map(anno -> anno.getAnnotationType().toString())
        .anyMatch(anno -> isNullableAnnotation(anno, config));
  }

  public static boolean hasNonNullAnnotation(
      Stream<? extends AnnotationMirror> annotations, Config config) {
    return annotations
        .map(anno -> anno.getAnnotationType().toString())
        .anyMatch(anno -> isNonNullAnnotation(anno, config));
  }

  /**
   * Check whether an annotation should be treated as equivalent to <code>@Nullable</code>.
   *
   * @param annotName annotation name
   * @return true if we treat annotName as a <code>@Nullable</code> annotation, false otherwise
   */
  public static boolean isNullableAnnotation(String annotName, Config config) {
    return annotName.endsWith(".Nullable")
        // endsWith and not equals and no `org.`, because gradle's shadow plug in rewrites strings
        // and will replace `org.checkerframework` with `shadow.checkerframework`. Yes, really...
        // I assume it's something to handle reflection.
        || annotName.endsWith(".checkerframework.checker.nullness.compatqual.NullableDecl")
        // matches javax.annotation.CheckForNull and edu.umd.cs.findbugs.annotations.CheckForNull
        || annotName.endsWith(".CheckForNull")
        // matches any of the multiple @ParametricNullness annotations used within Guava
        // (see https://github.com/google/guava/issues/6126)
        // We check the simple name first and the package prefix second for boolean short
        // circuiting, as Guava includes
        // many annotations
        || (annotName.endsWith(".ParametricNullness") && annotName.startsWith("com.google.common."))
        || (config.acknowledgeAndroidRecent()
            && annotName.equals("androidx.annotation.RecentlyNullable"))
        || config.isCustomNullableAnnotation(annotName);
  }

  /**
   * Check whether an annotation should be treated as equivalent to <code>@NonNull</code>.
   *
   * @param annotName annotation name
   * @return true if we treat annotName as a <code>@NonNull</code> annotation, false otherwise
   */
  private static boolean isNonNullAnnotation(String annotName, Config config) {
    return annotName.endsWith(".NonNull")
        || annotName.endsWith(".NotNull")
        || annotName.endsWith(".Nonnull")
        || (config.acknowledgeAndroidRecent()
            && annotName.equals("androidx.annotation.RecentlyNonNull"))
        || config.isCustomNonnullAnnotation(annotName);
  }

  /**
   * Does the symbol have a {@code @NonNull} declaration or type-use annotation?
   *
   * <p>NOTE: this method does not work for checking all annotations of parameters of methods from
   * class files. For that case, use {@link #paramHasNullableAnnotation(Symbol.MethodSymbol, int,
   * Config)}
   */
  public static boolean hasNonNullAnnotation(Symbol symbol, Config config) {
    return hasNonNullAnnotation(NullabilityUtil.getAllAnnotations(symbol, config), config);
  }

  /**
   * Does the symbol have a {@code @Nullable} declaration or type-use annotation?
   *
   * <p>NOTE: this method does not work for checking all annotations of parameters of methods from
   * class files. For that case, use {@link #paramHasNullableAnnotation(Symbol.MethodSymbol, int,
   * Config)}
   */
  public static boolean hasNullableAnnotation(Symbol symbol, Config config) {
    return hasNullableAnnotation(NullabilityUtil.getAllAnnotations(symbol, config), config);
  }

  /**
   * Does the parameter of {@code symbol} at {@code paramInd} have a {@code @Nullable} declaration
   * or type-use annotation? This method works for methods defined in either source or class files.
   */
  public static boolean paramHasNullableAnnotation(
      Symbol.MethodSymbol symbol, int paramInd, Config config) {
    // We treat the (generated) equals() method of record types to have a @Nullable parameter, as
    // the generated implementation handles null (as required by the contract of Object.equals())
    if (isRecordEqualsParam(symbol, paramInd)) {
      return true;
    }
    return hasNullableAnnotation(
        NullabilityUtil.getAllAnnotationsForParameter(symbol, paramInd, config), config);
  }

  private static boolean isRecordEqualsParam(Symbol.MethodSymbol symbol, int paramInd) {
    // Here we compare with toString() to preserve compatibility with JDK 11 (records only
    // introduced in JDK 16)
    if (!symbol.owner.getKind().toString().equals("RECORD")) {
      return false;
    }
    if (!symbol.getSimpleName().contentEquals("equals")) {
      return false;
    }
    // Check for a boolean return type and a single parameter of type java.lang.Object
    Type type = symbol.type;
    List<Type> parameterTypes = type.getParameterTypes();
    if (!(type.getReturnType().toString().equals("boolean")
        && parameterTypes != null
        && parameterTypes.size() == 1
        && parameterTypes.get(0).toString().equals("java.lang.Object"))) {
      return false;
    }
    return paramInd == 0;
  }

  /**
   * Does the parameter of {@code symbol} at {@code paramInd} have a {@code @NonNull} declaration or
   * type-use annotation? This method works for methods defined in either source or class files.
   */
  public static boolean paramHasNonNullAnnotation(
      Symbol.MethodSymbol symbol, int paramInd, Config config) {
    return hasNonNullAnnotation(
        NullabilityUtil.getAllAnnotationsForParameter(symbol, paramInd, config), config);
  }
}
