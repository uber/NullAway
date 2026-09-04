package com.uber.lib.unannotated;

import java.util.List;

/* @NullMarked */
public final class PolyNullMethods {

  private PolyNullMethods() {}

  /** Returns the first element available from either list. */
  public static Object first(List<Object> first, List<Object> second) {
    return first.isEmpty() ? second.get(0) : first.get(0);
  }

  /** Accepts two independently typed arguments. */
  public static <T, U> void twoTypeVariables(T first, U second) {}
}
