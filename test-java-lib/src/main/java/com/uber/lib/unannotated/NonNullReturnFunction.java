package com.uber.lib.unannotated;

/** Unannotated functional interface whose {@code apply} return is modeled as non-null. */
public interface NonNullReturnFunction<F, T> {

  T apply(F input);
}
