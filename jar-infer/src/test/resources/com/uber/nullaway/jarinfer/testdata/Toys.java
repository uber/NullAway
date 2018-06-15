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

public class Toys {
  public static void main(String arg[]) throws java.io.IOException {
    String s = new String("test string...");
    Foo f = new Foo("try");
    try {
      f.test(s, "string test...");
    } catch (Error e) {
      System.out.println(e.getMessage());
    }
  }
}
