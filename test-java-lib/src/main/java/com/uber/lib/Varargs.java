package com.uber.lib;

import javax.annotation.Nullable;

public class Varargs {

  public Varargs(@Nullable String... args) {}

  public static void fullyQualified(String @org.jspecify.annotations.Nullable ... args) {}
}
