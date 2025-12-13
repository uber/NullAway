package com.uber.nullaway.jdkannotations;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class ParameterAnnotation {

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

  public static void printObjectString(@NonNull Object object) {
    System.out.println(object.toString());
  }

  @SuppressWarnings("ArrayToString")
  public static void takesNullArray(Object @Nullable [] objects) {
    System.out.println(objects);
  }

  @SuppressWarnings("ArrayToString")
  public static void takesNonNullArray(Object[] objects) {
    String unused = objects.toString();
  }

  public static class Generic<T> {

    public String getString(@Nullable T t) {
      return t != null ? t.toString() : "";
    }

    public void printObjectString(T t) {
      System.out.println(t.toString());
    }
  }

  @SuppressWarnings("ArrayToString")
  public static void takesNullGenericArray(Generic<String> @Nullable [] objects) {
    System.out.println(objects);
  }

  @SuppressWarnings("ArrayToString")
  public static void takesNonNullGenericArray(Generic<String>[] objects) {
    System.out.println(objects);
  }

  public static <K, T extends @Nullable String> T nullableTypeParam(K k, T t) {
    return t;
  }

  public static <T> void nonNullTypeParam(T t) {}
}
