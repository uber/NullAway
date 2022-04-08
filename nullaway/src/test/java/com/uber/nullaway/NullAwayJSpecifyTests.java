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
