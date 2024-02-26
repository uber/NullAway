package com.uber.nullaway;

import org.junit.Test;

public class NullAwayJava8Tests extends NullAwayTestsBase {
  @Test
  public void java8PositiveCases() {
    defaultCompilationHelper.addSourceFile("NullAwayJava8PositiveCases.java").doTest();
  }

  @Test
  public void java8NegativeCases() {
    defaultCompilationHelper.addSourceFile("NullAwayJava8NegativeCases.java").doTest();
  }

  @Test
  public void functionalMethodSuperInterface() {
    defaultCompilationHelper.addSourceFile("NullAwaySuperFunctionalInterface.java").doTest();
  }

  @Test
  public void functionalMethodOverrideSuperInterface() {
    defaultCompilationHelper.addSourceFile("NullAwayOverrideFunctionalInterfaces.java").doTest();
  }

  @Test
  public void methodReferenceOnNullableVariable() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  public static boolean test(@Nullable String text, java.util.Set<String> s) {",
            "    // BUG: Diagnostic contains: dereferenced expression text is @Nullable",
            "    return s.stream().anyMatch(text::contains);",
            "  }",
            "}")
        .doTest();
  }
}
