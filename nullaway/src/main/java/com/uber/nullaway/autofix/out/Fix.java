package com.uber.nullaway.autofix.out;

import com.uber.nullaway.autofix.fixer.Location;
import com.uber.nullaway.autofix.qual.AnnotationFactory;
import java.util.Objects;

@SuppressWarnings({
  "UnusedVariable"
}) // TODO: remove this later, this class is still under construction on 'AutoFix' branch
public class Fix implements SeperatedValueDisplay {
  public Location location;
  public AnnotationFactory.Annotation annotation;
  public String reason;
  public boolean inject;
  public boolean compulsory;

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Fix)) return false;
    Fix fix = (Fix) o;
    return inject == fix.inject
        && compulsory == fix.compulsory
        && Objects.equals(location, fix.location)
        && Objects.equals(annotation, fix.annotation)
        && Objects.equals(reason, fix.reason);
  }

  @Override
  public int hashCode() {
    return Objects.hash(location, annotation, reason, inject, compulsory);
  }

  @Override
  public String toString() {
    return "Fix{"
        + "location="
        + location
        + ", annotation="
        + annotation
        + ", inject="
        + reason
        + ", reason="
        + compulsory
        + ", compulsory="
        + inject
        + '}';
  }

  @Override
  public String display(String delimiter) {
    return location.display(delimiter)
        + delimiter
        + ((reason == null) ? "Undefined" : reason)
        + delimiter
        + annotation.display(delimiter)
        + delimiter
        + compulsory
        + delimiter
        + inject;
  }

  @Override
  public String header(String delimiter) {
    return location.header(delimiter)
        + delimiter
        + "reason"
        + delimiter
        + "annotation"
        + delimiter
        + "compulsory"
        + delimiter
        + "inject";
  }
}
