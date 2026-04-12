package com.uber.nullaway.generics;

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
   * NullAway's nullability-aware subtype relation. Non-wildcard pairs require matching nullability
   * annotations and recursively matching nested type arguments. Wildcard formals are delegated to
   * {@link #wildcardContains}.
   */
  private boolean typeArgumentContainedBy(Type lhsTypeArgument, Type rhsTypeArgument) {
    if (lhsTypeArgument.getKind().equals(TypeKind.WILDCARD)) {
      return wildcardContains((Type.WildcardType) lhsTypeArgument, rhsTypeArgument);
    }
    if (rhsTypeArgument.getKind().equals(TypeKind.WILDCARD)) {
      // TODO: Add proper support for the remaining case where the formal type argument is not a
      // wildcard but the actual type argument is a wildcard.
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
   * subtyping. This covers the JLS cases behind both {@code T <= ? extends S} and {@code ? extends
   * T <= ? extends S}. For now, this method intentionally leaves {@code super} wildcards and other
   * more complex cases to existing fallback behavior.
   */
  private boolean wildcardContains(Type.WildcardType lhsWildcard, Type rhsTypeArgument) {
    if (lhsWildcard.kind == BoundKind.UNBOUND) {
      // TODO: For unbounded wildcards, we need to find the bound of the corresponding type
      // variable rather than accepting outright; see
      // https://jspecify.dev/docs/user-guide/#wildcard-bounds
      return true;
    }
    if (lhsWildcard.kind != BoundKind.EXTENDS) {
      // Treat non-extends wildcards as accepted here until we add more complete support.
      return true;
    }
    Type lhsBound = lhsWildcard.getExtendsBound();
    if (lhsBound == null) {
      return true;
    }
    if (rhsTypeArgument.getKind().equals(TypeKind.WILDCARD)) {
      Type.WildcardType rhsWildcard = (Type.WildcardType) rhsTypeArgument;
      if (rhsWildcard.kind != BoundKind.EXTENDS) {
        // Treat non-extends wildcard actual arguments as accepted here until we add more complete
        // support.
        return true;
      }
      Type rhsBound = rhsWildcard.getExtendsBound();
      return typeArgumentSubtype(lhsBound, rhsBound);
    }
    return typeArgumentSubtype(lhsBound, rhsTypeArgument);
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
