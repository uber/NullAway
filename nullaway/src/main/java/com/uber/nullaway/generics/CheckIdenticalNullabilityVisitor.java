package com.uber.nullaway.generics;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.common.base.Equivalence;
import com.google.errorprone.VisitorState;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.Config;
import com.uber.nullaway.handlers.Handler;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.lang.model.type.NullType;
import javax.lang.model.type.TypeKind;
import org.jspecify.annotations.Nullable;

/**
 * Checks nested nullability of the visited LHS type against the RHS argument.
 *
 * <p>Performance: a visitor caches successful wildcard containment checks for its lifetime. Reuse
 * the instance to share those results across checks, as long as the types and their annotations
 * have not changed.
 *
 * <p>A visitor given a {@link NullabilityMismatches} records every comparison it rejects there, and
 * goes on comparing after a rejection rather than returning at the first, so that a diagnostic can
 * name each position the check rejects. Its result is the same as without one.
 */
public class CheckIdenticalNullabilityVisitor extends Types.DefaultTypeVisitor<Boolean, Type> {
  private final VisitorState state;
  private final GenericsChecks genericsChecks;
  private final Config config;
  private final Handler handler;

  /** Where to record each rejected comparison, or {@code null} to return at the first one. */
  private final @Nullable NullabilityMismatches mismatches;

  /** Successful containment checks, reusable for the lifetime of this visitor. */
  private @Nullable Set<ContainmentKey> successfulWildcardComparisons;

  /**
   * Checks in progress or passed during the current outermost wildcard comparison. Every nested
   * check must pass for the outermost check to pass, so a repeat can return true while the first
   * check finishes. Save these results only if the outermost check succeeds; otherwise discard
   * them.
   */
  private @Nullable Set<ContainmentKey> provisionalWildcardComparisons;

  /**
   * Stops recursive bounds from rechecking the same wildcard/RHS pair, using object identity. The
   * {@link ContainmentKey}s used by {@link #successfulWildcardComparisons} and {@link
   * #provisionalWildcardComparisons} can change when capture conversion recreates other type
   * arguments, so those sets cannot replace this recursion guard.
   */
  private @Nullable IdentityHashMap<Type.WildcardType, Set<Type>> activeWildcardComparisons;

  /**
   * A class symbol, enclosing type, and all type arguments, used to cache containment results.
   * Every {@link Equivalence.Wrapper} here and in {@link ContainmentKey} compares by object
   * identity; javac's type equality can ignore nullability annotations.
   */
  private record ParameterizationKey(
      Equivalence.Wrapper<Symbol.TypeSymbol> symbol,
      Equivalence.Wrapper<Type> enclosing,
      List<Equivalence.Wrapper<Type>> arguments) {

    ParameterizationKey(Type.ClassType type) {
      this(
          Equivalence.identity().wrap(type.tsym),
          Equivalence.identity().wrap(type.getEnclosingType()),
          type.getTypeArguments().stream()
              .map(argument -> Equivalence.identity().wrap(argument))
              .toList());
    }
  }

  /**
   * A cached containment check: the LHS wildcard, RHS argument, their position, and their
   * containing parameterized types. The position is required since after substitutions the same
   * javac wildcard object may be used in multiple positions. The containing parameterized types
   * matter since upper bounds relevant to wildcard containment checking may depend on other bounds
   * in the type, e.g., if we have {@code Pair<T extends @Nullable Object, U extends T>}.
   */
  private record ContainmentKey(
      Equivalence.Wrapper<Type.WildcardType> lhsWildcard,
      Equivalence.Wrapper<Type> rhsArgument,
      int argumentIndex,
      ParameterizationKey lhsParameterization,
      ParameterizationKey rhsParameterization) {

    ContainmentKey(
        Type.WildcardType lhsWildcard,
        Type rhsArgument,
        int argumentIndex,
        ParameterizationKey lhsParameterization,
        ParameterizationKey rhsParameterization) {
      this(
          Equivalence.identity().wrap(lhsWildcard),
          Equivalence.identity().wrap(rhsArgument),
          argumentIndex,
          lhsParameterization,
          rhsParameterization);
    }
  }

  CheckIdenticalNullabilityVisitor(
      VisitorState state, GenericsChecks genericsChecks, Config config, Handler handler) {
    this(state, genericsChecks, config, handler, null);
  }

