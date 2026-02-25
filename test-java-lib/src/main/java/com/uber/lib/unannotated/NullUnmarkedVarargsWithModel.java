package com.uber.lib.unannotated;

public class NullUnmarkedVarargsWithModel {

  public static void nonNullContents(/* @NonNull */ String... args) {}

  public static void nonNullArray(String /* @NonNull */[] args) {}

  public static void bothNonNull(/* @NonNull */ String /* @NonNull */... args) {}
}
