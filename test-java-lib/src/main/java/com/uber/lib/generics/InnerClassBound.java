package com.uber.lib.generics;

import java.io.Serializable;
import org.jspecify.annotations.Nullable;

/** Holder for the inner class {@link Inner} that the type parameters below are bounded by. */
public class InnerClassBound {

  // Deliberately an inner class: only an inner class puts INNER_TYPE on the bound's type path.
  @SuppressWarnings("ClassCanBeStatic")
  public class Inner implements Serializable {}

  /**
   * A type parameter bounded by an inner class, used to test bounds read from bytecode. The
   * annotation applies to the inner class itself, so {@code T} includes {@code null}.
   */
  public static class Box<T extends InnerClassBound.@Nullable Inner> {}

  /**
   * A type parameter whose annotation qualifies the enclosing type rather than the bound, so {@code
   * T} excludes {@code null}.
   */
  @SuppressWarnings({"NullableOnContainingClass", "NullAway"}) // the misplacement is the fixture
  public static class OuterAnnotatedBox<T extends @Nullable InnerClassBound.Inner> {}

  /**
   * The intersection form of {@link OuterAnnotatedBox}. Both bounds are annotated, but only the
   * annotation on {@code Serializable} reaches the bound it is written on, so {@code T} excludes
   * {@code null}.
   */
  @SuppressWarnings({"NullableOnContainingClass", "NullAway"}) // the misplacement is the fixture
  public static class OuterAnnotatedIntersectionBox<
      T extends @Nullable InnerClassBound.Inner & @Nullable Serializable> {}
}
