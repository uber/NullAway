package com.uber.mylib;

import javax.annotation.Nullable;

/** A sample class. */
public class MyClass {

  static void log(@Nullable Object x) {
    if (x == null) return;
    System.out.println(x.toString());
  }

  static void foo() {
    log(null);
  }
}
