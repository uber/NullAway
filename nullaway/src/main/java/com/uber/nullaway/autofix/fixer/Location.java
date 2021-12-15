package com.uber.nullaway.autofix.fixer;

import com.google.common.base.Preconditions;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.autofix.out.SeperatedValueDisplay;
import java.net.URI;
import java.util.Objects;

public class Location implements SeperatedValueDisplay {
  URI uri;
  ClassTree classTree;
  MethodTree methodTree;
  Symbol variableSymbol;
  Kind kind;
  int index;

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
        + (classTree != null ? ASTHelpers.getSymbol(classTree).toString() : "null")
        + "\n\tMethod Symbol="
        + (methodTree != null
            ? escapeQuotationMark(ASTHelpers.getSymbol(methodTree).toString())
            : "null")
        + (methodTree != null
            ? escapeQuotationMark(ASTHelpers.getSymbol(methodTree).toString())
            : "null")
        + "\n\tvariable Symbol="
        + variableSymbol
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
        && classTree.equals(location.classTree)
        && methodTree.equals(location.methodTree)
        && variableSymbol.equals(location.variableSymbol)
        && kind == location.kind;
  }

  @Override
  public int hashCode() {
    return Objects.hash(uri, classTree, methodTree, variableSymbol, kind);
  }

  public static LocationBuilder Builder() {
    return new LocationBuilder();
  }

  public static class LocationBuilder {
    Location location;

    public LocationBuilder() {
      location = new Location();
    }

    public LocationBuilder setURI(URI uri) {
      location.uri = uri;
      return this;
    }

    public LocationBuilder setClassTree(ClassTree ct) {
      location.classTree = ct;
      return this;
    }

    public LocationBuilder setMethodTree(Tree tree) {
      if (tree instanceof MethodTree) return setMethodTree((MethodTree) tree);
      throw new RuntimeException("Tree: " + tree + " is not an instance of MethodTree");
    }

    public LocationBuilder setMethodTree(MethodTree mt) {
      location.methodTree = mt;
      return this;
    }

    public LocationBuilder setVariableSymbol(Symbol s) {
      Preconditions.checkNotNull(location.kind);
      location.variableSymbol = s;
      if (location.kind.equals(Kind.METHOD_PARAM)) {
        Preconditions.checkNotNull(location.methodTree);
        Symbol.MethodSymbol methodSym = ASTHelpers.getSymbol(location.methodTree);
        for (int i = 0; i < methodSym.getParameters().size(); i++) {
          if (methodSym.getParameters().get(i).equals(s)) {
            location.index = i;
            break;
          }
        }
      }
      return this;
    }

    public LocationBuilder setKind(Kind kind) {
      location.kind = kind;
      return this;
    }

    public Location build() {
      if (location.kind == null) {
        throw new RuntimeException("Location.kind field cannot be null");
      }
      return location;
    }
  }

  @Override
  public String display(String delimiter) {
    return kind.label
        + delimiter
        + "null"
        + delimiter
        + (classTree != null ? ASTHelpers.getSymbol(classTree).toString() : "null")
        + delimiter
        + (methodTree != null ? ASTHelpers.getSymbol(methodTree).toString() : "null")
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
