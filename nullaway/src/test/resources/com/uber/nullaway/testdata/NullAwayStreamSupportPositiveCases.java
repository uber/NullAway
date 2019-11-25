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

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public class NullAwayStreamSupportPositiveCases {

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

  private Stream<Integer> filterWithIfThenMapNullableContainerNullableOnSomeBranch(
      Stream<NullableContainer<String>> stream) {
    return stream
        .filter(
            new Predicate<NullableContainer<String>>() {
              @Override
              public boolean test(NullableContainer<String> container) {
                if (container.get() != null) {
                  return true;
                } else {
                  return perhaps();
                }
              }
            })
        .map(
            new Function<NullableContainer<String>, Integer>() {
              @Override
              public Integer apply(NullableContainer<String> c) {
                // BUG: Diagnostic contains: dereferenced expression
                return c.get().length();
              }
            });
  }

  private static boolean perhaps() {
    return Math.random() > 0.5;
  }

  private Stream<Integer> filterWithIfThenMapNullableContainerNullableOnSomeBranchAnyOrder(
      Stream<NullableContainer<String>> stream) {
    return stream
        .filter(
            new Predicate<NullableContainer<String>>() {
              @Override
              public boolean test(NullableContainer<String> container) {
                if (container.get() == null) {
                  return perhaps();
                } else {
                  return true;
                }
              }
            })
        .map(
            new Function<NullableContainer<String>, Integer>() {
              @Override
              public Integer apply(NullableContainer<String> c1) {
                // BUG: Diagnostic contains: dereferenced expression
                return c1.get().length();
              }
            });
  }

  private Stream<Integer> filterWithOrExpressionThenMapNullableContainer(
      Stream<NullableContainer<String>> stream) {
    return stream
        .filter(
            new Predicate<NullableContainer<String>>() {
              @Override
              public boolean test(NullableContainer<String> container) {
                return container.get() != null || perhaps();
              }
            })
        .map(
            new Function<NullableContainer<String>, Integer>() {
              @Override
              public Integer apply(NullableContainer<String> container) {
                // BUG: Diagnostic contains: dereferenced expression
                return container.get().length();
              }
            });
  }

  private Stream<Integer> filterThenMapNullableContainerLambdas(
      Stream<NullableContainer<String>> stream) {
    // BUG: Diagnostic contains: dereferenced expression
    return stream.filter(c -> c.get() != null || perhaps()).map(c -> c.get().length());
  }

  private IntStream mapToInt(Stream<NullableContainer<String>> stream) {
    // BUG: Diagnostic contains: dereferenced expression
    return stream.mapToInt(c -> c.get().length());
  }

  private LongStream mapToLong(Stream<NullableContainer<String>> stream) {
    // BUG: Diagnostic contains: dereferenced expression
    return stream.mapToLong(c -> c.get().length());
  }

  private DoubleStream mapToDouble(Stream<NullableContainer<String>> stream) {
    // BUG: Diagnostic contains: dereferenced expression
    return stream.mapToDouble(c -> c.get().length());
  }

  private void forEach(Stream<NullableContainer<String>> stream) {
    // BUG: Diagnostic contains: dereferenced expression
    stream.forEach(s -> System.out.println(s.get().length()));
  }

  private void forEachOrdered(Stream<NullableContainer<String>> stream) {
    // BUG: Diagnostic contains: dereferenced expression
    stream.forEachOrdered(s -> System.out.println(s.get().length()));
  }
}
