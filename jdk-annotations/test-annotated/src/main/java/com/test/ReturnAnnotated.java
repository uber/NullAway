package com.test;

import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class ReturnAnnotated {

  // return type : null, nullable, nonnull && type parameter, predefined types
  // type parameter: nada, exists && nullable, nonnull

  public @NonNull String nonNullString() {
    return new String();
  }

  public <T extends @Nullable Object> @Nullable T getTypePar(@Nullable T t) {
    return t;
  }

  public @Nullable String makeUpperCase(String inputString) {
    if (inputString == null || inputString.isEmpty()) {
      return null;
    } else {
      return inputString.toUpperCase(Locale.ROOT);
    }
  }

  public Integer @Nullable [] generateIntArray(int size) {
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

  public @Nullable String nullReturn() {
    return null;
  }

  public static class InnerExample {

    public @Nullable String returnNull() {
      return null;
    }
  }

  public static class UpperBoundExample<T extends @Nullable Object> {

    T nullableObject;

    public T getNullable() {
      return nullableObject;
    }
  }

  public static Integer add(Integer a, Integer b) {
    return a + b;
  }

  public static Object getNewObjectIfNull(@Nullable Object object) {
    if (object == null) {
      return new Object();
    } else {
      return object;
    }
  }
}