  /**
   * Creates a visitor that records every comparison it rejects in {@code mismatches}, where that is
   * not {@code null}.
   */
  CheckIdenticalNullabilityVisitor(
      VisitorState state,
      GenericsChecks genericsChecks,
      Config config,
      Handler handler,
      @Nullable NullabilityMismatches mismatches) {
    this.state = state;
    this.genericsChecks = genericsChecks;
    this.config = config;
    this.handler = handler;
    this.mismatches = mismatches;
  }

  /**
   * Returns whether {@code rhsType} may be assigned to {@code lhsType} as far as the nullability of
   * their type arguments goes. The nullness of the two types themselves is not compared. An array
   * is covariant in the nullness of its elements at every dimension, so an array of non-null
   * references is a subtype of an array of {@code @Nullable} ones; see <a
   * href="https://github.com/jspecify/jspecify/issues/65">jspecify/jspecify#65</a>.
   *
   * @param lhsType type for the lhs of the assignment
   * @param rhsType type for the rhs of the assignment
   */
  boolean isSubtype(Type lhsType, Type rhsType) {
    if (lhsType.isRaw()) {
      return true;
    }
    if (lhsType instanceof Type.ArrayType lhsArrayType
        && rhsType instanceof Type.ArrayType rhsArrayType) {
      NullabilityMismatches.Frame saved =
          mismatches == null ? null : mismatches.enterArrayElement(lhsArrayType, rhsArrayType);
      try {
        Type lhsComponentType = lhsArrayType.getComponentType();
        Type rhsComponentType = rhsArrayType.getComponentType();
        // an array of @Nullable references is _not_ a subtype of an array of @NonNull references
        if (genericsChecks.isNullableAnnotated(rhsComponentType)
            && !genericsChecks.isNullableAnnotated(lhsComponentType)) {
          reject(NullabilityMismatches.Relation.SUBTYPE, lhsComponentType, rhsComponentType);
          return false;
        }
        if (lhsComponentType instanceof Type.ArrayType
            && rhsComponentType instanceof Type.ArrayType) {
          // covariance applies at every dimension of a multi-dimensional array. An array nested
          // inside a type argument is compared by visitArrayType, since generics are not covariant
          return isSubtype(lhsComponentType, rhsComponentType);
        }
        return haveIdenticalNestedNullability(lhsComponentType, rhsComponentType);
      } finally {
        restore(saved);
      }
    }
    return haveIdenticalNestedNullability(lhsType, rhsType);
  }

  /**
   * Returns whether the type arguments of {@code lhsType} and {@code rhsType} have identical
   * nullability, without comparing the nullness of the two types themselves.
   */
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"}) // deliberate reference equality check
  private boolean haveIdenticalNestedNullability(Type lhsType, Type rhsType) {
    if (lhsType == rhsType) {
      return true;
    }
    return lhsType.accept(this, rhsType);
  }

  /** Records a rejected comparison, where this visitor records them. */
  private void reject(NullabilityMismatches.Relation relation, Type lhsNode, Type rhsNode) {
    if (mismatches != null) {
      mismatches.reject(relation, lhsNode, rhsNode);
    }
  }

  /** Puts back the frame an {@code enter} method of {@link #mismatches} returned. */
  private void restore(NullabilityMismatches.@Nullable Frame saved) {
    if (mismatches != null && saved != null) {
      mismatches.restore(saved);
    }
  }

