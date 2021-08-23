package com.uber.nullaway.autofix.out;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.autofix.fixer.Location;
import com.uber.nullaway.autofix.qual.AnnotationFactory;
import java.util.Objects;

public class Fix implements SeperatedValueDisplay {
  public Location location;
  public AnnotationFactory.Annotation annotation;
  public String reason;
  public boolean inject;
  public boolean compulsory;
  private Symbol.ClassSymbol rootClass;
  private Symbol.MethodSymbol rootMethod;

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
        + inject
        + delimiter
        + rootClass
        + delimiter
        + rootMethod;
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
        + "inject"
        + delimiter
        + "rootClass"
        + delimiter
        + "rootMethod";
  }

  public void setRoots(VisitorState state) {
    ClassTree classTree = ASTHelpers.findEnclosingNode(state.getPath(), ClassTree.class);
    MethodTree methodTree = ASTHelpers.findEnclosingNode(state.getPath(), MethodTree.class);
    this.rootClass = (classTree == null) ? null : ASTHelpers.getSymbol(classTree);
    this.rootMethod = (methodTree == null) ? null : ASTHelpers.getSymbol(methodTree);
  }
}
