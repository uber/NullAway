package com.uber.lib.unannotated;

/* @NullMarked */
public interface LambdaConsumer<T> {
  void accept(T value);
}
