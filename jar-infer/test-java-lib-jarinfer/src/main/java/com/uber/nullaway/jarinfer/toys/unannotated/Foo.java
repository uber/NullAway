package com.uber.nullaway.jarinfer.toys.unannotated;

public class Foo {
  private String foo;

  public Foo(String str) {
    if (str == null) {
      str = "foo";
    }
    this.foo = str;
  }

  public boolean run(@ExpectNonnull String str) {
    if (str.length() > 0) {
      return str.equals(foo);
    }
    return false;
  }

  // This method is expected to have a 'Nullable' annotation
  // on the result.
  public static String expectNullable(int x, String str) {
    if (x < 10) {
      return null;
    }
    return str;
  }

  // This method is expected to have a 'Nonnull' annotation on
  // its parameter.
  public static int expectNonnull(String str) {
    return str.length();
  }
}
