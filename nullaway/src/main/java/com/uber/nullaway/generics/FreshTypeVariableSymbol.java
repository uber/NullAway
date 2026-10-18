/*
 * Copyright (c) 2026 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to do so, subject to the following conditions:
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

package com.uber.nullaway.generics;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;

/**
 * A call-specific type-variable symbol retaining its declaration for library-model lookups.
 *
 * <p>The symbol can escape its creating solver through provisional lambda parameter types. A
 * separate solver treating it as fixed must still be able to find the declaration's modeled bound
 * nullability without copying modeled nullability into type annotations.
 */
final class FreshTypeVariableSymbol extends Symbol.TypeVariableSymbol {

  final Symbol.TypeVariableSymbol declaredSymbol;

  /**
   * Creates a distinct symbol and type whose bound references can be rebound by the creating
   * solver.
   */
  FreshTypeVariableSymbol(Symbol.TypeVariableSymbol declaredSymbol) {
    super(0, declaredSymbol.name, declaredSymbol.type, declaredSymbol.owner);
    this.declaredSymbol = declaredSymbol;
    Type.TypeVar declared = (Type.TypeVar) declaredSymbol.type;
    type = new Type.TypeVar(this, declared.getUpperBound(), declared.getLowerBound());
  }
}
