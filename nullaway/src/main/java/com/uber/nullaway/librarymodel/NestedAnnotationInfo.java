package com.uber.nullaway.librarymodel;

import com.google.common.collect.ImmutableList;

/**
 * Class to hold information about a nested nullability annotation within a type, including the type
 * of nullability annotation and the type path to reach it.
 *
 * @param annotation the nullability annotation
 * @param typePath the type path to reach the annotation. If empty, the annotation applies to the
 *     outermost type. Otherwise, each entry indicates one step in how to navigate to the nested
 *     type.
 */
public record NestedAnnotationInfo(Annotation annotation, ImmutableList<TypePathEntry> typePath) {

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
