package com.uber.lib.unannotated;

import javax.annotation.Nonnull;

public class RestrictivelyAnnotatedVarargs {

  public static void test(@Nonnull String... args) {}
}
