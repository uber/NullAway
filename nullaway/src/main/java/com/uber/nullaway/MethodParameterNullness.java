package com.uber.nullaway;

import com.sun.tools.javac.code.Symbol;
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
