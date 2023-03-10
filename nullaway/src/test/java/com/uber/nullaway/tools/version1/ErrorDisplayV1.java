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
package com.uber.nullaway.tools.version1;

import com.google.common.base.Preconditions;
import com.uber.nullaway.tools.Display;
import com.uber.nullaway.tools.DisplayFactory;
import com.uber.nullaway.tools.SerializationTestHelper;
import java.util.Objects;

/**
 * Helper class to test backward compatibility of different serialization versions and to represent
 * a {@link com.uber.nullaway.fixserialization.out.ErrorInfo} contents in a test case's (expected or
 * actual) output <strong>specifically for serialization version 1.</strong>
 */
public class ErrorDisplayV1 implements Display {
  public final String type;
  public final String message;
  public final String encMember;
  public final String encClass;
  public final String kind;
  public final String clazz;
  public final String method;
  public final String variable;
  public final String index;
  public final String path;

  public ErrorDisplayV1(
      String type,
      String message,
      String encClass,
      String encMember,
      String kind,
      String clazz,
      String method,
      String variable,
      String index,
      String path) {
    this.type = type;
    this.message = message;
    this.encMember = encMember;
    this.encClass = encClass;
    this.kind = kind;
    this.clazz = clazz;
    this.method = method;
    this.variable = variable;
    this.index = index;
    this.path = path;
  }

  public ErrorDisplayV1(String type, String message, String encClass, String encMember) {
    this(type, message, encClass, encMember, "null", "null", "null", "null", "null", "null");
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ErrorDisplayV1)) {
      return false;
    }
    ErrorDisplayV1 that = (ErrorDisplayV1) o;
    return type.equals(that.type)
        // To increase readability, a shorter version of the actual message might be present in the
        // expected output of tests.
        && (message.contains(that.message) || that.message.contains(message))
        && encMember.equals(that.encMember)
        && clazz.equals(that.clazz)
        && encClass.equals(that.encClass)
        && kind.equals(that.kind)
        && method.equals(that.method)
        && variable.equals(that.variable)
        && index.equals(that.index)
        && SerializationTestHelper.pathsAreEqual(path, that.path);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        type, message, encMember, encClass, kind, clazz, method, variable, index, path);
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
        + "\n\turi='"
        + path
        + '\''
        + '}';
  }

  /**
   * Returns the corresponding {@link DisplayFactory} for creating {@link ErrorDisplayV1} objects
   * from an array of strings.
   *
   * @return a {@link DisplayFactory} for {@link ErrorDisplayV1} objects.
   */
  public static DisplayFactory<ErrorDisplayV1> getFactory() {
    return values -> {
      Preconditions.checkArgument(
          values.length == 10,
          "Needs exactly 10 values to create ErrorDisplay for version 1 object but found: "
              + values.length);
      return new ErrorDisplayV1(
          values[0],
          values[1],
          values[2],
          values[3],
          values[4],
          values[5],
          values[6],
          values[7],
          values[8],
          SerializationTestHelper.getRelativePathFromUnitTestTempDirectory(values[9]));
    };
  }
}
