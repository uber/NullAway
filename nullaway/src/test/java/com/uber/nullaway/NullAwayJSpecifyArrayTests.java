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
            "    // BUG: Diagnostic contains: dereferenced expression fizz is @Nullable",
            "    int bar = fizz.length;",
            "  }",
            "  static void bar() {",
            "    // BUG: Diagnostic contains: dereferenced expression fizz is @Nullable",
            "    int bar = fizz[0];",
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
            "  static Object foo = new Object();",
            "  static void foo() {",
            "      // BUG: Diagnostic contains: dereferenced expression fizz[0] is @Nullable",
            "      int bar = fizz[0].length();",
            "      // OK: valid dereference since only elements of the array can be null",
            "      foo = fizz.length;",
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
            "  void m( @Nullable Integer [] foo) {",
            "      // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field",
            "      fizz = foo[0];",
            "      // OK: valid assignment since only elements can be null",
            "      fizz = foo;",
            "  }",
            "}")
        .doTest();
  }

  /**
   * Currently in JSpecify mode, JSpecify syntax only applies to type-use annotations. Declaration
   * annotations preserve their existing behavior, with annotations being treated on the top-level
   * type. We will very likely revisit this design in the future.
   */
  @Test
  public void arrayDeclarationAnnotation() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  static @Nullable String [] fizz = {\"1\"};",
            "  static Object o1 = new Object();",
            "  static void foo() {",
            "      // This should not report an error while using JSpecify type-use annotation",
            "      // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field",
            "      o1 = fizz;",
            "      // This should not report an error while using JSpecify type-use annotation",
            "      // BUG: Diagnostic contains: dereferenced expression fizz is @Nullable",
            "      o1 = fizz.length;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void arrayContentsAndTopLevelAnnotation() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static @Nullable String [] fizz = {\"1\"};",
            "  static Object foo = new Object();",
            "  static void foo() {",
            "     if (fizz != null) {",
            "       String s = fizz[0];",
            "      // TODO: This should report due to dereference of @Nullable s",
            "       int l = s.length();",
            "     }",
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
