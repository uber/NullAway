package com.uber.lib;

import org.checkerframework.checker.nullness.qual.Nullable;

@FunctionalInterface
public interface Loader<K, V> {
  @Nullable
  V load(K key) throws Exception;
}
