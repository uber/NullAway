package com.uber.nullaway;

import com.google.errorprone.CompilationTestHelper;
import java.util.Arrays;
import org.junit.Ignore;
import org.junit.Test;

public class NullAwayJSpecifyArrayTests extends NullAwayTestsBase {

  @Test
  public void ArrayTypeAnnotationDereference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static @Nullable Integer [] fizz = {1};",
            "  static void foo() {",
            "  // Ok since @Nullable annotations on type are ignored currently.",
            "  // however, this should report an error eventually.",
            "  int bar = fizz.length;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void ArrayTypeAnnotationAssignment() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  Object foo = new Object();",
            "  void m( @Nullable Integer [] bar) {",
            "  // OK: @Nullable annotations on type are ignored currently.",
            "  // however, this should report an error eventually.",
            "  foo = bar;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void ArrayElementAnnotationDereference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static String @Nullable [] fizz = {\"1\"};",
            "  static void foo() {",
            "    // BUG: Diagnostic contains: dereferenced expression fizz is @Nullable",
            "    int bar = fizz[0].length();",
            "  }",
            "}")
        .doTest();
  }

  @Ignore("We do not support annotations on array currently")
  @Test
  public void ArrayElementAnnotationAssignment() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  Object fizz = new Object();",
            "  void m( @Nullable Integer [] foo) {",
            "    // BUG: Diagnostic contains: dereferenced expression arr[0] is @Nullable",
            "    int bar = foo[0];",
            "    //valid assignment",
            "    fizz = foo;",
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
