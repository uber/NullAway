package com.uber.nullaway;

import org.junit.Test;

/**
 * Tests for cases where lambdas or anonymous class methods are invoked nearly synchronously, so it
 * is reasonable to propagat more nullability information to their bodies.
 */
public class SyncLambdasTests extends NullAwayTestsBase {

  @Test
  public void forEachOnMap() {
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

  @Test
  public void forEachOnHashMap() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.HashMap;",
            "import org.jspecify.annotations.Nullable;",
            "public class Test {",
            "    private @Nullable HashMap<Object, Object> target;",
            "    private @Nullable HashMap<Object, Object> resolved;",
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

  @Test
  public void otherForEach() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.HashMap;",
            "import java.util.function.BiConsumer;",
            "import org.jspecify.annotations.Nullable;",
            "public class Test {",
            "    private @Nullable MyMap<Object, Object> target;",
            "    private @Nullable MyMap<Object, Object> resolved;",
            "    static class MyMap<K,V> {",
            "        public void forEach(BiConsumer<Object, Object> consumer) {}",
            "        public void put(Object key, Object value) {}",
            "    }",
            "    public void initialize() {",
            "        if (this.target == null) {",
            "            throw new IllegalArgumentException();",
            "        }",
            "        this.resolved = new MyMap<>();",
            "        this.target.forEach((key, value) -> {",
            "            // BUG: Diagnostic contains: dereferenced expression this.resolved is @Nullable",
            "            this.resolved.put(key, value);",
            "        });",
            "    }",
            "}")
        .doTest();
  }
}
