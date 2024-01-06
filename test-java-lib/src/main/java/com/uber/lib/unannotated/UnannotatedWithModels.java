package com.uber.lib.unannotated;

public class UnannotatedWithModels {

  public Object nullableFieldUnannotated1;

  public Object nullableFieldUnannotated2;

  public Object returnsNullUnannotated() {
    return null;
  }

  public Object returnsNullUnannotated2() {
    return null;
  }

  public static boolean isNonNull(Object o) {
    return o != null;
  }
}
