package com.uber.nullaway;

import org.junit.Test;

/**
 * Tests for cases where lambdas or anonymous class methods are invoked nearly synchronously, so it
 * is reasonable to propagat more nullability information to their bodies.
 */
public class SyncLambdasTests extends NullAwayTestsBase {

  @Test
  public void testForEach() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Map;",
            "import java.util.HashMap;",
            "import org.jspecify.annotations.Nullable;",
            "public class Test {",
            "    private @Nullable Map<Object, Object> target;",
            "    private @Nullable Map<Object, Object> resolved;",
            "    public void initialize() {",
            "        if (this.target == null) {",
            "            throw new IllegalArgumentException();",
            "        }",
            "        this.resolved = new HashMap<>();",
            "        this.target.forEach((key, value) -> {",
            "            this.resolved.put(key, value);",
            "        });",
            "    }",
            "}")
        .doTest();
  }
}
