package com.uber.nullaway.jdk17;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAway;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NullAwayInstanceOfBindingTests {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper defaultCompilationHelper;

  @Before
  public void setup() {
    defaultCompilationHelper =
        CompilationTestHelper.newInstance(NullAway.class, getClass())
            .setArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber"));
  }

  @Test
  public void testInstanceOfBinding() {
    defaultCompilationHelper
        .addSourceLines(
            "InstanceOfBinding.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class InstanceOfBinding {",
            "  public void testInstanceOfBinding(@Nullable Object o) {",
            "    if (o instanceof String s) {",
            "      s.toString();",
            "      o.toString();",
            "    }",
            "    // BUG: Diagnostic contains: dereferenced expression o",
            "    o.toString();",
            "  }",
            "}")
        .doTest();
  }
}