  /**
   * Checks whether the nested nullability of an RHS type is compatible with an LHS class type.
   *
   * <p>The RHS is aligned with the LHS's base type before comparing type arguments. When wildcard
   * handling is enabled and the RHS itself is a wildcard or capture, the comparison continues with
   * its upper bound.
   *
   * @param lhsType class type on the left side of the comparison
   * @param rhsType type on the right side of the comparison
   * @return {@code true} if the types have compatible nested nullability, or if the comparison is
   *     intentionally skipped
   */
  @Override
  public Boolean visitClassType(Type.ClassType lhsType, Type rhsType) {
    if (rhsType instanceof NullType || rhsType.isPrimitive()) {
      return true;
    }
    if (!config.handleWildcardGenerics()) {
      // skip checking of wildcards
      if (rhsType.getKind().equals(TypeKind.WILDCARD)) {
        return true;
      }
    } else if (GenericsUtils.asWildcard(rhsType) != null) {
      Type rhsUpperBound =
          GenericsUtils.effectiveWildcardUpperBound(rhsType, state, config, handler);
      if (GenericsUtils.asWildcard(rhsUpperBound) != null) {
        // Bail out if resolving the upper bound did not produce a usable concrete bound.
        return true;
      }
      NullabilityMismatches.Frame saved =
          mismatches == null
              ? null
              : mismatches.enterUpperBounds(null, lhsType, rhsType, rhsUpperBound);
      try {
        return visitClassType(lhsType, rhsUpperBound);
      } finally {
        restore(saved);
      }
    }
    if (lhsType.isIntersection()) {
      return handleIntersectionType((Type.IntersectionClassType) lhsType, rhsType);
    }
    Types types = state.getTypes();
    // The base type of rhsType may be a subtype of lhsType's base type.  In such cases, we must
    // compare lhsType against the supertype of rhsType with a matching base type.
    Type rhsTypeAsSuper =
        TypeSubstitutionUtils.asSuper(types, rhsType, (Symbol.ClassSymbol) lhsType.tsym, config);
    if (rhsTypeAsSuper == null) {
      // Surprisingly, this can in fact occur, in cases involving raw types.  See, e.g.,
      // GenericsTests#issue1082 and https://github.com/uber/NullAway/pull/1086. Bail out.
      return true;
    }
    // bail out of checking raw types for now
    if (rhsTypeAsSuper.isRaw() || lhsType.isRaw()) {
      return true;
    }
    List<Type> lhsTypeArguments = lhsType.getTypeArguments();
    List<Type> rhsTypeArguments = rhsTypeAsSuper.getTypeArguments();
    // This is impossible, considering the fact that standard Java subtyping succeeds before
    // running NullAway
    if (lhsTypeArguments.size() != rhsTypeArguments.size()) {
      throw new RuntimeException(
          "Number of types arguments in " + rhsTypeAsSuper + " does not match " + lhsType);
    }
    Type.ClassType rhsClassTypeAsSuper = (Type.ClassType) rhsTypeAsSuper;
    List<Type> lhsUpperBounds = lhsTypeArguments;
    List<Type> rhsUpperBounds = rhsTypeArguments;
    ParameterizationKey lhsParameterization = null;
    ParameterizationKey rhsParameterization = null;
    if (config.handleWildcardGenerics()) {
      lhsParameterization = new ParameterizationKey(lhsType);
      rhsParameterization = new ParameterizationKey(rhsClassTypeAsSuper);
      lhsUpperBounds =
          GenericsUtils.effectiveUpperBoundsForTypeArguments(lhsType, state, config, handler);
      rhsUpperBounds =
          GenericsUtils.effectiveUpperBoundsForTypeArguments(
              rhsClassTypeAsSuper, state, config, handler);
    }
    if (mismatches != null) {
      mismatches.enterClassComparison(rhsType, rhsClassTypeAsSuper);
    }
    boolean contained = true;
    for (int i = 0; i < lhsTypeArguments.size(); i++) {
      Type lhsTypeArgument = lhsTypeArguments.get(i);
      Type lhsUpperBound = lhsUpperBounds.get(i);
      Type rhsTypeArgument = rhsTypeArguments.get(i);
      Type rhsUpperBound = rhsUpperBounds.get(i);
      NullabilityMismatches.Frame saved =
          mismatches == null ? null : mismatches.enterTypeArgument(i, lhsType, rhsClassTypeAsSuper);
      try {
        if (!typeArgumentContainedBy(
            lhsTypeArgument,
            rhsTypeArgument,
            lhsUpperBound,
            rhsUpperBound,
            i,
            lhsParameterization,
            rhsParameterization)) {
          if (mismatches == null) {
            return false;
          }
          contained = false;
        }
      } finally {
        restore(saved);
      }
    }
    // If there is an enclosing type (for non-static inner classes), its type argument nullability
    // should also match.  When there is no enclosing type, getEnclosingType() returns a NoType
    // object, which gets handled by the fallback visitType() method
    // We use rhsTypeAsSuper here rather than rhsType, since rhsType may be a captured type
    // variable, whose getEnclosingType() returns null.
    NullabilityMismatches.Frame saved =
        mismatches == null ? null : mismatches.enterEnclosingType(lhsType, rhsClassTypeAsSuper);
    try {
      return lhsType.getEnclosingType().accept(this, rhsTypeAsSuper.getEnclosingType())
          && contained;
    } finally {
      restore(saved);
    }
  }

