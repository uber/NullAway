package com.uber.nullaway.fixer;

import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import java.io.Serializable;
import org.json.simple.JSONObject;

@SuppressWarnings("ALL") // TODO: Remove this later, This class is still under construction
public class Location implements Serializable {
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

  @SuppressWarnings("unchecked")
  public JSONObject getJson() {
    JSONObject res = new JSONObject();
    String classSymbolRep = classTree != null ? ASTHelpers.getSymbol(classTree).toString() : "";
    String methodSymbolRep =
        methodTree != null ? escapeQuotationMark(ASTHelpers.getSymbol(methodTree).toString()) : "";
    String paramSymbolRep = variableSymbol != null ? variableSymbol.toString() : "";
    String pkg = compilationUnitTree != null ? compilationUnitTree.getPackageName().toString() : "";
    res.put(KEYS.CLASS.label, classSymbolRep);
    res.put(KEYS.METHOD.label, methodSymbolRep);
    res.put(KEYS.PARAM.label, paramSymbolRep);
    res.put(KEYS.LOCATION.label, kind.label);
    res.put(KEYS.PKG.label, pkg);
    if (compilationUnitTree != null) {
      res.put(KEYS.URI.label, compilationUnitTree.getSourceFile().toUri().toASCIIString());
    } else {
      res.put(KEYS.URI.label, "");
    }
    return res;
  }

  @Override
  public String toString() {
    return "Location{"
        + "\n\tURI="
        + compilationUnitTree.getSourceFile().toUri().toASCIIString()
        + "\n\tClass Symbol="
        + (classTree != null ? ASTHelpers.getSymbol(classTree).toString() : "null")
        + "\n\tMethod Symbol="
        + (methodTree != null ? ASTHelpers.getSymbol(methodTree).toString() : "null")
        + "\n\tvariable Symbol="
        + variableSymbol
        + "\n\tkind="
        + kind
        + "\n}";
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
}
