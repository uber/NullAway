package com.uber.nullaway;

import java.util.Arrays;
import org.junit.Test;

public class SuppressionNameAliasesTests extends NullAwayTestsBase {

  @Test
  public void nullnessSuppressesNullAway() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  @SuppressWarnings(\"nullness\")",
            "  void foo(@Nullable Object o) {",
            "    o.getClass();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void additionalSuppressionNamesTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SuppressionNameAliases=Foo,Bar"))
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            class Test {
              @SuppressWarnings("Foo")
              void foo(@Nullable Object o) {
                o.getClass();
              }
              @SuppressWarnings("Bar")
              void bar(@Nullable Object o) {
                o.getClass();
              }
              @SuppressWarnings("Baz")
              void baz(@Nullable Object o) {
                // BUG: Diagnostic contains: dereferenced expression o is @Nullable
                o.getClass();
              }
            }
            """)
        .doTest();
  }
}
