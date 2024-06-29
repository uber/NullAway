/*
 * Copyright (c) 2024 Uber Technologies, Inc.
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

package com.uber.nullaway.handlers;

import com.google.errorprone.VisitorState;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.uber.nullaway.NullAway;
import java.util.Objects;

/**
 * Context object for checks on methods. Contains the {@link NullAway} instance, the {@link
 * MethodSymbol} for the method being analyzed, and the {@link VisitorState} for the analysis.
 */
public class MethodAnalysisContext {
  private final NullAway analysis;
  private final MethodSymbol methodSymbol;
  private final VisitorState state;

  public NullAway analysis() {
    return analysis;
  }

  public VisitorState state() {
    return state;
  }

  public MethodSymbol methodSymbol() {
    return methodSymbol;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || !(o instanceof MethodAnalysisContext)) {
      return false;
    }
    MethodAnalysisContext that = (MethodAnalysisContext) o;
    return analysis.equals(that.analysis)
        && state.equals(that.state)
        && methodSymbol.equals(that.methodSymbol);
  }

  @Override
  public int hashCode() {
    return Objects.hash(analysis, state, methodSymbol);
  }

  @Override
  public String toString() {
    return "MethodAnalysisContext{"
        + "analysis="
        + analysis
        + ", state="
        + state
        + ", methodSymbol="
        + methodSymbol
        + '}';
  }

  public MethodAnalysisContext(NullAway analysis, VisitorState state, MethodSymbol methodSymbol) {
    this.analysis = analysis;
    this.state = state;
    this.methodSymbol = methodSymbol;
  }
}
