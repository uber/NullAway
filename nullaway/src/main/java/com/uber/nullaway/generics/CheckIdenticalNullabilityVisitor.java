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
    if (lhsTypeArgument.getKind().equals(TypeKind.WILDCARD)) {
      return wildcardContains((Type.WildcardType) lhsTypeArgument, rhsTypeArgument);
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
   * Handles a narrow slice of the JLS type-argument containment rules from JLS 4.5.1 for wildcard
   * type arguments. In particular, for a formal argument {@code ? extends S}, we accept either a
   * concrete actual argument {@code T} or a wildcard actual argument {@code ? extends T} whenever
   * {@code T <: S}, using NullAway's nullability-aware subtype check in place of plain Java
   * subtyping. For a formal argument {@code ? super S}, we accept either a concrete actual argument
   * {@code T} or a wildcard actual argument {@code ? super T} whenever {@code S <: T}. For now,
   * this method intentionally leaves other more complex cases to existing fallback behavior.
   */
  private boolean wildcardContains(Type.WildcardType lhsWildcard, Type rhsTypeArgument) {
    return switch (lhsWildcard.kind) {
      case UNBOUND -> {
        Type lhsBound = wildcardUpperBound(lhsWildcard);
        yield lhsBound == null || extendsBoundContains(lhsBound, rhsTypeArgument);
      }
      case EXTENDS -> extendsBoundContains(lhsWildcard.getExtendsBound(), rhsTypeArgument);
      case SUPER -> superWildcardContains(lhsWildcard, rhsTypeArgument);
      default -> throw new RuntimeException("Unexpected wildcard bound kind: " + lhsWildcard.kind);
    };
  }

  private boolean extendsBoundContains(Type lhsBound, Type rhsTypeArgument) {
    if (rhsTypeArgument.getKind().equals(TypeKind.WILDCARD)) {
      Type.WildcardType rhsWildcard = (Type.WildcardType) rhsTypeArgument;
      if (rhsWildcard.kind != BoundKind.EXTENDS) {
        Type rhsUpperBound = wildcardUpperBound(rhsWildcard);
        return rhsUpperBound == null || typeArgumentSubtype(lhsBound, rhsUpperBound);
      }
      Type rhsBound = rhsWildcard.getExtendsBound();
      return typeArgumentSubtype(lhsBound, rhsBound);
    }
    return typeArgumentSubtype(lhsBound, rhsTypeArgument);
  }

  /**
   * Returns the effective upper bound of a wildcard, using the corresponding type variable's upper
   * bound for unbounded wildcards and {@code super} wildcards.
   */
  private @Nullable Type wildcardUpperBound(Type.WildcardType wildcardType) {
    if (wildcardType.kind == BoundKind.EXTENDS) {
      return wildcardType.getExtendsBound();
    }
    // For ? and ? super L, javac stores the wildcard's corresponding type variable in the `bound`
    // field. The upper bound of that type variable is the wildcard's effective upper bound.
    return wildcardType.bound == null ? null : wildcardType.bound.getUpperBound();
  }

  /**
   * Returns whether a formal {@code ? super S} contains the actual type argument on the right. For
   * concrete actuals {@code T} and wildcard actuals {@code ? super T}, containment holds when
   * {@code S <: T}, interpreted with NullAway's nullability-aware subtype relation.
   */
  private boolean superWildcardContains(Type.WildcardType lhsWildcard, Type rhsTypeArgument) {
    // caller must ensure that lhsWildcard has a super bound
    Type lhsBound = castToNonNull(lhsWildcard.getSuperBound());
    if (rhsTypeArgument.getKind().equals(TypeKind.WILDCARD)) {
      Type.WildcardType rhsWildcard = (Type.WildcardType) rhsTypeArgument;
      if (rhsWildcard.kind != BoundKind.SUPER) {
        // Treat non-super wildcard actual arguments as accepted here until we add more complete
        // support.
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
