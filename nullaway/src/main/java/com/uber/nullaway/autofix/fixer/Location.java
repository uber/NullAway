package com.uber.nullaway.autofix.fixer;

import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.autofix.out.SeperatedValueDisplay;
import java.util.Objects;

@SuppressWarnings("ALL") // TODO: Remove this later, This class is still under construction
public class Location implements SeperatedValueDisplay {
  CompilationUnitTree compilationUnitTree;
  ClassTree classTree;
  MethodTree methodTree;
  Symbol variableSymbol;
  Kind kind;

  public enum Kind {
    CLASS_FIELD("CLASS_FIELD"),
    METHOD_PARAM("METHOD_PARAM"),
    METHOD_RETURN("METHOD_RETURN"),
    METHOD_LOCAL_VAR("METHOD_LOCAL_VAR");
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
        + compilationUnitTree.getSourceFile().toUri().toASCIIString()
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
        + "\n\tkind="
        + kind
        + "\n}";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Location)) return false;
    Location location = (Location) o;
    return compilationUnitTree.equals(location.compilationUnitTree)
        && classTree.equals(location.classTree)
        && methodTree.equals(location.methodTree)
        && variableSymbol.equals(location.variableSymbol)
        && kind == location.kind;
  }

  @Override
  public int hashCode() {
    return Objects.hash(compilationUnitTree, classTree, methodTree, variableSymbol, kind);
  }

  public static LocationBuilder Builder() {
    return new LocationBuilder();
  }

  public static class LocationBuilder {
    Location location;

    public LocationBuilder() {
      location = new Location();
    }

    public LocationBuilder setCompilationUnitTree(CompilationUnitTree cut) {
      location.compilationUnitTree = cut;
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
      location.variableSymbol = s;
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
    StringBuilder ans = new StringBuilder();
    ans.append(kind.label)
        .append(delimiter)
        .append(
            compilationUnitTree != null ? compilationUnitTree.getPackageName().toString() : "null")
        .append(delimiter)
        .append(classTree != null ? ASTHelpers.getSymbol(classTree).toString() : "null")
        .append(delimiter)
        .append(methodTree != null ? ASTHelpers.getSymbol(methodTree).toString() : "null")
        .append(delimiter)
        .append(variableSymbol != null ? variableSymbol.toString() : "null")
        .append(delimiter)
        .append(
            compilationUnitTree != null
                ? compilationUnitTree.getSourceFile().toUri().toASCIIString()
                : "null");
    return ans.toString();
  }
}
