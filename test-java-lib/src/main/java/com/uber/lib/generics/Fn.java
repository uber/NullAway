package com.uber.lib.generics;

import org.jspecify.annotations.Nullable;

public interface Fn<P extends @Nullable Object, R extends @Nullable Object> {
  R apply(P p);
}
