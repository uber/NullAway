package com.uber.lib.unannotated;

import javax.annotation.Nullable;

public interface RestrictivelyAnnotatedFI {

  Object takeNullable(@Nullable Object o);
}
