package com.uber.nullaway;

import org.junit.Test;

public class NullAwayAccessPathsTests extends NullAwayTestsBase {

  @Test
  public void testConstantsInAccessPathsNegative() {
    defaultCompilationHelper
        .addSourceLines(
            "NullableContainer.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public interface NullableContainer<K, V> {",
            " @Nullable public V get(K k);",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  public void testSingleStringCheck(NullableContainer<String, Object> c) {",
            "    if (c.get(\"KEY_STR\") != null) {",
            "      c.get(\"KEY_STR\").toString(); // is safe",
            "    }",
            "  }",
            "  public void testSingleIntCheck(NullableContainer<Integer, Object> c) {",
            "    if (c.get(42) != null) {",
            "      c.get(42).toString(); // is safe",
            "    }",
            "  }",
            "  public void testMultipleChecks(NullableContainer<String, NullableContainer<Integer, Object>> c) {",
            "    if (c.get(\"KEY_STR\") != null && c.get(\"KEY_STR\").get(42) != null) {",
            "      c.get(\"KEY_STR\").get(42).toString(); // is safe",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testConstantsInAccessPathsPositive() {
    defaultCompilationHelper
        .addSourceLines(
            "NullableContainer.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public interface NullableContainer<K, V> {",
            " @Nullable public V get(K k);",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  public void testEnhancedFor(NullableContainer<String, NullableContainer<Integer, Object>> c) {",
            "    if (c.get(\"KEY_STR\") != null && c.get(\"KEY_STR\").get(0) != null) {",
            "      // BUG: Diagnostic contains: dereferenced expression c.get(\"KEY_STR\").get(42)",
            "      c.get(\"KEY_STR\").get(42).toString();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testVariablesInAccessPathsNegative() {
    defaultCompilationHelper
        .addSourceLines(
            "NullableContainer.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public interface NullableContainer<K, V> {",
            " @Nullable public V get(K k);",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  private static final int INT_KEY = 42;", // Guaranteed constant!
            "  public void testEnhancedFor(NullableContainer<String, NullableContainer<Integer, Object>> c) {",
            "    if (c.get(\"KEY_STR\") != null && c.get(\"KEY_STR\").get(INT_KEY) != null) {",
            "      c.get(\"KEY_STR\").get(INT_KEY).toString();",
            "      c.get(\"KEY_STR\").get(Test.INT_KEY).toString();",
            "      c.get(\"KEY_STR\").get(42).toString();", // Extra magic!
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testVariablesInAccessPathsPositive() {
    defaultCompilationHelper
        .addSourceLines(
            "NullableContainer.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public interface NullableContainer<K, V> {",
            " @Nullable public V get(K k);",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  private Integer intKey = 42;", // No guarantee it's a constant
            "  public void testEnhancedFor(NullableContainer<String, NullableContainer<Integer, Object>> c) {",
            "    if (c.get(\"KEY_STR\") != null && c.get(\"KEY_STR\").get(this.intKey) != null) {",
            "      // BUG: Diagnostic contains: dereferenced expression c.get(\"KEY_STR\").get",
            "      c.get(\"KEY_STR\").get(this.intKey).toString();",
            "    }",
            "  }",
            "}")
        .doTest();
  }
}
