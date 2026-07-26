package com.uber.nullaway.libmodel;

import com.google.common.collect.ImmutableList;
import org.jspecify.annotations.NullMarked;

/**
 * Class to hold information about a nested nullability annotation within a type, including the type
 * of nullability annotation and the type path to reach it.
 *
 * @param annotation the nullability annotation
 * @param typePath the nonempty type path to reach the nested annotation. Top-level annotations must
 *     be represented using the dedicated parameter or return models.
 */
@NullMarked
public record NestedAnnotationInfo(Annotation annotation, ImmutableList<TypePathEntry> typePath) {

  public NestedAnnotationInfo {
    if (typePath.isEmpty()) {
      throw new IllegalArgumentException(
          "Nested annotation type paths must be nonempty; use a top-level parameter or return model");
    }
  }

  /**
   * Class for a single entry in a type path, indicating how to navigate the "next step" in the type
   * to eventually reach some target type.
   *
   * @param kind the kind of this type path entry
   * @param index the index associated with the kind. For TYPE_ARGUMENT, this is the type argument
   *     index. For WILDCARD_BOUND, this is 0 for the upper bound ({@code ? extends Foo}) and 1 for
   *     the lower bound ({@code ? super Foo}). For ARRAY_ELEMENT, this is unused and set to -1.
   */
  public record TypePathEntry(Kind kind, int index) {

    /** Possible nested type kinds for an entry */
    public enum Kind {
      ARRAY_ELEMENT,
      TYPE_ARGUMENT,
      WILDCARD_BOUND
    }
  }

  /** Possible annotations for nullability */
  public enum Annotation {
    NULLABLE,
    NONNULL
  }
}
