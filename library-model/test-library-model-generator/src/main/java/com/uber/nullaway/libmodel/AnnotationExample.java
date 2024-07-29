package com.uber.nullaway.libmodel;

import java.util.Locale;

/**
 * This class has the same name as the class under
 * resources/sample_annotated/src/com/uber/nullaway/libmodel/AnnotationExample.java because we use
 * this as the unannotated version for our test cases to see if we are appropriately processing the
 * annotations as an external library model.
 */
public class AnnotationExample {
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

  /** In the library model we add a {@code @Nullable} upper bound for T */
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
