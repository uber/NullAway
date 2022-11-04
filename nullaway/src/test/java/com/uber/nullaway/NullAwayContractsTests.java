package com.uber.nullaway;

import java.util.Arrays;
import org.junit.Test;

public class NullAwayContractsTests extends NullAwayTestsBase {

  @Test
  public void checkContractPositiveCases() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:CheckContracts=true"))
        .addSourceFile("CheckContractPositiveCases.java")
        .doTest();
  }

  @Test
  public void checkContractNegativeCases() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:CheckContracts=true"))
        .addSourceFile("CheckContractNegativeCases.java")
        .doTest();
  }

  @Test
  public void basicContractAnnotation() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "NullnessChecker.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import org.jetbrains.annotations.Contract;",
            "public class NullnessChecker {",
            "  @Contract(\"_, null -> true\")",
            "  static boolean isNull(boolean flag, @Nullable Object o) { return o == null; }",
            "  @Contract(\"null -> false\")",
            "  static boolean isNonNull(@Nullable Object o) { return o != null; }",
            "  @Contract(\"null -> fail\")",
            "  static void assertNonNull(@Nullable Object o) { if (o != null) throw new Error(); }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  String test1(@Nullable Object o) {",
            "    return NullnessChecker.isNonNull(o) ? o.toString() : \"null\";",
            "  }",
            "  String test2(@Nullable Object o) {",
            "    return NullnessChecker.isNull(false, o) ? \"null\" : o.toString();",
            "  }",
            "  String test3(@Nullable Object o) {",
            "    NullnessChecker.assertNonNull(o);",
            "    return o.toString();",
            "  }",
            "  String test4(java.util.Map<String,Object> m) {",
            "    NullnessChecker.assertNonNull(m.get(\"foo\"));",
            "    return m.get(\"foo\").toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void impliesNonNullContractAnnotation() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "NullnessChecker.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import org.jetbrains.annotations.Contract;",
            "public class NullnessChecker {",
            "  @Contract(\"!null -> !null\")",
            "  static @Nullable Object noOp(@Nullable Object o) { return o; }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  String test1(@Nullable Object o1) {",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    return NullnessChecker.noOp(o1).toString();",
            "  }",
            "  String test2(Object o2) {",
            "    return NullnessChecker.noOp(o2).toString();",
            "  }",
            "  Object test3(@Nullable Object o1) {",
            "    // BUG: Diagnostic contains: returning @Nullable expression",
            "    return NullnessChecker.noOp(o1);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void malformedContractAnnotations() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import org.jetbrains.annotations.Contract;",
            "class Test {",
            "  @Contract(\"!null -> -> !null\")",
            "  static @Nullable Object foo(@Nullable Object o) { return o; }",
            "  @Contract(\"!null -> !null\")",
            "  static @Nullable Object bar(@Nullable Object o, String s) { return o; }",
            "  @Contract(\"jabberwocky -> !null\")",
            "  static @Nullable Object baz(@Nullable Object o) { return o; }",
            // We don't care as long as nobody calls the method:
            "  @Contract(\"!null -> -> !null\")",
            "  static @Nullable Object dontcare(@Nullable Object o) { return o; }",
            "  static Object test1() {",
            "    // BUG: Diagnostic contains: Invalid @Contract annotation",
            "    return foo(null);",
            "  }",
            "  static Object test2() {",
            "    // BUG: Diagnostic contains: Invalid @Contract annotation",
            "    return bar(null, \"\");",
            "  }",
            "  static Object test3() {",
            "    // BUG: Diagnostic contains: Invalid @Contract annotation",
            "    return baz(null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void contractNonVarArg() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "NullnessChecker.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import org.jetbrains.annotations.Contract;",
            "public class NullnessChecker {",
            "  @Contract(\"null -> fail\")",
            "  static void assertNonNull(@Nullable Object o) { if (o != null) throw new Error(); }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  void test(java.util.function.Function<Object, Object> fun) {",
            "    NullnessChecker.assertNonNull(fun.apply(new Object()));",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void contractPureOnlyIgnored() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "PureLibrary.java",
            "package com.example.library;",
            "import org.jetbrains.annotations.Contract;",
            "public class PureLibrary {",
            "  @Contract(",
            "    pure = true",
            "  )",
            "  public static String pi() {",
            "    // Meh, close enough...",
            "    return Double.toString(3.14);",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.example.library.PureLibrary;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  String piValue() {",
            "    String pi = PureLibrary.pi();",
            "    // Note: we must trigger dataflow to ensure that",
            "    // ContractHandler.onDataflowVisitMethodInvocation is called",
            "    if (pi != null) {",
            "       return pi;",
            "    }",
            "    return Integer.toString(3);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void customContractAnnotation() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:CustomContractAnnotations=com.example.library.CustomContract",
                "-XepOpt:NullAway:CheckContracts=true"))
        .addSourceLines(
            "CustomContract.java",
            "package com.example.library;",
            "import static java.lang.annotation.RetentionPolicy.CLASS;",
            "import java.lang.annotation.Retention;",
            "@Retention(CLASS)",
            "public @interface CustomContract {",
            "  String value();",
            "}")
        .addSourceLines(
            "NullnessChecker.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.example.library.CustomContract;",
            "public class NullnessChecker {",
            "  @CustomContract(\"_, !null -> !null\")",
            "  @Nullable",
            "  static Object bad(Object a, @Nullable Object b) {",
            "    if (a.hashCode() % 2 == 0) {",
            "      // BUG: Diagnostic contains: Method bad has @Contract",
            "      return null;",
            "    }",
            "    return new Object();",
            "  }",
            "",
            "  @CustomContract(\"_, !null -> !null\")",
            "  @Nullable",
            "  static Object good(Object a, @Nullable Object b) {",
            "    if (a.hashCode() % 2 == 0) {",
            "      return b;",
            "    }",
            "    return new Object();",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  String test1() {",
            "    return NullnessChecker.good(\"bar\", \"foo\").toString();",
            "  }",
            "  String test2() {",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    return NullnessChecker.good(\"bar\", null).toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void contractDeclaringBothNotNull() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "NullnessChecker.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import org.jetbrains.annotations.Contract;",
            "public class NullnessChecker {",
            "  @Contract(\"null, _ -> false; _, null -> false\")",
            "  static boolean bothNotNull(@Nullable Object o1, @Nullable Object o2) {",
            "    // null, _ -> false",
            "    if (o1 == null) {",
            "      return false;",
            "    }",
            "    // _, null -> false",
            "    if (o2 == null) {",
            "      return false;",
            "    }",
            "    // Function cannot declare a contract for true",
            "    return System.currentTimeMillis() % 100 == 0;",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  String test1(@Nullable Object o) {",
            "    return NullnessChecker.bothNotNull(\"\", o)",
            "      ? o.toString()",
            "      // BUG: Diagnostic contains: dereferenced expression",
            "      : o.toString();",
            "  }",
            "  String test2(@Nullable Object o) {",
            "    return NullnessChecker.bothNotNull(o, \"\")",
            "      ? o.toString()",
            "      // BUG: Diagnostic contains: dereferenced expression",
            "      : o.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void contractDeclaringEitherNull() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "NullnessChecker.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import org.jetbrains.annotations.Contract;",
            "public class NullnessChecker {",
            "  @Contract(\"null, _ -> true; _, null -> true\")",
            "  static boolean eitherIsNullOrRandom(@Nullable Object o1, @Nullable Object o2) {",
            "    // null, _ -> true",
            "    if (o1 == null) {",
            "      return true;",
            "    }",
            "    // _, null -> true",
            "    if (o2 == null) {",
            "      return true;",
            "    }",
            "    // Function cannot declare a contract for false",
            "    return System.currentTimeMillis() % 100 == 0;",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  String test1(@Nullable Object o) {",
            "    return NullnessChecker.eitherIsNullOrRandom(\"\", o)",
            "      // BUG: Diagnostic contains: dereferenced expression",
            "      ? o.toString()",
            "      : o.toString();",
            "  }",
            "  String test2(@Nullable Object o) {",
            "    return NullnessChecker.eitherIsNullOrRandom(o, \"\")",
            "      // BUG: Diagnostic contains: dereferenced expression",
            "      ? o.toString()",
            "      : o.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void contractDeclaringNullOrRandomFalse() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "NullnessChecker.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import org.jetbrains.annotations.Contract;",
            "public class NullnessChecker {",
            "  @Contract(\"null -> false\")",
            "  static boolean isHashCodeZero(@Nullable Object o) {",
            "    // null -> false",
            "    if (o == null) {",
            "      return false;",
            "    }",
            "    // Function cannot declare a contract for true",
            "    return o.hashCode() == 0;",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  String test1(@Nullable Object o) {",
            "    return NullnessChecker.isHashCodeZero(o)",
            "      ? o.toString()",
            "      // BUG: Diagnostic contains: dereferenced expression",
            "      : o.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void contractUnreachablePath() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "NullnessChecker.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import org.jetbrains.annotations.Contract;",
            "public class NullnessChecker {",
            "  @Contract(\"!null -> false\")",
            "  static boolean isNull(@Nullable Object o) {",
            "    // !null -> false",
            "    if (o != null) {",
            "      return false;",
            "    }",
            "    return true;",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  String test(Object required) {",
            "    return NullnessChecker.isNull(required)",
            "      ? required.toString()",
            "      : required.toString();",
            "  }",
            "}")
        .doTest();
  }
}
