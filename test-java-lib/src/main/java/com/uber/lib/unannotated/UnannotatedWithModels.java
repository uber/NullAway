package com.uber.lib.unannotated;

import java.util.List;

public class UnannotatedWithModels {

  public Object nullableFieldUnannotated1;

  public Object nullableFieldUnannotated2;

  public Object returnsNullUnannotated() {
    return null;
  }

  public Object returnsNullUnannotated2() {
    return null;
  }

  public static List<String> returnsListWithNullableElements() {
    return null;
  }

  public static List<String> returnsListWithNonNullElements() {
    return null;
  }

  public static boolean isNonNull(Object o) {
    return o != null;
  }
}
