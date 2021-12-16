package com.uber.nullaway.autofix.out;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.uber.nullaway.ErrorMessage;

public class ErrorInfo extends EnclosingNode implements SeperatedValueDisplay {

  public ErrorInfo(ErrorMessage errorMessage) {
    this.errorMessage = errorMessage;
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

  public static String header(String delimiter) {
    return "MESSAGE_TYPE"
        + delimiter
        + "MESSAGE"
        + delimiter
        + "CLASS"
        + delimiter
        + "METHOD"
        + delimiter
        + "COVERED";
  }

  public void findEnclosing(VisitorState state) {
    super.findEnclosing(state, errorMessage);
  }
}
