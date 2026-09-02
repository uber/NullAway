package com.uber.mylib;

import org.jspecify.annotations.Nullable;
import org.utilities.StringUtils;

/** A sample class. */
public class MyClass {

  static void log(@Nullable Object x) {
    if (x == null) {
      return;
    }
    System.out.println(x.toString());
  }

  static int checkModel(@Nullable String s) {
    if (!StringUtils.isEmptyOrNull(s)) {
      return s.hashCode();
    }
    return 0;
  }

  static int checkModelTrim(@Nullable String s) {
    if (!StringUtils.isEmptyOrNull(s, true)) {
      return s.hashCode();
    }
    return 0;
  }

  static void foo() {
    log(null);
  }
}
