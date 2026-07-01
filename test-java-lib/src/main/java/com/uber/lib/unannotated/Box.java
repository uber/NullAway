package com.uber.lib.unannotated;

/* @NullMarked */
public class Box<T> {

  public /* @Nullable */ T orElse(/* @Nullable */ T other) {
    return other;
  }
}