  /** Check identical nullability for every type in the intersection */
  private Boolean handleIntersectionType(
      Type.IntersectionClassType intersectionType, Type rhsType) {
    boolean contained = true;
    for (Type bound : intersectionType.getExplicitComponents()) {
      if (!bound.accept(this, rhsType)) {
        if (mismatches == null) {
          return false; // diff-coverage: ignore -- no test reaches an intersection target that
          // fails, here or on master
        }
        contained =
            false; // diff-coverage: ignore -- no test reaches an intersection target that fails,
        // here or on master
      }
    }
    return contained;
  }

  @Override
  public Boolean visitArrayType(Type.ArrayType lhsType, Type rhsType) {
    if (rhsType instanceof NullType) {
      return true;
    }
    Type lhsComponentType = lhsType.getComponentType();
    if (!(rhsType instanceof Type.ArrayType rhsArrayType)) {
      // this can happen, e.g., with captured types.  don't attempt to handle this yet.
      return true;
    }
    Type rhsComponentType = rhsArrayType.getComponentType();
    NullabilityMismatches.Frame saved =
        mismatches == null ? null : mismatches.enterArrayElement(lhsType, rhsArrayType);
    try {
      return haveIdenticalNullability(lhsComponentType, rhsComponentType);
    } finally {
      restore(saved);
    }
  }

  @Override
  public Boolean visitType(Type t, Type type) {
    return true;
  }

  /**
   * Returns whether the actual type argument on the right is contained by the formal type argument
   * on the left, following the JLS 4.5.1 notion of type-argument containment but interpreted with
   * <a href="https://jspecify.dev/docs/spec/#subtyping">JSpecify's nullability-aware subtype
   * relation</a>. Non-wildcard pairs require matching nullability annotations and recursively
   * matching nested type arguments. Wildcard formals are delegated to {@link #wildcardContains}.
   *
   * @param lhsTypeArgument the formal type argument on the left
   * @param rhsTypeArgument the actual type argument on the right whose containment is checked
   * @param lhsUpperBound the upper bound of {@code lhsTypeArgument} ({@code lhsTypeArgument} itself
   *     if not a wildcard)
   * @param rhsUpperBound the upper bound of {@code rhsTypeArgument} ({@code rhsTypeArgument} itself
   *     if not a wildcard)
   * @param argumentIndex the type argument's position in both parameterizations
   * @param lhsParameterization the LHS parameterized type, identified by its declaration, enclosing
   *     type, and type arguments; {@code null} if wildcard handling is off
   * @param rhsParameterization the RHS parameterized type, identified by its declaration, enclosing
   *     type, and type arguments; {@code null} if wildcard handling is off
   * @return whether {@code rhsTypeArgument} is contained by {@code lhsTypeArgument}
   */
  private boolean typeArgumentContainedBy(
      Type lhsTypeArgument,
      Type rhsTypeArgument,
      Type lhsUpperBound,
      Type rhsUpperBound,
      int argumentIndex,
      @Nullable ParameterizationKey lhsParameterization,
      @Nullable ParameterizationKey rhsParameterization) {
    if (!config.handleWildcardGenerics()) {
      if (lhsTypeArgument.getKind().equals(TypeKind.WILDCARD)
          || rhsTypeArgument.getKind().equals(TypeKind.WILDCARD)) {
        // Preserve the pre-flag behavior of skipping wildcard-aware checks entirely.
        return true;
      }
    } else {
      // Do not use GenericsUtils.asWildcard() for the LHS. A captured LHS is a type variable, not a
      // wildcard formal; unwrapping it can repeatedly expand recursive upper bounds (for example,
      // F-bounded type parameters) as containment delegates back into subtype checking.  See test
      // com.uber.nullaway.jspecify.WildcardTests.capturedLhsWithFBoundedTypeParametersDoesNotRecurse
      Type.WildcardType lhsWildcard =
          lhsTypeArgument instanceof Type.WildcardType wildcardType ? wildcardType : null;
      Type.WildcardType rhsWildcard = GenericsUtils.asWildcard(rhsTypeArgument);
      if (lhsWildcard != null) {
        return wildcardContains(
            lhsWildcard,
            lhsUpperBound,
            rhsTypeArgument,
            rhsUpperBound,
            new ContainmentKey(
                lhsWildcard,
                rhsTypeArgument,
                argumentIndex,
                castToNonNull(lhsParameterization),
                castToNonNull(rhsParameterization)));
      }
      if (rhsWildcard != null) {
        // This case should only arise when generic method invocation inference / capture conversion
        // lets a wildcard actual argument flow into a non-wildcard formal type argument, e.g.,
        // passing Foo<? extends T> to <U> void m(Foo<U>). We do not yet support wildcard inference.
        // For non-inference assignment / return / parameter checks, javac rejects these conversions
        // before NullAway runs.
        // TODO: Add proper support when inference for wildcards is implemented.
        return true;
      }
    }
    return haveIdenticalNullability(lhsTypeArgument, rhsTypeArgument);
  }

