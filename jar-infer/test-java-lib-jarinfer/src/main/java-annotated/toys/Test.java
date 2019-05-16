package toys;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class Test {
  private String foo;

  public Test(String str) {
    if (str == null) str = "foo";
    this.foo = str;
  }

  public boolean run(String str) {
    if (str != null) {
      return str.equals(foo);
    }
    return false;
  }

  @Nullable
  public String getString(boolean a) {
    if (a == true) {
      return foo;
    }
    return null;
  }

  public void test(@Nonnull String s, String t) {
    if (s.length() >= 5) {
      this.run(s);
    } else {
      this.run(t);
    }
  }
}
