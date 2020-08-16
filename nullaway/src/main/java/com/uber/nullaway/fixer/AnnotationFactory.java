package com.uber.nullaway.fixer;

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

  public AnnotationFactory(String annotations) {
    this();
    if (annotations == null || annotations.equals("") || !annotations.contains(",")) return;
    String[] annots = annotations.split(",");
    setFullNames(annots[0], annots[1]);
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