  /**
   * Returns whether two types have identical top-level nullability and compatible nested
   * nullability.
   */
  private boolean haveIdenticalNullability(Type lhsType, Type rhsType) {
    boolean isLHSNullableAnnotated = genericsChecks.isNullableAnnotated(lhsType);
    boolean isRHSNullableAnnotated = genericsChecks.isNullableAnnotated(rhsType);
    if (isLHSNullableAnnotated != isRHSNullableAnnotated) {
      reject(NullabilityMismatches.Relation.SAME, lhsType, rhsType);
      return false;
    }
    return lhsType.accept(this, rhsType);
  }

  /**
   * Handles JLS 4.5.1 type-argument containment for wildcard formal type arguments, using
   * NullAway's nullability-aware subtype relation in place of plain Java subtyping. A formal {@code
   * ? extends S} contains actual arguments whose upper bound is a subtype of {@code S}; a formal
   * {@code ? super S} contains concrete actuals {@code T} and wildcard actuals {@code ? super T}
   * when {@code S <: T}; and a formal {@code ?} is treated as {@code ? extends B}, where {@code B}
   * is the corresponding type variable's upper bound.
   *
   * @param lhsWildcard the formal wildcard type argument on the left
   * @param lhsUpperBound the upper bound of {@code lhsWildcard}
   * @param rhsTypeArgument the actual type argument on the right whose containment is checked
   * @param rhsUpperBound the upper bound of {@code rhsTypeArgument}
   * @param key identifies this comparison: {@code lhsWildcard}, {@code rhsTypeArgument}, their
   *     position, and the parameterized types that contain them, before their bounds are computed
   * @return whether {@code lhsWildcard} contains {@code rhsTypeArgument}
   */
  private boolean wildcardContains(
      Type.WildcardType lhsWildcard,
      Type lhsUpperBound,
      Type rhsTypeArgument,
      Type rhsUpperBound,
      ContainmentKey key) {
    if (successfulWildcardComparisons != null && successfulWildcardComparisons.contains(key)) {
      return true;
    }
    boolean outermost = false;
    if (provisionalWildcardComparisons == null) {
      provisionalWildcardComparisons = new HashSet<>();
      outermost = true;
    }
    Set<ContainmentKey> provisional = provisionalWildcardComparisons;
    if (activeWildcardComparisons == null) {
      activeWildcardComparisons = new IdentityHashMap<>();
    }
    IdentityHashMap<Type.WildcardType, Set<Type>> active = activeWildcardComparisons;
    Set<Type> activeRhsArguments = active.get(lhsWildcard);
    if ((activeRhsArguments != null && activeRhsArguments.contains(rhsTypeArgument))
        || !provisional.add(key)) {
      // This check is in progress or has already passed. Any later failure still fails the outer
      // check.
      return true;
    }
    if (activeRhsArguments == null) {
      activeRhsArguments = Collections.newSetFromMap(new IdentityHashMap<>());
      active.put(lhsWildcard, activeRhsArguments);
    }
    activeRhsArguments.add(rhsTypeArgument);
    try {
      boolean result =
          switch (lhsWildcard.kind) {
            case UNBOUND, EXTENDS ->
                upperBoundContains(lhsWildcard, lhsUpperBound, rhsTypeArgument, rhsUpperBound);
            case SUPER -> superWildcardContains(lhsWildcard, rhsTypeArgument);
          };
      // A nested success may rely on an unfinished check, so cache results only after the outer
      // check passes. A failed result is not cached: without a recorder the failure ends the
      // check, and with one the provisional entry keeps the traversal from entering the same
      // comparison twice within the outer check, so recording stays linear in the bound graph.
      if (outermost && result) {
        if (successfulWildcardComparisons == null) {
          successfulWildcardComparisons = new HashSet<>();
        }
        successfulWildcardComparisons.addAll(provisional);
      }
      return result;
    } finally {
      activeRhsArguments.remove(rhsTypeArgument);
      if (activeRhsArguments.isEmpty()) {
        active.remove(lhsWildcard);
      }
      if (outermost) {
        provisionalWildcardComparisons = null;
      }
    }
  }

