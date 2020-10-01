/*
 * Copyright (c) 2017 Uber Technologies, Inc.
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

package com.uber.nullaway.testdata;

import javax.annotation.Nullable;
import org.jetbrains.annotations.Contract;

public class CheckContractNegativeCases {

  @Contract("_, !null -> !null")
  @Nullable
  Object foo(Object a, @Nullable Object b) {
    if (a.hashCode() % 2 == 0) {
      return b;
    }
    return new Object();
  }

  @Contract("_, !null -> !null")
  @Nullable
  Object fooTwo(Object a, @Nullable Object b) {
    if (b != null) {
      return b;
    }
    return new Object();
  }

  @Nullable Object value = null;

  @Contract("_ -> !null")
  public @Nullable Object orElse(Object other) {
    // 'other' is assumed to NONNULL using the information from method signature
    return value != null ? value : other;
  }
}
