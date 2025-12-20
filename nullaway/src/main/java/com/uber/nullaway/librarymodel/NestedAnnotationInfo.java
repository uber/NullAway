package com.uber.nullaway.librarymodel;

import com.google.common.collect.ImmutableList;
import java.util.Objects;

/**
 * Class to hold information about a nested annotation within a type, including the annotation's
 * class name and the type path to reach it.
 */
public class NestedAnnotationInfo {

  public static class TypePathEntry {
    public enum Kind {
      ARRAY_ELEMENT,
      TYPE_ARGUMENT,
      WILDCARD_BOUND
    }

    private final Kind kind;

    /**
     * The index associated with the kind. For TYPE_ARGUMENT, this is the type argument index. For
     * WILDCARD_BOUND, this is 0 for the upper bound ({@code ? extends Foo}) and 1 for the lower
     * bound ({@code ? super Foo}). For ARRAY_ELEMENT, this is unused and set to -1.
     */
    private final int index;

    public TypePathEntry(Kind kind, int index) {
      this.kind = kind;
      this.index = index;
    }

    public TypePathEntry(Kind kind) {
      this(kind, -1);
    }

    public Kind getKind() {
      return kind;
    }

    public int getIndex() {
      return index;
    }
  }

  public enum Annotation {
    NULLABLE,
    NONNULL
  }

  private final Annotation annotation;
  private final ImmutableList<TypePathEntry> typePath;

  public NestedAnnotationInfo(Annotation annotation, ImmutableList<TypePathEntry> typePath) {
    this.annotation = annotation;
    this.typePath = typePath;
  }

  public Annotation getAnnotation() {
    return annotation;
  }

  public ImmutableList<TypePathEntry> getTypePath() {
    return typePath;
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || !(o instanceof NestedAnnotationInfo)) {
      return false;
    }
    NestedAnnotationInfo that = (NestedAnnotationInfo) o;
    return annotation == that.annotation && Objects.equals(typePath, that.typePath);
  }

  @Override
  public int hashCode() {
    return Objects.hash(annotation, typePath);
  }
}
