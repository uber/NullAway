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

import io.reactivex.Observable;
import io.reactivex.functions.Function;
import io.reactivex.functions.Predicate;
import javax.annotation.Nullable;

public class NullAwayRxSupportPositiveCases {

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

  private Observable<Integer> filterWithIfThenMapNullableContainerNullableOnSomeBranch(
      Observable<NullableContainer<String>> observable) {
    return observable
        .filter(
            new Predicate<NullableContainer<String>>() {
              @Override
              public boolean test(NullableContainer<String> container) throws Exception {
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
              public Integer apply(NullableContainer<String> c) throws Exception {
                // BUG: Diagnostic contains: dereferenced expression
                return c.get().length();
              }
            });
  }

  private Observable<Integer> filterWithIfThenMapNullableContainerNullableOnSomeBranchAnyOrder(
      Observable<NullableContainer<String>> observable) {
    return observable
        .filter(
            new Predicate<NullableContainer<String>>() {
              @Override
              public boolean test(NullableContainer<String> container) throws Exception {
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
              public Integer apply(NullableContainer<String> c1) throws Exception {
                // BUG: Diagnostic contains: dereferenced expression
                return c1.get().length();
              }
            });
  }

  private Observable<Integer> filterWithOrExpressionThenMapNullableContainer(
      Observable<NullableContainer<String>> observable) {
    return observable
        .filter(
            new Predicate<NullableContainer<String>>() {
              @Override
              public boolean test(NullableContainer<String> container) throws Exception {
                return container.get() != null || perhaps();
              }
            })
        .map(
            new Function<NullableContainer<String>, Integer>() {
              @Override
              public Integer apply(NullableContainer<String> container) throws Exception {
                // BUG: Diagnostic contains: dereferenced expression
                return container.get().length();
              }
            });
  }

  private Observable<Integer> filterWithLambdaNullExpressionBody(Observable<String> observable) {
    // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return
    // type
    return observable.map(o -> perhaps() ? o : null).map(o -> o.length());
  }

  private Observable<Integer> filterThenMapNullableContainerLambdas(
      Observable<NullableContainer<String>> observable) {
    // BUG: Diagnostic contains: dereferenced expression
    return observable.filter(c -> c.get() != null || perhaps()).map(c -> c.get().length());
  }

  private Observable<Integer> filterThenMapMethodRefs1(
      Observable<NullableContainer<String>> observable) {
    // this is to make sure the analysis doesn't get confused by two instances of the same method
    // ref
    Object o =
        observable
            .filter(c -> c.get() != null && perhaps())
            .map(NullableContainer::get)
            .map(String::length);
    return observable
        .filter(c -> c.get() != null || perhaps())
        // BUG: Diagnostic contains: referenced method returns @Nullable
        .map(NullableContainer::get)
        .map(String::length);
  }
}
