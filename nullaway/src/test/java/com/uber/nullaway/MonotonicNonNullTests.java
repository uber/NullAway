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
  public void rootedAtParameterOrLocal() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.annotations.MonotonicNonNull;",
            "class Test {",
            "  @MonotonicNonNull Object f1;",
            "  void testPositiveParam(Test t) {",
            "    Runnable r = () -> {",
            "      // BUG: Diagnostic contains: dereferenced expression t.f1",
            "      t.f1.toString();",
            "    };",
            "  }",
            "  void testNegativeParam(Test t) {",
            "    t.f1 = new Object();",
            "    Runnable r = () -> {",
            "      t.f1.toString();",
            "    };",
            "  }",
            "  void testPositiveLocal() {",
            "    Test t = new Test();",
            "    Runnable r = () -> {",
            "      // BUG: Diagnostic contains: dereferenced expression t.f1",
            "      t.f1.toString();",
            "    };",
            "  }",
            "  void testNegativeLocal() {",
            "    Test t = new Test();",
            "    t.f1 = new Object();",
            "    Runnable r = () -> {",
            "      t.f1.toString();",
            "    };",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void rootedAtStaticFinal() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.annotations.MonotonicNonNull;",
            "class Test {",
            "  @MonotonicNonNull Object f1;",
            "  static final Test t = new Test();",
            "  void testPositive() {",
            "    Runnable r = () -> {",
            "      // BUG: Diagnostic contains: dereferenced expression t.f1",
            "      t.f1.toString();",
            "    };",
            "  }",
            "  void testNegative() {",
            "    t.f1 = new Object();",
            "    Runnable r = () -> {",
            "      t.f1.toString();",
            "    };",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void monotonicNonNullStatic() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.annotations.MonotonicNonNull;",
            "class Test {",
            "  @MonotonicNonNull static Object f1;",
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
  public void anonymousClasses() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.annotations.MonotonicNonNull;",
            "class Test {",
            "  @MonotonicNonNull Object f1;",
            "  void testPositive() {",
            "    Runnable r = new Runnable() {",
            "      @Override",
            "      public void run() {",
            "        // BUG: Diagnostic contains: dereferenced expression f1",
            "        f1.toString();",
            "      }",
            "    };",
            "  }",
            "  void testNegative() {",
            "    f1 = new Object();",
            "    Runnable r = new Runnable() {",
            "      @Override",
            "      public void run() {",
            "        f1.toString();",
            "      }",
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

  @Test
  public void accessPathsWithMethodCalls() {
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
            "  Foo f1 = new Foo();",
            "  final Foo getF1() {",
            "    return f1;",
            "  }",
            "  final @Nullable Foo getOther() {",
            "    return null;",
            "  }",
            "  void testPositive1() {",
            "    getF1().x = new Object();",
            "    Runnable r = () -> {",
            "      // BUG: Diagnostic contains: dereferenced expression",
            "      getF1().x.toString();",
            "    };",
            "  }",
            "  void testPositive2() {",
            "    if (getOther() != null) {",
            "      getOther().x = new Object();",
            "      Runnable r1 = () -> {",
            "        // getOther() should be treated as @Nullable in the lambda",
            "        // BUG: Diagnostic contains: dereferenced expression",
            "        getOther().toString();",
            "      };",
            "      Runnable r2 = () -> {",
            "        // BUG: Diagnostic contains: dereferenced expression",
            "        getOther().x.toString();",
            "      };",
            "    }",
            "  }",
            "}")
        .doTest();
  }
}
