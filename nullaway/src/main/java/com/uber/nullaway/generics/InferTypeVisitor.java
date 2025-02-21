package com.uber.nullaway.generics;

import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.Config;
import com.uber.nullaway.Nullness;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Visitor that uses two types to infer the type of type variables.
 */
public class InferTypeVisitor extends Types.DefaultTypeVisitor<@Nullable Map<Type, Type>, Type> {
  private final Config config;

  InferTypeVisitor(Config config) {
    this.config = config;
  }

  @Override
  public @Nullable Map<Type, Type> visitClassType(Type.ClassType rhsType, Type lhsType) {
    Map<Type, Type> genericNullness = new HashMap<>();
    // for each type parameter, call accept with this visitor and add all results to one map
    com.sun.tools.javac.util.List<Type> rhsTypeArguments = rhsType.getTypeArguments();
    com.sun.tools.javac.util.List<Type> lhsTypeArguments = lhsType.getTypeArguments();
    for (int i = 0; i < rhsTypeArguments.size(); i++) {
      Type rhsTypeArg = rhsTypeArguments.get(i);
      Type lhsTypeArg = lhsTypeArguments.get(i);
      // get the inferred type for each type arguments and add them to genericNullness
      Map<Type, Type> map = rhsTypeArg.accept(this, lhsTypeArg);
      if (map != null) {
        genericNullness.putAll(map);
      }
    }
    return genericNullness.isEmpty() ? null : genericNullness;
  }

  @Override
  public Map<Type, Type> visitTypeVar(Type.TypeVar rhsType, Type lhsType) { // type variable itself
    Map<Type, Type> genericNullness = new HashMap<>();
    Boolean isLhsNullable =
        Nullness.hasNullableAnnotation(lhsType.getAnnotationMirrors().stream(), config);
    Type upperBound = rhsType.getUpperBound();
    Boolean isRhsNullable =
        Nullness.hasNullableAnnotation(upperBound.getAnnotationMirrors().stream(), config);
    if (!isLhsNullable) { // lhsType is NonNull, we can just use this
      genericNullness.put(rhsType, lhsType);
    } else if (isRhsNullable) { // lhsType & rhsType is Nullable, can use lhs for inference
      genericNullness.put(rhsType, lhsType);
    } else { // rhs can't be nullable, use upperbound
      genericNullness.put(rhsType, upperBound);
    }
    return genericNullness;
  }

  @Override
  public @Nullable Map<Type, Type> visitType(Type t, Type type) {
    return null;
  }
}
