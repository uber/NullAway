package com.uber.nullaway.autofix.out;

import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;

public class TrackerNode implements SeperatedValueDisplay {
  private final Symbol member;
  private final Symbol.ClassSymbol callerClass;
  private final Symbol.MethodSymbol callerMethod;

  public TrackerNode(Symbol member, TreePath path) {
    ClassTree callerClass = ASTHelpers.findEnclosingNode(path, ClassTree.class);
    MethodTree callerMethod = ASTHelpers.findEnclosingNode(path, MethodTree.class);
    this.member = member;
    this.callerClass = (callerClass != null) ? ASTHelpers.getSymbol(callerClass) : null;
    this.callerMethod = (callerMethod != null) ? ASTHelpers.getSymbol(callerMethod) : null;
  }

  @Override
  public String display(String delimiter) {
    if (callerClass == null) {
      return null;
    }
    return callerClass
        + delimiter
        + ((callerMethod == null) ? "null" : callerMethod)
        + delimiter
        + member
        + delimiter
        + ASTHelpers.enclosingClass(member);
  }

  public static String header(String delimiter) {
    return "CALLER_CLASS"
        + delimiter
        + "CALLER_METHOD"
        + delimiter
        + "MEMBER"
        + delimiter
        + "CALLEE_CLASS";
  }
}
