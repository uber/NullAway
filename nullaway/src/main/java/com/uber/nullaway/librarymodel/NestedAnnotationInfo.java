package com.uber.nullaway.librarymodel;

import com.google.common.collect.ImmutableList;

/**
 * Class to hold information about a nested annotation within a type, including the annotation's
 * class name and the type path to reach it.
 */
public class NestedAnnotationInfo {

  static class TypePathEntry {
    enum Kind {
      ARRAY_ELEMENT,
      TYPE_ARGUMENT,
      WILDCARD_BOUND
    }

    private final Kind kind;
    private final int index; // only for TYPE_ARGUMENT kind

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

  private final String annotationClassName;
  private final ImmutableList<TypePathEntry> typePath;

  public NestedAnnotationInfo(String annotationClassName, ImmutableList<TypePathEntry> typePath) {
    this.annotationClassName = annotationClassName;
    this.typePath = typePath;
  }

  public String getAnnotationClassName() {
    return annotationClassName;
  }

  public ImmutableList<TypePathEntry> getTypePath() {
    return typePath;
  }
}
