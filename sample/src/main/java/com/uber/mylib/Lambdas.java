package com.uber.mylib;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.annotation.Nullable;

/** Code that uses Java 8 lambdas */
@SuppressWarnings("UnusedVariable") // This is sample code
public class Lambdas {

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
    Function<String, Object> foo2 = (x) -> null;
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
