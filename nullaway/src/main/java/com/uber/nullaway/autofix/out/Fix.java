package com.uber.nullaway.autofix.out;

import com.google.errorprone.util.ASTHelpers;
import com.uber.nullaway.autofix.Location;
import com.uber.nullaway.autofix.qual.AnnotationFactory;
import java.util.Objects;

public class Fix extends EnclosingNode implements SeperatedValueDisplay {
  public Location location;
  public AnnotationFactory.Annotation annotation;
  public boolean inject;

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Fix)) return false;
    Fix fix = (Fix) o;
    return inject == fix.inject
        && Objects.equals(location, fix.location)
        && Objects.equals(annotation, fix.annotation)
        && Objects.equals(
            errorMessage.getMessageType().toString(), fix.errorMessage.getMessageType().toString());
  }

  @Override
  public int hashCode() {
    return Objects.hash(location, annotation, errorMessage.getMessageType().toString(), inject);
  }

  @Override
  public String toString() {
    return "Fix{"
        + "location="
        + location
        + ", annotation="
        + annotation
        + ", reason="
        + errorMessage.getMessageType().toString()
        + ", inject="
        + inject
        + '}';
  }

  @Override
  public String display(String delimiter) {
    return location.display(delimiter)
        + delimiter
        + (errorMessage == null ? "Undefined" : errorMessage.getMessageType().toString())
        + delimiter
        + annotation.display(delimiter)
        + delimiter
        + inject
        + delimiter
        + (enclosingClass == null ? "null" : ASTHelpers.getSymbol(enclosingClass))
        + delimiter
        + (enclosingMethod == null ? "null" : ASTHelpers.getSymbol(enclosingMethod));
  }

  public static String header(String delimiter) {
    return Location.header(delimiter)
        + delimiter
        + "reason"
        + delimiter
        + "annotation"
        + delimiter
        + "inject"
        + delimiter
        + "rootClass"
        + delimiter
        + "rootMethod";
  }
}
