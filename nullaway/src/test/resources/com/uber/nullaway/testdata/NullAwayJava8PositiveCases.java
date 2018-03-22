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

import io.reactivex.functions.BiFunction;
import io.reactivex.functions.Function;
import javax.annotation.Nullable;

public class NullAwayJava8PositiveCases {

  @FunctionalInterface
  interface RetNonNullFunction {

    Object getVal();
  }

  public static void testRetNonNull() {
    RetNonNullFunction p =
        () -> {
          // BUG: Diagnostic contains: returning @Nullable expression from method with
          return null;
        };
    p.getVal();
  }

  @FunctionalInterface
  interface NullableParamFunction {

    String takeVal(@Nullable Object x);
  }

  @FunctionalInterface
  interface NonNullParamFunction {

    String takeVal(Object x);
  }

  static void testNullableParam() {
    // BUG: Diagnostic contains: dereferenced expression x is @Nullable
    NullableParamFunction n = (x) -> x.toString();
    // BUG: Diagnostic contains: parameter x is @NonNull, but parameter in functional interface
    NullableParamFunction n2 = (Object x) -> x.toString();
    // BUG: Diagnostic contains: dereferenced expression x is @Nullable
    NonNullParamFunction n3 = (@Nullable Object x) -> x.toString();
  }

  static void testAnnoatedThirdParty() {
    // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return
    Function<String, Object> f1 = (x) -> null; // io.reactivex.(Bi)Function is anotated
    Function<String, Object> f2 =
        (x) -> {
          // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull
          return null;
        };
    // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return
    BiFunction<String, String, Object> f3 = (x, y) -> null;
  }

  ////////////////////////
  // method references  //
  ////////////////////////

  interface Function<T, R> {
    R apply(T t);
  }

  static <R, T> R map(T t, Function<T, R> fun) {
    return fun.apply(t);
  }

  static String applyTakeVal(Object o, NullableParamFunction nn) {
    return nn.takeVal(null);
  }

  @Nullable
  static Object returnNull(String t) {
    return null;
  }

  static String derefParam(Object o) {
    return o.toString();
  }

  static void testRefsToStaticMethods() {
    String ex = "hi";
    // BUG: Diagnostic contains: referenced method returns @Nullable, but functional
    map(ex, NullAwayJava8PositiveCases::returnNull);
    // BUG: Diagnostic contains: parameter o of referenced method is @NonNull, but
    applyTakeVal(ex, NullAwayJava8PositiveCases::derefParam);
  }

  @FunctionalInterface
  interface NullableSecondParamFunction<T> {

    String takeVal(T x, @Nullable Object y);
  }

  static <T> String applyDoubleTakeVal(NullableSecondParamFunction<T> ns, T firstParam) {
    return ns.takeVal(firstParam, null);
  }

  static class MethodContainer {

    @Nullable
    Object returnNull(String t) {
      return null;
    }

    @Nullable
    String returnNullWithNullableParam(@Nullable Object t) {
      return null;
    }

    String derefSecondParam(Object w, Object z) {
      return z.toString();
    }

    String derefParam(Object p) {
      return p.toString();
    }

    void testRefsToInstanceMethods() {
      String ex = "bye";
      MethodContainer m = new MethodContainer();
      // BUG: Diagnostic contains: referenced method returns @Nullable, but functional
      map(ex, m::returnNull);
      // BUG: Diagnostic contains: parameter z of referenced method is @NonNull, but
      applyDoubleTakeVal(m::derefSecondParam, new Object());
      // BUG: Diagnostic contains: parameter p of referenced method is @NonNull, but parameter in
      applyDoubleTakeVal(MethodContainer::derefParam, m);
      // BUG: Diagnostic contains: referenced method returns @Nullable, but functional interface
      applyDoubleTakeVal(MethodContainer::returnNullWithNullableParam, m);
    }
  }
}
