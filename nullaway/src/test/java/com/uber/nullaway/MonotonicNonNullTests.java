package com.uber.nullaway;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MonotonicNonNullTests extends NullAwayTestsBase {

  @Test
  public void initializerExpression() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.annotations.MonotonicNonNull;",
            "class Test {",
            "  // this is fine; same as implicit initialization",
            "  @MonotonicNonNull Object f1 = null;",
            "  @MonotonicNonNull Object f2 = new Object();",
            "}")
        .doTest();
  }

  @Test
  public void assignments() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.annotations.MonotonicNonNull;",
            "class Test {",
            "  @MonotonicNonNull Object f1;",
            "  void testPositive() {",
            "    // BUG: Diagnostic contains: assigning @Nullable expression",
            "    f1 = null;",
            "  }",
            "  void testNegative() {",
            "    f1 = new Object();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void lambdas() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.annotations.MonotonicNonNull;",
            "class Test {",
            "  @MonotonicNonNull Object f1;",
            "  void testPositive() {",
            "    Runnable r = () -> {",
            "      // BUG: Diagnostic contains: dereferenced expression f1",
            "      f1.toString();",
            "    };",
            "  }",
            "  void testNegative() {",
            "    f1 = new Object();",
            "    Runnable r = () -> {",
            "      f1.toString();",
            "    };",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nestedObjects() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.annotations.MonotonicNonNull;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  class Foo {",
            "    @MonotonicNonNull Object x;",
            "  }",
            "  final Foo f1 = new Foo();",
            "  Foo f2 = new Foo(); // not final",
            "  @Nullable Foo f3;",
            "  void testPositive1() {",
            "    f2.x = new Object();",
            "    Runnable r = () -> {",
            "      // report a bug since f2 may be overwritten",
            "      // BUG: Diagnostic contains: dereferenced expression f2.x",
            "      f2.x.toString();",
            "    };",
            "  }",
            "  void testPositive2() {",
            "    f3 = new Foo();",
            "    f3.x = new Object();",
            "    Runnable r = () -> {",
            "      // report a bug since f3 may be overwritten",
            "      // BUG: Diagnostic contains: dereferenced expression f3.x",
            "      f3.x.toString();",
            "    };",
            "  }",
            "  void testNegative() {",
            "    f1.x = new Object();",
            "    Runnable r = () -> {",
            "      f1.x.toString();",
            "    };",
            "  }",
            "}")
        .doTest();
  }
}
