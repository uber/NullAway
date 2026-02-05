package com.uber.nullaway.jdkannotations;

import java.util.ArrayList;
import java.util.List;
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

  public static @Nullable List<? super String> getList(Integer i, @Nullable Character c) {
    return null;
  }

  public static List<@Nullable String> nestedAnnotTypeArg() {
    List<@Nullable String> list = new ArrayList<>();
    list.add("safe");
    list.add(null);
    return list;
  }

  public static List<? extends @Nullable String> nestedAnnotWildcard() {
    List<@Nullable String> list = new ArrayList<>();
    list.add(null);
    list.add("string");
    return list;
  }

  public static @Nullable String[] nestedAnnotArrayElement() {
    return new @Nullable String[] {"populated", "value", null};
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static @Nullable List<@Nullable Integer>[] nestedAnnotMixed() {
    // inner list
    List<@Nullable Integer> innerList = new ArrayList<>();
    innerList.add(null);
    innerList.add(1);

    List[] rawArray = new List[2];
    rawArray[0] = innerList;
    rawArray[1] = null;

    return rawArray;
  }
}
