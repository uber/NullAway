package com.uber.nullaway.autofix.fixer;

import com.google.common.base.Preconditions;
import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.autofix.out.SeperatedValueDisplay;
import java.net.URI;
import javax.lang.model.element.ElementKind;

public class Location implements SeperatedValueDisplay {

  private final URI uri;
  private final Symbol.ClassSymbol classSymbol;
  private Symbol.MethodSymbol methodSymbol;
  private Symbol.VarSymbol variableSymbol;
  private Kind kind;
  int index = 0;

  public enum Kind {
    CLASS_FIELD("CLASS_FIELD"),
    METHOD_PARAM("METHOD_PARAM"),
    METHOD_RETURN("METHOD_RETURN");
    public final String label;

    Kind(String label) {
      this.label = label;
    }
  }

  public Location(Symbol target) {
    switch (target.getKind()) {
      case PARAMETER:
        createMethodParamLocation(target);
        break;
      case METHOD:
        createMethodLocation(target);
        break;
      case FIELD:
        createFieldLocation(target);
        break;
      default:
        throw new IllegalStateException("Cannot locate node: " + target);
    }
    this.classSymbol = ASTHelpers.enclosingClass(target);
    this.uri = classSymbol.sourcefile.toUri();
  }

  private void createFieldLocation(Symbol field) {
    Preconditions.checkArgument(field.getKind() == ElementKind.FIELD);
    variableSymbol = (Symbol.VarSymbol) field;
    kind = Kind.CLASS_FIELD;
  }

  private void createMethodLocation(Symbol method) {
    Preconditions.checkArgument(method.getKind() == ElementKind.METHOD);
    kind = Kind.METHOD_RETURN;
    methodSymbol = (Symbol.MethodSymbol) method;
  }

  private void createMethodParamLocation(Symbol parameter) {
    Preconditions.checkArgument(parameter.getKind() == ElementKind.PARAMETER);
    this.kind = Kind.METHOD_PARAM;
    this.variableSymbol = (Symbol.VarSymbol) parameter;
    Symbol enclosingMethod = parameter;
    while (enclosingMethod != null && enclosingMethod.getKind() != ElementKind.METHOD) {
      enclosingMethod = enclosingMethod.owner;
    }
    Preconditions.checkNotNull(enclosingMethod);
    methodSymbol = (Symbol.MethodSymbol) enclosingMethod;
    for (int i = 0; i < methodSymbol.getParameters().size(); i++) {
      if (methodSymbol.getParameters().get(i).equals(parameter)) {
        index = i;
        break;
      }
    }
  }

  @Override
  public String display(String delimiter) {
    return kind.label
        + delimiter
        + classSymbol.toString()
        + delimiter
        + (methodSymbol != null ? methodSymbol.toString() : "null")
        + delimiter
        + (variableSymbol != null ? variableSymbol.toString() : "null")
        + delimiter
        + index
        + delimiter
        + (uri != null ? uri.toASCIIString() : "null");
  }

  public static String header(String delimiter) {
    return "location"
        + delimiter
        + "class"
        + delimiter
        + "method"
        + delimiter
        + "param"
        + delimiter
        + "index"
        + delimiter
        + "uri";
  }

  public boolean isInAnonymousClass() {
    return classSymbol.toString().startsWith("<anonymous");
  }
}
