package toys;

class Foo {
  private String foo;

  public Foo(String str) {
    if (str == null) str = "foo";
    this.foo = str;
  }

  public boolean run(String str) {
    if (str != null) {
      return str == foo;
    }
    return false;
  }

  public void test(String s, String t) {
    if (s.length() >= 5) {
      this.run(s);
    } else {
      this.run(t);
    }
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

public class Main {
  public static void test(String s, Foo f, Bar b) {
    b.b = 5;
    if (s.length() >= 5) {
      Foo f1 = new Foo(s);
      f1.run(s);
    } else {
      f.run(s);
    }
    b.run(s);
  }

  public static void main(String arg[]) throws java.io.IOException {
    String s = new String("test string...");
    Foo f = new Foo("try");
    Bar b = new Bar(null);
    try {
      test(s, f, b);
    } catch (Error e) {
      System.out.println(e.getMessage());
    }
  }
}
