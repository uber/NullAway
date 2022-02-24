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
package com.uber.nullaway.tools;

import java.util.Objects;

/**
 * Helper class to represent a {@link com.uber.nullaway.fixserialization.out.ErrorInfo} contents in
 * a test case's (expected or actual) output.
 */
public class ErrorDisplay implements Display {
  public final String type;
  public final String message;
  public final String enclosingMethod;
  public final String enclosingClass;

  public ErrorDisplay(String type, String message, String enclosingClass, String enclosingMethod) {
    this.type = type;
    this.message = message;
    this.enclosingMethod = enclosingMethod;
    this.enclosingClass = enclosingClass;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ErrorDisplay)) {
      return false;
    }
    ErrorDisplay that = (ErrorDisplay) o;
    return type.equals(that.type)
        // To increase readability, a shorter version of the actual message might be present in the
        // expected output of tests.
        && (message.contains(that.message) || that.message.contains(message))
        && enclosingMethod.equals(that.enclosingMethod)
        && enclosingClass.equals(that.enclosingClass);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, message, enclosingMethod, enclosingClass);
  }

  @Override
  public String toString() {
    return "\n  ErrorDisplay{"
        + "\n\ttype='"
        + type
        + '\''
        + "\n\tmessage='"
        + message
        + '\''
        + "\n\tenclosingMethod='"
        + enclosingMethod
        + '\''
        + "\n\tenclosingClass='"
        + enclosingClass
        + '\''
        + '}';
  }
}
