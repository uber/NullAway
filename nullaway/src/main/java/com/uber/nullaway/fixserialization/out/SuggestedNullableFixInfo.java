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
import com.uber.nullaway.fixserialization.location.FixLocation;
import java.util.Objects;

/** Stores information suggesting adding @Nullable on an element in source code. */
public class SuggestedNullableFixInfo {

  /** FixLocation of the target element in source code. */
  private final FixLocation fixLocation;
  /** Error which will be resolved by this type change. */
  private final ErrorMessage errorMessage;

  private final ClassAndMethodInfo classAndMethodInfo;

  public SuggestedNullableFixInfo(
      TreePath path, FixLocation fixLocation, ErrorMessage errorMessage) {
    this.classAndMethodInfo = new ClassAndMethodInfo(path);
    this.fixLocation = fixLocation;
    this.errorMessage = errorMessage;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SuggestedNullableFixInfo)) {
      return false;
    }
    SuggestedNullableFixInfo suggestedNullableFixInfo = (SuggestedNullableFixInfo) o;
    return Objects.equals(fixLocation, suggestedNullableFixInfo.fixLocation)
        && Objects.equals(
            errorMessage.getMessageType().toString(),
            suggestedNullableFixInfo.errorMessage.getMessageType().toString());
  }

  @Override
  public int hashCode() {
    return Objects.hash(fixLocation, errorMessage.getMessageType().toString());
  }

  /**
   * returns string representation of content of an object.
   *
   * @return string representation of contents of an object in a line separated by tabs.
   */
  public String tabSeparatedToString() {
    return fixLocation.tabSeparatedToString()
        + '\t'
        + errorMessage.getMessageType().toString()
        + '\t'
        + "nullable"
        + '\t'
        + (classAndMethodInfo.getClazz() == null
            ? "null"
            : ASTHelpers.getSymbol(classAndMethodInfo.getClazz()).flatName())
        + '\t'
        + (classAndMethodInfo.getMethod() == null
            ? "null"
            : ASTHelpers.getSymbol(classAndMethodInfo.getMethod()));
  }

  /** Finds the class and method of program point where triggered this type change. */
  public void initEnclosing() {
    classAndMethodInfo.findValues();
  }

  /**
   * Creates header of an output file containing all {@link SuggestedNullableFixInfo} written in
   * string which values are separated by tabs.
   *
   * @return string representation of the header separated by tabs.
   */
  public static String header() {
    return FixLocation.header()
        + '\t'
        + "reason"
        + '\t'
        + "annotation"
        + '\t'
        + "rootClass"
        + '\t'
        + "rootMethod";
  }
}
