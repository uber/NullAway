package com.uber.lib.generics;

import org.jspecify.annotations.Nullable;

public class GenericConstructor {

  public <U extends @Nullable Object> GenericConstructor(NullableTypeParam<? extends U> b) {}

  public <U extends @Nullable CharSequence> GenericConstructor(
      NullableTypeParam<? extends U> b, int interfaceBound) {}

  public <U> GenericConstructor(NullableTypeParam<? extends U> b, boolean strict) {}
}
