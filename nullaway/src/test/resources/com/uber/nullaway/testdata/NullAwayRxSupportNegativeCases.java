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

import com.google.common.collect.ImmutableList;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.Single;
import io.reactivex.functions.BiPredicate;
import io.reactivex.functions.Function;
import io.reactivex.functions.Predicate;
import javax.annotation.Nullable;

public class NullAwayRxSupportNegativeCases {

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

  private Observable<Integer> filterThenMap(Observable<String> observable) {
    return observable
        .filter(
            new Predicate<String>() {
              @Override
              public boolean test(String s) throws Exception {
                return s != null;
              }
            })
        .map(
            new Function<String, Integer>() {
              @Override
              public Integer apply(String s) throws Exception {
                return s.length();
              }
            });
  }

  private Observable<Integer> filterWithIfThenMapNullableContainer(
      Observable<NullableContainer<String>> observable) {
    return observable
        .filter(
            new Predicate<NullableContainer<String>>() {
              @Override
              public boolean test(NullableContainer<String> container) throws Exception {
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
              public Integer apply(NullableContainer<String> c) throws Exception {
                return c.get().length();
              }
            });
  }

  private Observable<Integer> filterWithNEExpressionThenMapNullableContainer(
      Observable<NullableContainer<String>> observable) {
    return observable
        .filter(
            new Predicate<NullableContainer<String>>() {
              @Override
              public boolean test(NullableContainer<String> container) throws Exception {
                return container.get() != null;
              }
            })
        .map(
            new Function<NullableContainer<String>, Integer>() {
              @Override
              public Integer apply(NullableContainer<String> container) throws Exception {
                return container.get().length();
              }
            });
  }

  private Observable<Integer> filterWithAndExpressionThenMapNullableContainer(
      Observable<NullableContainer<NullableContainer<String>>> observable) {
    return observable
        .filter(
            new Predicate<NullableContainer<NullableContainer<String>>>() {
              @Override
              public boolean test(NullableContainer<NullableContainer<String>> container)
                  throws Exception {
                return container.get() != null && container.get().get() != null;
              }
            })
        .map(
            new Function<NullableContainer<NullableContainer<String>>, Integer>() {
              @Override
              public Integer apply(NullableContainer<NullableContainer<String>> container)
                  throws Exception {
                return container.get().get().length();
              }
            });
  }

  private Observable<Integer> filterThenMapNullableContainerMergesReturns(
      Observable<NullableContainer<String>> observable) {
    return observable
        .filter(
            new Predicate<NullableContainer<String>>() {
              @Override
              public boolean test(NullableContainer<String> container) throws Exception {
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
              public Integer apply(NullableContainer<String> c) throws Exception {
                return c.get().length();
              }
            });
  }

  private Observable<Integer> filterThenMapNullableContainerWPassthroughMethods(
      Observable<NullableContainer<String>> observable) {
    return observable
        .filter(
            new Predicate<NullableContainer<String>>() {
              @Override
              public boolean test(NullableContainer<String> container) throws Exception {
                return container.get() != null;
              }
            })
        .distinctUntilChanged()
        .distinct()
        .flatMap(
            new Function<NullableContainer<String>, ObservableSource<Integer>>() {
              @Override
              public ObservableSource<Integer> apply(NullableContainer<String> container)
                  throws Exception {
                return io.reactivex.Observable.fromIterable(
                    ImmutableList.of(container.get().length(), container.get().length()));
              }
            });
  }

  private Observable<NullableContainer<String>> filterThenDistinctUntilChanged(
      Observable<NullableContainer<String>> observable) {
    return observable
        .filter(
            new Predicate<NullableContainer<String>>() {
              @Override
              public boolean test(NullableContainer<String> container) throws Exception {
                return container.get() != null;
              }
            })
        .distinctUntilChanged(
            new BiPredicate<NullableContainer<String>, NullableContainer<String>>() {
              @Override
              public boolean test(NullableContainer<String> nc1, NullableContainer<String> nc2) {
                return nc1.get().length() == nc2.get().length()
                    && nc1.get().contains(nc2.get())
                    && nc2.get().contains(nc1.get());
              }
            });
  }

  private static class NoOpFilterClass<T> implements Predicate<T> {
    public NoOpFilterClass() {}

    public boolean test(T o) throws Exception {
      return true;
    }
  }

  private Observable<Integer> filterThenMapDoesntBreakWithNonAnnonClass(
      Observable<String> observable) {
    return observable
        .filter(new NoOpFilterClass<String>())
        .map(
            new Function<String, Integer>() {
              @Override
              public Integer apply(String s) throws Exception {
                // No new nullability facts, this test is only to ensure our handler doesn't
                // break the checker when using Observables with non-annonymous functions.
                return s.length();
              }
            });
  }

  private Maybe<Integer> testMaybe(Maybe<NullableContainer<String>> maybe) {
    return maybe
        .filter(
            new Predicate<NullableContainer<String>>() {
              @Override
              public boolean test(NullableContainer<String> container) throws Exception {
                return container.get() != null;
              }
            })
        .map(
            new Function<NullableContainer<String>, Integer>() {
              @Override
              public Integer apply(NullableContainer<String> c) throws Exception {
                return c.get().length();
              }
            });
  }

  private Maybe<Integer> testSingle(Single<NullableContainer<String>> single) {
    return single
        .filter(
            new Predicate<NullableContainer<String>>() {
              @Override
              public boolean test(NullableContainer<String> container) throws Exception {
                return container.get() != null;
              }
            })
        .map(
            new Function<NullableContainer<String>, Integer>() {
              @Override
              public Integer apply(NullableContainer<String> c) throws Exception {
                return c.get().length();
              }
            });
  }

  private Observable<Integer> filterThenMapLambdas(Observable<String> observable) {
    return observable.filter(s -> s != null).map(s -> s.length());
  }

  private Observable<Integer> filterThenMapNullableContainerLambdas(
      Observable<NullableContainer<String>> observable) {
    return observable.filter(c -> c.get() != null).map(c -> c.get().length());
  }

  private Observable<Integer> filterThenMapNullableContainerLambdas2(
      Observable<NullableContainer<String>> observable) {
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

  private Observable<Integer> filterThenMapNullableContainerLambdas3(
      Observable<NullableContainer<String>> observable) {
    return observable
        .filter(c -> c.get() != null)
        .map(
            c -> {
              String s = c.get();
              return s.length();
            });
  }

  private Observable<Integer> filterThenMapLambdas4(Observable<String> observable) {
    return observable.filter(s -> s != null && perhaps()).map(s -> s.length());
  }

  private Observable<Integer> filterThenDoOnNextThenMapLambdas(Observable<String> observable) {
    return observable
        .filter(s -> s != null && perhaps())
        .doOnNext(
            s -> {
              if (s.length() == 0) {
                throw new Error();
              } else {
                return;
              }
            })
        .map(s -> s.length());
  }

  private Observable<Integer> filterThenDoOnNextThenMapLambdas2(
      Observable<NullableContainer<String>> observable) {
    return observable
        .filter(c -> c.get() != null && perhaps())
        .doOnNext(
            c -> {
              String s = c.get();
              if (s.length() == 0) {
                throw new Error();
              } else {
                return;
              }
            })
        .map(
            c -> {
              String s = c.get();
              return s.length();
            });
  }

  private static <T> boolean predtest(Predicate<T> f, T val) {
    try {
      return f.test(val);
    } catch (Exception e) {
      return false;
    }
  }

  private static <T, R> R funcapply(Function<T, R> f, T val) throws Exception {
    return f.apply(val);
  }

  private Observable<Integer> filterThenMapLambdas5(Observable<String> observable) {
    return observable
        .filter(s -> predtest(r -> r != null, s))
        .map(s -> funcapply(r -> r.length(), s));
  }

  private Observable<Integer> filterThenMapMethodRefs1(
      Observable<NullableContainer<String>> observable) {
    return observable
        .filter(c -> c.get() != null && perhaps())
        .map(NullableContainer::get)
        .map(String::length);
  }
}
