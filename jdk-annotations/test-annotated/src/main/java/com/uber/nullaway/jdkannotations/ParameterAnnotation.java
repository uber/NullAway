package com.uber.nullaway.jdkannotations;

import java.util.List;
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

  public static void takesNullArray(Object @Nullable [] objects) {}

  public static void takesNullableArray(String @Nullable [] strings) {}

  public static void takesNullableElements(@Nullable String[] strings) {}

  public static void takesNullableArrayAndElements(@Nullable String @Nullable [] strings) {}

  public static void takesNonNullArray(Object[] objects) {}

  public static class Generic<T> {

    public String getString(@Nullable T t) {
      return t != null ? t.toString() : "";
    }

    public void printObjectString(T t) {
      System.out.println(t.toString());
    }
  }

  public static void takesNullGenericArray(Generic<String> @Nullable [] objects) {}

  public static void takesNonNullGenericArray(Generic<String>[] objects) {}

  public static <K, T extends @Nullable String> T nullableTypeParam(K k, T t) {
    return t;
  }

  public static <K extends @Nullable Object, T extends @Nullable Object> K twoNullableTypeParam(
      K k, T t) {
    return k;
  }

  public static <T> void nonNullTypeParam(T t) {}

  public static void nestedAnnotations(
      List<@Nullable String> typeArg,
      @Nullable String[] array,
      @Nullable List<@Nullable Integer>[] mixed) {}

  public static void varargsArrayNullable(Object @Nullable ... args) {}

  public static void varargsElementsNullable(@Nullable Object... args) {}

  public static void varargs(@Nullable Object @Nullable ... args) {}
}
