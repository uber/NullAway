package com.uber.lib;

import org.checkerframework.checker.nullness.qual.Nullable;

public class CFNullableStuff {

  public interface NullableReturn {

    @Nullable Object get();
  }

  public interface NullableParam {
    void doSomething(@Nullable Object o);

    void doSomething2(Object o, @Nullable Object p);
  }

  public @Nullable Object f;
}
