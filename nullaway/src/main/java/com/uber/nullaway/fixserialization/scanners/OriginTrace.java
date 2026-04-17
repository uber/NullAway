/*
 * MIT License
 *
 * Copyright (c) 2025 Nima Karimipour
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

package com.uber.nullaway.fixserialization.scanners;

import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import java.util.Objects;

public class OriginTrace {
  /** The origin symbol */
  private final Symbol origin;

  /** The trace where the origin contributes to the value of the local variable. */
  private final Tree trace;

  public OriginTrace(Symbol origin, Tree trace) {
    this.origin = origin;
    this.trace = trace;
  }

  public Symbol getOrigin() {
    return origin;
  }

  public Tree getTrace() {
    return trace;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof OriginTrace)) {
      return false;
    }
    OriginTrace that = (OriginTrace) o;
    return Objects.equals(getOrigin(), that.getOrigin())
        && Objects.equals(getTrace(), that.getTrace());
  }

  @Override
  public int hashCode() {
    return Objects.hash(getOrigin(), getTrace());
  }
}
