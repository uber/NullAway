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

package com.uber.nullaway.fixserialization.location;

import com.sun.tools.javac.code.Symbol;

/** Provides method for fix locations. */
public interface FixLocation {

  /**
   * returns string representation of contents of the instance. It must have the format below: type
   * of the element, symbol of the containing class, symbol of the enclosing method, symbol of the
   * variable, index of the element and uri to containing file.
   *
   * @return string representation of contents in a line seperated by tabs.
   */
  String tabSeparatedToString();

  /**
   * Creates header of an output file containing all {@link FixLocation} written in string which
   * values are separated tabs.
   *
   * @return string representation of the header separated by tabs.
   */
  static String header() {
    return "location"
        + '\t'
        + "class"
        + '\t'
        + "method"
        + '\t'
        + "param"
        + '\t'
        + "index"
        + '\t'
        + "uri";
  }

  /**
   * returns the appropriate subtype of {@link FixLocation} based on the target kind.
   *
   * @param target Target element.
   * @return subtype of {@link FixLocation} matching target's type.
   */
  static FixLocation createFixLocationFromSymbol(Symbol target) {
    switch (target.getKind()) {
      case PARAMETER:
        return new MethodParameterLocation(target);
      case METHOD:
        return new MethodLocation(target);
      case FIELD:
        return new FieldLocation(target);
      default:
        throw new IllegalArgumentException("Cannot locate node: " + target);
    }
  }
}
