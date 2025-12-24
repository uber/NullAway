package com.uber.lib.unannotated;

/* @NullMarked */
@SuppressWarnings("DoNotCallSuggester")
public class NestedAnnots<T /* extends @Nullable Object */> {
  public static <T /* extends @Nullable Object */> NestedAnnots<T> genericMethod(
      Class</* @NonNull */ T> clazz) {
    return new NestedAnnots<>();
  }

  public static void deeplyNested(NestedAnnots<NestedAnnots</* @Nullable */ String>> t) {}

  public static NestedAnnots<NestedAnnots</* @Nullable */ String>[]> nestedArray1() {
    throw new RuntimeException();
  }
}