  /**
   * Returns whether the upper bound of the actual type argument on the right is a subtype of the
   * upper bound of the formal {@code ? extends S} or {@code ?} on the left.
   */
  private boolean upperBoundContains(
      Type.WildcardType lhsWildcard, Type lhsUpperBound, Type rhsTypeArgument, Type rhsUpperBound) {
    NullabilityMismatches.Frame saved =
        mismatches == null
            ? null
            : mismatches.enterUpperBounds(
                lhsWildcard,
                lhsUpperBound,
                GenericsUtils.asWildcard(rhsTypeArgument) == null ? null : rhsTypeArgument,
                rhsUpperBound);
    try {
      return typeArgumentSubtype(lhsUpperBound, rhsUpperBound);
    } finally {
      restore(saved);
    }
  }

  /**
   * Returns whether a formal {@code ? super S} contains the actual type argument on the right. For
   * concrete actuals {@code T} and wildcard actuals {@code ? super T}, containment holds when
   * {@code S <: T}, interpreted with NullAway's nullability-aware subtype relation.
   */
  private boolean superWildcardContains(Type.WildcardType lhsWildcard, Type rhsTypeArgument) {
    // caller must ensure that lhsWildcard has a super bound
    Type lhsBound = castToNonNull(lhsWildcard.getSuperBound());
    Type.WildcardType rhsWildcard = GenericsUtils.asWildcard(rhsTypeArgument);
    if (rhsWildcard != null) {
      if (rhsWildcard.kind != BoundKind.SUPER) {
        // This case cannot occur outside of inference: if the rhs is ? extends T, that is never
        // assignable to ? super S, since the rhs could be an arbitrary subtype of T (which may be a
        // subtype of S).
        // TODO handle when we implement inference
        return true;
      }
      Type rhsBound = castToNonNull(rhsWildcard.getSuperBound());
      NullabilityMismatches.Frame saved =
          mismatches == null
              ? null
              : mismatches.enterLowerBounds(lhsWildcard, lhsBound, rhsTypeArgument, rhsBound);
      try {
        return typeArgumentSubtype(rhsBound, lhsBound);
      } finally {
        restore(saved);
      }
    }
    NullabilityMismatches.Frame saved =
        mismatches == null
            ? null
            : mismatches.enterLowerBounds(lhsWildcard, lhsBound, null, rhsTypeArgument);
    try {
      return typeArgumentSubtype(rhsTypeArgument, lhsBound);
    } finally {
      restore(saved);
    }
  }

  /**
   * Returns whether the actual type argument on the right is a nullability-aware subtype of the
   * formal type argument on the left. This check first rejects flows from nullable to non-null at
   * the top level of the type argument, then compares the nested nullability with {@link
   * #isSubtype}.
   */
  private boolean typeArgumentSubtype(Type lhsType, Type rhsType) {
    boolean isLHSNullableAnnotated = genericsChecks.isNullableAnnotated(lhsType);
    boolean isRHSNullableAnnotated = genericsChecks.isNullableAnnotated(rhsType);
    if (isRHSNullableAnnotated && !isLHSNullableAnnotated) {
      reject(NullabilityMismatches.Relation.SUBTYPE, lhsType, rhsType);
      return false;
    }
    return isSubtype(lhsType, rhsType);
  }
}
