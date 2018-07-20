package com.uber.lib.unannotated;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

public class RestrictivelyAnnotatedClass {

  public static @Nullable Object returnsNull() {
    return null;
  }

  public static void consumesObjectNonNull(@NonNull Object o) {}

  public static void consumesObjectUnannotated(Object o) {}
}
