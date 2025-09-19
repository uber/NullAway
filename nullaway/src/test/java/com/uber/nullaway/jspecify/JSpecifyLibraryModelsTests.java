package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import java.util.Arrays;
import org.junit.Test;

public class JSpecifyLibraryModelsTests extends NullAwayTestsBase {

  @Test
  public void atomicReferenceGet() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.*;",
            "import java.util.concurrent.atomic.AtomicReference;",
            "@NullMarked",
            "class Test {",
            "  void testNegative() {",
            "    AtomicReference<Integer> x = new AtomicReference<>(Integer.valueOf(3));",
            "    x.get().hashCode();",
            "  }",
            "  void testPositive() {",
            "    AtomicReference<@Nullable Integer> x = new AtomicReference<>(Integer.valueOf(3));",
            "    // BUG: Diagnostic contains: dereferenced expression x.get() is @Nullable",
            "    x.get().hashCode();",
            "  }",
            "}")
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        Arrays.asList(
            "-XepOpt:NullAway:OnlyNullMarked=true", "-XepOpt:NullAway:JSpecifyMode=true"));
  }
}
