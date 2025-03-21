package com.uber.nullaway.generics;

import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.Config;
import com.uber.nullaway.Nullness;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.lang.model.type.TypeVariable;

/**
 * Visitor that infers a substitution for type variables via types appearing at the same position in
 * a type provided via the assignment context.
 */
public class InferSubstitutionViaAssignmentContextVisitor
    extends Types.DefaultTypeVisitor<Void, Type> {

  private final Config config;
  private final Map<TypeVariable, Type> inferredSubstitution = new LinkedHashMap<>();

  InferSubstitutionViaAssignmentContextVisitor(Config config) {
    this.config = config;
  }

  @Override
  public Void visitClassType(Type.ClassType rhsType, Type lhsType) {
    com.sun.tools.javac.util.List<Type> rhsTypeArguments = rhsType.getTypeArguments();
    com.sun.tools.javac.util.List<Type> lhsTypeArguments = lhsType.getTypeArguments();
    // recursively visit the type arguments
    if (!rhsTypeArguments.isEmpty() && !lhsTypeArguments.isEmpty()) {
      for (int i = 0; i < rhsTypeArguments.size(); i++) {
        Type rhsTypeArg = rhsTypeArguments.get(i);
        Type lhsTypeArg = lhsTypeArguments.get(i);
        rhsTypeArg.accept(this, lhsTypeArg);
      }
    }
    return null;
  }

  @Override
  public Void visitArrayType(Type.ArrayType rhsType, Type lhsType) {
    if (!(lhsType instanceof Type.ArrayType)) {
      return null;
    }
    // recursively visit the component types
    Type rhsComponentType = rhsType.elemtype;
    Type lhsComponentType = ((Type.ArrayType) lhsType).elemtype;
    rhsComponentType.accept(this, lhsComponentType);
    return null;
  }

  @Override
  public Void visitTypeVar(Type.TypeVar typeVar, Type lhsType) {
    if (inferredSubstitution.containsKey(typeVar)) {
      return null; // already inferred a type
    }
    Type upperBound = typeVar.getUpperBound();
    boolean typeVarHasNullableUpperBound =
        Nullness.hasNullableAnnotation(upperBound.getAnnotationMirrors().stream(), config);
    if (typeVarHasNullableUpperBound) { // can just use the lhs type nullability
      inferredSubstitution.put(typeVar, lhsType);
    } else { // rhs can't be nullable.  use lhsType but strip @Nullable annotation
      inferredSubstitution.put(typeVar, lhsType.stripMetadata());
    }
    return null;
  }

  @Override
  public Void visitType(Type t, Type type) {
    return null;
  }

  public Map<TypeVariable, Type> getInferredSubstitution() {
    return this.inferredSubstitution;
  }
}
