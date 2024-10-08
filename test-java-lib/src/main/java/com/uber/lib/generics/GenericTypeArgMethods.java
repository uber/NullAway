package com.uber.lib.generics;

import org.jspecify.annotations.Nullable;

public class GenericTypeArgMethods {

  public static void nullableTypeParamArg(NullableTypeParam<@Nullable String> s) {}

  public static NullableTypeParam<@Nullable String> nullableTypeParamReturn() {
    return new NullableTypeParam<@Nullable String>();
  }
}
