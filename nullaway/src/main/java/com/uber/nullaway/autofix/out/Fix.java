package com.uber.nullaway.autofix.out;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.autofix.fixer.Location;
import com.uber.nullaway.autofix.qual.AnnotationFactory;
import java.util.Objects;

public class Fix implements SeperatedValueDisplay {
  public Location location;
  public AnnotationFactory.Annotation annotation;
  public String reason;
  public boolean inject;
  public boolean compulsory;
  private MethodTree enclosingMethod;
  private ClassTree enclosingClass;

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
        + (reason == null ? "Undefined" : reason)
        + delimiter
        + annotation.display(delimiter)
        + delimiter
        + compulsory
        + delimiter
        + inject
        + delimiter
        + (enclosingClass == null ? "null" : ASTHelpers.getSymbol(enclosingClass))
        + delimiter
        + (enclosingMethod == null ? "null" : ASTHelpers.getSymbol(enclosingMethod));
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

  public void findEnclosing(VisitorState state, ErrorMessage errorMessage) {

    this.enclosingMethod = ASTHelpers.findEnclosingNode(state.getPath(), MethodTree.class);
    this.enclosingClass = ASTHelpers.findEnclosingNode(state.getPath(), ClassTree.class);
    if (enclosingClass == null && state.getPath().getLeaf() instanceof ClassTree) {
      ErrorMessage.MessageTypes messageTypes = errorMessage.getMessageType();
      if (messageTypes.equals(ErrorMessage.MessageTypes.ASSIGN_FIELD_NULLABLE)
          || messageTypes.equals(ErrorMessage.MessageTypes.FIELD_NO_INIT)
          || messageTypes.equals(ErrorMessage.MessageTypes.METHOD_NO_INIT)) {
        this.enclosingClass = (ClassTree) state.getPath().getLeaf();
      }
    }
    if (enclosingMethod == null
        && errorMessage.getMessageType().equals(ErrorMessage.MessageTypes.WRONG_OVERRIDE_RETURN)) {
      Tree methodTree = state.getPath().getLeaf();
      if (methodTree instanceof MethodTree) {
        this.enclosingMethod = (MethodTree) methodTree;
      }
    }
  }
}
