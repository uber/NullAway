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
public class InferTypeVisitor extends Types.DefaultTypeVisitor<@Nullable Map<TypeVariable, Type>, Type> {
  private final Config config;

  InferTypeVisitor(Config config) {
    this.config = config;
  }

  @Override
  public @Nullable Map<TypeVariable, Type> visitClassType(Type.ClassType rhsType, Type lhsType) {
    Map<TypeVariable, Type> genericNullness = new HashMap<>();
    com.sun.tools.javac.util.List<Type> rhsTypeArguments = rhsType.getTypeArguments();
    com.sun.tools.javac.util.List<Type> lhsTypeArguments =
        ((Type.ClassType) lhsType).getTypeArguments();
    // get the inferred type for each type arguments and add them to genericNullness
    for (int i = 0; i < rhsTypeArguments.size(); i++) {
      Type rhsTypeArg = rhsTypeArguments.get(i);
      Type lhsTypeArg = lhsTypeArguments.get(i);
      Map<TypeVariable, Type> map = rhsTypeArg.accept(this, lhsTypeArg);
      if (map != null) {
        genericNullness.putAll(map);
      }
    }
    return genericNullness.isEmpty() ? null : genericNullness;
  }

  @Override
  public @Nullable Map<TypeVariable, Type> visitArrayType(Type.ArrayType rhsType, Type lhsType) {
    // unwrap the type of the array and call accept on it
    Type rhsComponentType = rhsType.elemtype;
    Type lhsComponentType = ((Type.ArrayType) lhsType).elemtype;
    Map<TypeVariable, Type> genericNullness = rhsComponentType.accept(this, lhsComponentType);
    return genericNullness;
  }

  @Override
  public Map<TypeVariable, Type> visitTypeVar(Type.TypeVar rhsType, Type lhsType) {
    Map<TypeVariable, Type> genericNullness = new HashMap<>();
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
    return genericNullness;
  }

  @Override
  public @Nullable Map<TypeVariable, Type> visitType(Type t, Type type) {
    return null;
  }
}
