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

import com.uber.nullaway.fixserialization.out.FieldInitializationInfo;
import java.util.Objects;

/**
 * Helper class to represent a {@link FieldInitializationInfo} contents in a test case's (expected
 * or actual) output.
 */
public record FieldInitDisplay(
    String field, String method, String param, String location, String className, String path)
    implements Display {

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof FieldInitDisplay that)) {
      return false;
    }
    return Objects.equals(method, that.method)
        && Objects.equals(param, that.param)
        && Objects.equals(location, that.location)
        && Objects.equals(className, that.className)
        && Objects.equals(field, that.field)
        && SerializationTestHelper.pathsAreEqual(path, that.path);
  }

  @Override
  public String toString() {
    return "\n  FieldInitDisplay{"
        + "\n\tfield='"
        + field
        + '\''
        + ", \n\tmethod='"
        + method
        + '\''
        + ", \n\tparam='"
        + param
        + '\''
        + ", \n\tlocation='"
        + location
        + '\''
        + ", \n\tclassName='"
        + className
        + '\''
        + ", \n\tpath='"
        + path
        + '\''
        + "\n  }\n";
  }
}
