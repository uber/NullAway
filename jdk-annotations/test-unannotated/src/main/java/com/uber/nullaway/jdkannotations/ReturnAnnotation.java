package com.uber.nullaway.jdkannotations;

import java.util.ArrayList;
import java.util.List;
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

  public static UpperBoundExample<String>[] returnNullableGenericArray() {
    return null;
  }

  public static UpperBoundExample<String> returnNonNullGenericContainingNullable() {
    return new UpperBoundExample<String>();
  }

  public static UpperBoundExample<String> returnNullableGenericContainingNullable() {
    return null;
  }

  public static List<? super String> getList(Integer i, Character c) {
    return null;
  }

  public static List<String> nestedAnnotTypeArg() {
    List<String> list = new ArrayList<>();
    list.add("safe");
    list.add(null);
    return list;
  }

  public static List<? extends String> nestedAnnotWildcard() {
    List<String> list = new ArrayList<>();
    list.add(null);
    list.add("string");
    return list;
  }

  public static String[] nestedAnnotArrayElement() {
    return new String[] {"populated", "value", null};
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static List<Integer>[] nestedAnnotMixed() {
    // inner list
    List<Integer> innerList = new ArrayList<>();
    innerList.add(null);
    innerList.add(1);

    List[] rawArray = new List[2];
    rawArray[0] = innerList;
    rawArray[1] = null;

    return rawArray;
  }
}
