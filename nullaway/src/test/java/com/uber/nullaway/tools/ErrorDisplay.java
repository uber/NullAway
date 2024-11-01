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
  public final String encMember;
  public final String encClass;
  public final int offset;
  public final String path;
  public final String kind;
  public final String clazz;
  public final String method;
  public final String variable;
  public final String index;
  public final String nonElementPath;

  public ErrorDisplay(
      String type,
      String message,
      String encClass,
      String encMember,
      int offset,
      String path,
      String kind,
      String clazz,
      String method,
      String variable,
      String index,
      String nonElementPath) {
    this.type = type;
    this.message = message;
    this.encMember = encMember;
    this.encClass = encClass;
    this.offset = offset;
    this.path = path;
    this.kind = kind;
    this.clazz = clazz;
    this.method = method;
    this.variable = variable;
    this.index = index;
    this.nonElementPath = nonElementPath;
  }

  public ErrorDisplay(
      String type, String message, String encClass, String encMember, int offset, String path) {
    this(
        type, message, encClass, encMember, offset, path, "null", "null", "null", "null", "null",
        "null");
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
        && encMember.equals(that.encMember)
        && clazz.equals(that.clazz)
        && encClass.equals(that.encClass)
        && offset == that.offset
        && SerializationTestHelper.pathsAreEqual(path, that.path)
        && kind.equals(that.kind)
        && method.equals(that.method)
        && variable.equals(that.variable)
        && index.equals(that.index)
        && SerializationTestHelper.pathsAreEqual(nonElementPath, that.nonElementPath);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        type,
        message,
        encMember,
        encClass,
        offset,
        path,
        kind,
        clazz,
        method,
        variable,
        index,
        nonElementPath);
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
        + "\n\tencMember='"
        + encMember
        + '\''
        + "\n\tencClass='"
        + encClass
        + '\''
        + "\n\toffset='"
        + offset
        + '\''
        + "\n\tpath='"
        + path
        + '\''
        + "\n\tkind='"
        + kind
        + '\''
        + "\n\tclazz='"
        + clazz
        + '\''
        + "\n\tmethod='"
        + method
        + '\''
        + "\n\tvariable='"
        + variable
        + '\''
        + "\n\tindex='"
        + index
        + '\''
        + "\n\tnonElementPath='"
        + nonElementPath
        + '\''
        + '}';
  }
}
