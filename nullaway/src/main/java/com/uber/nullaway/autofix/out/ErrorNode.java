package com.uber.nullaway.autofix.out;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
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
    if (enclosingClass == null && state.getPath().getLeaf() instanceof ClassTree) {
      ErrorMessage.MessageTypes messageTypes = errorMessage.getMessageType();
      if (messageTypes.equals(ErrorMessage.MessageTypes.ASSIGN_FIELD_NULLABLE)
          || messageTypes.equals(ErrorMessage.MessageTypes.FIELD_NO_INIT)
          || messageTypes.equals(ErrorMessage.MessageTypes.METHOD_NO_INIT)) {
        enclosingClass = (ClassTree) state.getPath().getLeaf();
      }
    }
    if (enclosingMethod == null
        && errorMessage.getMessageType().equals(ErrorMessage.MessageTypes.WRONG_OVERRIDE_RETURN)) {
      Tree methodTree = state.getPath().getLeaf();
      if (methodTree instanceof MethodTree) {
        enclosingMethod = (MethodTree) methodTree;
      }
    }
  }

  @Override
  public String display(String delimiter) {
    return errorMessage.getMessageType().toString()
        + delimiter
        + errorMessage.getMessage()
        + delimiter
        + (enclosingClass != null ? ASTHelpers.getSymbol(enclosingClass) : "null")
        + delimiter
        + (enclosingMethod != null ? ASTHelpers.getSymbol(enclosingMethod) : "null");
  }

  @Override
  public String header(String delimiter) {
    return "MESSAGE_TYPE" + delimiter + "MESSAGE" + delimiter + "CLASS" + delimiter + "METHOD";
  }
}
