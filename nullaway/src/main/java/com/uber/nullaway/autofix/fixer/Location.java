package com.uber.nullaway.autofix.fixer;

import com.google.common.base.Preconditions;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.autofix.out.SeperatedValueDisplay;
import java.net.URI;
import java.util.Objects;
import javax.lang.model.element.ElementKind;

public class Location implements SeperatedValueDisplay {

  URI uri;
  Symbol.ClassSymbol classSymbol;
  Symbol.MethodSymbol methodSymbol;
  Symbol.VarSymbol variableSymbol;
  Symbol target;
  Kind kind;
  int index = 0;

  public enum Kind {
    CLASS_FIELD("CLASS_FIELD"),
    METHOD_PARAM("METHOD_PARAM"),
    METHOD_RETURN("METHOD_RETURN");
    public final String label;

    Kind(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return "Kind{" + "label='" + label + '\'' + '}';
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
    classSymbol = findEnclosingClass(target);
  }

  public Location setUri(URI uri) {
    this.uri = uri;
    return this;
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

  private Symbol.ClassSymbol findEnclosingClass(Symbol symbol) {
    Symbol enclosingClass = symbol;
    while (enclosingClass != null && enclosingClass.getKind() != ElementKind.CLASS) {
      enclosingClass = enclosingClass.owner;
    }
    Preconditions.checkNotNull(enclosingClass);
    return (Symbol.ClassSymbol) enclosingClass;
  }

  private String escapeQuotationMark(String text) {
    StringBuilder ans = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '"') ans.append("\\");
      ans.append(text.charAt(i));
    }
    return ans.toString();
  }

  @Override
  public String toString() {
    return "Location{"
        + "\n\tURI="
        + uri.toASCIIString()
        + "\n\tClass Symbol="
        + (classSymbol != null ? classSymbol.toString() : "null")
        + "\n\tMethod Symbol="
        + (methodSymbol != null ? escapeQuotationMark(methodSymbol.toString()) : "null")
        + "\n\tvariable Symbol="
        + target
        + "\n\tindex="
        + index
        + "\n\tkind="
        + kind
        + "\n}";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Location)) return false;
    Location location = (Location) o;
    return uri.equals(location.uri)
        && classSymbol.equals(location.classSymbol)
        && methodSymbol.equals(location.methodSymbol)
        && target.equals(location.target)
        && kind == location.kind;
  }

  @Override
  public int hashCode() {
    return Objects.hash(uri, classSymbol, methodSymbol, target, kind);
  }

  @Override
  public String display(String delimiter) {
    return kind.label
        + delimiter
        + "null"
        + delimiter
        + (classSymbol != null ? classSymbol.toString() : "null")
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
        + "pkg"
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
}
