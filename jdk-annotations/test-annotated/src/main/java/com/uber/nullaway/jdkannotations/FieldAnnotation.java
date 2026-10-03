package com.uber.nullaway.jdkannotations;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class FieldAnnotation {

  public static @Nullable String nullableStaticField;

  public @Nullable String nullableField;

  public String nonNullField = "";

  public static class Inner {
    public @Nullable Object nullableInnerField;
  }
}
