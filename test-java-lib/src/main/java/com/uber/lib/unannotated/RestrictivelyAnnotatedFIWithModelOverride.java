package com.uber.lib.unannotated;

import org.jspecify.annotations.Nullable;

public interface RestrictivelyAnnotatedFIWithModelOverride {

  @Nullable Object apply(@Nullable Object o);
}
