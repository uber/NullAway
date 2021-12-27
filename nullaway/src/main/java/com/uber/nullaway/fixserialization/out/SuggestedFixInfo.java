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
import com.uber.nullaway.fixserialization.Location;
import com.uber.nullaway.fixserialization.qual.AnnotationFactory;
import java.util.Objects;

/** Stores information suggesting a type change of an element in source code. */
public class SuggestedFixInfo extends EnclosingNode implements SeperatedValueDisplay {

  /** Location of the target element in source code. */
  private final Location location;
  /** Error which will be resolved by this type change. */
  private final ErrorMessage errorMessage;
  /** Suggested annotation. */
  private final AnnotationFactory.Annotation annotation;

  public SuggestedFixInfo(
      Location location, ErrorMessage errorMessage, AnnotationFactory.Annotation annotation) {
    this.location = location;
    this.errorMessage = errorMessage;
    this.annotation = annotation;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof SuggestedFixInfo)) return false;
    SuggestedFixInfo suggestedFixInfo = (SuggestedFixInfo) o;
    return Objects.equals(location, suggestedFixInfo.location)
        && Objects.equals(annotation, suggestedFixInfo.annotation)
        && Objects.equals(
            errorMessage.getMessageType().toString(),
            suggestedFixInfo.errorMessage.getMessageType().toString());
  }

  @Override
  public int hashCode() {
    return Objects.hash(location, annotation, errorMessage.getMessageType().toString());
  }

  @Override
  public String display(String delimiter) {
    return location.display(delimiter)
        + delimiter
        + (errorMessage == null ? "Undefined" : errorMessage.getMessageType().toString())
        + delimiter
        + annotation.display(delimiter)
        + delimiter
        + (enclosingClass == null ? "null" : ASTHelpers.getSymbol(enclosingClass))
        + delimiter
        + (enclosingMethod == null ? "null" : ASTHelpers.getSymbol(enclosingMethod));
  }

  /**
   * creates header of a csv file containing all {@link SuggestedFixInfo}.
   *
   * @param delimiter the delimiter.
   * @return string representation of the header separated by the {@code delimiter}.
   */
  public static String header(String delimiter) {
    return Location.header(delimiter)
        + delimiter
        + "reason"
        + delimiter
        + "annotation"
        + delimiter
        + "rootClass"
        + delimiter
        + "rootMethod";
  }
}
