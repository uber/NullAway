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

  @Test
  public void testField() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Foo {",
            " @Nullable public Object o;",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.ArrayList;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  public String testFieldCheck(Foo foo) {",
            "    if (foo.o == null) {",
            "      foo.o = new Object();",
            "    }",
            "    return foo.o.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testArrayListField() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import java.util.List;",
            "import javax.annotation.Nullable;",
            "public class Foo {",
            " @Nullable public List<Object> list;",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.ArrayList;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  public Object testFieldCheck(Foo foo) {",
            "    if (foo.list == null) {",
            "      foo.list = new ArrayList<Object>();",
            "    }",
            "    return foo.list.get(0);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testFieldWithoutValidAccessPath() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Foo {",
            " @Nullable public Object o;",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.ArrayList;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  public String testFieldCheck(Foo foo) {",
            "    if (foo.o == null) {",
            "      (new Foo()).o = new Object();",
            "    }",
            "    // BUG: Diagnostic contains: dereferenced expression foo.o is @Nullable",
            "    return foo.o.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testFieldWithoutValidAccessPathLongChain() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Foo {",
            " public Foo nonNull;",
            " public Foo() {",
            "   nonNull = this;",
            " }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.ArrayList;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  public String testFieldCheck(Foo foo) {",
            "    // Just checking that NullAway doesn't crash on a long but ultimately rootless access path",
            "    return (new Foo()).nonNull.nonNull.toString().toLowerCase();",
            "  }",
            "}")
        .doTest();
  }
}
