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
import java.util.function.Function;
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
  interface NullableParamFunction {

    String takeVal(@Nullable Object x);
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
    Function<String, Object> foo2 = (x) -> null; // java.util.Function is unnanotated
    Function<String, Object> foo3 =
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
}
