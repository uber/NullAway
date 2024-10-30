package com.uber.nullaway;

import org.junit.Test;

public class Java8Tests extends NullAwayTestsBase {
  @Test
  public void java8PositiveCases() {
    defaultCompilationHelper.addSourceFile("testdata/NullAwayJava8PositiveCases.java").doTest();
  }

  @Test
  public void java8NegativeCases() {
    defaultCompilationHelper.addSourceFile("testdata/NullAwayJava8NegativeCases.java").doTest();
  }

  @Test
  public void functionalMethodSuperInterface() {
    defaultCompilationHelper
        .addSourceFile("testdata/NullAwaySuperFunctionalInterface.java")
        .doTest();
  }

  @Test
  public void functionalMethodOverrideSuperInterface() {
    defaultCompilationHelper
        .addSourceFile("testdata/NullAwayOverrideFunctionalInterfaces.java")
        .doTest();
  }

  @Test
  public void methodReferenceOnNullableVariable() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import java.util.Arrays;",
            "import java.util.stream.Collectors;",
            "class Test {",
            "  public static boolean testPositive(@Nullable String text, java.util.Set<String> s) {",
            "    // BUG: Diagnostic contains: dereferenced expression text is @Nullable",
            "    return s.stream().anyMatch(text::contains);",
            "  }",
            "  public static String[] testNegative(Object[] arr) {",
            "    // also tests we don't crash when the qualifier expression of a method reference is a type",
            "    return Arrays.stream(arr).map(Object::toString).toArray(String[]::new);",
            "  }",
            "  public static <T> boolean testNegativeWithTypeVariable(T[] arr) {",
            "    return Arrays.stream(arr).map(T::toString).collect(Collectors.toList()).isEmpty();",
            "  }",
            "}")
        .doTest();
  }

  /** test that we can properly read an explicit type-use annotation on a lambda parameter */
  @Test
  public void testNullableLambdaParamTypeUse() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "    @FunctionalInterface",
            "    interface NullableParamFunctionTypeUse<T, U> {",
            "      U takeVal(@Nullable T x);",
            "    }",
            "    static void testParamTypeUse() {",
            "      NullableParamFunctionTypeUse n3 = (@Nullable Object x) -> (x == null) ? \"null\" : x.toString();",
            "      NullableParamFunctionTypeUse n4 = (x) -> (x == null) ? \"null\" : x.toString();",
            "    }",
            "}")
        .doTest();
  }
}
