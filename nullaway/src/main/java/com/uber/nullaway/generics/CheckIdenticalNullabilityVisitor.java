package com.uber.nullaway.generics;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.errorprone.VisitorState;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.Config;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.handlers.Handler;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.lang.model.type.NullType;
import javax.lang.model.type.TypeKind;
import org.jspecify.annotations.Nullable;

/**
 * Visitor that checks for identical nullability annotations at all nesting levels within two types.
 * Compares the Type it is called upon, i.e. the LHS type and the Type passed as an argument, i.e.
 * The RHS type.
 */
public class CheckIdenticalNullabilityVisitor extends Types.DefaultTypeVisitor<Boolean, Type> {
  private final VisitorState state;
  private final GenericsChecks genericsChecks;
  private final Config config;
  private final Handler handler;

  /**
   * Wildcard argument pairs currently being checked for containment. Allocated lazily, as the map
   * is only needed for types involving wildcards.
   */
  private @Nullable IdentityHashMap<Type.WildcardType, Set<Type>> activeWildcardComparisons;

  private IdentityHashMap<Type.WildcardType, Set<Type>> getActiveWildcardComparisons() {
    if (activeWildcardComparisons == null) {
      activeWildcardComparisons = new IdentityHashMap<>();
    }
    return activeWildcardComparisons;
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
    if (config.handleWildcardGenerics()) {
      lhsUpperBounds =
          GenericsUtils.effectiveUpperBoundsForTypeArguments(lhsType, state, config, handler);
      rhsUpperBounds =
          GenericsUtils.effectiveUpperBoundsForTypeArguments(
              (Type.ClassType) rhsTypeAsSuper, state, config, handler);
    }
    for (int i = 0; i < lhsTypeArguments.size(); i++) {
      Type lhsTypeArgument = lhsTypeArguments.get(i);
      Type lhsUpperBound = lhsUpperBounds.get(i);
      Type rhsTypeArgument = rhsTypeArguments.get(i);
      Type rhsUpperBound = rhsUpperBounds.get(i);
      if (!typeArgumentContainedBy(
          lhsTypeArgument, rhsTypeArgument, lhsUpperBound, rhsUpperBound)) {
        return false;
      }
    }
    // If there is an enclosing type (for non-static inner classes), its type argument nullability
    // should also match.  When there is no enclosing type, getEnclosingType() returns a NoType
    // object, which gets handled by the fallback visitType() method
    // NOTE: I don't think we need to use rhsTypeAsSuper here, since the enclosing type of rhsType
    // should be converted properly via another call to asSuper when we recurse.
    return lhsType.getEnclosingType().accept(this, rhsType.getEnclosingType());
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
   * @return whether {@code rhsTypeArgument} is contained by {@code lhsTypeArgument}
   */
  private boolean typeArgumentContainedBy(
      Type lhsTypeArgument, Type rhsTypeArgument, Type lhsUpperBound, Type rhsUpperBound) {
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
        return wildcardContains(lhsWildcard, lhsUpperBound, rhsTypeArgument, rhsUpperBound);
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
   * is the corresponding type variable's upper bound. The top-level nullness of each subtype check
   * is {@link #isNullnessSubtype}, which judges a type variable by its declared bounds.
   *
   * @param lhsWildcard the formal wildcard type argument on the left
   * @param lhsUpperBound the upper bound of {@code lhsWildcard}
   * @param rhsTypeArgument the actual type argument on the right whose containment is checked
   * @param rhsUpperBound the upper bound of {@code rhsTypeArgument}
   * @return whether {@code lhsWildcard} contains {@code rhsTypeArgument}
   */
  private boolean wildcardContains(
      Type.WildcardType lhsWildcard, Type lhsUpperBound, Type rhsTypeArgument, Type rhsUpperBound) {
    IdentityHashMap<Type.WildcardType, Set<Type>> activeComparisons =
        getActiveWildcardComparisons();
    Set<Type> activeRhsArguments = activeComparisons.get(lhsWildcard);
    if (activeRhsArguments == null) {
      activeRhsArguments = Collections.newSetFromMap(new IdentityHashMap<>());
      activeComparisons.put(lhsWildcard, activeRhsArguments);
    } else if (activeRhsArguments.contains(rhsTypeArgument)) {
      // Recursive bounds can lead back to the exact same containment question. Re-entering that
      // pair cannot reveal a mismatch that was not already handled on the first visit.
      return true;
    }
    activeRhsArguments.add(rhsTypeArgument);
    try {
      return switch (lhsWildcard.kind) {
        case UNBOUND, EXTENDS ->
            typeArgumentSubtype(
                lhsUpperBound,
                rhsUpperBound,
                actualOperator(lhsWildcard.kind, rhsTypeArgument, rhsUpperBound));
        case SUPER -> superWildcardContains(lhsWildcard, rhsTypeArgument);
      };
    } finally {
      activeRhsArguments.remove(rhsTypeArgument);
      if (activeRhsArguments.isEmpty()) {
        activeComparisons.remove(lhsWildcard);
      }
    }
  }

  /**
   * The <a href="https://jspecify.dev/docs/spec/#nullness-operator">JSpecify nullness operator</a>
   * of a type usage: what the usage says about null beyond what its base type says. {@link
   * #isNullInclusive}, {@link #isNullExclusive}, and {@link #hasSubtypeEstablishingPath} read
   * {@link #UNSPECIFIED} leniently, as the operator the check needs it to be, which is the
   * specification's "some world" rule.
   */
  enum NullnessOperator {
    /** The usage is annotated {@code @Nullable}, in the code or by NullAway. */
    UNION_NULL,
    /** The usage is annotated {@code @NonNull}, in the code or by NullAway. */
    MINUS_NULL,
    /** The usage carries no annotation and takes the nullness of its base type. */
    NO_CHANGE,
    /**
     * The usage carries no decision this check can read. {@link #operatorOf} assigns it to a type
     * inference marked {@link ConstraintSolver.InferredNullability#UNCONSTRAINED}, whose nullness
     * is that of a type variable the substitution no longer names; {@link #boundOperator} to a bare
     * class-type bound, or a bare element of an intersection bound, declared in unannotated code;
     * and {@link #actualOperator} to a bare type variable left by capture conversion, which drops a
     * written {@code @NonNull}, and to a bare type variable actual against an unbounded {@code ?}.
     */
    UNSPECIFIED
  }

  private NullnessOperator operatorOf(Type type) {
    if (genericsChecks.isNullableAnnotated(type)) {
      return NullnessOperator.UNION_NULL;
    }
    if (Nullness.hasNonNullAnnotation(type.getAnnotationMirrors().stream(), config)) {
      return NullnessOperator.MINUS_NULL;
    }
    if (type.getAnnotationMirrors().stream()
        .anyMatch(a -> GenericsChecks.isSyntheticUnconstrainedAnnotation(a.type))) {
      return NullnessOperator.UNSPECIFIED;
    }
    return NullnessOperator.NO_CHANGE;
  }

  /**
   * Returns the operator of an actual type argument's effective upper bound. A bare type variable
   * is {@link NullnessOperator#UNSPECIFIED} where the actual is a captured type, and where the
   * formal is an unbounded {@code ?}: there the implicit bound is the formal type parameter's own,
   * which the actual already instantiates, so whether it satisfies that bound is a question about
   * the declaration of the actual, not about containment.
   */
  private NullnessOperator actualOperator(
      BoundKind lhsWildcardKind, Type rhsTypeArgument, Type rhsUpperBound) {
    NullnessOperator operator = operatorOf(rhsUpperBound);
    if (operator == NullnessOperator.NO_CHANGE
        && rhsUpperBound instanceof Type.TypeVar
        && (rhsTypeArgument instanceof Type.CapturedType || lhsWildcardKind == BoundKind.UNBOUND)) {
      return NullnessOperator.UNSPECIFIED;
    }
    return operator;
  }

  /**
   * Returns the operator of a type variable's declared upper bound, or of one element of it where
   * the bound is an intersection. A library model that makes the bound nullable counts as {@link
   * NullnessOperator#UNION_NULL}. A bare class-type bound declared in unannotated code is {@link
   * NullnessOperator#UNSPECIFIED}; a bare bound that names another type variable stays {@link
   * NullnessOperator#NO_CHANGE} there too, so the walk follows it to that variable's own
   * declaration, as {@link GenericsUtils#boundIsExplicitlyNullable} does. An intersection is {@link
   * NullnessOperator#NO_CHANGE} as a whole, since javac keeps the annotations on its elements, and
   * each element is read by a call of its own.
   */
  private NullnessOperator boundOperator(Type.TypeVar typeVar, Type bound) {
    NullnessOperator operator = operatorOf(bound);
    if (operator != NullnessOperator.NO_CHANGE) {
      return operator;
    }
    if (GenericsUtils.libraryModelMakesBoundNullable(typeVar.asElement(), handler, state)) {
      return NullnessOperator.UNION_NULL;
    }
    if (!(bound instanceof Type.TypeVar)
        && !bound.isIntersection()
        && GenericsUtils.fromUnannotatedMethodOrClass(
            typeVar.asElement(), config, handler, state)) {
      return NullnessOperator.UNSPECIFIED;
    }
    return NullnessOperator.NO_CHANGE;
  }

  /** Returns the elements of an intersection type. */
  private static Stream<Type> elementsOf(Type intersection) {
    return ((Type.IntersectionClassType) intersection)
        .getExplicitComponents().stream().map(e -> (Type) e);
  }

  /**
   * Returns whether the type includes null under every instantiation of the type variables in
   * scope: the usage is {@link NullnessOperator#UNION_NULL}, or an intersection whose every element
   * includes null.
   */
  private boolean isNullInclusive(Type type, NullnessOperator operator) {
    if (operator == NullnessOperator.UNION_NULL || operator == NullnessOperator.UNSPECIFIED) {
      return true;
    }
    if (type.isIntersection()) {
      return elementsOf(type).allMatch(e -> isNullInclusive(e, operatorOf(e)));
    }
    return false;
  }

  /**
   * Returns whether a value of the type is never null under any instantiation of the type variables
   * in scope: the usage is not {@link NullnessOperator#UNION_NULL}, and it is {@link
   * NullnessOperator#MINUS_NULL}, a class, array, or null type, a type variable whose declared
   * bound is null-exclusive and not nullable, or an intersection with a null-exclusive element.
   */
  private boolean isNullExclusive(Type type, NullnessOperator operator) {
    if (operator == NullnessOperator.UNION_NULL) {
      return false;
    }
    if (operator == NullnessOperator.MINUS_NULL || operator == NullnessOperator.UNSPECIFIED) {
      return true;
    }
    if (type instanceof Type.TypeVar typeVar) {
      Type bound = typeVar.getUpperBound();
      NullnessOperator boundOperator = boundOperator(typeVar, bound);
      if (boundOperator == NullnessOperator.UNION_NULL) {
        return false;
      }
      if (bound.isIntersection()) {
        return elementsOf(bound).anyMatch(e -> isNullExclusive(e, boundOperator(typeVar, e)));
      }
      return isNullExclusive(bound, boundOperator);
    }
    if (type.isIntersection()) {
      return elementsOf(type).anyMatch(e -> isNullExclusive(e, operatorOf(e)));
    }
    return true;
  }

  /**
   * Returns whether the type reaches the type variable {@code target} through declared upper bounds
   * none of which is nullable: the specification's nullness-subtype-establishing path, which makes
   * {@code S} a subtype of {@code T} for {@code <S extends T>} but not for {@code <S
   * extends @Nullable T>}. Returns {@code false} where no chain of bounds reaches {@code target},
   * as for a {@code target} declared by another method.
   */
  private boolean hasSubtypeEstablishingPath(
      Type type, NullnessOperator operator, Type.TypeVar target) {
    if (operator == NullnessOperator.UNION_NULL) {
      return false;
    }
    if (type instanceof Type.TypeVar typeVar) {
      if (typeVar.tsym.equals(target.tsym)) {
        return true;
      }
      Type bound = typeVar.getUpperBound();
      NullnessOperator boundOperator = boundOperator(typeVar, bound);
      if (boundOperator == NullnessOperator.UNION_NULL) {
        return false;
      }
      if (bound.isIntersection()) {
        return elementsOf(bound)
            .anyMatch(e -> hasSubtypeEstablishingPath(e, boundOperator(typeVar, e), target));
      }
      return hasSubtypeEstablishingPath(bound, boundOperator, target);
    }
    if (type.isIntersection()) {
      return elementsOf(type).anyMatch(e -> hasSubtypeEstablishingPath(e, operatorOf(e), target));
    }
    return false;
  }

  /**
   * Returns whether {@code subtype} is a <a
   * href="https://jspecify.dev/docs/spec/#nullness-subtyping">JSpecify nullness subtype</a> of
   * {@code supertype}, judged at the top level only: a value of {@code subtype} that may be null
   * fits {@code supertype} only where {@code supertype} admits null. That holds where {@code
   * supertype} is null-inclusive, where {@code subtype} is null-exclusive, or where {@code
   * supertype} is a type variable, not written {@code @NonNull}, that {@code subtype} reaches
   * through bounds none of which is nullable. Nested type arguments are compared by the caller.
   */
  private boolean isNullnessSubtype(
      Type subtype,
      NullnessOperator subtypeOperator,
      Type supertype,
      NullnessOperator supertypeOperator) {
    if (isNullInclusive(supertype, supertypeOperator)
        || isNullExclusive(subtype, subtypeOperator)) {
      return true;
    }
    return supertype instanceof Type.TypeVar target
        && supertypeOperator != NullnessOperator.MINUS_NULL
        && hasSubtypeEstablishingPath(subtype, subtypeOperator, target);
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
      return typeArgumentSubtype(rhsBound, lhsBound, operatorOf(lhsBound));
    }
    return typeArgumentSubtype(rhsTypeArgument, lhsBound, operatorOf(lhsBound));
  }

  /**
   * Returns whether the actual type argument on the right is a nullability-aware subtype of the
   * formal type argument on the left. The top level is {@link #isNullnessSubtype}; nested type
   * arguments are delegated to {@link GenericsChecks#subtypeParameterNullability(Type, Type,
   * VisitorState, CheckIdenticalNullabilityVisitor)}.
   *
   * @param lhsType the formal type argument on the left
   * @param rhsType the actual type argument on the right
   * @param rhsOperator the nullness operator of {@code rhsType}, which the caller may have relaxed
   *     to {@link NullnessOperator#UNSPECIFIED}
   */
  private boolean typeArgumentSubtype(Type lhsType, Type rhsType, NullnessOperator rhsOperator) {
    if (!isNullnessSubtype(rhsType, rhsOperator, lhsType, operatorOf(lhsType))) {
      return false;
    }
    return genericsChecks.subtypeParameterNullability(lhsType, rhsType, state, this);
  }
}
