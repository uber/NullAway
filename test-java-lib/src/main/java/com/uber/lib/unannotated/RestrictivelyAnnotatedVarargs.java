package com.uber.lib.unannotated;

import javax.annotation.Nonnull;

public class RestrictivelyAnnotatedVarargs {

  public static void test(@Nonnull String... args) {}

  public static void typeUseArrayNonNull(String @org.jspecify.annotations.NonNull ... args) {}

  public static void typeUseEachNonNull(@org.jspecify.annotations.NonNull String... args) {}

  public static void typeUseBothNonNull(
      @org.jspecify.annotations.NonNull String @org.jspecify.annotations.NonNull ... args) {}
}
