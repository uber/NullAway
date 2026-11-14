package com.uber.nullaway.generics;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Equivalence;
import com.google.errorprone.VisitorState;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.Config;
import com.uber.nullaway.handlers.Handler;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.type.NullType;
import javax.lang.model.type.TypeKind;
import org.jspecify.annotations.Nullable;

/**
 * Visitor that checks for identical nullability annotations at all nesting levels within two types.
 * Compares the Type it is called upon, i.e. the LHS type and the Type passed as an argument, i.e.
 * The RHS type.
 *
 * <p>The effective-upper-bound and completed-containment caches last for the visitor instance.
 * {@link GenericsChecks} creates one instance per top-level {@code subtypeParameterNullability}
 * call and reuses it for recursive comparisons. Later comparisons on the same instance can reuse
 * cached results for the same identity-based keys, provided the relevant type structure and
 * nullness annotations have not changed. Pending containment results last only for one outermost
 * {@code wildcardContains} call.
 */
public class CheckIdenticalNullabilityVisitor extends Types.DefaultTypeVisitor<Boolean, Type> {
  private final VisitorState state;
  private final GenericsChecks genericsChecks;
  private final Config config;
  private final Handler handler;

  /**
   * Containment successes proven by an outermost comparison, retained for this visitor instance.
   */
  private @Nullable Set<ContainmentKey> completedWildcardComparisons;

  /**
   * Active and provisionally successful comparisons in the current wildcard traversal. All
   * obligations are conjunctive, so these can be reused within the traversal, but are published as
   * completed only if its outermost comparison succeeds. A failure or exception discards them.
   */
  private @Nullable Set<ContainmentKey> pendingWildcardComparisons;

  /**
   * Effective upper bounds of type arguments, retained for this visitor instance. This avoids
   * repeating capture conversion and bound resolution when the same parameterized type is compared
   * again, even with a different counterpart. Cycle termination does not depend on this cache.
   */
  private @Nullable Map<ParameterizationKey, List<Type>> effectiveUpperBounds;

