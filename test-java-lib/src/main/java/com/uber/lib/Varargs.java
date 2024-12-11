package com.uber.lib;

import javax.annotation.Nullable;

public class Varargs {

  public Varargs(@Nullable String... args) {}

  public static void typeUse(String @org.jspecify.annotations.Nullable ... args) {}

  public static void typeUseNullableElementsJSpecify(
      @org.jspecify.annotations.Nullable String... args) {}
}
