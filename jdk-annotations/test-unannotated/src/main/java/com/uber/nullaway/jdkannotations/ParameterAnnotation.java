package com.uber.nullaway.jdkannotations;

import java.util.List;

public class ParameterAnnotation {
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

  public static void printObjectString(Object object) {
    System.out.println(object.toString());
  }

  public static void takesNullArray(Object[] objects) {}

  public static void takesNullableArray(String[] strings) {}

  public static void takesNullableElements(String[] strings) {}

  public static void takesNullableArrayAndElements(String[] strings) {}

  public static void takesNonNullArray(Object[] objects) {}

  public static class Generic<T> {

    public String getString(T t) {
      return t != null ? t.toString() : "";
    }

    public void printObjectString(T t) {
      System.out.println(t.toString());
    }
  }

  public static void takesNullGenericArray(Generic<String>[] objects) {}

  public static void takesNonNullGenericArray(Generic<String>[] objects) {}

  public static <K, T extends String> T nullableTypeParam(K k, T t) {
    return t;
  }

  public static <K, T> K twoNullableTypeParam(K k, T t) {
    return k;
  }

  public static <T> void nonNullTypeParam(T t) {}

  public static void nestedAnnotations(
      List<String> typeArg, String[] array, List<Integer>[] mixed) {}

  public static void varargsArrayNullable(Object... args) {}

  public static void varargsElementsNullable(Object... args) {}

  public static void varargs(Object... args) {}
}
