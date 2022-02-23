package com.example.jspecify.unannotatedpackage;

import org.jspecify.nullness.NullMarked;

@NullMarked
public class TopLevel {
  public static String foo(String s) {
    return s;
  }
}
