package com.example.jspecify.unannotatedpackage;

import org.jspecify.annotations.NullMarked;

public class Outer {
  @NullMarked
  public static class Inner {
    public static String foo(String s) {
      return s;
    }
  }

  public static void unchecked(Object o) {}
}
