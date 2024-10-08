package com.test;

/**
 * This class has the same name as the class under
 * resources/sample_annotated/src/com/uber/nullaway/libmodel/AnnotationExample.java because we use
 * this as the unannotated version for our test cases to see if we are appropriately processing the
 * annotations as an external library model.
 */
public class ParameterAnnotationExample {

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

  @SuppressWarnings("ArrayToString")
  public static void takesNullArray(Object[] objects) {
    System.out.println(objects);
  }

  @SuppressWarnings("ArrayToString")
  public static void takesNonNullArray(Object[] objects) {
    String unused = objects.toString();
  }

  public static class Generic<T> {
    public String getString(T t) {
      return t != null ? t.toString() : "";
    }

    public void printObjectString(T t) {
      System.out.println(t.toString());
    }
  }
}
