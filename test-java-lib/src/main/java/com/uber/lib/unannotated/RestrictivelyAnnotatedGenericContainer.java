package com.uber.lib.unannotated;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

public class RestrictivelyAnnotatedGenericContainer<T> {

  public @Nullable T field;

  public RestrictivelyAnnotatedGenericContainer() {}

  public void setField(@NonNull T field) {
    this.field = field;
  }

  public @Nullable T getField() {
    return field;
  }
}
