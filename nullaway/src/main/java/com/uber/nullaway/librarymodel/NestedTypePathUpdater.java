package com.uber.nullaway.librarymodel;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;
import static com.uber.nullaway.generics.TypeMetadataBuilder.TYPE_METADATA_BUILDER;

import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.ListBuffer;
import com.uber.nullaway.generics.TypeSubstitutionUtils;
import com.uber.nullaway.libmodel.NestedAnnotationInfo;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Updates a type at a nested location identified by a library-model type path. */
@SuppressWarnings({"ReferenceEquality", "TypeEquals"}) // deliberate reference equality checks
public final class NestedTypePathUpdater extends Types.MapVisitor<Integer> {

  private enum UpdateKind {
    ADD_ANNOTATION,
    REPLACE_TYPE
  }

  private final ImmutableList<NestedAnnotationInfo.TypePathEntry> typePath;
  private final UpdateKind updateKind;

  /** The type that replaces the type at {@link #typePath}, for {@link UpdateKind#REPLACE_TYPE}. */
  private final @Nullable Type replacementType;

  /**
   * For {@link UpdateKind#ADD_ANNOTATION}, maps the type at {@link #typePath} to the annotation
   * type added to it, or to {@code null} where that type stays unchanged.
   */
  private final @Nullable Function<Type, @Nullable Type> annotationFor;

  private NestedTypePathUpdater(
      ImmutableList<NestedAnnotationInfo.TypePathEntry> typePath,
      UpdateKind updateKind,
      @Nullable Type replacementType,
      @Nullable Function<Type, @Nullable Type> annotationFor) {
    this.typePath = typePath;
    this.updateKind = updateKind;
    this.replacementType = replacementType;
    this.annotationFor = annotationFor;
  }

  /**
   * Adds {@code annotationType} to {@code type} at {@code typePath}.
   *
   * @return the updated type, or the original {@code type} instance if no change was made
   */
  public static Type addAnnotation(
      Type type, ImmutableList<NestedAnnotationInfo.TypePathEntry> typePath, Type annotationType) {
    return addAnnotation(type, typePath, leaf -> annotationType);
  }

  /**
   * Adds the annotation type that {@code annotationFor} returns for the type at {@code typePath} to
   * that type, as {@link #addAnnotation(Type, ImmutableList, Type)} adds a fixed one; where {@code
   * annotationFor} returns {@code null}, the type stays unchanged. A wildcard or a captured type
   * variable at {@code typePath} is passed to {@code annotationFor} itself, and the annotation it
   * returns is added to its upper bound: the bound of {@code ? extends}, the upper bound of the
   * corresponding type parameter for another wildcard, or the upper bound of the capture.
   *
   * @return the updated type, or the original {@code type} instance if no change was made
   */
  public static Type addAnnotation(
      Type type,
      ImmutableList<NestedAnnotationInfo.TypePathEntry> typePath,
      Function<Type, @Nullable Type> annotationFor) {
    return new NestedTypePathUpdater(typePath, UpdateKind.ADD_ANNOTATION, null, annotationFor)
        .apply(type);
  }

  /**
   * Replaces the nested type at {@code typePath} with {@code replacement}.
   *
   * @return the updated type, or the original {@code type} instance if no change was made
   */
  public static Type replaceType(
      Type type, ImmutableList<NestedAnnotationInfo.TypePathEntry> typePath, Type replacement) {
    return new NestedTypePathUpdater(typePath, UpdateKind.REPLACE_TYPE, replacement, null)
        .apply(type);
  }

  private Type apply(Type type) {
    return type.accept(this, 0);
  }

  private Type updateLeaf(Type type) {
    if (updateKind == UpdateKind.REPLACE_TYPE) {
      return castToNonNull(replacementType);
    }
    Type annotationType = annotationTypeFor(type);
    return annotationType == null
        ? type
        : TypeSubstitutionUtils.typeWithAnnot(type, annotationType);
  }

  /** Returns the annotation type to add to {@code type} at {@link #typePath}, or {@code null}. */
  private @Nullable Type annotationTypeFor(Type type) {
    return castToNonNull(annotationFor).apply(type);
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
      // Member-type substitution can replace a modeled type variable with a wildcard. Annotate
      // a copy of its upper bound: reads use the bound's nullness, and annotations directly on
      // wildcards are not legal under JSpecify. REPLACE_TYPE replaces the entire wildcard.
      if (updateKind == UpdateKind.REPLACE_TYPE) {
        return updateLeaf(t);
      }
      Type annotationType = annotationTypeFor(t);
      if (annotationType == null) {
        // diff-coverage: ignore -- PolyNull inference returns no annotation only for a type
        // variable or an intersection
        return t;
      }
      if (t.kind == BoundKind.EXTENDS) {
        return TYPE_METADATA_BUILDER.createWildcardType(
            t, TypeSubstitutionUtils.typeWithAnnot(t.type, annotationType));
      }
      Type.TypeVar formalTypeVariable =
          Verify.verifyNotNull(t.bound, "wildcard has no corresponding formal type variable");
      Type updatedUpperBound =
          TypeSubstitutionUtils.typeWithAnnot(formalTypeVariable.getUpperBound(), annotationType);
      return TypeSubstitutionUtils.replaceImplicitWildcardUpperBound(
          t, formalTypeVariable, updatedUpperBound);
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

  /**
   * Updates a captured type while preserving its backing wildcard when adding an annotation.
   *
   * <p>javac represents a captured wildcard as a type variable plus its original wildcard.
   * NullAway's effective-bound computations inspect both the structural upper bound and the backing
   * wildcard, so annotation updates must be reflected in both.
   */
  @Override
  public Type visitCapturedType(Type.CapturedType t, Integer pathIndex) {
    if (updateKind == UpdateKind.REPLACE_TYPE && pathIndex == typePath.size()) {
      return updateLeaf(t);
    }
    Type.WildcardType wildcard = t.wildcard;
    if (wildcard.kind == BoundKind.UNBOUND && wildcard.bound == null) {
      // javac can omit the formal type variable on a captured wildcard. Use the capture's upper
      // bound in that case, on a detached copy so neither path traversal nor annotation updates
      // mutate compiler-owned types.
      wildcard =
          TypeSubstitutionUtils.replaceUnboundedWildcardUpperBound(wildcard, t, t.getUpperBound());
    }
    Type.WildcardType updatedWildcard;
    if (pathIndex < typePath.size()) {
      updatedWildcard = (Type.WildcardType) wildcard.accept(this, pathIndex);
    } else {
      Verify.verify(pathIndex == typePath.size(), "path index out of bounds");
      Type annotationType = annotationTypeFor(t);
      if (annotationType == null) {
        return t;
      }
      Type updatedUpperBound =
          TypeSubstitutionUtils.typeWithAnnot(t.getUpperBound(), annotationType);
      if (wildcard.kind == BoundKind.EXTENDS) {
        Type updatedBound = TypeSubstitutionUtils.typeWithAnnot(wildcard.type, annotationType);
        updatedWildcard = TYPE_METADATA_BUILDER.createWildcardType(wildcard, updatedBound);
      } else {
        // The capture supplies the resolved upper bound, including receiver substitutions.
        updatedWildcard =
            TypeSubstitutionUtils.replaceImplicitWildcardUpperBound(wildcard, t, updatedUpperBound);
      }
      return TYPE_METADATA_BUILDER.createDetachedCapturedType(
          t, updatedWildcard, updatedUpperBound);
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
