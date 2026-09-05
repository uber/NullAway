package com.uber.lib.unannotated;

import java.util.List;
import java.util.function.Supplier;

/* @NullMarked */
public final class PolyNullMethods {

  private PolyNullMethods() {}

  /** Returns the first element available from either list. */
  public static /* @PolyNull */ Object first(
      List</* @PolyNull */ Object> first, List</* @PolyNull */ Object> second) {
    return first.isEmpty() ? second.get(0) : first.get(0);
  }

  /** Accepts two independently typed arguments. */
  public static <T, U> void twoTypeVariables(/* @PolyNull */ T first, /* @PolyNull */ U second) {}

  /** Returns the first of two independently typed arguments. */
  public static <T, U> /* @PolyNull */ T genericFirst(
      /* @PolyNull */ T first, /* @PolyNull */ U second) {
    return first;
  }

  /** Returns the first argument as an object. */
  public static <T, U> /* @PolyNull */ Object genericObject(
      /* @PolyNull */ T first, /* @PolyNull */ U second) {
    return first;
  }

  /** Returns a value from the first of two independently typed suppliers. */
  public static <T, U> /* @PolyNull */ T genericFromSuppliers(
      Supplier<? extends /* @PolyNull */ T> first, Supplier<? extends /* @PolyNull */ U> second) {
    return first.get();
  }
}
