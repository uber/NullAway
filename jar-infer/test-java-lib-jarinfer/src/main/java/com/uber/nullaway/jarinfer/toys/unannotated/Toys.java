package com.uber.nullaway.jarinfer.toys.unannotated;

import javax.annotation.Nonnull;

public class Toys {

  @ExpectNullable
  public static String getString(boolean flag, String str) {
    if (flag) {
      return null;
    }
    return str;
  }

  public static void test(@ExpectNonnull @Nonnull String s, Foo f, @ExpectNonnull Bar b) {
    if (s.length() >= 5) {
      Foo f1 = new Foo(s);
      f1.run(s);
    } else {
      f.run(s);
    }
    b.run(s);
  }

  public static void test1(@ExpectNonnull String s, String t, String u) {
    if (s.length() >= 5) {
      Foo fs = new Foo(s);
      fs.run(u);
    } else {
      Foo ft = new Foo(t);
      ft.run(u);
    }
  }

  @SuppressWarnings("ArrayHashCode")
  public static int testArray(Object[] o) {
    return o.hashCode();
  }

  public abstract static class Generic<T> {
    public String getString(T t) {
      return t.toString();
    }

    public void doNothing() {}

    public abstract T getSomething();
  }

  public static void genericParam(Generic<String> g) {
    g.getString("hello");
  }

  public static void nestedGenericParam(Generic<Generic<String>> g) {
    g.getString(null);
  }

  public static void genericWildcard(Generic<?> g) {
    g.doNothing();
  }

  public static void nestedGenericWildcard(Generic<Generic<?>> g) {
    g.doNothing();
  }

  public static String genericWildcardUpper(Generic<? extends String> g) {
    return g.getSomething();
  }

  public static void genericWildcardLower(Generic<? super String> g) {
    g.getString("hello");
  }

  public abstract static class DoubleGeneric<T, U> {
    public void doNothing() {}
  }

  public static void doubleGenericWildcard(String s, DoubleGeneric<?, ?> g) {
    g.doNothing();
  }

  public static void doubleGenericWildcardNullOk(String s, DoubleGeneric<?, ?> g) {
    if (g != null) {
      g.doNothing();
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
