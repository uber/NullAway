package com.uber.nullaway.generics;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.common.base.Equivalence;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.VisitorState;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.Config;
import com.uber.nullaway.Nullness;
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
 */
public class CheckIdenticalNullabilityVisitor extends Types.DefaultTypeVisitor<Boolean, Type> {
  private final VisitorState state;
  private final GenericsChecks genericsChecks;
  private final Config config;
  private final Handler handler;
  private final ComparisonContext context;

  /**
   * What the caller of a comparison knows about the types it compares that the types do not show.
   *
   * @param uninferredTypeVariables type variables of the constructor or method whose call or method
   *     reference is being checked, where NullAway does not instantiate them; a bare one is
   *     compared by its annotation rather than by its bounds
   * @param actualFromCallInference whether the actual type is the type of a generic method call or
   *     a diamond constructor call that needs inference, directly or through a {@code var} local it
   *     initializes; that type is built from javac's types, whose captured wildcards carry no
   *     type-use annotation on their bounds, written or not
   */
  record ComparisonContext(
      ImmutableSet<Symbol.TypeSymbol> uninferredTypeVariables, boolean actualFromCallInference) {
    /** A context with no uninferred type variables and an actual type taken from the code. */
    static final ComparisonContext NONE = new ComparisonContext(ImmutableSet.of(), false);
  }

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
      VisitorState state,
      GenericsChecks genericsChecks,
      Config config,
      Handler handler,
      ComparisonContext context) {
    this.state = state;
    this.genericsChecks = genericsChecks;
    this.config = config;
    this.handler = handler;
    this.context = context;
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
      lhsUpperBounds =
          GenericsUtils.effectiveUpperBoundsForTypeArguments(lhsType, state, config, handler);
      rhsUpperBounds =
          GenericsUtils.effectiveUpperBoundsForTypeArguments(
              (Type.ClassType) rhsTypeAsSuper, state, config, handler);
    }
    List<Type> formalTypeVariables = lhsType.tsym.type.getTypeArguments();
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
          rhsParameterization,
          (Type.TypeVar) formalTypeVariables.get(i))) {
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
   * @param formalTypeVariable the type parameter of the generic class that both type arguments
   *     instantiate
   * @return whether {@code rhsTypeArgument} is contained by {@code lhsTypeArgument}
   */
  private boolean typeArgumentContainedBy(
      Type lhsTypeArgument,
      Type rhsTypeArgument,
      Type lhsUpperBound,
      Type rhsUpperBound,
      int argumentIndex,
      @Nullable ParameterizationKey lhsParameterization,
      @Nullable ParameterizationKey rhsParameterization,
      Type.TypeVar formalTypeVariable) {
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
                castToNonNull(rhsParameterization)),
            formalTypeVariable);
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
   * @param key identifies this comparison: {@code lhsWildcard}, {@code rhsTypeArgument}, their
   *     position, and the parameterized types that contain them, before their bounds are computed
   * @param formalTypeVariable the type parameter that {@code lhsWildcard} instantiates
   * @return whether {@code lhsWildcard} contains {@code rhsTypeArgument}
   */
  private boolean wildcardContains(
      Type.WildcardType lhsWildcard,
      Type lhsUpperBound,
      Type rhsTypeArgument,
      Type rhsUpperBound,
      ContainmentKey key,
      Type.TypeVar formalTypeVariable) {
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
                typeArgumentSubtype(
                    lhsUpperBound,
                    rhsUpperBound,
                    actualOperator(formalTypeVariable, rhsTypeArgument, rhsUpperBound));
            case SUPER -> superWildcardContains(lhsWildcard, rhsTypeArgument);
          };
      // A nested success may rely on an unfinished check, so cache results only after the outer
      // check passes. A failure ends the traversal immediately; there is no failed result to reuse.
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
   * The <a href="https://jspecify.dev/docs/spec/#nullness-operator">JSpecify nullness operator</a>
   * of a type usage: what the usage says about null beyond what its base type says.
   */
  enum NullnessOperator {
    /** The usage is annotated {@code @Nullable}, in the code or by NullAway. */
    UNION_NULL,
    /** The usage is annotated {@code @NonNull}, in the code or by NullAway. */
    MINUS_NULL,
    /** The usage carries no nullness annotation and takes the nullness of its base type. */
    NO_CHANGE,
    /**
     * The check does not decide the usage's nullness: inference removed its annotations, or another
     * check owns the question. {@link #actualOperator} assigns it to an actual type argument, and
     * {@link #isNullExclusive} reads it as excluding null, which is the specification's "some
     * world" rule.
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
    return NullnessOperator.NO_CHANGE;
  }

  /**
   * Returns the operator of an actual type argument's effective upper bound against a formal {@code
   * ?} or {@code ? extends}: the operator written on that bound, which for a capture is the one
   * written on the wildcard it captures. The operator is {@link NullnessOperator#UNSPECIFIED} where
   * the bound carries none and either of these holds:
   *
   * <ul>
   *   <li>The actual is a capture in the type of a generic call that needs inference, as {@link
   *       ComparisonContext#actualFromCallInference} says, so its bound may have lost a written
   *       {@code @NonNull}. A bound written bare is accepted too: a false negative until NullAway
   *       infers the full types of a call (#1585, #2195).
   *   <li>The bound is a type variable and the bound of {@code formalTypeVariable} is not nullable,
   *       as {@link GenericsUtils#upperBoundIsNullable} decides. Whether such an actual fits is
   *       whether it is a well-formed instantiation of {@code formalTypeVariable}, a check of its
   *       own (#1946), and the answer is the same for {@code ?} and for any {@code ? extends} on
   *       that type variable.
   * </ul>
   */
  private NullnessOperator actualOperator(
      Type.TypeVar formalTypeVariable, Type rhsTypeArgument, Type rhsUpperBound) {
    NullnessOperator operator = operatorOf(rhsUpperBound);
    if (operator == NullnessOperator.NO_CHANGE
        && rhsTypeArgument instanceof Type.CapturedType
        && context.actualFromCallInference()) {
      return NullnessOperator.UNSPECIFIED;
    }
    if (operator == NullnessOperator.NO_CHANGE
        && rhsUpperBound instanceof Type.TypeVar
        && !GenericsUtils.upperBoundIsNullable(
            formalTypeVariable.asElement(), config, handler, state)) {
      return NullnessOperator.UNSPECIFIED;
    }
    return operator;
  }

  /**
   * Returns whether the check treats a value of the type as null-exclusive: never null under any
   * instantiation of the type variables in scope, or {@link NullnessOperator#UNSPECIFIED}, which
   * the check reads leniently. That holds for {@link NullnessOperator#MINUS_NULL} and {@link
   * NullnessOperator#UNSPECIFIED}, never for {@link NullnessOperator#UNION_NULL}, and for {@link
   * NullnessOperator#NO_CHANGE} where the type is one of these:
   *
   * <ul>
   *   <li>a type variable whose bound is not explicitly nullable, as {@link
   *       GenericsUtils#boundIsExplicitlyNullable} decides;
   *   <li>an intersection with a null-exclusive explicit element, as the bound of a capture or the
   *       least upper bound of the branches of a conditional expression can be;
   *   <li>any other type: a class or an array.
   * </ul>
   */
  private boolean isNullExclusive(Type type, NullnessOperator operator) {
    return switch (operator) {
      case UNION_NULL -> false;
      case MINUS_NULL, UNSPECIFIED -> true;
      case NO_CHANGE -> {
        if (type instanceof Type.TypeVar typeVar) {
          yield !GenericsUtils.boundIsExplicitlyNullable(
              typeVar.asElement(), config, handler, state);
        }
        if (type instanceof Type.IntersectionClassType intersection) {
          yield intersection.getExplicitComponents().stream()
              .anyMatch(component -> isNullExclusive(component, operatorOf(component)));
        }
        yield true;
      }
    };
  }

  /**
   * Returns whether the type reaches the type variable {@code target} through declared upper bounds
   * none of which is explicitly nullable: the specification's nullness-subtype-establishing path,
   * which makes {@code S} a subtype of {@code T} for {@code <S extends T>} and {@code <S
   * extends @NonNull T>} but not for {@code <S extends @Nullable T>}. The path may pass through any
   * element of an intersection, so a capture bounded by {@code T & @Nullable Runnable} reaches
   * {@code T}. Returns {@code false} where no chain of bounds reaches {@code target}, as for a
   * {@code target} declared by another method.
   */
  private boolean hasSubtypeEstablishingPath(
      Type type, NullnessOperator operator, Type.TypeVar target) {
    if (operator == NullnessOperator.UNION_NULL) {
      return false;
    }
    if (type instanceof Type.IntersectionClassType intersection) {
      return intersection.getExplicitComponents().stream()
          .anyMatch(
              component -> hasSubtypeEstablishingPath(component, operatorOf(component), target));
    }
    if (!(type instanceof Type.TypeVar typeVar)) {
      return false;
    }
    if (typeVar.tsym.equals(target.tsym)) {
      return true;
    }
    if (GenericsUtils.declaredBoundIsExplicitlyNullable(
        typeVar.asElement(), config, handler, state)) {
      return false;
    }
    return hasSubtypeEstablishingPath(typeVar.getUpperBound(), NullnessOperator.NO_CHANGE, target);
  }

  /**
   * Returns whether {@code subtype} is a <a
   * href="https://jspecify.dev/docs/spec/#nullness-subtyping">JSpecify nullness subtype</a> of
   * {@code supertype}, judged at the top level only: a value of {@code subtype} that may be null
   * fits {@code supertype} only where {@code supertype} admits null. That holds where {@code
   * supertype} is null-inclusive, where {@code subtype} is null-exclusive, or where {@code
   * supertype} is a type variable, not written {@code @NonNull}, that {@code subtype} reaches
   * through bounds none of which is explicitly nullable. Nested type arguments are compared by the
   * caller.
   *
   * <p>Where either side is a bare type variable that NullAway does not infer at the call or method
   * reference being checked (see {@link ComparisonContext#uninferredTypeVariables}), its bounds are
   * not read, and only a {@code subtype} annotated {@code @Nullable} fails.
   */
  private boolean isNullnessSubtype(
      Type subtype,
      NullnessOperator subtypeOperator,
      Type supertype,
      NullnessOperator supertypeOperator) {
    if (isUninferredTypeVariable(subtype, subtypeOperator)
        || isUninferredTypeVariable(supertype, supertypeOperator)) {
      return subtypeOperator != NullnessOperator.UNION_NULL;
    }
    if (supertypeOperator == NullnessOperator.UNION_NULL
        || isNullExclusive(subtype, subtypeOperator)) {
      return true;
    }
    return supertype instanceof Type.TypeVar target
        && supertypeOperator != NullnessOperator.MINUS_NULL
        && hasSubtypeEstablishingPath(subtype, subtypeOperator, target);
  }

  /**
   * Returns whether {@code type} is a type variable, written without a nullness annotation, that
   * NullAway does not infer at the call or method reference being checked.
   */
  private boolean isUninferredTypeVariable(Type type, NullnessOperator operator) {
    return operator == NullnessOperator.NO_CHANGE
        && type instanceof Type.TypeVar
        && !(type instanceof Type.CapturedType)
        && context.uninferredTypeVariables().contains(type.tsym);
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
   * Returns whether {@code rhsType} is a nullability-aware subtype of {@code lhsType}. For a formal
   * {@code ? extends}, {@code rhsType} is the actual's upper bound; for a formal {@code ? super S},
   * {@code rhsType} is {@code S} and {@code lhsType} is the actual. The top level is {@link
   * #isNullnessSubtype}; nested type arguments are delegated to {@link
   * GenericsChecks#subtypeParameterNullability(Type, Type, VisitorState,
   * CheckIdenticalNullabilityVisitor)}.
   *
   * @param lhsType the supertype
   * @param rhsType the subtype
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
