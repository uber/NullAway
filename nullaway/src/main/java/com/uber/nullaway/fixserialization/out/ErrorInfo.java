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

package com.uber.nullaway.fixserialization.out;

import com.google.errorprone.util.ASTHelpers;
import com.uber.nullaway.ErrorMessage;

/** Stores information regarding an error which will be reported by NullAway. */
public class ErrorInfo extends EnclosingNode implements SeperatedValueDisplay {

  private final ErrorMessage errorMessage;

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

  /**
   * creates header of a csv file containing all {@link ErrorInfo}.
   *
   * @param delimiter the delimiter.
   * @return string representation of the header separated by the {@code delimiter}.
   */
  public static String header(String delimiter) {
    return "MESSAGE_TYPE" + delimiter + "MESSAGE" + delimiter + "CLASS" + delimiter + "METHOD";
  }
}
