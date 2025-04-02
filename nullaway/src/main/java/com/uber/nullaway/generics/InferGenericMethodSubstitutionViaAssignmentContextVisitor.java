package com.uber.nullaway.generics;

import com.google.common.base.Verify;
import com.google.errorprone.VisitorState;
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
public class InferGenericMethodSubstitutionViaAssignmentContextVisitor
    extends Types.DefaultTypeVisitor<Void, Type> {

  private final VisitorState state;
  private final Config config;
  private final boolean invokedMethodIsNullUnmarked;
  private final Map<TypeVariable, Type> inferredSubstitution = new LinkedHashMap<>();

  InferGenericMethodSubstitutionViaAssignmentContextVisitor(
      VisitorState state, Config config, boolean invokedMethodIsNullUnmarked) {
    this.state = state;
    this.config = config;
    this.invokedMethodIsNullUnmarked = invokedMethodIsNullUnmarked;
  }

  @Override
  public Void visitClassType(Type.ClassType rhsType, Type lhsType) {
    Type rhsTypeAsSuper = state.getTypes().asSuper(rhsType, lhsType.tsym);
    if (rhsTypeAsSuper == null || rhsTypeAsSuper.isRaw() || lhsType.isRaw()) {
      return null;
    }
    com.sun.tools.javac.util.List<Type> rhsTypeArguments = rhsTypeAsSuper.getTypeArguments();
    com.sun.tools.javac.util.List<Type> lhsTypeArguments = lhsType.getTypeArguments();
    int numTypeArgs = rhsTypeArguments.size();
    Verify.verify(numTypeArgs == lhsTypeArguments.size());
    for (int i = 0; i < numTypeArgs; i++) {
      Type rhsTypeArg = rhsTypeArguments.get(i);
      Type lhsTypeArg = lhsTypeArguments.get(i);
      rhsTypeArg.accept(this, lhsTypeArg);
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
    if (typeVarHasNullableUpperBound
        || invokedMethodIsNullUnmarked) { // can just use the lhs type nullability
      inferredSubstitution.put(typeVar, lhsType);
    } else { // rhs can't be nullable.  use lhsType but strip @Nullable annotation
      // TODO we should just strip out the top-level @Nullable annotation;
      //  stripMetadata() also removes nested @Nullable annotations
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
