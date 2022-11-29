package com.example.jspecify.unannotatedpackage;

import org.jspecify.annotations.NullMarked;

@NullMarked
public class TopLevel {
  public static String foo(String s) {
    return s;
  }
}
