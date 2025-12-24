package com.uber.lib.unannotated;

public class NestedAnnots<T> {
  public static <T> NestedAnnots<T> genericMethod(Class<T> clazz) {
    return new NestedAnnots<>();
  }
}
