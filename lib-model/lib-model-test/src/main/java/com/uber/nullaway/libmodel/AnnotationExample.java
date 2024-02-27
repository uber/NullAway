package com.uber.nullaway.libmodel;

import java.util.Locale;

public class AnnotationExample {
  public String makeUpperCase(String inputString) {
    if (inputString == null || inputString.isEmpty()) {
      return null;
    } else {
      return inputString.toUpperCase(Locale.ROOT);
    }
  }
}
