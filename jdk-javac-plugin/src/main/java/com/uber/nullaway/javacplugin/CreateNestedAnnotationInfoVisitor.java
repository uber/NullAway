package com.uber.nullaway.javacplugin;

import com.google.common.collect.ImmutableList;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.librarymodel.NestedAnnotationInfo;
import com.uber.nullaway.librarymodel.NestedAnnotationInfo.Annotation;
import com.uber.nullaway.librarymodel.NestedAnnotationInfo.TypePathEntry;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class CreateNestedAnnotationInfoVisitor
    extends Types.DefaultTypeVisitor<Set<NestedAnnotationInfo>, @Nullable Void> {

  private ArrayDeque<TypePathEntry> path;
  private Set<NestedAnnotationInfo> nestedAnnotationInfoList;

  public CreateNestedAnnotationInfoVisitor() {
    path = new ArrayDeque<>();
    nestedAnnotationInfoList = new HashSet<>();
  }

  @Override
  public @Nullable Set<NestedAnnotationInfo> visitClassType(
      Type.ClassType classType, @Nullable Void unused) {
    List<Type> typeArguments = classType.getTypeArguments();
    if (!typeArguments.isEmpty()) {
      for (int idx = 0; idx < typeArguments.size(); idx++) {
        path.addLast(new TypePathEntry(TypePathEntry.Kind.TYPE_ARGUMENT, idx));
        Type typeArg = typeArguments.get(idx);
        ImmutableList<TypePathEntry> typePath = getTypePath();
        NestedAnnotationInfo nestedAnnotationInfo = null;
        if (hasNullableAnnotation(typeArg)) {
          nestedAnnotationInfo = new NestedAnnotationInfo(Annotation.NULLABLE, typePath);
        } else if (hasNonNullAnnotation(typeArg)) {
          nestedAnnotationInfo = new NestedAnnotationInfo(Annotation.NONNULL, typePath);
        }
        if (nestedAnnotationInfo != null) {
          nestedAnnotationInfoList.add(nestedAnnotationInfo);
        }
        typeArg.accept(this, null);
        path.removeLast();
      }
    }
    return nestedAnnotationInfoList;
  }

  @Override
  public @Nullable Set<NestedAnnotationInfo> visitArrayType(
      Type.ArrayType t, @Nullable Void unused) {
    path.addLast(new TypePathEntry(TypePathEntry.Kind.ARRAY_ELEMENT, -1));
    ImmutableList<TypePathEntry> typePath = getTypePath();
    NestedAnnotationInfo nestedAnnotationInfo = null;
    if (hasNullableAnnotation(t)) {
      nestedAnnotationInfo = new NestedAnnotationInfo(Annotation.NULLABLE, typePath);
    } else if (hasNonNullAnnotation(t)) {
      nestedAnnotationInfo = new NestedAnnotationInfo(Annotation.NONNULL, typePath);
    }
    if (nestedAnnotationInfo != null) {
      nestedAnnotationInfoList.add(nestedAnnotationInfo);
    }
    t.elemtype.accept(this, null);
    path.removeLast();
    return nestedAnnotationInfoList;
  }

  @Override
  public @Nullable Set<NestedAnnotationInfo> visitWildcardType(
      Type.WildcardType t, @Nullable Void unused) {
    // Upper Bound (? extends T)
    NestedAnnotationInfo nestedAnnotationInfo = null;
    if (t.getExtendsBound() != null) {
      path.addLast(new TypePathEntry(TypePathEntry.Kind.WILDCARD_BOUND, 0));
      ImmutableList<TypePathEntry> typePath = getTypePath();
      Type upperBound = t.getExtendsBound();
      if (hasNullableAnnotation(upperBound)) {
        nestedAnnotationInfo = new NestedAnnotationInfo(Annotation.NULLABLE, typePath);
      } else if (hasNonNullAnnotation(upperBound)) {
        nestedAnnotationInfo = new NestedAnnotationInfo(Annotation.NONNULL, typePath);
      }
      if (nestedAnnotationInfo != null) {
        nestedAnnotationInfoList.add(nestedAnnotationInfo);
      }
      t.getExtendsBound().accept(this, null);
      path.removeLast();
    }

    // Lower Bound (? super T)
    if (t.getSuperBound() != null) {
      path.addLast(new TypePathEntry(TypePathEntry.Kind.WILDCARD_BOUND, 1));
      ImmutableList<TypePathEntry> typePath = getTypePath();
      Type lowerBound = t.getSuperBound();
      if (hasNullableAnnotation(lowerBound)) {
        nestedAnnotationInfo = new NestedAnnotationInfo(Annotation.NULLABLE, typePath);
      } else if (hasNonNullAnnotation(lowerBound)) {
        nestedAnnotationInfo = new NestedAnnotationInfo(Annotation.NONNULL, typePath);
      }
      if (nestedAnnotationInfo != null) {
        nestedAnnotationInfoList.add(nestedAnnotationInfo);
      }
      t.getSuperBound().accept(this, null);
      path.removeLast();
    }

    return nestedAnnotationInfoList;
  }

  @Override
  public @Nullable Set<NestedAnnotationInfo> visitType(Type t, @Nullable Void unused) {
    return nestedAnnotationInfoList;
  }

  private boolean hasNullableAnnotation(TypeMirror type) {
    if (type == null) {
      return false;
    }
    for (AnnotationMirror annotation : type.getAnnotationMirrors()) {
      String qualifiedName =
          ((TypeElement) annotation.getAnnotationType().asElement()).getQualifiedName().toString();
      if (qualifiedName.equals("org.jspecify.annotations.Nullable")) {
        return true;
      }
    }
    return false;
  }

  private boolean hasNonNullAnnotation(TypeMirror type) {
    if (type == null) {
      return false;
    }
    for (AnnotationMirror annotation : type.getAnnotationMirrors()) {
      String qualifiedName =
          ((TypeElement) annotation.getAnnotationType().asElement()).getQualifiedName().toString();
      if (qualifiedName.equals("org.jspecify.annotations.NonNull")) {
        return true;
      }
    }
    return false;
  }

  private ImmutableList<TypePathEntry> getTypePath() {
    return ImmutableList.copyOf(path);
  }
}
