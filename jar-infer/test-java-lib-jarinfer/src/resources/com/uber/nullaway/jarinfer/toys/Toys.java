package com.uber.nullaway.jarinfer.toys;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class Foo {
  private String foo;

  public Foo(String str) {
    if (str == null) str = "foo";
    this.foo = str;
  }

  public boolean run(@Nonnull String str) {
    if (str.length() > 0) {
      return str.equals(foo);
    }
    return false;
  }
}

class Bar {
  private String bar;
  public int b;

  public Bar(String str) {
    if (str == null) str = "bar";
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

public class Toys {

  @Nullable
  public static String getString(boolean flag, String str) {
    if (flag) {
      return null;
    }
    return str;
  }

  public static void test(@Nonnull String s, Foo f, @Nonnull Bar b) {
    if (s.length() >= 5) {
      Foo f1 = new Foo(s);
      f1.run(s);
    } else {
      f.run(s);
    }
    b.run(s);
  }

  public static void test1(@Nonnull String s, String t, String u) {
    if (s.length() >= 5) {
      Foo fs = new Foo(s);
      fs.run(u);
    } else {
      Foo ft = new Foo(t);
      ft.run(u);
    }
  }

  public static void main(String arg[]) throws java.io.IOException {
    String s = "test string...";
    Foo f = new Foo("let's");
    Bar b = new Bar("try");
    try {
      test(s, f, b);
    } catch (Error e) {
      System.out.println(e.getMessage());
    }
  }
}
