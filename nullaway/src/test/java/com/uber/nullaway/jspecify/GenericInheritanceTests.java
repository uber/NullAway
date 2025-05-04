package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import java.util.Arrays;
import org.junit.Test;

public class GenericInheritanceTests extends NullAwayTestsBase {

  @Test
  public void nullableTypeArgInExtends() {
    makeHelper()
        .addSourceLines(
            "Foo.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "class Foo {",
            "  interface Supplier<T extends @Nullable Object> {",
            "    T get();",
            "  }",
            "  interface SubSupplier<T2 extends @Nullable Object> extends Supplier<@Nullable T2> {}",
            "  static void helper(SubSupplier<Foo> sup) {",
            "  }",
            "  static void main() {",
            "    helper(() -> null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullableTypeArgInExtendsMulti() {
    makeHelper()
        .addSourceLines(
            "Example.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "class Example {",
            "  /** Declaring interface with *two* type parameters. */",
            "  interface BiSupplier<",
            "      K extends @Nullable Object,",
            "      V extends @Nullable Object> {",
            "    K key();",
            "  }",
            "",
            "  /**",
            "   * Sub‑interface swaps the order of the type variables *and*",
            "   * annotates only the first one with {@code @Nullable}.",
            "   *",
            "   *   - Its first actual argument is {@code @Nullable V2}",
            "   *   - Its second actual argument is {@code K2}",
            "   */",
            "  interface FlippedSupplier<",
            "      K2 extends @Nullable Object,",
            "      V2 extends @Nullable Object>",
            "      extends BiSupplier<@Nullable V2, K2> {}",
            "  static void helper(FlippedSupplier<String, String> sup) {}",
            "  static void main() {",
            "    helper(() -> null);",
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
