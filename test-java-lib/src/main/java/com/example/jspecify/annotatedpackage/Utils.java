package com.example.jspecify.annotatedpackage;

import org.jspecify.annotations.Nullable;

public class Utils {

  public static String toStringOrDefault(@Nullable Object o1, String s) {
    if (o1 != null) {
      return o1.toString();
    }
    return s;
  }
}
