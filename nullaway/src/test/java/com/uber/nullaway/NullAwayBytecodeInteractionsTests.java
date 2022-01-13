package com.uber.nullaway;

import org.junit.Test;

public class NullAwayBytecodeInteractionsTests extends NullAwayTestsBase {
  @Test
  public void typeUseJarReturn() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.lib.*;",
            "class Test {",
            "  void foo(CFNullableStuff.NullableReturn r) {",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    r.get().toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void typeUseJarParam() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.lib.*;",
            "class Test {",
            "  void foo(CFNullableStuff.NullableParam p) {",
            "    p.doSomething(null);",
            "    // BUG: Diagnostic contains: passing @Nullable parameter",
            "    p.doSomething2(null, new Object());",
            "    p.doSomething2(new Object(), null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void typeUseJarField() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.lib.*;",
            "class Test {",
            "  void foo(CFNullableStuff c) {",
            "    // BUG: Diagnostic contains: dereferenced expression c.f",
            "    c.f.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void typeUseJarOverride() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.lib.*;",
            "import org.checkerframework.checker.nullness.qual.Nullable;",
            "class Test {",
            "  class Test1 implements CFNullableStuff.NullableReturn {",
            "    public @Nullable Object get() { return null; }",
            "  }",
            "  class Test2 implements CFNullableStuff.NullableParam {",
            "    // BUG: Diagnostic contains: parameter o is @NonNull",
            "    public void doSomething(Object o) {}",
            "    // BUG: Diagnostic contains: parameter p is @NonNull",
            "    public void doSomething2(Object o, Object p) {}",
            "  }",
            "  class Test3 implements CFNullableStuff.NullableParam {",
            "    public void doSomething(@Nullable Object o) {}",
            "    public void doSomething2(Object o, @Nullable Object p) {}",
            "  }",
            "}")
        .doTest();
  }
}
