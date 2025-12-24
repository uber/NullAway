package com.uber.lib.unannotated;

/* @NullMarked */
public class NestedAnnots<T /* extends @Nullable Object */> {
  public static <T /* extends @Nullable Object */> NestedAnnots<T> genericMethod(
      Class</* @NonNull */ T> clazz) {
    return new NestedAnnots<>();
  }

  public static void deeplyNested(NestedAnnots<NestedAnnots</* @Nullable */ String>> t) {}
}
