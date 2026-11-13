package com.uber.nullaway;

import com.sun.tools.javac.code.Symbol;
import java.util.BitSet;
import org.jspecify.annotations.Nullable;

/**
 * Mutable nullness information for a method's formal parameters and varargs array. For a varargs
 * method, the nullability of individual arguments is represented in the last parameter position,
 * while the nullability of the varargs array itself is represented separately.
 */
public final class MethodParameterNullness {

  private final @Nullable Nullness[] parameterNullness;
  private final boolean hasVarargsArrayNullness;
  private @Nullable Nullness varargsArrayNullness;

  /** Positions whose nullness was set since the last {@link #clearSetPositions()}. */
  private final BitSet setPositions = new BitSet();

  private MethodParameterNullness(int parameterCount, boolean hasVarargsArrayNullness) {
    this.parameterNullness = new Nullness[parameterCount];
    this.hasVarargsArrayNullness = hasVarargsArrayNullness;
  }

  public static MethodParameterNullness create(Symbol.MethodSymbol methodSymbol) {
    return new MethodParameterNullness(
        methodSymbol.getParameters().size(), methodSymbol.isVarArgs());
  }

  public @Nullable Nullness getParameterNullness(int index) {
    return parameterNullness[index];
  }

  public void setParameterNullness(int index, @Nullable Nullness nullness) {
    parameterNullness[index] = nullness;
    setPositions.set(index);
  }

  /**
   * Returns whether the nullness of the parameter at {@code index} was set since the last {@link
   * #clearSetPositions()}, or since creation if that was never called. NullAway clears the record
   * once the base analysis has filled in the parameters, so a set position is one where a handler
   * decided the nullness.
   */
  boolean wasSet(int index) {
    return setPositions.get(index);
  }

  /** Forgets which parameter positions have been set, for {@link #wasSet(int)}. */
  void clearSetPositions() {
    setPositions.clear();
  }

  public @Nullable Nullness getVarargsArrayNullness() {
    checkHasVarargsArrayNullness();
    return varargsArrayNullness;
  }

  public void setVarargsArrayNullness(@Nullable Nullness nullness) {
    checkHasVarargsArrayNullness();
    varargsArrayNullness = nullness;
  }

  private void checkHasVarargsArrayNullness() {
    if (!hasVarargsArrayNullness) {
      throw new IllegalStateException("No varargs array nullness slot for non-varargs method");
    }
  }
}
