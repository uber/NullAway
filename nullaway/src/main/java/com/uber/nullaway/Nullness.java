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

import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.util.List;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Stream;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.TypeKind;
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
   *          Nullable
   *         /        \
   *     Null          No-change
   *         \        /         \
   *     Null (no-change)     Non-null
   *              \           /
   *                 Bottom
   * </pre>
   */
  NULLABLE("Nullable"),
  NULL("Null"),
  /**
   * A value read through a type variable {@code T} with a nullable upper bound, such as the result
   * of {@code Box<? extends T>.get()} or an enhanced-for element of type {@code T}, which is null
   * only when {@code T} is instantiated with a nullable type argument. This is the JSpecify {@code
   * NO_CHANGE} nullness operator applied to {@code T}. NullAway computes it only with {@code
   * HandleWildcardGenerics} on; with that option off, such a value is {@link #NONNULL}. A read of a
   * parameter of type {@code T} is {@link #NONNULL}, not {@code NO_CHANGE}.
   *
   * <p>Such a value is treated as nullable, except where {@link
   * com.uber.nullaway.generics.GenericsChecks#acceptsNoChangeValue} accepts it: there a location of
   * type {@code T} receives it, it initializes a field, and generic method inference takes {@code
   * T} from it. A {@code NO_CHANGE} fact holds relative to the static type of the variable or
   * expression that holds the value: where the value moves to a holder that rule rejects, it
   * becomes {@link #withoutNoChange()}. As the nullness of a parameter in the {@link
   * MethodParameterNullness} built for a call of an annotated method or constructor, it marks a
   * parameter that accepts such an argument.
   *
   * @see <a href="https://jspecify.dev/docs/spec/#nullness-operator">JSpecify: nullness
   *     operator</a>
   */
  NO_CHANGE("No-change"),
  /**
   * A {@link #NO_CHANGE} value that dataflow has found equal to null, such as inside {@code if
   * (value == null)}. A location of its type variable accepts it, as it accepts a {@code NO_CHANGE}
   * value, and everywhere else it is {@link #NULL}: a dereference is reported, and generic method
   * inference takes {@code @Nullable T} from it. Where it moves to a holder that {@link
   * com.uber.nullaway.generics.GenericsChecks#acceptsNoChangeValue} rejects, it becomes {@link
   * #NULL}.
   */
  NULL_NO_CHANGE("Null (no-change)"),
  NONNULL("Non-null"),
  BOTTOM("Bottom");

  private final String displayName;

  Nullness(String displayName) {
    this.displayName = displayName;
  }

  /**
   * Check whether an annotation should be treated as equivalent to <code>@MonotonicNonNull</code>.
   * For now checks if the simple name of the annotation is {@code MonotonicNonNull}, from any
   * package, or if the annotation is {@code
   * org.checkerframework.checker.nullness.compatqual.MonotonicNonNullDecl}.
   */
  public static boolean isMonotonicNonNullAnnotation(String annotName) {
    return annotName.endsWith(".MonotonicNonNull")
        // no `org.` prefix, for the same reason as NullableDecl in isNullableAnnotation
        || annotName.endsWith(".checkerframework.checker.nullness.compatqual.MonotonicNonNullDecl");
  }

  /**
   * Check for either a {@code @Nullable} annotation or a {@code @MonotonicNonNull} annotation on
   * {@code symbol}. Used to reason whether a field may be null.
   */
  public static boolean hasNullableOrMonotonicNonNullAnnotation(Symbol symbol, Config config) {
    return NullabilityUtil.hasAnyAnnotationMatchingBackCompat(
        symbol,
        annot -> isNullableAnnotation(annot, config) || isMonotonicNonNullAnnotation(annot));
  }

  /** Returns whether this value is below {@code other} in the lattice, or equal to it. */
  private boolean isAtMost(Nullness other) {
    return this == other
        || this == BOTTOM
        || other == NULLABLE
        || (this == NULL_NO_CHANGE && (other == NULL || other == NO_CHANGE))
        || (this == NONNULL && other == NO_CHANGE);
  }

  /** Least upper bound of every pair of values. */
  private static final Map<Nullness, Map<Nullness, Nullness>> LEAST_UPPER_BOUNDS =
      boundsTable(/* upper= */ true);

  /** Greatest lower bound of every pair of values. */
  private static final Map<Nullness, Map<Nullness, Nullness>> GREATEST_LOWER_BOUNDS =
      boundsTable(/* upper= */ false);

  /**
   * Computes the least upper bound, or the greatest lower bound, of every pair of values from
   * {@link #isAtMost}.
   *
   * @param upper {@code true} for least upper bounds, {@code false} for greatest lower bounds
   * @return the bound of each pair of values
   */
  private static Map<Nullness, Map<Nullness, Nullness>> boundsTable(boolean upper) {
    Nullness[] values = values();
    Map<Nullness, Map<Nullness, Nullness>> table = new EnumMap<>(Nullness.class);
    for (Nullness a : values) {
      Map<Nullness, Nullness> row = new EnumMap<>(Nullness.class);
      table.put(a, row);
      for (Nullness b : values) {
        Nullness best = null;
        for (Nullness candidate : values) {
          boolean isBound =
              upper
                  ? a.isAtMost(candidate) && b.isAtMost(candidate)
                  : candidate.isAtMost(a) && candidate.isAtMost(b);
          if (isBound
              && (best == null || (upper ? candidate.isAtMost(best) : best.isAtMost(candidate)))) {
            best = candidate;
          }
        }
        row.put(b, castToNonNull(best));
      }
    }
    return table;
  }

  @Override
  public Nullness leastUpperBound(Nullness other) {
    return castToNonNull(castToNonNull(LEAST_UPPER_BOUNDS.get(this)).get(other));
  }

  /**
   * Returns the greatest lower bound of this value and {@code other} in the lattice: the most
   * precise value that holds wherever both of them hold.
   */
  public Nullness greatestLowerBound(Nullness other) {
    return castToNonNull(castToNonNull(GREATEST_LOWER_BOUNDS.get(this)).get(other));
  }

  /**
   * Returns whether a location of the value's type variable accepts this value although it may be
   * null: true for {@link #NO_CHANGE} and {@link #NULL_NO_CHANGE}.
   */
  public boolean isNoChange() {
    return this == NO_CHANGE || this == NULL_NO_CHANGE;
  }

  /**
   * Returns whether this value is known to be null: true for {@link #NULL} and {@link
   * #NULL_NO_CHANGE}.
   */
  public boolean isNull() {
    return this == NULL || this == NULL_NO_CHANGE;
  }

  /**
   * Returns the nullness of this value once it moves to a holder that does not accept a {@code
   * NO_CHANGE} value: {@link #NULLABLE} for {@link #NO_CHANGE}, {@link #NULL} for {@link
   * #NULL_NO_CHANGE}, and this value for any other.
   */
  public Nullness withoutNoChange() {
    return switch (this) {
      case NO_CHANGE -> NULLABLE;
      case NULL_NO_CHANGE -> NULL;
      default -> this;
    };
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
    return switch (this) {
      case NULLABLE, NO_CHANGE, NONNULL -> NULLABLE;
      case NULL, NULL_NO_CHANGE -> NONNULL;
      case BOTTOM -> BOTTOM;
    };
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
        // matches any of the multiple @ParametricNullness annotations used within Guava (see
        // https://github.com/google/guava/issues/6126). We check the simple name first and the
        // package prefix second for boolean short circuiting, as Guava includes many annotations.
        // We do _not_ match @ParametricNullness annotations in JSpecify mode and rely on generics
        // checking instead.
        || (!config.isJSpecifyMode()
            && annotName.endsWith(".ParametricNullness")
            && annotName.startsWith("com.google.common."))
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
  public static boolean isNonNullAnnotation(String annotName, Config config) {
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
    return NullabilityUtil.hasAnyAnnotationMatchingBackCompat(
        symbol, annot -> isNonNullAnnotation(annot, config));
  }

  /**
   * Does the symbol have a {@code @Nullable} declaration or type-use annotation?
   *
   * <p>NOTE: this method does not work for checking all annotations of parameters of methods from
   * class files. For that case, use {@link #paramHasNullableAnnotation(Symbol.MethodSymbol, int,
   * Config)}
   */
  public static boolean hasNullableAnnotation(Symbol symbol, Config config) {
    return NullabilityUtil.hasAnyAnnotationMatchingBackCompat(
        symbol, annot -> isNullableAnnotation(annot, config));
  }

  private static boolean hasNullableTypeUseAnnotation(Symbol symbol, Config config) {
    return hasNullableAnnotation(NullabilityUtil.getTypeUseAnnotations(symbol), config);
  }

  private static boolean hasNonNullTypeUseAnnotation(Symbol symbol, Config config) {
    return hasNonNullAnnotation(NullabilityUtil.getTypeUseAnnotations(symbol), config);
  }

  /**
   * Does the parameter of {@code symbol} at {@code paramInd} have a {@code @Nullable} declaration
   * or type-use annotation? This method works for methods defined in either source or class files.
   *
   * <p>For a varargs parameter, this method returns true if <em>individual</em> arguments passed in
   * the varargs positions can be null.
   */
  public static boolean paramHasNullableAnnotation(
      Symbol.MethodSymbol symbol, int paramInd, Config config) {
    // We treat the (generated) equals() method of record types to have a @Nullable parameter, as
    // the generated implementation handles null (as required by the contract of Object.equals())
    if (isRecordEqualsParam(symbol, paramInd)) {
      return true;
    }
    if (symbol.isVarArgs() && paramInd == symbol.getParameters().size() - 1) {
      return NullabilityUtil.nullableVarargsElementsForSourceOrBytecode(
          symbol.getParameters().get(paramInd), config);
    } else {
      return hasNullableAnnotation(
          NullabilityUtil.getAllAnnotationsForParameter(symbol, paramInd), config);
    }
  }

  @SuppressWarnings(
      "TypeToString") // fixing this will require threading a VisitorState object through many
  // places
  private static boolean isRecordEqualsParam(Symbol.MethodSymbol symbol, int paramInd) {
    if (!symbol.owner.getKind().equals(ElementKind.RECORD)) {
      return false;
    }
    if (!symbol.getSimpleName().contentEquals("equals")) {
      return false;
    }
    // Check for a boolean return type and a single parameter of type java.lang.Object
    Type type = symbol.type;
    List<Type> parameterTypes = type.getParameterTypes();
    if (!(type.getReturnType().getKind() == TypeKind.BOOLEAN
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
   *
   * <p>For a varargs parameter, this method returns true if <em>individual</em> arguments passed in
   * the varargs positions must be {@code @NonNull}.
   */
  public static boolean paramHasNonNullAnnotation(
      Symbol.MethodSymbol symbol, int paramInd, Config config) {
    if (symbol.isVarArgs() && paramInd == symbol.getParameters().size() - 1) {
      return NullabilityUtil.nonnullVarargsElementsForSourceOrBytecode(
          symbol.getParameters().get(paramInd), config);
    } else {
      return hasNonNullAnnotation(
          NullabilityUtil.getAllAnnotationsForParameter(symbol, paramInd), config);
    }
  }

  /**
   * Does the varargs parameter {@code paramSymbol} have a {@code @Nullable} annotation indicating
   * that the argument array passed at a call site can be {@code null}? Syntactically, this would be
   * written as {@code foo(Object @Nullable... args}}
   */
  public static boolean varargsArrayIsNullable(Symbol paramSymbol, Config config) {
    return hasNullableTypeUseAnnotation(paramSymbol, config);
  }

  /**
   * Does the varargs parameter {@code paramSymbol} have a {@code @NonNull} annotation indicating
   * that the argument array passed at a call site cannot be {@code null}? Syntactically, this would
   * be written as {@code foo(Object @NonNull... args}}
   */
  public static boolean varargsArrayIsNonNull(Symbol paramSymbol, Config config) {
    return hasNonNullTypeUseAnnotation(paramSymbol, config)
        // For Kotlin-generated class files, we treat the presence of a JetBrains @NotNull
        // declaration annotation on a varargs parameter as indicating that the varargs array itself
        // is non-null.  See https://github.com/uber/NullAway/issues/720
        || NullabilityUtil.hasJetBrainsNotNullDeclarationAnnotation(paramSymbol);
  }

  /** Checks if the symbol has a {@code @Nullable} declaration annotation */
  public static boolean hasNullableDeclarationAnnotation(Symbol symbol, Config config) {
    for (AnnotationMirror annotationMirror : symbol.getRawAttributes()) {
      if (isNullableAnnotation(annotationMirror.getAnnotationType().toString(), config)) {
        return true;
      }
    }
    return false;
  }

  /** Checks if the symbol has a {@code @NonNull} declaration annotation */
  public static boolean hasNonNullDeclarationAnnotation(Symbol symbol, Config config) {
    for (AnnotationMirror annotationMirror : symbol.getRawAttributes()) {
      if (isNonNullAnnotation(annotationMirror.getAnnotationType().toString(), config)) {
        return true;
      }
    }
    return false;
  }
}
