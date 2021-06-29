package com.uber.nullaway.autofix.out;

import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Symbol;

public class CallGraphNode implements SeperatedValueDisplay {

  private final Symbol.MethodSymbol methodSymbol;
  private final Symbol.ClassSymbol classSymbol;

  public CallGraphNode(Symbol.MethodSymbol methodSymbol, Symbol.ClassSymbol classSymbol) {
    this.methodSymbol = methodSymbol;
    this.classSymbol = classSymbol;
  }

  @Override
  public String display(String delimiter) {
    return classSymbol
        + delimiter
        + methodSymbol
        + delimiter
        + ASTHelpers.enclosingClass(methodSymbol);
  }
}
