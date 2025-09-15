package com.uber.nullaway.jdkannotations;

import java.util.Locale;

public class ReturnAnnotation {

  public String makeUpperCase(String inputString) {
    if (inputString == null || inputString.isEmpty()) {
      return null;
    } else {
      return inputString.toUpperCase(Locale.ROOT);
    }
  }

  public Integer[] generateIntArray(int size) {
    if (size <= 0) {
      return null;
    } else {
      Integer[] result = new Integer[size];
      for (int i = 0; i < size; i++) {
        result[i] = i + 1;
      }
      return result;
    }
  }

  public String nullReturn() {
    return null;
  }

  public static class InnerExample {
    public String returnNull() {
      return null;
    }
  }

  public static class UpperBoundExample<T> {

    T nullableObject;

    public T getNullable() {
      return nullableObject;
    }
  }

  public static Integer add(Integer a, Integer b) {
    return a + b;
  }

  public static Object getNewObjectIfNull(Object object) {
    if (object == null) {
      return new Object();
    } else {
      return object;
    }
  }
}
