package com.uber.lib.unannotated;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/* @NullMarked */
public final class PolyNullMethods {

  private PolyNullMethods() {}

  /** Returns the first element available from either list. */
  public static /* @PolyNull */ Object first(
      List</* @PolyNull */ Object> first, List</* @PolyNull */ Object> second) {
    return first.isEmpty() ? second.get(0) : first.get(0);
  }

  /**
   * Returns the first argument, with nullness linked to the independently typed supplier result.
   */
  public static <T, U> /* @PolyNull */ T genericFirst(
      /* @PolyNull */ T first, Supplier<? extends /* @PolyNull */ U> second) {
    return first;
  }

  /** Returns the first argument as an object, with nullness linked to the supplier result. */
  public static <T, U> /* @PolyNull */ Object genericObject(
      /* @PolyNull */ T first, Supplier<? extends /* @PolyNull */ U> second) {
    return first;
  }

  /** Returns a value from the first of two independently typed suppliers. */
  public static <T, U> /* @PolyNull */ T genericFromSuppliers(
      Supplier<? extends /* @PolyNull */ T> first, Supplier<? extends /* @PolyNull */ U> second) {
    return first.get();
  }

  /** Links the mapper input's nullness to the seed independently of its generic type. */
  public static <T> String withMapper(
      /* @PolyNull */ T seed, Function</* @PolyNull */ Object, String> mapper) {
    return mapper.apply(seed);
  }

  /** Links nullness like {@link #genericObject}, but requires a non-null type argument. */
  public static <T> /* @PolyNull */ Object boundedGenericObject(
      /* @PolyNull */ T seed, Supplier<? extends /* @PolyNull */ Object> other) {
    return seed;
  }

  /** Returns the varargs array, with nullness linked to the supplier result. */
  public static /* @PolyNull */ Object varargsArray(
      Supplier<? extends /* @PolyNull */ Object> nested, Object /* @PolyNull */... values) {
    return values;
  }

  /** Returns the first argument, with nullness linked to the supplier result. */
  public static /* @PolyNull */ Object linkedBeforeVarargs(
      /* @PolyNull */ Object linked,
      Supplier<? extends /* @PolyNull */ Object> nested,
      Object... rest) {
    return linked;
  }

  /** Returns nullable contents, with a PolyNull model that has no input to infer from. */
  public static List</* @PolyNull */ @Nullable Object> outputOnly() {
    return java.util.Collections.singletonList(null);
  }
}
