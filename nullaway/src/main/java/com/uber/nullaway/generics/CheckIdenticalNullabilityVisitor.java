package com.uber.nullaway.generics;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.errorprone.VisitorState;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.Config;
import java.util.List;
import javax.lang.model.type.NullType;
import javax.lang.model.type.TypeKind;

/**
 * Visitor that checks for identical nullability annotations at all nesting levels within two types.
 * Compares the Type it is called upon, i.e. the LHS type and the Type passed as an argument, i.e.
 * The RHS type.
 */
public class CheckIdenticalNullabilityVisitor extends Types.DefaultTypeVisitor<Boolean, Type> {
  private final VisitorState state;
  private final GenericsChecks genericsChecks;
  private final Config config;

  CheckIdenticalNullabilityVisitor(
      VisitorState state, GenericsChecks genericsChecks, Config config) {
    this.state = state;
    this.genericsChecks = genericsChecks;
    this.config = config;
  }

  @Override
  public Boolean visitClassType(Type.ClassType lhsType, Type rhsType) {
    if (rhsType instanceof NullType || rhsType.isPrimitive()) {
      return true;
    }
    if (rhsType.getKind().equals(TypeKind.WILDCARD)) {
      // TODO Handle wildcard types
      return true;
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
    for (int i = 0; i < lhsTypeArguments.size(); i++) {
      Type lhsTypeArgument = lhsTypeArguments.get(i);
      Type rhsTypeArgument = rhsTypeArguments.get(i);
      if (!typeArgumentContainedBy(lhsTypeArgument, rhsTypeArgument)) {
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
    boolean isLHSNullableAnnotated = genericsChecks.isNullableAnnotated(lhsComponentType);
    boolean isRHSNullableAnnotated = genericsChecks.isNullableAnnotated(rhsComponentType);
    if (isRHSNullableAnnotated != isLHSNullableAnnotated) {
      return false;
    }
    return lhsComponentType.accept(this, rhsComponentType);
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
   */
  private boolean typeArgumentContainedBy(Type lhsTypeArgument, Type rhsTypeArgument) {
    if (!config.handleWildcardGenerics()
        && (lhsTypeArgument.getKind().equals(TypeKind.WILDCARD)
            || rhsTypeArgument.getKind().equals(TypeKind.WILDCARD))) {
      // Preserve the pre-flag behavior of skipping wildcard-aware checks entirely.
      return true;
    }
    if (lhsTypeArgument instanceof Type.WildcardType lhsWildcard) {
      return wildcardContains(lhsWildcard, rhsTypeArgument);
    }
    if (rhsTypeArgument.getKind().equals(TypeKind.WILDCARD)) {
      // This case should only arise when generic method invocation inference / capture conversion
      // lets a wildcard actual argument flow into a non-wildcard formal type argument, e.g.,
      // passing Foo<? extends T> to <U> void m(Foo<U>). We do not yet support wildcard inference.
      // For non-inference assignment / return / parameter checks, javac rejects these conversions
      // before NullAway runs.
      // TODO: Add proper support when inference for wildcards is implemented.
      return true;
    }
    boolean isLHSNullableAnnotated = genericsChecks.isNullableAnnotated(lhsTypeArgument);
    boolean isRHSNullableAnnotated = genericsChecks.isNullableAnnotated(rhsTypeArgument);
    if (isLHSNullableAnnotated != isRHSNullableAnnotated) {
      return false;
    }
    return lhsTypeArgument.accept(this, rhsTypeArgument);
  }

  /**
   * Handles JLS 4.5.1 type-argument containment for wildcard formal type arguments, using
   * NullAway's nullability-aware subtype relation in place of plain Java subtyping. A formal {@code
   * ? extends S} contains actual arguments whose effective upper bound is a subtype of {@code S}; a
   * formal {@code ? super S} contains concrete actuals {@code T} and wildcard actuals {@code ?
   * super T} when {@code S <: T}; and a formal {@code ?} is treated as {@code ? extends B}, where
   * {@code B} is the corresponding type variable's upper bound.
   */
  private boolean wildcardContains(Type.WildcardType lhsWildcard, Type rhsTypeArgument) {
    return switch (lhsWildcard.kind) {
      case UNBOUND, EXTENDS ->
          extendsBoundContains(wildcardUpperBound(lhsWildcard), rhsTypeArgument);
      case SUPER -> superWildcardContains(lhsWildcard, rhsTypeArgument);
    };
  }

  /**
   * Returns whether a formal {@code ? extends S} contains the actual type argument on the right.
   * For concrete actuals {@code T}, wildcard actuals {@code ? extends T}, and non-extends wildcard
   * actuals whose effective upper bound is {@code T}, containment holds when {@code T <: S}.
   */
  private boolean extendsBoundContains(Type lhsBound, Type rhsTypeArgument) {
    if (rhsTypeArgument instanceof Type.WildcardType rhsWildcard) {
      Type rhsUpperBound = wildcardUpperBound(rhsWildcard);
      return typeArgumentSubtype(lhsBound, rhsUpperBound);
    }
    return typeArgumentSubtype(lhsBound, rhsTypeArgument);
  }

  /**
   * Returns the effective upper bound of a wildcard, using the corresponding type variable's upper
   * bound for unbounded wildcards and {@code super} wildcards.
   */
  private Type wildcardUpperBound(Type.WildcardType wildcardType) {
    Type upperBound;
    if (wildcardType.kind == BoundKind.EXTENDS) {
      upperBound = wildcardType.getExtendsBound();
    } else {
      // For ? and ? super L, javac stores the wildcard's corresponding type variable in the `bound`
      // field. The upper bound of that type variable is the wildcard's effective upper bound.
      upperBound = wildcardType.bound.getUpperBound();
    }
    if (upperBound instanceof Type.WildcardType nestedWildcard) {
      return wildcardUpperBound(nestedWildcard);
    }
    if (upperBound instanceof Type.CapturedType capturedType && capturedType.wildcard != null) {
      return wildcardUpperBound(capturedType.wildcard);
    }
    return upperBound;
  }

  /**
   * Returns whether a formal {@code ? super S} contains the actual type argument on the right. For
   * concrete actuals {@code T} and wildcard actuals {@code ? super T}, containment holds when
   * {@code S <: T}, interpreted with NullAway's nullability-aware subtype relation.
   */
  private boolean superWildcardContains(Type.WildcardType lhsWildcard, Type rhsTypeArgument) {
    // caller must ensure that lhsWildcard has a super bound
    Type lhsBound = castToNonNull(lhsWildcard.getSuperBound());
    if (rhsTypeArgument instanceof Type.WildcardType rhsWildcard) {
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
    return genericsChecks.subtypeParameterNullability(lhsType, rhsType, state);
  }
}
