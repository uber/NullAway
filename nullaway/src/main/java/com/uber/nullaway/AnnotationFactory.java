package com.uber.nullaway;

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

  public AnnotationFactory(String nonnullFullName, String nullableFullName) {
    setFullNames(nonnullFullName, nullableFullName);
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
