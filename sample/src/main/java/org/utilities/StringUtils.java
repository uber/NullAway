package org.utilities;

import org.jspecify.annotations.Nullable;

public class StringUtils {

  public static boolean isEmptyOrNull(@Nullable CharSequence value) {
    return value == null || value.length() == 0;
  }
}
