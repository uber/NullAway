package com.uber.lib.unannotated;

import org.jspecify.annotations.Nullable;

/** Library methods with conditional return-nullness models supplied by the test provider. */
public final class ConditionalReturns {

  /** Creates a non-null object regardless of the argument's nullness. */
  public ConditionalReturns(@Nullable Object value) {}

  /** Returns the first argument when both arguments are non-null, or null otherwise. */
  public static Object both(Object first, Object second) {
    return first == null || second == null ? null : first;
  }

  /** Returns the varargs array, preserving its top-level nullness. */
  public static Object varargs(Object... values) {
    return values;
  }
}
