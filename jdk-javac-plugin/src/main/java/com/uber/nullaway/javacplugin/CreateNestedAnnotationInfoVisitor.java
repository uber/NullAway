package com.uber.nullaway.javacplugin;

import com.google.common.collect.ImmutableList;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.javacplugin.NestedAnnotationInfo.Annotation;
import com.uber.nullaway.javacplugin.NestedAnnotationInfo.TypePathEntry;
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

  private final ArrayDeque<TypePathEntry> path;
  private final Set<NestedAnnotationInfo> nestedAnnotationInfoSet;

  private static final String NULLABLE_QNAME = "org.jspecify.annotations.Nullable";
  private static final String NONNULL_QNAME = "org.jspecify.annotations.NonNull";

  public CreateNestedAnnotationInfoVisitor() {
    path = new ArrayDeque<>();
    nestedAnnotationInfoSet = new HashSet<>();
  }

  @Override
  public @Nullable Set<NestedAnnotationInfo> visitClassType(
      Type.ClassType classType, @Nullable Void unused) {
    // only processes type arguments
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
          nestedAnnotationInfoSet.add(nestedAnnotationInfo);
        }
        typeArg.accept(this, null);
        path.removeLast();
      }
    }
    return nestedAnnotationInfoSet;
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
      nestedAnnotationInfoSet.add(nestedAnnotationInfo);
    }
    t.elemtype.accept(this, null);
    path.removeLast();
    return nestedAnnotationInfoSet;
  }

  @Override
  public @Nullable Set<NestedAnnotationInfo> visitWildcardType(
      Type.WildcardType t, @Nullable Void unused) {
    // Upper Bound (? extends T)
    if (t.getExtendsBound() != null) {
      NestedAnnotationInfo nestedAnnotationInfo = null;
      path.addLast(new TypePathEntry(TypePathEntry.Kind.WILDCARD_BOUND, 0));
      ImmutableList<TypePathEntry> typePath = getTypePath();
      Type upperBound = t.getExtendsBound();
      if (hasNullableAnnotation(upperBound)) {
        nestedAnnotationInfo = new NestedAnnotationInfo(Annotation.NULLABLE, typePath);
      } else if (hasNonNullAnnotation(upperBound)) {
        nestedAnnotationInfo = new NestedAnnotationInfo(Annotation.NONNULL, typePath);
      }
      if (nestedAnnotationInfo != null) {
        nestedAnnotationInfoSet.add(nestedAnnotationInfo);
      }
      t.getExtendsBound().accept(this, null);
      path.removeLast();
    }

    // Lower Bound (? super T)
    if (t.getSuperBound() != null) {
      NestedAnnotationInfo nestedAnnotationInfo = null;
      path.addLast(new TypePathEntry(TypePathEntry.Kind.WILDCARD_BOUND, 1));
      ImmutableList<TypePathEntry> typePath = getTypePath();
      Type lowerBound = t.getSuperBound();
      if (hasNullableAnnotation(lowerBound)) {
        nestedAnnotationInfo = new NestedAnnotationInfo(Annotation.NULLABLE, typePath);
      } else if (hasNonNullAnnotation(lowerBound)) {
        nestedAnnotationInfo = new NestedAnnotationInfo(Annotation.NONNULL, typePath);
      }
      if (nestedAnnotationInfo != null) {
        nestedAnnotationInfoSet.add(nestedAnnotationInfo);
      }
      t.getSuperBound().accept(this, null);
      path.removeLast();
    }

    return nestedAnnotationInfoSet;
  }

  @Override
  public @Nullable Set<NestedAnnotationInfo> visitType(Type t, @Nullable Void unused) {
    return nestedAnnotationInfoSet;
  }

  private static boolean hasAnnotation(TypeMirror type, String qname) {
    if (type == null) {
      return false;
    }
    for (AnnotationMirror annotation : type.getAnnotationMirrors()) {
      String qualifiedName =
          ((TypeElement) annotation.getAnnotationType().asElement()).getQualifiedName().toString();
      if (qualifiedName.equals(qname)) {
        return true;
      }
    }
    return false;
  }

  private boolean hasNullableAnnotation(TypeMirror type) {
    return hasAnnotation(type, NULLABLE_QNAME);
  }

  private boolean hasNonNullAnnotation(TypeMirror type) {
    return hasAnnotation(type, NONNULL_QNAME);
  }

  private ImmutableList<TypePathEntry> getTypePath() {
    return ImmutableList.copyOf(path);
  }
}
