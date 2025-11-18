package com.uber.lib.unannotated;

public interface ProviderNullMarkedViaModel<T> {
  T get();

  static <T> ProviderNullMarkedViaModel<T> of(T value) {
    return () -> value;
  }
}
