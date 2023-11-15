package com.uber.nullaway.generics;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import java.util.List;

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
    Types types = state.getTypes();
    // The base type of rhsType may be a subtype of lhsType's base type.  In such cases, we must
    // compare lhsType against the supertype of rhsType with a matching base type.
    rhsType = types.asSuper(rhsType, lhsType.tsym);
    // This is impossible, considering the fact that standard Java subtyping succeeds before
    // running NullAway
    if (rhsType == null) {
      throw new RuntimeException("Did not find supertype of " + rhsType + " matching " + lhsType);
    }
    List<Type> lhsTypeArguments = lhsType.getTypeArguments();
    List<Type> rhsTypeArguments = rhsType.getTypeArguments();
    // This is impossible, considering the fact that standard Java subtyping succeeds before
    // running NullAway
    if (lhsTypeArguments.size() != rhsTypeArguments.size()) {
      throw new RuntimeException(
          "Number of types arguments in " + rhsType + " does not match " + lhsType);
    }
    for (int i = 0; i < lhsTypeArguments.size(); i++) {
      Type lhsTypeArgument = lhsTypeArguments.get(i);
      Type rhsTypeArgument = rhsTypeArguments.get(i);
      boolean isLHSNullableAnnotated = false;
      List<Attribute.TypeCompound> lhsAnnotations = lhsTypeArgument.getAnnotationMirrors();
      // To ensure that we are checking only jspecify nullable annotations
      Type jspecifyNullableType = GenericsChecks.JSPECIFY_NULLABLE_TYPE_SUPPLIER.get(state);
      for (Attribute.TypeCompound annotation : lhsAnnotations) {
        if (ASTHelpers.isSameType(
            (Type) annotation.getAnnotationType(), jspecifyNullableType, state)) {
          isLHSNullableAnnotated = true;
          break;
        }
      }
      boolean isRHSNullableAnnotated = false;
      List<Attribute.TypeCompound> rhsAnnotations = rhsTypeArgument.getAnnotationMirrors();
      for (Attribute.TypeCompound annotation : rhsAnnotations) {
        if (ASTHelpers.isSameType(
            (Type) annotation.getAnnotationType(), jspecifyNullableType, state)) {
          isRHSNullableAnnotated = true;
          break;
        }
      }
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

  @Override
  public Boolean visitArrayType(Type.ArrayType lhsType, Type rhsType) {
    Type.ArrayType arrRhsType = (Type.ArrayType) rhsType;
    return lhsType.getComponentType().accept(this, arrRhsType.getComponentType());
  }

  @Override
  public Boolean visitType(Type t, Type type) {
    return true;
  }
}
