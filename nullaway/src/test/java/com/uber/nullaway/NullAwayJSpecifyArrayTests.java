package com.uber.nullaway;

import com.google.errorprone.CompilationTestHelper;
import java.util.Arrays;
import org.junit.Test;

public class NullAwayJSpecifyArrayTests extends NullAwayTestsBase {

  @Test
  public void arrayTopLevelAnnotationDereference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static Integer @Nullable [] fizz = {1};",
            "  static void foo() {",
            "     // BUG: Diagnostic contains: dereferenced expression fizz is @Nullable",
            "     int bar = fizz.length;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void arrayTopLevelAnnotationAssignment() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  Object foo = new Object();",
            "  void m( Integer @Nullable [] bar) {",
            "      // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field",
            "      foo = bar;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void arrayContentsAnnotationDereference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static @Nullable String [] fizz = {\"1\"};",
            "  static void foo() {",
            "      // TODO: This should report an error due to dereference of @Nullable fizz[0]",
            "      int bar = fizz[0].length();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void arrayContentsAnnotationAssignment() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  Object fizz = new Object();",
            "  Object bar = new Object();",
            "  void m( @Nullable Integer [] foo) {",
            "      // TODO: This should report an error due to assignment of @Nullable foo[0] to @NonNull field",
            "      fizz = foo[0];",
            "      // OK: valid assignment since only elements can be null",
            "      bar = foo;",
            "  }",
            "}")
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        Arrays.asList(
            "-XepOpt:NullAway:AnnotatedPackages=com.uber", "-XepOpt:NullAway:JSpecifyMode=true"));
  }
}
