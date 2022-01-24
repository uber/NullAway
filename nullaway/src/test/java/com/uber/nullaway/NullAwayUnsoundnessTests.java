package com.uber.nullaway;

import com.google.errorprone.CompilationTestHelper;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;

/** Unit tests showing cases where NullAway is unsound. Useful for documentation purposes. */
public class NullAwayUnsoundnessTests extends NullAwayTestsBase {

  @Before
  @Override
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
  public void mapReassignUnsound() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.*;",
            "public class Test {",
            "  public void mapReassignUnsound(Map m, Object o) {",
            "    if (m.containsKey(o)) {",
            "      // NullAway is currently unsound for this case.  It ignores",
            "      // the re-assignment of m and still assumes m.get(o) is non-null",
            "      // on the subsequent line.",
            "      m = new HashMap();",
            "      m.get(o).toString();",
            "    }",
            "  }",
            "}")
        .doTest();
  }
}
