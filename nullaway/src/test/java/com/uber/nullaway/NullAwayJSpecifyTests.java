package com.uber.nullaway;

import java.util.Arrays;
import org.junit.Test;

public class NullAwayJSpecifyTests extends NullAwayTestsBase {

  @Test
  public void nullMarkedPackageLevel() {
    defaultCompilationHelper
        .addSourceLines(
            "package-info.java",
            "@NullMarked package com.example.thirdparty;",
            "import org.jspecify.nullness.NullMarked;")
        .addSourceLines(
            "ThirdPartyAnnotatedUtils.java",
            "package com.example.thirdparty;",
            "import org.jspecify.nullness.Nullable;",
            "public class ThirdPartyAnnotatedUtils {",
            "  public static String toStringOrDefault(@Nullable Object o1, String s) {",
            "    if (o1 != null) {",
            "      return o1.toString();",
            "    }",
            "    return s;",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.example.thirdparty.ThirdPartyAnnotatedUtils;",
            "public class Test {",
            "  public static void test(Object o) {",
            "    // Safe: passing @NonNull on both args",
            "    ThirdPartyAnnotatedUtils.toStringOrDefault(o, \"default\");",
            "    // Safe: first arg is @Nullable",
            "    ThirdPartyAnnotatedUtils.toStringOrDefault(null, \"default\");",
            "    // Unsafe: @NullMarked means the second arg is @NonNull by default, not @Nullable",
            "    // BUG: Diagnostic contains: passing @Nullable parameter",
            "    ThirdPartyAnnotatedUtils.toStringOrDefault(o, null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullMarkedPackageEnablesChecking() {
    defaultCompilationHelper
        .addSourceLines(
            "package-info.java",
            "@NullMarked package com.example.thirdparty;",
            "import org.jspecify.nullness.NullMarked;")
        .addSourceLines(
            "Foo.java",
            "package com.example.thirdparty;",
            "import org.jspecify.nullness.Nullable;",
            "public class Foo {",
            "  public static String foo(String s) {",
            "    return s;",
            "  }",
            "  public static void test() {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter",
            "    foo(null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullMarkedClassLevel() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.example.thirdparty;",
            "import org.jspecify.nullness.NullMarked;",
            "@NullMarked",
            "public class Foo {",
            "  public static String foo(String s) {",
            "    return s;",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.example.thirdparty.Foo;",
            "public class Test {",
            "  public static void test(Object o) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter",
            "    Foo.foo(null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullMarkedClassLevelEnablesChecking() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.example.thirdparty;",
            "import org.jspecify.nullness.NullMarked;",
            "@NullMarked",
            "public class Foo {",
            "  public static String foo(String s) {",
            "    return s;",
            "  }",
            "  public static void test(Object o) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter",
            "    foo(null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullMarkedClassLevelOuter() {
    defaultCompilationHelper
        .addSourceLines(
            "Bar.java",
            "package com.example.thirdparty;",
            "import org.jspecify.nullness.Nullable;",
            "import org.jspecify.nullness.NullMarked;",
            "@NullMarked",
            "public class Bar {",
            "  public static class Foo {",
            "    public static String foo(String s) {",
            "      return s;",
            "    }",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.example.thirdparty.Bar;",
            "public class Test {",
            "  public static void test(Object o) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter",
            "    Bar.Foo.foo(null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullMarkedClassLevelInner() {
    defaultCompilationHelper
        .addSourceLines(
            "Bar.java",
            "package com.example.thirdparty;",
            "import org.jspecify.nullness.Nullable;",
            "import org.jspecify.nullness.NullMarked;",
            "public class Bar {",
            "  @NullMarked",
            "  public static class Foo {",
            "    public static String foo(String s) {",
            "      return s;",
            "    }",
            "  }",
            "  public static void unchecked(Object o) {}",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.example.thirdparty.Bar;",
            "public class Test {",
            "  public static void test(Object o) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter",
            "    Bar.Foo.foo(null);",
            "    Bar.unchecked(null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullMarkedClassLevelInnerControlsChecking() {
    defaultCompilationHelper
        .addSourceLines(
            "Bar.java",
            "package com.example.thirdparty;",
            "import org.jspecify.nullness.Nullable;",
            "import org.jspecify.nullness.NullMarked;",
            "public class Bar {",
            "  @NullMarked",
            "  public static class Foo {",
            "    public static String foo(String s) {",
            "      return s;",
            "    }",
            "    // @NullMarked should also control checking of source",
            "    public static void test(Object o) {",
            "      // BUG: Diagnostic contains: passing @Nullable parameter",
            "      foo(null);",
            "    }",
            "  }",
            "  public static void unchecked() {",
            "    Object x = null;",
            "    // fine since this code is still unchecked",
            "    x.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullMarkedClassLevelLocalAndEnvironment() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.example.thirdparty;",
            "import org.jspecify.nullness.Nullable;",
            "import org.jspecify.nullness.NullMarked;",
            "public class Test {",
            "  public static Object test() {",
            "    Object x = null;",
            "    final Object y = new Object();",
            "    @NullMarked",
            "    class Local {",
            "      public Object returnsNonNull() {",
            "        return y;",
            "      }",
            "      @Nullable",
            "      public Object returnsNullable() {",
            "        return x;",
            "      }",
            "      public Object returnsNonNullWithError() {",
            "        // BUG: Diagnostic contains: returning @Nullable expression",
            "        return x;",
            "      }",
            "    }",
            "    Local local = new Local();",
            "    // Allowed, since unmarked",
            "    return local.returnsNullable();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullMarkedMethodLevel() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.example.thirdparty;",
            "import com.uber.nullaway.testdata.annotations.jspecify.future.NullMarked;",
            "public class Foo {",
            "  @NullMarked",
            "  public static String foo(String s) {",
            "    return s;",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.example.thirdparty.Foo;",
            "public class Test {",
            "  public static void test(Object o) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter",
            "    Foo.foo(null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullMarkedMethodLevelScan() {
    // Test that we turn on analysis/scanning within @NullMarked methods in a non-annotated
    // package/class
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.example.thirdparty;",
            "import com.uber.nullaway.testdata.annotations.jspecify.future.NullMarked;",
            "public class Foo {",
            "  @NullMarked",
            "  public static String foo(String s) {",
            "    return s;",
            "  }",
            "}")
        .addSourceLines(
            "Bar.java",
            "package com.example.thirdparty;",
            "import com.uber.nullaway.testdata.annotations.jspecify.future.NullMarked;",
            "public class Bar {",
            "  public static void bar1() {",
            "    // No report, unannotated caller!",
            "    Foo.foo(null);",
            "  }",
            "  @NullMarked",
            "  public static void bar2() {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter",
            "    Foo.foo(null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullMarkedOuterMethodLevelWithAnonymousClass() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.example.thirdparty;",
            "import com.uber.nullaway.testdata.annotations.jspecify.future.NullMarked;",
            "public class Foo {",
            "  @NullMarked",
            "  public static String foo(String s) {",
            "    return s;",
            "  }",
            "}")
        .addSourceLines(
            "Bar.java",
            "package com.example.thirdparty;",
            "import com.uber.nullaway.testdata.annotations.jspecify.future.NullMarked;",
            "public class Bar {",
            "  @NullMarked",
            "  public static Runnable runFoo() {",
            "    return new Runnable() {",
            "      public void run() {",
            "        // BUG: Diagnostic contains: passing @Nullable parameter",
            "        Foo.foo(null);",
            "      }",
            "    };",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullMarkedOuterMethodLevelUsage() {
    defaultCompilationHelper
        .addSourceLines(
            "IConsumer.java",
            "package com.example.thirdparty;",
            "public interface IConsumer {",
            "  void consume(Object s);",
            "}")
        .addSourceLines(
            "Foo.java",
            "package com.example.thirdparty;",
            "import com.uber.nullaway.testdata.annotations.jspecify.future.NullMarked;",
            "public class Foo {",
            "  @NullMarked",
            "  public static IConsumer getConsumer() {",
            "    return new IConsumer() {",
            "      // Transitively null marked! Explicitly non-null arg, which is a safe override of unknown-nullness.",
            "      public void consume(Object s) {",
            "        System.out.println(s);",
            "      }",
            "    };",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.example.thirdparty.Foo;",
            "public class Test {",
            "  public static void test(Object o) {",
            "    // Safe because IConsumer::consume is unmarked? And no static knowledge of Foo$1",
            "    Foo.getConsumer().consume(null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullMarkedOuterMethodLevelWithLocalClass() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.example.thirdparty;",
            "import com.uber.nullaway.testdata.annotations.jspecify.future.NullMarked;",
            "public class Foo {",
            "  @NullMarked",
            "  public static String foo(String s) {",
            "    return s;",
            "  }",
            "}")
        .addSourceLines(
            "Bar.java",
            "package com.example.thirdparty;",
            "import com.uber.nullaway.testdata.annotations.jspecify.future.NullMarked;",
            "public class Bar {",
            "  @NullMarked",
            "  public static Object bar() {",
            "    class Baz {",
            "      public void baz() {",
            "        // BUG: Diagnostic contains: passing @Nullable parameter",
            "        Foo.foo(null);",
            "      }",
            "    }",
            "    Baz b = new Baz();",
            "    b.baz();",
            "    return b;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullMarkedOuterMethodLevelWithLocalClassInit() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.example.thirdparty;",
            "import com.uber.nullaway.testdata.annotations.jspecify.future.NullMarked;",
            "public class Test {",
            "  @NullMarked",
            "  public static Object test() {",
            "    class Foo {",
            "      private Object o;",
            "      // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field o",
            "      public Foo() { }",
            "      public String foo(String s) {",
            "        return s;",
            "      }",
            "    }",
            "    return new Foo();",
            "  }",
            // This currently causes a crash, because updateEnvironmentMapping() is only called when
            // analyzing the class (which we don't do here, since Test$Foo is unmarked), but used
            // within analysis of the null-marked method Test$Foo.foo(...).
            "  public static Object test2() {",
            "    class Foo {",
            "      private Object o;", // No init checking, since Test$2Foo is unmarked.
            "      public Foo() { }",
            "      @NullMarked",
            "      public String foo(String s) {",
            "        return s;",
            "      }",
            "    }",
            "    return new Foo();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void configUnannotatedOverridesNullMarked() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.example"))
        .addSourceLines(
            "package-info.java",
            "@NullMarked package com.example.thirdparty;",
            "import org.jspecify.nullness.NullMarked;")
        .addSourceLines(
            "Foo.java",
            "package com.example.thirdparty;",
            "import org.jspecify.nullness.Nullable;",
            "public class Foo {",
            "  public static String foo(String s) {",
            "    return s;",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.example.thirdparty.Foo;",
            "public class Test {",
            "  public static void test(Object o) {",
            "    // Safe: Foo is treated as unannotated",
            "    Foo.foo(null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void bytecodeNullMarkedPackageLevel() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.example.jspecify.annotatedpackage.Utils;",
            "public class Test {",
            "  public static void test(Object o) {",
            "    // Safe: passing @NonNull on both args",
            "    Utils.toStringOrDefault(o, \"default\");",
            "    // Safe: first arg is @Nullable",
            "    Utils.toStringOrDefault(null, \"default\");",
            "    // Unsafe: @NullMarked means the second arg is @NonNull by default, not @Nullable",
            "    // BUG: Diagnostic contains: passing @Nullable parameter",
            "    Utils.toStringOrDefault(o, null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void bytecodeNullMarkedClassLevel() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.example.jspecify.unannotatedpackage.TopLevel;",
            "public class Test {",
            "  public static void test(Object o) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter",
            "    TopLevel.foo(null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void bytecodeNullMarkedClassLevelInner() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.example.jspecify.unannotatedpackage.Outer;",
            "public class Test {",
            "  public static void test(Object o) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter",
            "    Outer.Inner.foo(null);",
            "    Outer.unchecked(null);",
            "  }",
            "}")
        .doTest();
  }
}
