package com.uber.nullaway.librarymodel;

import static com.uber.nullaway.generics.TypeMetadataBuilder.TYPE_METADATA_BUILDER;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.ListBuffer;
import com.uber.nullaway.generics.TypeSubstitutionUtils;

/**
 * A visitor to add an annotation to a type at a specified type path. The desired annotation and
 * type path are specified in the constructor. Then, calling {@link #apply(Type)} on a type will
 * return a new type with the annotation added at the specified nested location, or the original
 * type if no change was made.
 */
@SuppressWarnings("ReferenceEquality")
public final class AddAnnotationToNestedTypeVisitor extends Types.MapVisitor<Integer> {
  private final ImmutableList<NestedAnnotationInfo.TypePathEntry> typePath;
  private final Type annotationType;

  /**
   * Constructor.
   *
   * @param typePath the type path to the nested type where the annotation should be added
   * @param annotationType the annotation type to add
   */
  public AddAnnotationToNestedTypeVisitor(
      ImmutableList<NestedAnnotationInfo.TypePathEntry> typePath, Type annotationType) {
    this.typePath = typePath;
    this.annotationType = annotationType;
  }

  /**
   * Applies this visitor to the given type.
   *
   * @param type the type to apply the visitor to
   * @return the resulting type with the annotation added at the specified nested location
   */
  public Type apply(Type type) {
    return type.accept(this, 0);
  }

  @Override
  public Type visitClassType(Type.ClassType t, Integer pathIndex) {
    if (pathIndex == typePath.size()) {
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
    if (pathIndex == typePath.size()) {
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
    Preconditions.checkArgument(
        pathIndex != typePath.size(), "cannot apply an annotation directly to a wildcard type");
    NestedAnnotationInfo.TypePathEntry entry = typePath.get(pathIndex);
    if (entry.kind() != NestedAnnotationInfo.TypePathEntry.Kind.WILDCARD_BOUND) {
      return t;
    }
    int boundIndex = entry.index();
    if (t.type == null) {
      // TODO we need to add logic to _introduce_ a bound if none exists (add follow-up issue)
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
    if (pathIndex == typePath.size()) {
      return TypeSubstitutionUtils.typeWithAnnot(t, annotationType);
    }
    return t;
  }
}
