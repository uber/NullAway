package com.example.jspecify.unannotatedpackage;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.NullUnmarked;

public class Methods {
  @NullMarked
  public static void foo(Object o) {}

  public static void unchecked(Object o) {}

  public static class ExtendMe {
    @NullMarked
    public Object foo(Object o) {
      return o;
    }

    public Object unchecked(Object o) {
      return null;
    }
  }

  @NullMarked
  public static class Marked {
    public static void foo(Object o) {}

    @NullUnmarked
    public static void unchecked(Object o) {}
  }
}
