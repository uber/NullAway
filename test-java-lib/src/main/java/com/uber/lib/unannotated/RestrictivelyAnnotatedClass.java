package com.uber.lib.unannotated;

import javax.validation.constraints.NotNull;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

public class RestrictivelyAnnotatedClass {

  public final Object field;

  public RestrictivelyAnnotatedClass(Object field) {
    this.field = field;
  }

  public @Nullable Object getField() {
    return field;
  }

  public static @Nullable Object returnsNull() {
    return null;
  }

  public static void consumesObjectNonNull(@NonNull Object o) {}

  public static void consumesObjectNotNull(@NotNull Object o) {}

  public static void consumesObjectUnannotated(Object o) {}

  public void acceptsNonNull(@NonNull Object o) {}

  public void acceptsNonNull2(@NonNull Object o) {}

  public void acceptsNullable(@Nullable Object o) {}

  public void acceptsNullable2(@Nullable Object o) {}

  public @NonNull Object returnsNonNull() {
    return new Object();
  }

  public @NonNull Object returnsNonNull2() {
    return new Object();
  }

  public @Nullable Object returnsNullable() {
    return null;
  }

  public @Nullable Object returnsNullable2() {
    return null;
  }
}
