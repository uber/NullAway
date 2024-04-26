package com.uber.nullaway.generics;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import java.util.List;
import javax.lang.model.type.NullType;

/**
 * Visitor that checks equality of nullability annotations for all nested generic type arguments
 * within a type. Compares the Type it is called upon, i.e. the LHS type and the Type passed as an
 * argument, i.e. The RHS type.
 */
public class CompareNullabilityVisitor extends Types.DefaultTypeVisitor<Boolean, Type> {
  private final VisitorState state;

  CompareNullabilityVisitor(VisitorState state) {
    this.state = state;
  }

  @Override
  public Boolean visitClassType(Type.ClassType lhsType, Type rhsType) {
    if (rhsType instanceof NullType || rhsType.isPrimitive()) {
      return true;
    }
    Types types = state.getTypes();
    // The base type of rhsType may be a subtype of lhsType's base type.  In such cases, we must
    // compare lhsType against the supertype of rhsType with a matching base type.
    Type rhsTypeAsSuper = types.asSuper(rhsType, lhsType.tsym);
    // This is impossible, considering the fact that standard Java subtyping succeeds before
    // running NullAway
    if (rhsTypeAsSuper == null) {
      throw new RuntimeException("Did not find supertype of " + rhsType + " matching " + lhsType);
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
      boolean isLHSNullableAnnotated = isNullableAnnotated(lhsTypeArgument);
      boolean isRHSNullableAnnotated = isNullableAnnotated(rhsTypeArgument);
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
    return lhsType.getEnclosingType().accept(this, rhsType.getEnclosingType());
  }

  private boolean isNullableAnnotated(Type type) {
    boolean result = false;
    // To ensure that we are checking only jspecify nullable annotations
    Type jspecifyNullableType = GenericsChecks.JSPECIFY_NULLABLE_TYPE_SUPPLIER.get(state);
    List<Attribute.TypeCompound> lhsAnnotations = type.getAnnotationMirrors();
    for (Attribute.TypeCompound annotation : lhsAnnotations) {
      if (ASTHelpers.isSameType(
          (Type) annotation.getAnnotationType(), jspecifyNullableType, state)) {
        result = true;
        break;
      }
    }
    return result;
  }

  @Override
  public Boolean visitArrayType(Type.ArrayType lhsType, Type rhsType) {
    if (rhsType instanceof NullType) {
      return true;
    }
    Type.ArrayType arrRhsType = (Type.ArrayType) rhsType;
    Type lhsComponentType = lhsType.getComponentType();
    Type rhsComponentType = arrRhsType.getComponentType();
    boolean isLHSNullableAnnotated = isNullableAnnotated(lhsComponentType);
    boolean isRHSNullableAnnotated = isNullableAnnotated(rhsComponentType);
    if (isLHSNullableAnnotated != isRHSNullableAnnotated) {
      return false;
    }
    return lhsComponentType.accept(this, rhsComponentType);
  }

  @Override
  public Boolean visitType(Type t, Type type) {
    return true;
  }
}
