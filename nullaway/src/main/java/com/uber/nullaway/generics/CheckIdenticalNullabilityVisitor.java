package com.uber.nullaway.generics;

import com.google.errorprone.VisitorState;
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
      if (lhsTypeArgument.getKind().equals(TypeKind.WILDCARD)
          || rhsTypeArgument.getKind().equals(TypeKind.WILDCARD)) {
        // TODO Handle wildcard types
        continue;
      }
      boolean isLHSNullableAnnotated = genericsChecks.isNullableAnnotated(lhsTypeArgument);
      boolean isRHSNullableAnnotated = genericsChecks.isNullableAnnotated(rhsTypeArgument);
      if (isLHSNullableAnnotated != isRHSNullableAnnotated) {
        return false;
      }
      // nested generics
      if (!lhsTypeArgument.accept(this, rhsTypeArgument)) {
        return false;
      }
    }
    // If there is an enclosing type (for non-static inner classes), its type argument nullability
    // should also match.  When there is no enclosing type, getEnclosingType() returns a NoType
    // object, which gets handled by the fallback visitType() method
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
    Type.ArrayType arrRhsType = (Type.ArrayType) rhsType;
    Type lhsComponentType = lhsType.getComponentType();
    Type rhsComponentType = arrRhsType.getComponentType();
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
}
