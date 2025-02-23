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
}
