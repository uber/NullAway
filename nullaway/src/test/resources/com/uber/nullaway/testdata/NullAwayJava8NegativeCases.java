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

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;
import javax.annotation.Nullable;

public class NullAwayJava8NegativeCases {

  @FunctionalInterface
  interface RetNullableFunction {

    @Nullable
    Object getVal();
  }

  public static void testLambda() {
    RetNullableFunction p =
        () -> {
          return null;
        };
    p.getVal();
  }

  @FunctionalInterface
  interface NonNullParamFunction {

    String takeVal(Object x);
  }

  @FunctionalInterface
  interface NullableParamFunction<T, U> {

    U takeVal(@Nullable T x);
  }

  static void testNonNullParam() {
    NonNullParamFunction n = (x) -> x.toString();
    NonNullParamFunction n2 = (@Nullable Object x) -> (x == null) ? "null" : x.toString();
    NullableParamFunction n3 = (@Nullable Object x) -> (x == null) ? "null" : x.toString();
    NullableParamFunction n4 = (x) -> (x == null) ? "null" : x.toString();
  }

  static void testBuiltIn() {
    java.util.function.Function<String, String> foo = (x) -> x.toString();
    BiFunction<String, Object, String> bar = (x, y) -> x.toString() + y.toString();
    java.util.function.Function<String, Object> foo2 = (x) -> null; // java.util.Function is
    // unnanotated
    java.util.function.Function<String, Object> foo3 =
        (x) -> {
          return null;
        };
  }

  static class Size {
    public Size(int h, int w) {
      this.height = h;
      this.width = w;
    }

    public final int height;
    public final int width;
  }

  static void testSort(List<Integer> intList, List<Size> sizeList) {
    Collections.sort(
        intList,
        (a, b) -> {
          return (b - a);
        });
    Collections.sort(
        intList,
        (a, b) -> {
          return a;
        });
    Collections.sort(
        sizeList,
        (a, b) -> {
          int aPixels = a.height * a.width;
          int bPixels = b.height * b.width;
          if (bPixels < aPixels) {
            return -1;
          }
          if (bPixels > aPixels) {
            return 1;
          }
          return 0;
        });
  }

  static Comparator<Integer> testLambdaExpressionsAreNotNull() {
    return (a, b) -> {
      return (b - a);
    };
  }

  @FunctionalInterface
  interface VoidFunction {

    void doSomething();
  }

  static void wrapDoSomething() {
    RetNullableFunction r = () -> null;
    VoidFunction v = () -> r.getVal();
    v.doSomething();
  }

  ////////////////////////
  // method references  //
  ////////////////////////

  interface MyFunction<T, R> {
    R apply(T t);
  }

  static <R, T> R map(T t, MyFunction<T, R> fun) {
    return fun.apply(t);
  }

  static <T, U> U applyTakeVal(NullableParamFunction<T, U> nn) {
    return nn.takeVal(null);
  }

  static Object returnNonNull(String t) {
    return new Object();
  }

  static String derefParam(@Nullable Object o) {
    return o != null ? o.toString() : "";
  }

  static void testRefsToStaticMethods() {
    String ex = "hi";
    map(ex, NullAwayJava8NegativeCases::returnNonNull);
    applyTakeVal(NullAwayJava8NegativeCases::derefParam);
  }

  @FunctionalInterface
  interface NullableSecondParamFunction<T> {

    String takeVal(T x, @Nullable Object y);
  }

  static <T> String applyDoubleTakeVal(NullableSecondParamFunction<T> ns, T firstParam) {
    return ns.takeVal(firstParam, null);
  }

  static class MethodContainer {

    Object returnNonNull(String t) {
      return new Object();
    }

    String returnNonNullWithNullableParam(@Nullable Object t) {
      return "";
    }

    String derefSecondParam(Object w, @Nullable Object z) {
      return z != null ? z.toString() : w.toString();
    }

    String derefSecondParam2(Object w, Object z) {
      return z.toString();
    }

    String derefParam(@Nullable Object p) {
      return (p != null) ? p.toString() : "";
    }

    String makeStr() {
      return "buzz";
    }

    void testRefsToInstanceMethods() {
      String ex = "bye";
      MethodContainer m = new MethodContainer();
      map(ex, m::returnNonNull);
      applyDoubleTakeVal(m::derefSecondParam, new Object());
      applyDoubleTakeVal(MethodContainer::derefParam, m);
      applyDoubleTakeVal(MethodContainer::returnNonNullWithNullableParam, m);
      map(this, MethodContainer::makeStr);
    }
  }

  static class MethodContainerSub extends MethodContainer {
    @Override
    String derefSecondParam2(Object w, @Nullable Object z) {
      return "" + ((z != null) ? z.hashCode() : 10);
    }

    void testOverrideWithMethodRef() {
      applyDoubleTakeVal(this::derefSecondParam2, new Object());
    }
  }

  static class ConstructorRefs {

    public ConstructorRefs(@Nullable Object p) {}

    class Inner {

      public Inner(@Nullable Object q) {}
    }

    void testConstRefs() {
      applyTakeVal(ConstructorRefs::new);
      applyTakeVal(Inner::new);
    }
  }
}
