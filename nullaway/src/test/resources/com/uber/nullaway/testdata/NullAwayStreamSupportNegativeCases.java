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

package com.uber.nullaway.testdata;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public class NullAwayStreamSupportNegativeCases {

  static class NullableContainer<T> {
    @Nullable private T ref;

    public NullableContainer() {
      ref = null;
    }

    @Nullable
    public T get() {
      return ref;
    }

    public void set(T o) {
      ref = o;
    }
  }

  private Stream<Integer> filterThenMap(Stream<String> observable) {
    return observable
        .filter(
            new Predicate<String>() {
              @Override
              public boolean test(String s) {
                return s != null;
              }
            })
        .map(
            new Function<String, Integer>() {
              @Override
              public Integer apply(String s) {
                return s.length();
              }
            });
  }

  private Stream<Integer> filterWithIfThenMapNullableContainer(
      Stream<NullableContainer<String>> stream) {
    return stream
        .filter(
            new Predicate<NullableContainer<String>>() {
              @Override
              public boolean test(NullableContainer<String> container) {
                if (container.get() != null) {
                  return true;
                } else {
                  return false;
                }
              }
            })
        .map(
            new Function<NullableContainer<String>, Integer>() {
              @Override
              public Integer apply(NullableContainer<String> c) {
                return c.get().length();
              }
            });
  }
}
