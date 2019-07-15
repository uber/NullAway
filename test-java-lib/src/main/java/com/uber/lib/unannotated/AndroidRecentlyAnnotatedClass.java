package com.uber.lib.unannotated;

import androidx.annotation.RecentlyNonNull;
import androidx.annotation.RecentlyNullable;

// don't actually check this code
@SuppressWarnings("NullAway")
public class AndroidRecentlyAnnotatedClass {

  public final Object field;

  public AndroidRecentlyAnnotatedClass(Object field) {
    this.field = field;
  }

  public @RecentlyNullable Object getField() {
    return field;
  }

  public static @RecentlyNullable Object returnsNull() {
    return null;
  }

  public static void consumesObjectNonNull(@RecentlyNonNull Object o) {}

  public static void consumesObjectUnannotated(Object o) {}

  public void acceptsNonNull(@RecentlyNonNull Object o) {}

  public void acceptsNonNull2(@RecentlyNonNull Object o) {}

  public void acceptsNullable(@RecentlyNullable Object o) {}

  public void acceptsNullable2(@RecentlyNullable Object o) {}

  public @RecentlyNonNull Object returnsNonNull() {
    return new Object();
  }

  public @RecentlyNonNull Object returnsNonNull2() {
    return new Object();
  }

  public @RecentlyNullable Object returnsNullable() {
    return null;
  }

  public @RecentlyNullable Object returnsNullable2() {
    return null;
  }
}
