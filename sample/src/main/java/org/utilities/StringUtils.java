package org.utilities;

import org.jspecify.annotations.Nullable;

public class StringUtils {

  public static boolean isEmptyOrNull(@Nullable CharSequence value) {
    return value == null || value.length() == 0;
  }

  public static boolean isEmptyOrNull(@Nullable CharSequence value, boolean trim) {
    if (value == null) {
      return true;
    }
    return trim ? value.toString().trim().isEmpty() : value.length() == 0;
  }
}
