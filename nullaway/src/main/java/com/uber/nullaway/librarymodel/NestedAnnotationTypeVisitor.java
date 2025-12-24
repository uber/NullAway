package com.uber.nullaway.librarymodel;

import static com.uber.nullaway.generics.TypeMetadataBuilder.TYPE_METADATA_BUILDER;

import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.ListBuffer;
import com.uber.nullaway.generics.TypeSubstitutionUtils;
import java.util.List;

@SuppressWarnings("ReferenceEquality")
public final class NestedAnnotationTypeVisitor extends Types.MapVisitor<Integer> {
  private final List<NestedAnnotationInfo.TypePathEntry> typePath;
  private final Type annotationType;

  public NestedAnnotationTypeVisitor(
      List<NestedAnnotationInfo.TypePathEntry> typePath, Type annotationType) {
    this.typePath = typePath;
    this.annotationType = annotationType;
  }

  public Type apply(Type type) {
    return type.accept(this, 0);
  }

  @Override
  public Type visitClassType(Type.ClassType t, Integer pathIndex) {
    if (pathIndex >= typePath.size()) {
      return TypeSubstitutionUtils.typeWithAnnot(t, annotationType);
    }
    NestedAnnotationInfo.TypePathEntry entry = typePath.get(pathIndex);
    if (entry.kind() != NestedAnnotationInfo.TypePathEntry.Kind.TYPE_ARGUMENT) {
      return t;
    }
    com.sun.tools.javac.util.List<Type> typeArgs = t.getTypeArguments();
    int argIndex = entry.index();
    if (argIndex < 0 || argIndex >= typeArgs.size()) {
      return t;
    }
    Type oldTypeArg = typeArgs.get(argIndex);
    Type newTypeArg = oldTypeArg.accept(this, pathIndex + 1);
    if (newTypeArg == oldTypeArg) {
      return t;
    }
    ListBuffer<Type> updatedTypeArgs = new ListBuffer<>();
    int currentIndex = 0;
    for (com.sun.tools.javac.util.List<Type> l = typeArgs; l.nonEmpty(); l = l.tail) {
      updatedTypeArgs.append(currentIndex == argIndex ? newTypeArg : l.head);
      currentIndex++;
    }
    return TYPE_METADATA_BUILDER.createClassType(t, t.getEnclosingType(), updatedTypeArgs.toList());
  }

  @Override
  public Type visitArrayType(Type.ArrayType t, Integer pathIndex) {
    if (pathIndex >= typePath.size()) {
      return TypeSubstitutionUtils.typeWithAnnot(t, annotationType);
    }
    NestedAnnotationInfo.TypePathEntry entry = typePath.get(pathIndex);
    if (entry.kind() != NestedAnnotationInfo.TypePathEntry.Kind.ARRAY_ELEMENT) {
      return t;
    }
    Type newElemType = t.elemtype.accept(this, pathIndex + 1);
    if (newElemType == t.elemtype) {
      return t;
    }
    return TYPE_METADATA_BUILDER.createArrayType(t, newElemType);
  }

  @Override
  public Type visitWildcardType(Type.WildcardType t, Integer pathIndex) {
    if (pathIndex >= typePath.size()) {
      return TypeSubstitutionUtils.typeWithAnnot(t, annotationType);
    }
    NestedAnnotationInfo.TypePathEntry entry = typePath.get(pathIndex);
    if (entry.kind() != NestedAnnotationInfo.TypePathEntry.Kind.WILDCARD_BOUND) {
      return t;
    }
    int boundIndex = entry.index();
    // TODO this logic is wrong; it should introduce a bound if there is none there
    if (t.type == null) {
      return t;
    }
    if (boundIndex == 0 && t.kind == BoundKind.EXTENDS) {
      Type newBound = t.type.accept(this, pathIndex + 1);
      return newBound == t.type ? t : TYPE_METADATA_BUILDER.createWildcardType(t, newBound);
    }
    if (boundIndex == 1 && t.kind == BoundKind.SUPER) {
      Type newBound = t.type.accept(this, pathIndex + 1);
      return newBound == t.type ? t : TYPE_METADATA_BUILDER.createWildcardType(t, newBound);
    }
    return t;
  }

  @Override
  public Type visitType(Type t, Integer pathIndex) {
    if (pathIndex >= typePath.size()) {
      return TypeSubstitutionUtils.typeWithAnnot(t, annotationType);
    }
    return t;
  }
}
