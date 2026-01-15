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

/**
 * Visitor that traverses a {@link Type} structure to discover and record nested JSpecify
 * annotations.
 *
 * <p>This visitor records annotations that occur on:
 *
 * <ul>
 *   <li>Type arguments of parameterized types (e.g. {@code List<@Nullable String>})
 *   <li>Array element types (e.g. {@code @Nullable String[]})
 *   <li>Wildcard bounds (e.g. {@code ? extends @Nullable T}, {@code ? super @NonNull T})
 * </ul>
 *
 * <p>After the visitor has completed traversal, callers should invoke {@link
 * #getNestedAnnotationInfoSet()} to retrieve the set of collected {@link NestedAnnotationInfo}
 * instances.
 */
@NullMarked
public class CreateNestedAnnotationInfoVisitor
    extends Types.DefaultTypeVisitor<@Nullable Void, @Nullable Void> {

  private final ArrayDeque<TypePathEntry> path;
  private final Set<NestedAnnotationInfo> nestedAnnotationInfoSet;

  private static final String NULLABLE_QNAME = "org.jspecify.annotations.Nullable";
  private static final String NONNULL_QNAME = "org.jspecify.annotations.NonNull";

  public CreateNestedAnnotationInfoVisitor() {
    path = new ArrayDeque<>();
    nestedAnnotationInfoSet = new HashSet<>();
  }

  @Override
  public @Nullable Void visitClassType(Type.ClassType classType, @Nullable Void unused) {
    // only processes type arguments
    List<Type> typeArguments = classType.getTypeArguments();
    if (!typeArguments.isEmpty()) {
      for (int idx = 0; idx < typeArguments.size(); idx++) {
        path.addLast(new TypePathEntry(TypePathEntry.Kind.TYPE_ARGUMENT, idx));
        Type typeArg = typeArguments.get(idx);
        addNestedAnnotationInfo(typeArg);
        typeArg.accept(this, null);
        path.removeLast();
      }
    }
    return null;
  }

  @Override
  public @Nullable Void visitArrayType(Type.ArrayType arrayType, @Nullable Void unused) {
    path.addLast(new TypePathEntry(TypePathEntry.Kind.ARRAY_ELEMENT, -1));
    addNestedAnnotationInfo(arrayType.elemtype);
    arrayType.elemtype.accept(this, null);
    path.removeLast();
    return null;
  }

  @Override
  public @Nullable Void visitWildcardType(Type.WildcardType wildcardTypet, @Nullable Void unused) {
    // Upper Bound (? extends T)
    if (wildcardTypet.getExtendsBound() != null) {
      path.addLast(new TypePathEntry(TypePathEntry.Kind.WILDCARD_BOUND, 0));
      Type upperBound = wildcardTypet.getExtendsBound();
      addNestedAnnotationInfo(upperBound);
      upperBound.accept(this, null);
      path.removeLast();
    }

    // Lower Bound (? super T)
    if (wildcardTypet.getSuperBound() != null) {
      path.addLast(new TypePathEntry(TypePathEntry.Kind.WILDCARD_BOUND, 1));
      Type lowerBound = wildcardTypet.getSuperBound();
      addNestedAnnotationInfo(lowerBound);
      wildcardTypet.getSuperBound().accept(this, null);
      path.removeLast();
    }

    return null;
  }

  @Override
  public @Nullable Void visitType(Type type, @Nullable Void unused) {
    return null;
  }

  public Set<NestedAnnotationInfo> getNestedAnnotationInfoSet() {
    return nestedAnnotationInfoSet;
  }

  private void addNestedAnnotationInfo(Type type) {
    if (hasNullableAnnotation(type)) {
      nestedAnnotationInfoSet.add(new NestedAnnotationInfo(Annotation.NULLABLE, getTypePath()));
    } else if (hasNonNullAnnotation(type)) {
      nestedAnnotationInfoSet.add(new NestedAnnotationInfo(Annotation.NONNULL, getTypePath()));
    }
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
