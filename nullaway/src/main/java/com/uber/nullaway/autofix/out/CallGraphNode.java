package com.uber.nullaway.autofix.out;

import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Symbol;

public class CallGraphNode implements SeperatedValueDisplay {

  private final Symbol.MethodSymbol calleeMethod;
  private final Symbol.ClassSymbol callerClass;

  public CallGraphNode(Symbol.MethodSymbol calleeMethod, Symbol.ClassSymbol callerClass) {
    this.calleeMethod = calleeMethod;
    this.callerClass = callerClass;
  }

  @Override
  public String display(String delimiter) {
    return callerClass
        + delimiter
        + calleeMethod
        + delimiter
        + ASTHelpers.enclosingClass(calleeMethod);
  }

  @Override
  public String header(String delimiter) {
    return "CALLER_CLASS" + delimiter + "CALLEE_METHOD" + "CALLEE_CLASS";
  }
}
