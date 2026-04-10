package com.uber.nullaway.handlers;

import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.Nullness;
import org.jspecify.annotations.Nullable;

/** Mutable nullness information for a method invocation's formal parameters and varargs array. */
public final class InvocationArgumentNullness {

  private final @Nullable Nullness[] parameterNullness;
  private final boolean hasVarargsArrayNullness;
  private @Nullable Nullness varargsArrayNullness;

  private InvocationArgumentNullness(int parameterCount, boolean hasVarargsArrayNullness) {
    this.parameterNullness = new Nullness[parameterCount];
    this.hasVarargsArrayNullness = hasVarargsArrayNullness;
  }

  public static InvocationArgumentNullness create(Symbol.MethodSymbol methodSymbol) {
    return new InvocationArgumentNullness(
        methodSymbol.getParameters().size(), methodSymbol.isVarArgs());
  }

  public int parameterCount() {
    return parameterNullness.length;
  }

  public boolean hasVarargsArrayNullness() {
    return hasVarargsArrayNullness;
  }

  public @Nullable Nullness getParameterNullness(int index) {
    return parameterNullness[index];
  }

  public void setParameterNullness(int index, @Nullable Nullness nullness) {
    parameterNullness[index] = nullness;
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
