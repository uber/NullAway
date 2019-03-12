package com.uber.nullaway;

/** Contains error message string to be displayed and the message type from {@link MessageTypes}. */
public class ErrorMessage {
  MessageTypes messageType;
  String message;

  public ErrorMessage(MessageTypes messageType, String message) {
    this.messageType = messageType;
    this.message = message;
  }

  public void updateErrorMessage(MessageTypes messageType, String message) {
    this.messageType = messageType;
    this.message = message;
  }

  public enum MessageTypes {
    DEREFERENCE_NULLABLE,
    RETURN_NULLABLE,
    PASS_NULLABLE,
    ASSIGN_FIELD_NULLABLE,
    WRONG_OVERRIDE_RETURN,
    WRONG_OVERRIDE_PARAM,
    METHOD_NO_INIT,
    FIELD_NO_INIT,
    UNBOX_NULLABLE,
    NONNULL_FIELD_READ_BEFORE_INIT,
    ANNOTATION_VALUE_INVALID,
    CAST_TO_NONNULL_ARG_NONNULL,
    GET_ON_EMPTY_OPTIONAL;
  }
}
