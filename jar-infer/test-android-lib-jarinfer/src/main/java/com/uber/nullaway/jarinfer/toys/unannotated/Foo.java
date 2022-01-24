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
}
