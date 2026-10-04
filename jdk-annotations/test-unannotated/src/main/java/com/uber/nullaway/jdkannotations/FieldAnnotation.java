package com.uber.nullaway.jdkannotations;

public class FieldAnnotation {

  public static String nullableStaticField;

  public String nullableField;

  public String nonNullField = "";

  public static class Inner {
    public Object nullableInnerField;
  }
}
