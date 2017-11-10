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

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import org.checkerframework.dataflow.analysis.AbstractValue;

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
  NULLABLE("Nullable"), // TODO(eaftan): Rename to POSSIBLY_NULL?
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

  private static Nullness nullnessFromAnnotations(Element element) {
    for (AnnotationMirror anno : NullabilityUtil.getAllAnnotations(element)) {
      if (anno.getAnnotationType().toString().endsWith(".Nullable")) {
        return Nullness.NULLABLE;
      }
    }
    return Nullness.NONNULL;
  }

  public static boolean hasNullableAnnotation(Element element) {
    return nullnessFromAnnotations(element) == Nullness.NULLABLE;
  }
}
