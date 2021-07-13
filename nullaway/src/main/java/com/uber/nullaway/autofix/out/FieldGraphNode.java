package com.uber.nullaway.autofix.out;

import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MemberSelectTree;
import com.sun.tools.javac.code.Symbol;

public class FieldGraphNode implements SeperatedValueDisplay {

  private final Symbol member;
  private final Symbol.ClassSymbol classSymbol;

  public FieldGraphNode(MemberSelectTree member, Symbol.ClassSymbol classSymbol) {
    this.member = ASTHelpers.getSymbol(member);
    this.classSymbol = classSymbol;
  }

  @Override
  public String display(String delimiter) {
    return classSymbol + delimiter + member + delimiter + ASTHelpers.enclosingClass(member);
  }

  @Override
  public String header(String delimiter) {
    return "CALLER_CLASS" + delimiter + "CALLEE_FIELD" + delimiter + "CALLEE_CLASS";
  }
}
