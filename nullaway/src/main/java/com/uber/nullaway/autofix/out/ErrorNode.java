package com.uber.nullaway.autofix.out;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.ErrorMessage;

public class ErrorNode implements SeperatedValueDisplay {

  private final ErrorMessage errorMessage;
  private MethodTree enclosingMethod;
  private ClassTree enclosingClass;

  public ErrorNode(ErrorMessage errorMessage) {
    this.errorMessage = errorMessage;
  }

  public void findEnclosing(VisitorState state) {
    enclosingMethod = ASTHelpers.findEnclosingNode(state.getPath(), MethodTree.class);
    enclosingClass = ASTHelpers.findEnclosingNode(state.getPath(), ClassTree.class);
  }

  @Override
  public String display(String delimiter) {
    StringBuilder newLine = new StringBuilder();
    newLine
        .append(errorMessage.getMessageType().toString())
        .append(delimiter)
        .append(errorMessage.getMessage());
    if (enclosingClass != null && enclosingMethod != null) {
      Symbol.ClassSymbol classSymbol = ASTHelpers.getSymbol(enclosingClass);
      Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(enclosingMethod);
      newLine.append(delimiter).append(classSymbol).append(delimiter).append(methodSymbol);
    } else {
      newLine.append(delimiter).append("null").append(delimiter).append("null");
    }
    return newLine.toString();
  }

  @Override
  public String header(String delimiter) {
    return "MESSAGE_TYPE" + delimiter + "MESSAGE" + delimiter + "CLASS" + delimiter + "METHOD";
  }
}
