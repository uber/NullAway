package com.uber.lib;

import org.checkerframework.checker.nullness.qual.Nullable;

public interface Loader<K, V> {
  @Nullable
  V load(K key);

  void doSomething(@Nullable Object o);
}
