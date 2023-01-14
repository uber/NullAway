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
 * Helper class to represent a suggested fix contents in a test case's (expected or actual) output.
 */
public class FixDisplay implements Display {
  public final String annotation;
  public final String method;
  public final String param;
  public final String location;
  public final String className;
  public final String path;

  public FixDisplay(
      String annotation,
      String method,
      String param,
      String location,
      String className,
      String path) {
    this.annotation = annotation;
    this.method = method;
    this.param = param;
    this.location = location;
    this.className = className;
    this.path = path;
  }

  @Override
  public String toString() {
    return "\n  FixDisplay{"
        + "\n\tannotation='"
        + annotation
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

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof FixDisplay)) {
      return false;
    }
    FixDisplay fix = (FixDisplay) o;
    return Objects.equals(annotation, fix.annotation)
        && Objects.equals(method, fix.method)
        && Objects.equals(param, fix.param)
        && Objects.equals(location, fix.location)
        && Objects.equals(className, fix.className)
        && SerializationTestHelper.pathsAreEqual(path, fix.path);
  }

  @Override
  public int hashCode() {
    return Objects.hash(annotation, method, param, location, className, path);
  }
}
