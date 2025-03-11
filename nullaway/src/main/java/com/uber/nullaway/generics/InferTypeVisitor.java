package com.uber.nullaway.generics;

import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.Config;
import com.uber.nullaway.Nullness;
import java.util.HashMap;
import java.util.Map;
import javax.lang.model.type.TypeVariable;

/** Visitor that uses two types to infer the type of type variables. */
public class InferTypeVisitor extends Types.DefaultTypeVisitor<Void, Type> {

  private final Config config;
  private final Map<TypeVariable, Type> genericNullness = new HashMap<>();

  InferTypeVisitor(Config config) {
    this.config = config;
  }

  @Override
  public Void visitClassType(Type.ClassType rhsType, Type lhsType) {
    com.sun.tools.javac.util.List<Type> rhsTypeArguments = rhsType.getTypeArguments();
    com.sun.tools.javac.util.List<Type> lhsTypeArguments =
        ((Type.ClassType) lhsType).getTypeArguments();
    // get the inferred type for each type arguments and add them to genericNullness
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
    // unwrap the type of the array and call accept on it
    Type rhsComponentType = rhsType.elemtype;
    Type lhsComponentType = ((Type.ArrayType) lhsType).elemtype;
    rhsComponentType.accept(this, lhsComponentType);
    return null;
  }

  @Override
  public Void visitTypeVar(Type.TypeVar typeVar, Type lhsType) {
    if (genericNullness.containsKey(typeVar)) {
      return null; // genericNullness already contains inference for this type
    }
    Type upperBound = typeVar.getUpperBound();
    boolean typeVarHasNullableUpperBound =
        Nullness.hasNullableAnnotation(upperBound.getAnnotationMirrors().stream(), config);
    if (typeVarHasNullableUpperBound) { // can just use the lhs type nullability
      genericNullness.put(typeVar, lhsType);
    } else { // rhs can't be nullable, use upperbound
      // this is a bit weird.  the nullability might be right, but the base type may be wrong?
      genericNullness.put(typeVar, upperBound);
    }
    return null;
  }

  @Override
  public Void visitType(Type t, Type type) {
    return null;
  }

  public Map<TypeVariable, Type> getGenericNullnessMap() {
    return this.genericNullness;
  }
}
