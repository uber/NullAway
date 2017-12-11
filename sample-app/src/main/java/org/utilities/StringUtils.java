package org.utilities;

import android.support.annotation.Nullable;

public class StringUtils {

  public static boolean isEmptyOrNull(@Nullable final CharSequence value) {
    return value == null || value.length() == 0;
  }
}
