package com.uber.lib.unannotated;

import java.util.List;
import java.util.function.Supplier;

/* @NullMarked */
public final class PolyNullMethods {

  private PolyNullMethods() {}

  /** Returns the first element available from either list. */
  public static Object first(List<Object> first, List<Object> second) {
    return first.isEmpty() ? second.get(0) : first.get(0);
  }

  /** Accepts two independently typed arguments. */
  public static <T, U> void twoTypeVariables(T first, U second) {}

  /** Returns the first of two independently typed arguments. */
  public static <T, U> T genericFirst(T first, U second) {
    return first;
  }

  /** Returns a value from the first of two independently typed suppliers. */
  public static <T, U> T genericFromSuppliers(
      Supplier<? extends T> first, Supplier<? extends U> second) {
    return first.get();
  }
}
