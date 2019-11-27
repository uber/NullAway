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

import com.google.common.collect.ImmutableList;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
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

  private static boolean perhaps() {
    return Math.random() > 0.5;
  }

  private Stream<Integer> filterThenMap(Stream<String> stream) {
    return stream
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

  private Stream<Integer> filterWithNEExpressionThenMapNullableContainer(
      Stream<NullableContainer<String>> stream) {
    return stream
        .filter(
            new Predicate<NullableContainer<String>>() {
              @Override
              public boolean test(NullableContainer<String> container) {
                return container.get() != null;
              }
            })
        .map(
            new Function<NullableContainer<String>, Integer>() {
              @Override
              public Integer apply(NullableContainer<String> container) {
                return container.get().length();
              }
            });
  }

  private Stream<Integer> filterWithAndExpressionThenMapNullableContainer(
      Stream<NullableContainer<NullableContainer<String>>> stream) {
    return stream
        .filter(
            new Predicate<NullableContainer<NullableContainer<String>>>() {
              @Override
              public boolean test(NullableContainer<NullableContainer<String>> container) {
                return container.get() != null && container.get().get() != null;
              }
            })
        .map(
            new Function<NullableContainer<NullableContainer<String>>, Integer>() {
              @Override
              public Integer apply(NullableContainer<NullableContainer<String>> container) {
                return container.get().get().length();
              }
            });
  }

  private Stream<Integer> filterThenMapNullableContainerMergesReturns(
      Stream<NullableContainer<String>> stream) {
    return stream
        .filter(
            new Predicate<NullableContainer<String>>() {
              @Override
              public boolean test(NullableContainer<String> container) {
                if (perhaps() && container.get() != null) {
                  return true;
                } else {
                  return (container.get() != null);
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

  private Stream<Integer> filterThenMapNullableContainerWPassthroughMethods(
      Stream<NullableContainer<String>> stream) {
    return stream
        .filter(
            new Predicate<NullableContainer<String>>() {
              @Override
              public boolean test(NullableContainer<String> container) {
                return container.get() != null;
              }
            })
        .distinct()
        .flatMap(
            new Function<NullableContainer<String>, Stream<Integer>>() {
              @Override
              public Stream<Integer> apply(NullableContainer<String> container) {
                return ImmutableList.of(container.get().length(), container.get().length())
                    .stream();
              }
            });
  }

  private static class NoOpFilterClass<T> implements Predicate<T> {
    public NoOpFilterClass() {}

    public boolean test(T o) {
      return true;
    }
  }

  private Stream<Integer> filterThenMapDoesntBreakWithNonAnnonClass(Stream<String> observable) {
    return observable
        .filter(new NoOpFilterClass<String>())
        .map(
            new Function<String, Integer>() {
              @Override
              public Integer apply(String s) {
                // No new nullability facts, this test is only to ensure our handler doesn't
                // break the checker when using Streams with non-annonymous functions.
                return s.length();
              }
            });
  }

  private Stream<Integer> filterThenMapLambdas(Stream<String> observable) {
    return observable.filter(s -> s != null).map(s -> s.length());
  }

  private Stream<Integer> filterThenMapNullableContainerLambdas(
      Stream<NullableContainer<String>> observable) {
    return observable.filter(c -> c.get() != null).map(c -> c.get().length());
  }

  private Stream<Integer> filterThenMapNullableContainerLambdas2(
      Stream<NullableContainer<String>> observable) {
    return observable
        .filter(
            c -> {
              if (c.get() == null) {
                return false;
              } else {
                return true;
              }
            })
        .map(c -> c.get().length());
  }

  private Stream<Integer> filterThenMapNullableContainerLambdas3(
      Stream<NullableContainer<String>> observable) {
    return observable
        .filter(c -> c.get() != null)
        .map(
            c -> {
              String s = c.get();
              return s.length();
            });
  }

  private Stream<Integer> filterThenMapLambdas4(Stream<String> observable) {
    return observable.filter(s -> s != null && perhaps()).map(s -> s.length());
  }

  private static <T> boolean predtest(Predicate<T> f, T val) {
    try {
      return f.test(val);
    } catch (Exception e) {
      return false;
    }
  }

  private static <T, R> R funcapply(Function<T, R> f, T val) {
    return f.apply(val);
  }

  private Stream<Integer> filterThenMapLambdas5(Stream<String> observable) {
    return observable
        .filter(s -> predtest(r -> r != null, s))
        .map(s -> funcapply(r -> r.length(), s));
  }

  private Stream<Integer> filterThenMapMethodRefs1(Stream<NullableContainer<String>> observable) {
    return observable
        .filter(c -> c.get() != null && perhaps())
        .map(NullableContainer::get)
        .map(String::length);
  }

  private IntStream filterThenMapToInt(Stream<NullableContainer<String>> stream) {
    return stream.filter(c -> c.get() != null).mapToInt(c -> c.get().length());
  }

  private LongStream filterThenMapToLong(Stream<NullableContainer<String>> stream) {
    return stream.filter(c -> c.get() != null).mapToLong(c -> c.get().length());
  }

  private DoubleStream filterThenMapToDouble(Stream<NullableContainer<String>> stream) {
    return stream.filter(c -> c.get() != null).mapToDouble(c -> c.get().length());
  }

  private void filterThenForEach(Stream<NullableContainer<String>> stream) {
    stream.filter(s -> s.get() != null).forEach(s -> System.out.println(s.get().length()));
  }

  private void filterThenForEachOrdered(Stream<NullableContainer<String>> stream) {
    stream.filter(s -> s.get() != null).forEachOrdered(s -> System.out.println(s.get().length()));
  }
}
