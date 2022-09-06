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
            "import com.example.jspecify.future.annotations.NullMarked;",
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
            "import com.example.jspecify.future.annotations.NullMarked;",
            "public class Foo {",
            "  @NullMarked",
            "  public static String foo(String s) {",
            "    return s;",
            "  }",
            "}")
        .addSourceLines(
            "Bar.java",
            "package com.example.thirdparty;",
            "import com.example.jspecify.future.annotations.NullMarked;",
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
            "import com.example.jspecify.future.annotations.NullMarked;",
            "public class Foo {",
            "  @NullMarked",
            "  public static String foo(String s) {",
            "    return s;",
            "  }",
            "}")
        .addSourceLines(
            "Bar.java",
            "package com.example.thirdparty;",
            "import com.example.jspecify.future.annotations.NullMarked;",
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
            "import com.example.jspecify.future.annotations.NullMarked;",
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
            "import com.example.jspecify.future.annotations.NullMarked;",
            "public class Foo {",
            "  @NullMarked",
            "  public static String foo(String s) {",
            "    return s;",
            "  }",
            "}")
        .addSourceLines(
            "Bar.java",
            "package com.example.thirdparty;",
            "import com.example.jspecify.future.annotations.NullMarked;",
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
            "import com.example.jspecify.future.annotations.NullMarked;",
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
            "  public static Object test3() {",
            "    class Foo {",
            "      private Object o;", // No init checking, since Test$2Foo is unmarked.
            "      public Foo() { }",
            "      @NullMarked",
            "      public String foo() {",
            "        return o.toString();",
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

  @Test
  public void bytecodeNullMarkedMethodLevel() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.example.jspecify.unannotatedpackage.Methods;",
            "public class Test {",
            "  public static void test(Object o) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter",
            "    Methods.foo(null);",
            "    Methods.unchecked(null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void bytecodeNullMarkedMethodLevelOverriding() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.example.jspecify.unannotatedpackage.Methods;",
            "import org.jspecify.nullness.Nullable;",
            "public class Test extends Methods.ExtendMe {",
            "  @Nullable",
            "  // BUG: Diagnostic contains: method returns @Nullable, but superclass method",
            "  public Object foo(@Nullable Object o) { return o; }",
            "  @Nullable",
            "  public Object unchecked(@Nullable Object o) { return o; }",
            "}")
        .doTest();
  }

  @Test
  public void nullUnmarkedPackageLevel() {
    defaultCompilationHelper
        .addSourceLines(
            "package-info.java",
            "@NullUnmarked package com.uber.unmarked;",
            "import com.example.jspecify.future.annotations.NullUnmarked;")
        .addSourceLines(
            "MarkedBecauseAnnotatedFlag.java",
            "package com.uber.marked;",
            "import org.jspecify.nullness.Nullable;",
            "public class MarkedBecauseAnnotatedFlag {",
            "  public static String nullSafeStringOrDefault(@Nullable Object o1, String s) {",
            "    if (o1 != null) {",
            "      return o1.toString();",
            "    }",
            "    return s;",
            "  }",
            "  @Nullable",
            "  public static String nullRet() {",
            "    return null;",
            "  }",
            "  public static String unsafeStringOrDefault(@Nullable Object o1, String s) {",
            "    // BUG: Diagnostic contains: dereferenced expression o1 is @Nullable",
            "    return o1.toString();",
            "  }",
            "}")
        .addSourceLines(
            "UnmarkedBecausePackageDirectAnnotation.java",
            "package com.uber.unmarked;",
            "import com.uber.marked.MarkedBecauseAnnotatedFlag;",
            "import org.jspecify.nullness.Nullable;",
            "public class UnmarkedBecausePackageDirectAnnotation {",
            "  public static String directlyUnsafeStringOrDefault(@Nullable Object o1, String s) {",
            "    // No error: unannotated",
            "    return o1.toString();",
            "  }",
            "  @Nullable",
            "  public static String nullRet() {",
            "    return null;",
            "  }",
            "  public static String indirectlyUnsafeStringOrDefault(@Nullable Object o1, String s) {",
            "    // No error: unannotated",
            "    return (o1 == null ? MarkedBecauseAnnotatedFlag.nullRet() : o1.toString());",
            "  }",
            "}")
        .addSourceLines(
            "MarkedImplicitly.java",
            "package com.uber.unmarked.bar;",
            "// Note: this package is annotated, because packages do not enclose each other for the purposes",
            "// of @NullMarked/@NullUnmarked, see https://jspecify.dev/docs/spec#null-marked-scope",
            "import com.uber.marked.MarkedBecauseAnnotatedFlag;",
            "import com.uber.unmarked.UnmarkedBecausePackageDirectAnnotation;",
            "import org.jspecify.nullness.Nullable;",
            "public class MarkedImplicitly {",
            "  public static String directlyUnsafeStringOrDefault(@Nullable Object o1, String s) {",
            "    // BUG: Diagnostic contains: dereferenced expression o1 is @Nullable",
            "    return o1.toString();",
            "  }",
            "  public static String indirectlyUnsafeStringOrDefault(@Nullable Object o1, String s) {",
            "    // BUG: Diagnostic contains: returning @Nullable expression from method",
            "    return (o1 == null ? MarkedBecauseAnnotatedFlag.nullRet() : o1.toString());",
            "  }",
            "  public static String indirectlyUnsafeStringOrDefaultCallingUnmarked(@Nullable Object o1, String s) {",
            "    // No error: nullRet() is unannotated",
            "    return (o1 == null ? UnmarkedBecausePackageDirectAnnotation.nullRet() : o1.toString());",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullUnmarkedClassLevel() {
    defaultCompilationHelper
        .addSourceLines(
            "package-info.java",
            "@NullMarked package com.example.thirdparty.marked;",
            "import com.example.jspecify.future.annotations.NullMarked;")
        .addSourceLines(
            "Foo.java",
            "package com.uber.foo;",
            "import com.example.jspecify.future.annotations.NullUnmarked;",
            "import org.jspecify.nullness.Nullable;",
            "@NullUnmarked",
            "public class Foo {",
            "  @Nullable",
            "  public static String nullRet() {",
            "    return null;",
            "  }",
            "  public static String takeNonNull(Object o) {",
            "    return o.toString();",
            "  }",
            "  public static String takeNullable(@Nullable Object o) {",
            "    return o.toString();",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.foo.Foo;",
            "public class Test {",
            "  public static Object test(Object o) {",
            "    // No errors, because Foo is @NullUnmarked",
            "    Foo.takeNonNull(null);",
            "    return Foo.nullRet();",
            "  }",
            "  public static String sanity() {",
            "    // BUG: Diagnostic contains: returning @Nullable expression",
            "    return null;",
            "  }",
            "}")
        .addSourceLines(
            "Test2.java", // Note: Safe to have same-name files in recent Error Prone, but breaks EP
                          // 2.4.0
            "package com.example.thirdparty.marked;",
            "import com.uber.foo.Foo;",
            "public class Test2 {",
            "  public static Object test(Object o) {",
            "    // No errors, because Foo is @NullUnmarked",
            "    Foo.takeNonNull(null);",
            "    return Foo.nullRet();",
            "  }",
            "  public static String sanity() {",
            "    // BUG: Diagnostic contains: returning @Nullable expression",
            "    return null;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullUnmarkedClassLevelOuter() {
    defaultCompilationHelper
        .addSourceLines(
            "Bar.java",
            "package com.uber.foo;",
            "import com.example.jspecify.future.annotations.NullUnmarked;",
            "import org.jspecify.nullness.Nullable;",
            "@NullUnmarked",
            "public class Bar {",
            "  public static class Foo {",
            "    @Nullable",
            "    public static String nullRet() {",
            "      return null;",
            "    }",
            "    public static String takeNonNull(Object o) {",
            "      return o.toString();",
            "    }",
            "    public static String takeNullable(@Nullable Object o) {",
            "      // No errors, because Foo is @NullUnmarked",
            "      return o.toString();",
            "    }",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.foo.Bar;",
            "public class Test {",
            "  public static Object test(Object o) {",
            "    // No errors, because Foo is @NullUnmarked",
            "    Bar.Foo.takeNonNull(null);",
            "    return Bar.Foo.nullRet();",
            "  }",
            "  public static String sanity() {",
            "    // BUG: Diagnostic contains: returning @Nullable expression",
            "    return null;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullUnmarkedMarkedClassLevelInner() {
    defaultCompilationHelper
        .addSourceLines(
            "Bar.java",
            "package com.uber.foo;",
            "import com.example.jspecify.future.annotations.NullUnmarked;",
            "import org.jspecify.nullness.Nullable;",
            "public class Bar {",
            "  @NullUnmarked",
            "  public static class Foo {",
            "    @Nullable",
            "    public static String nullRet() {",
            "      return null;",
            "    }",
            "    public static String takeNonNull(Object o) {",
            "      return o.toString();",
            "    }",
            "    public static String takeNullable(@Nullable Object o) {",
            "      // No errors, because Foo is @NullUnmarked",
            "      return o.toString();",
            "    }",
            "  }",
            "  // In marked outer class Bar",
            "  public static String sanity() {",
            "    // BUG: Diagnostic contains: returning @Nullable expression",
            "    return null;",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.foo.Bar;",
            "public class Test {",
            "  public static Object test(Object o) {",
            "    // No errors, because Foo is @NullUnmarked",
            "    Bar.Foo.takeNonNull(null);",
            "    return Bar.Foo.nullRet();",
            "  }",
            "  public static String sanity() {",
            "    // BUG: Diagnostic contains: returning @Nullable expression",
            "    return null;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullUnmarkedClassLevelDeep() {
    defaultCompilationHelper
        .addSourceLines(
            "Bar.java",
            "package com.uber.foo;",
            "import com.example.jspecify.future.annotations.NullMarked;",
            "import com.example.jspecify.future.annotations.NullUnmarked;",
            "import org.jspecify.nullness.Nullable;",
            "public class Bar {",
            "  @NullUnmarked",
            "  public static class Foo {",
            "    @NullMarked",
            "    public static class Deep {",
            "      @Nullable",
            "      public static String nullRet() {",
            "        return null;",
            "      }",
            "      public static String takeNonNull(Object o) {",
            "        return o.toString();",
            "      }",
            "      public static String takeNullable(@Nullable Object o) {",
            "        // BUG: Diagnostic contains: dereferenced expression o is @Nullable",
            "        return o.toString();",
            "      }",
            "    }",
            "    // In unmarked inner class Foo",
            "    public static String sanity() {",
            "      // No errors, because Foo is @NullUnmarked,",
            "      return null;",
            "    }",
            "  }",
            "  // In marked outer class Bar",
            "  public static String sanity() {",
            "    // BUG: Diagnostic contains: returning @Nullable expression",
            "    return null;",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.foo.Bar;",
            "public class Test {",
            "  public static void test(Object o) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'Bar.Foo.Deep.nullRet()'",
            "    Bar.Foo.Deep.takeNonNull(Bar.Foo.Deep.nullRet());",
            "  }",
            "  public static String sanity() {",
            "    // BUG: Diagnostic contains: returning @Nullable expression",
            "    return null;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullUnmarkedMethodLevel() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import com.example.jspecify.future.annotations.NullUnmarked;",
            "import org.jspecify.nullness.Nullable;",
            "public class Foo {",
            "  @NullUnmarked",
            "  @Nullable",
            "  public static String callee(@Nullable Object o) {",
            "    // No error, since this code is unannotated",
            "    return o.toString();",
            "  }",
            "  public static String caller() {",
            "    // No error, since callee is unannotated",
            "    return callee(null);",
            "  }",
            "  public static String sanity() {",
            "    // BUG: Diagnostic contains: returning @Nullable expression",
            "    return null;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullUnmarkedOuterMethodLevelWithLocalClass() {
    defaultCompilationHelper
        .addSourceLines(
            "Bar.java",
            "package com.example.thirdparty;",
            "import com.example.jspecify.future.annotations.NullMarked;",
            "import com.example.jspecify.future.annotations.NullUnmarked;",
            "@NullMarked",
            "public class Bar {",
            "  public static String takeNonNull(Object o) {",
            "    return o.toString();",
            "  }",
            "  @NullUnmarked",
            "  public static Object bar1() {",
            "    class Baz {",
            "      public void baz() {",
            "        // No error, unmarked code",
            "        Bar.takeNonNull(null);",
            "      }",
            "    }",
            "    Baz b = new Baz();",
            "    b.baz();",
            "    return b;",
            "  }",
            "  @NullUnmarked",
            "  public static Object bar2() {",
            "    @NullMarked",
            "    class Baz {",
            "      public void baz() {",
            "        // BUG: Diagnostic contains: passing @Nullable parameter",
            "        Bar.takeNonNull(null);",
            "      }",
            "    }",
            "    Baz b = new Baz();",
            "    b.baz();",
            "    return b;",
            "  }",
            "  @NullUnmarked",
            "  public static Object bar3() {",
            "    class Baz {",
            "      @NullMarked",
            "      public void baz() {",
            "        // BUG: Diagnostic contains: passing @Nullable parameter",
            "        Bar.takeNonNull(null);",
            "      }",
            "    }",
            "    Baz b = new Baz();",
            "    b.baz();",
            "    return b;",
            "  }",
            "  public static String sanity() {",
            "    // BUG: Diagnostic contains: returning @Nullable expression",
            "    return null;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void bytecodeNullUnmarkedMethodLevel() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.example.jspecify.unannotatedpackage.Methods;",
            "public class Test {",
            "  public static void test(Object o) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter",
            "    Methods.Marked.foo(null);",
            "    Methods.Marked.unchecked(null);",
            "  }",
            "}")
        .doTest();
  }
}
