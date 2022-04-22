/*
 * Copyright (c) 2022 Uber Technologies, Inc.
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
import com.sun.source.util.TreePath;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.fixserialization.Serializer;

/** Stores information regarding an error which will be reported by NullAway. */
public class ErrorInfo {

  private final ErrorMessage errorMessage;
  private final EnclosingClassAndMethodInfo enclosingInfo;

  public ErrorInfo(TreePath path, ErrorMessage errorMessage) {
    this.enclosingInfo = new EnclosingClassAndMethodInfo(path);
    this.errorMessage = errorMessage;
  }

  @Override
  public String toString() {
    return errorMessage.getMessageType().toString()
        + '\t'
        + Serializer.encodeEscapeSequences(errorMessage.getMessage())
        + '\t'
        + (enclosingInfo.getClazz() != null
            ? ASTHelpers.getSymbol(enclosingInfo.getClazz())
            : "null")
        + '\t'
        + (enclosingInfo.getMethod() != null
            ? ASTHelpers.getSymbol(enclosingInfo.getMethod())
            : "null");
  }

  /** Finds the enclosing class and method of program point where the error is reported. */
  public void initEnclosing() {
    enclosingInfo.findEnclosing();
  }

  /**
   * Creates header of an output file containing all {@link ErrorInfo} written in string which
   * values are separated tabs.
   *
   * @return string representation of the header separated by tabs.
   */
  public static String header() {
    return "MESSAGE_TYPE" + '\t' + "MESSAGE" + '\t' + "CLASS" + '\t' + "METHOD";
  }
}
