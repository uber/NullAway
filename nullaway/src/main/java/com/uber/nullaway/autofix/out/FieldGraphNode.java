package com.uber.nullaway.autofix.out;

import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;

public class FieldGraphNode implements SeperatedValueDisplay {

  private final Symbol member;
  private final Symbol.ClassSymbol classSymbol;

  public FieldGraphNode(Tree member, Symbol.ClassSymbol classSymbol) {
    this.member = ASTHelpers.getSymbol(member);
    this.classSymbol = classSymbol;
  }

  @Override
  public String display(String delimiter) {
    Symbol enclosing = ASTHelpers.enclosingClass(member);
    if (classSymbol.equals(enclosing)) {
      return null;
    }
    return classSymbol + delimiter + member + delimiter + enclosing;
  }

  @Override
  public String header(String delimiter) {
    return "CALLER_CLASS" + delimiter + "CALLEE_FIELD" + delimiter + "CALLEE_CLASS";
  }
}
