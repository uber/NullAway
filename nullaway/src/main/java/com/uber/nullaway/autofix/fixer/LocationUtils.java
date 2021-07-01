package com.uber.nullaway.autofix.fixer;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Symbol;
import java.util.List;

public class LocationUtils extends ASTHelpers {

  public static ClassTree getClassTree(Symbol symbol, VisitorState state) {
    return findClass(enclosingClass(symbol), state);
  }

  public static ClassTree getClassTree(Tree tree, VisitorState state) {
    return getClassTree(getSymbol(tree), state);
  }

  public static VariableTree getVariableTree(MethodTree methodTree, Symbol.VarSymbol varSymbol) {
    for (VariableTree variableTree : methodTree.getParameters()) {
      if (ASTHelpers.getSymbol(variableTree)
          .getSimpleName()
          .toString()
          .equals(varSymbol.getSimpleName().toString())) return variableTree;
    }
    return null;
  }

  public static Symbol.VarSymbol getParamSymbol(Symbol.MethodSymbol methodSymbol, int position) {
    List<Symbol.VarSymbol> params = methodSymbol.getParameters();
    if (position < 0 || position >= params.size())
      throw new RuntimeException("Wrong position: " + position + " for method: " + methodSymbol);
    return params.get(position);
  }
}
