package com.uber.nullaway.fixer;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.tree.JCTree;

@SuppressWarnings("ALL")
public class LocationUtils extends ASTHelpers {

  public static JCTree.JCCompilationUnit getCompilationUnit(VisitorState state) {
    return (JCTree.JCCompilationUnit) state.getPath().getCompilationUnit();
  }

  public static ClassTree getClassTree(Symbol.MethodSymbol methodTree, VisitorState state) {
    return ASTHelpers.findClass(ASTHelpers.enclosingClass(methodTree), state);
  }
}
