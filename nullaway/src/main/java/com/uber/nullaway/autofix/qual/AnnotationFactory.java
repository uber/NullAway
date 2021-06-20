package com.uber.nullaway.autofix.qual;

public class AnnotationFactory {

  private Annotation nonNull;
  private Annotation nullable;

  public static class Annotation {
    public String name;
    public String fullName;

    private Annotation(String fullName) {
      this.fullName = fullName;
      String[] paths = fullName.split("\\.");
      this.name = paths[paths.length - 1];
    }
  }

  public AnnotationFactory() {
    setFullNames("javax.annotation.Nonnull", "javax.annotation.Nullable");
  }

  public AnnotationFactory(String nullable, String nonNull) {
    this();
    if (nullable == null || nullable.equals("") || nonNull == null || nonNull.equals("")) return;
    setFullNames(nonNull, nullable);
  }

  public void setFullNames(String nonnullFullName, String nullableFullName) {
    nonNull = new Annotation(nonnullFullName);
    nullable = new Annotation(nullableFullName);
  }

  public Annotation getNonNull() {
    return nonNull;
  }

  public Annotation getNullable() {
    return nullable;
  }
}
