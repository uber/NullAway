package com.uber.nullaway.jarinfer.toys.unannotated;

public class Bar {
  private String bar;
  public int b;

  public Bar(String str) {
    if (str == null) {
      str = "bar";
    }
    this.bar = str;
    this.b = bar.length();
  }

  public int run(String str) {
    if (str != null) {
      return str.length();
    }
    return bar.length();
  }
}
