package com.uber.nullaway.autofix.out;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.uber.nullaway.ErrorMessage;

public class EnclosingNode {
  public ErrorMessage errorMessage;
  protected MethodTree enclosingMethod;
  protected ClassTree enclosingClass;

  public void findEnclosing(VisitorState state, ErrorMessage errorMessage) {
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
}
