package com.uber.lib.unannotated;

import javax.annotation.Nullable;

public interface RestrictivelyAnnotatedFIWithModelOverride {

  @Nullable
  Object apply(@Nullable Object o);
}
