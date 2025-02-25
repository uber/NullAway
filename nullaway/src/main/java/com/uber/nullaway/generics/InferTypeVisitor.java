package com.uber.nullaway.generics;

import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.Config;
import com.uber.nullaway.Nullness;
import java.util.HashMap;
import java.util.Map;
import javax.lang.model.type.TypeVariable;
import org.jspecify.annotations.Nullable;

/** Visitor that uses two types to infer the type of type variables. */
public class InferTypeVisitor
    extends Types.DefaultTypeVisitor<Void, Type> { // Void return type

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
    for (int i = 0; i < rhsTypeArguments.size(); i++) {
      Type rhsTypeArg = rhsTypeArguments.get(i);
      Type lhsTypeArg = lhsTypeArguments.get(i);
      rhsTypeArg.accept(this, lhsTypeArg);
    }
    return null;
  }

  @Override
  public Void visitArrayType(Type.ArrayType rhsType, Type lhsType) {
    // unwrap the type of the array and call accept on it
    Type rhsComponentType = rhsType.elemtype;
    Type lhsComponentType = ((Type.ArrayType) lhsType).elemtype;
    rhsComponentType.accept(this, lhsComponentType);
    return null;
  }

  @Override
  public Void visitTypeVar(Type.TypeVar rhsType, Type lhsType) {
    Boolean isLhsNullable =
        Nullness.hasNullableAnnotation(lhsType.getAnnotationMirrors().stream(), config);
    Type upperBound = rhsType.getUpperBound();
    Boolean isRhsNullable =
        Nullness.hasNullableAnnotation(upperBound.getAnnotationMirrors().stream(), config);
    if (!isLhsNullable) { // lhsType is NonNull, we can just use this
      genericNullness.put(rhsType, lhsType);
    } else if (isRhsNullable) { // lhsType & rhsType are Nullable, can use lhs for inference
      genericNullness.put(rhsType, lhsType);
    } else { // rhs can't be nullable, use upperbound
      genericNullness.put(rhsType, upperBound);
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
