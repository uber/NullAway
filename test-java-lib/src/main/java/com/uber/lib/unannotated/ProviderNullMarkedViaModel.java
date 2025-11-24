package com.uber.lib.unannotated;

public interface ProviderNullMarkedViaModel<T> {
  T get();

  static <U> ProviderNullMarkedViaModel<U> of(U value) {
    return () -> value;
  }
}
