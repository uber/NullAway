package com.uber.nullaway.librarymodel;

import static com.uber.nullaway.generics.TypeMetadataBuilder.TYPE_METADATA_BUILDER;

import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.ListBuffer;
import com.uber.nullaway.generics.TypeSubstitutionUtils;
import com.uber.nullaway.libmodel.NestedAnnotationInfo;

/** Updates a type at a nested location identified by a library-model type path. */
@SuppressWarnings({"ReferenceEquality", "TypeEquals"}) // deliberate reference equality checks
public final class NestedTypePathUpdater extends Types.MapVisitor<Integer> {

  private enum UpdateKind {
    ADD_ANNOTATION,
    REPLACE_TYPE
  }

  private final ImmutableList<NestedAnnotationInfo.TypePathEntry> typePath;
  private final Type updateType;
  private final UpdateKind updateKind;

  private NestedTypePathUpdater(
      ImmutableList<NestedAnnotationInfo.TypePathEntry> typePath,
      Type updateType,
      UpdateKind updateKind) {
    this.typePath = typePath;
    this.updateType = updateType;
    this.updateKind = updateKind;
  }

  /** Adds {@code annotationType} to {@code type} at {@code typePath}. */
  public static Type addAnnotation(
      Type type, ImmutableList<NestedAnnotationInfo.TypePathEntry> typePath, Type annotationType) {
    return new NestedTypePathUpdater(typePath, annotationType, UpdateKind.ADD_ANNOTATION)
        .apply(type);
  }

  /** Replaces the nested type at {@code typePath} with {@code replacement}. */
  public static Type replaceType(
      Type type, ImmutableList<NestedAnnotationInfo.TypePathEntry> typePath, Type replacement) {
    return new NestedTypePathUpdater(typePath, replacement, UpdateKind.REPLACE_TYPE).apply(type);
  }

  private Type apply(Type type) {
    return type.accept(this, 0);
  }

  private Type updateLeaf(Type type) {
    return updateKind == UpdateKind.ADD_ANNOTATION
        ? TypeSubstitutionUtils.typeWithAnnot(type, updateType)
        : updateType;
  }

  @Override
  public Type visitClassType(Type.ClassType t, Integer pathIndex) {
    if (pathIndex == typePath.size()) {
      return updateLeaf(t);
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
      return updateLeaf(t);
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
    if (pathIndex == typePath.size()) {
      // Nullness annotations directly on wildcards are not legal under JSpecify. This case can
      // arise when member-type substitution replaces an annotated type variable with a wildcard;
      // leave the wildcard unchanged and rely on the dedicated top-level parameter/return model.
      return updateKind == UpdateKind.ADD_ANNOTATION ? t : updateLeaf(t);
    }
    NestedAnnotationInfo.TypePathEntry entry = typePath.get(pathIndex);
    if (entry.kind() != NestedAnnotationInfo.TypePathEntry.Kind.WILDCARD_BOUND) {
      return t;
    }
    int boundIndex = entry.index();
    if (t.kind == BoundKind.UNBOUND) {
      if (boundIndex != 0) {
        // An unbounded wildcard has an implicit upper bound, but no lower bound.
        return t;
      }
      Type.TypeVar formalTypeVariable =
          Verify.verifyNotNull(
              t.bound, "unbounded wildcard has no corresponding formal type variable");
      Type upperBound = formalTypeVariable.getUpperBound();
      Type updatedUpperBound = upperBound.accept(this, pathIndex + 1);
      return updatedUpperBound == upperBound
          ? t
          : TypeSubstitutionUtils.replaceUnboundedWildcardUpperBound(t, updatedUpperBound);
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

  /** Updates a captured type while preserving its backing wildcard when adding an annotation. */
  @Override
  public Type visitCapturedType(Type.CapturedType t, Integer pathIndex) {
    if (updateKind == UpdateKind.REPLACE_TYPE && pathIndex == typePath.size()) {
      return updateLeaf(t);
    }
    Type.WildcardType updatedWildcard;
    if (pathIndex < typePath.size()) {
      updatedWildcard = (Type.WildcardType) t.wildcard.accept(this, pathIndex);
    } else {
      Verify.verify(pathIndex == typePath.size(), "path index out of bounds");
      if (t.wildcard.kind == BoundKind.UNBOUND) {
        Type.TypeVar formalTypeVariable =
            Verify.verifyNotNull(
                t.wildcard.bound, "unbounded wildcard has no corresponding formal type variable");
        Type updatedUpperBound =
            TypeSubstitutionUtils.typeWithAnnot(formalTypeVariable.getUpperBound(), updateType);
        updatedWildcard =
            TypeSubstitutionUtils.replaceUnboundedWildcardUpperBound(t.wildcard, updatedUpperBound);
      } else {
        Type updatedBound = TypeSubstitutionUtils.typeWithAnnot(t.wildcard.type, updateType);
        updatedWildcard = TYPE_METADATA_BUILDER.createWildcardType(t.wildcard, updatedBound);
      }
    }
    if (updatedWildcard == t.wildcard) {
      return t;
    }
    return TypeSubstitutionUtils.replaceCapturedTypeWildcard(t, updatedWildcard);
  }

  @Override
  public Type visitType(Type t, Integer pathIndex) {
    if (pathIndex == typePath.size()) {
      return updateLeaf(t);
    }
    return t;
  }
}
