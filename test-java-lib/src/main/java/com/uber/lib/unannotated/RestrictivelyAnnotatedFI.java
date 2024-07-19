package com.uber.lib.unannotated;

import org.jspecify.annotations.Nullable;

public interface RestrictivelyAnnotatedFI {

  Object takeNullable(@Nullable Object o);
}
