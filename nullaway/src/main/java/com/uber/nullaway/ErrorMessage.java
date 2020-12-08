/*
 * Copyright (c) 2019 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.nullaway;

/** Contains error message string to be displayed and the message type from {@link MessageTypes}. */
public class ErrorMessage {
  MessageTypes messageType;
  String message;

  public ErrorMessage(MessageTypes messageType, String message) {
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
    NULLABLE_VARARGS_UNSUPPORTED,
    ANNOTATION_VALUE_INVALID,
    CAST_TO_NONNULL_ARG_NONNULL,
    GET_ON_EMPTY_OPTIONAL,
    SWITCH_EXPRESSION_NULLABLE,
    POSTCONDITION_NOT_SATISFIED,
    PRECONDITION_NOT_SATISFIED,
    WRONG_OVERRIDE_POSTCONDITION,
    WRONG_OVERRIDE_PRECONDITION,
  }
}
