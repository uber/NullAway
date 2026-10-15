package com.uber.lib.unannotated;

/* @NullMarked */
public interface ProviderNullMarkedViaModel<T /* extends @Nullable Object */> {
  T get();

  static <U /* extends @Nullable Object */> ProviderNullMarkedViaModel<U> of(U value) {
    return () -> value;
  }

  static <A, B extends A /* B extends @Nullable A */> ProviderNullMarkedViaModel<A> upcast(
      B value) {
    return () -> value;
  }
}
