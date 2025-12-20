package com.uber.lib.unannotated;

class Generic<T> {
  public static <T> Generic<T> genericMethod(Class<T> clazz) {
    return new Generic<>();
  }
}
