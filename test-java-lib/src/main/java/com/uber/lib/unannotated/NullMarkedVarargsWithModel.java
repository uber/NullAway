package com.uber.lib.unannotated;

/* @NullMarked */
public class NullMarkedVarargsWithModel {

  public static void nullableContents(/* @Nullable */ String... args) {}

  public static void nullableArray(String /* @Nullable */[] args) {}

  public static void bothNullable(/* @Nullable */ String /* @Nullable */... args) {}
}
