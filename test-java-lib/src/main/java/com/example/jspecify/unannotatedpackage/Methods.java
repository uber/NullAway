package com.example.jspecify.unannotatedpackage;

// Needed for annotating methods, should be removed and replaced with the standard
// JSpecify @NullMarked annotation once v0.3.0 is out.
import com.example.jspecify.future.annotations.NullMarked;

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
}
