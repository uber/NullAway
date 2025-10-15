package com.uber.nullaway.jdkannotations;

import java.util.Locale;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class ReturnAnnotation {

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

  /**
   * This method exists to test that we do not process this annotation. Since for the purposes of
   * this tool, we are only considering the jspecify annotation.
   */
  @javax.annotation.Nullable
  public String nullReturn() {
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

  public static UpperBoundExample<String> @Nullable [] returnNullableGenericArray() {
    return null;
  }

  public static UpperBoundExample<@Nullable String> returnNonNullGenericContainingNullable() {
    return new UpperBoundExample<@Nullable String>();
  }

  public static @Nullable UpperBoundExample<@Nullable String>
      returnNullableGenericContainingNullable() {
    return null;
  }
}
