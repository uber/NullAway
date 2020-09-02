package com.uber.lib.unannotated;

import androidx.annotation.RecentlyNonNull;
import androidx.annotation.RecentlyNullable;

public class AndroidRecentlyAnnotatedClass {

  public static @RecentlyNullable Object returnsNull() {
    return null;
  }

  public static void consumesObjectNonNull(@RecentlyNonNull Object o) {}

  public static void consumesObjectUnannotated(Object o) {}
}
