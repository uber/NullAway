package com.uber.lib.generics;

import org.jspecify.annotations.Nullable;

public class NullableTypeParam<E extends @Nullable Object> {

  public static NullableTypeParam<@Nullable String> staticField =
      new NullableTypeParam<@Nullable String>();
}
