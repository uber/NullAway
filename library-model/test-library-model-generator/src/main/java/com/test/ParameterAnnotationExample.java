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
}