  /**
   * Identifies a parameterization without relying on the identity of its class-type wrapper, which
   * capture conversion can recreate. Bound computation depends on the declaration, enclosing type,
   * and argument identities, not the containing class type's top-level nullness annotation.
   */
  @VisibleForTesting
  record ParameterizationKey(
      Equivalence.Wrapper<Symbol.TypeSymbol> symbol,
      Equivalence.Wrapper<Type> enclosing,
      List<Equivalence.Wrapper<Type>> arguments) {
    /** Uses identity equivalence because javac type equality can ignore annotation metadata. */
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
   * Identifies an argument-containment comparison by its position in the two parameterized types,
   * rather than by the identities of their computed effective upper bounds. This lets the cycle
   * guard terminate independently of the upper-bound cache. The argument index distinguishes a
   * shared wildcard used at multiple positions with different declared bounds.
   */
  private record ContainmentKey(
      Equivalence.Wrapper<Type.WildcardType> lhsWildcard,
      Equivalence.Wrapper<Type> rhsArgument,
      int argumentIndex,
      ParameterizationKey lhsParameterization,
      ParameterizationKey rhsParameterization) {
    /** Preserves the argument identities and both parameterized types when comparing questions. */
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
    this.state = state;
    this.genericsChecks = genericsChecks;
    this.config = config;
    this.handler = handler;
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
      rhsType = rhsUpperBound;
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
    List<Type> lhsUpperBounds = lhsTypeArguments;
    List<Type> rhsUpperBounds = rhsTypeArguments;
    ParameterizationKey lhsParameterization = null;
    ParameterizationKey rhsParameterization = null;
    if (config.handleWildcardGenerics()) {
      lhsParameterization = new ParameterizationKey(lhsType);
      rhsParameterization = new ParameterizationKey((Type.ClassType) rhsTypeAsSuper);
      lhsUpperBounds = effectiveUpperBounds(lhsType, lhsParameterization);
      rhsUpperBounds = effectiveUpperBounds((Type.ClassType) rhsTypeAsSuper, rhsParameterization);
    }
    for (int i = 0; i < lhsTypeArguments.size(); i++) {
      Type lhsTypeArgument = lhsTypeArguments.get(i);
      Type lhsUpperBound = lhsUpperBounds.get(i);
      Type rhsTypeArgument = rhsTypeArguments.get(i);
      Type rhsUpperBound = rhsUpperBounds.get(i);
      if (!typeArgumentContainedBy(
          lhsTypeArgument,
          rhsTypeArgument,
          lhsUpperBound,
          rhsUpperBound,
          i,
          lhsParameterization,
          rhsParameterization)) {
        return false;
      }
    }
    // If there is an enclosing type (for non-static inner classes), its type argument nullability
    // should also match.  When there is no enclosing type, getEnclosingType() returns a NoType
    // object, which gets handled by the fallback visitType() method
    // We use rhsTypeAsSuper here rather than rhsType, since rhsType may be a captured type
    // variable, whose getEnclosingType() returns null.
    return lhsType.getEnclosingType().accept(this, rhsTypeAsSuper.getEnclosingType());
  }

  /** Returns effective upper bounds computed once per parameterized type per visitor instance. */
  @VisibleForTesting
  List<Type> effectiveUpperBounds(Type.ClassType type, ParameterizationKey key) {
    if (effectiveUpperBounds == null) {
      effectiveUpperBounds = new HashMap<>();
    }
    return effectiveUpperBounds.computeIfAbsent(key, unused -> computeEffectiveUpperBounds(type));
  }

  /** Computes effective upper bounds on a cache miss; tests override this to count computations. */
  @VisibleForTesting
  List<Type> computeEffectiveUpperBounds(Type.ClassType type) {
    return GenericsUtils.effectiveUpperBoundsForTypeArguments(type, state, config, handler);
  }

  /** Check identical nullability for every type in the intersection */
  private Boolean handleIntersectionType(
      Type.IntersectionClassType intersectionType, Type rhsType) {
    return intersectionType.getBounds().stream()
        .allMatch(type -> ((Type) type).accept(this, rhsType));
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
    return haveIdenticalNullability(lhsComponentType, rhsComponentType);
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
   * @param key the argument position and parameterized types before capture conversion
   * @return whether {@code lhsWildcard} contains {@code rhsTypeArgument}
   */
  private boolean wildcardContains(
      Type.WildcardType lhsWildcard,
      Type lhsUpperBound,
      Type rhsTypeArgument,
      Type rhsUpperBound,
      ContainmentKey key) {
    if (completedWildcardComparisons != null && completedWildcardComparisons.contains(key)) {
      return true;
    }
    boolean outermost = pendingWildcardComparisons == null;
    if (outermost) {
      pendingWildcardComparisons = new HashSet<>();
    }
    Set<ContainmentKey> pending = castToNonNull(pendingWildcardComparisons);
    if (!pending.add(key)) {
      // A repeated question is either active (a cycle) or already checked in this traversal.
      // Its success remains provisional until every obligation of the outermost question passes.
      return true;
    }
    try {
      boolean result =
          switch (lhsWildcard.kind) {
            case UNBOUND, EXTENDS -> typeArgumentSubtype(lhsUpperBound, rhsUpperBound);
            case SUPER -> superWildcardContains(lhsWildcard, rhsTypeArgument);
          };
      if (outermost && result) {
        if (completedWildcardComparisons == null) {
          completedWildcardComparisons = new HashSet<>();
        }
        completedWildcardComparisons.addAll(pending);
      }
      return result;
    } finally {
      if (outermost) {
        pendingWildcardComparisons = null;
      }
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
      return typeArgumentSubtype(rhsBound, lhsBound);
    }
    return typeArgumentSubtype(rhsTypeArgument, lhsBound);
  }

  /**
   * Returns whether the actual type argument on the right is a nullability-aware subtype of the
   * formal type argument on the left. This check first rejects flows from nullable to non-null at
   * the top level of the type argument, then delegates to {@link
   * GenericsChecks#subtypeParameterNullability(Type, Type, VisitorState)} for recursive nested
   * checks.
   */
  private boolean typeArgumentSubtype(Type lhsType, Type rhsType) {
    boolean isLHSNullableAnnotated = genericsChecks.isNullableAnnotated(lhsType);
    boolean isRHSNullableAnnotated = genericsChecks.isNullableAnnotated(rhsType);
    if (isRHSNullableAnnotated && !isLHSNullableAnnotated) {
      return false;
    }
    return genericsChecks.subtypeParameterNullability(lhsType, rhsType, state, this);
  }
}
