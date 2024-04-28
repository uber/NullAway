package com.uber.nullaway;

import org.junit.Test;

/**
 * Tests for cases where lambdas or anonymous class methods are invoked nearly synchronously, so it
 * is reasonable to propagate more nullability information to their bodies.
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
            "            // no error here as info gets propagated",
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
            "            // no error here as info gets propagated",
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
            "    private @Nullable Object resolved;",
            "    static class MyMap<K,V> {",
            "        public void forEach(BiConsumer<Object, Object> consumer) {}",
            "        public void put(Object key, Object value) {}",
            "    }",
            "    public void initialize() {",
            "        if (this.target == null) {",
            "            throw new IllegalArgumentException();",
            "        }",
            "        this.resolved = new Object();",
            "        this.target.forEach((key, value) -> {",
            "            // error since this is a custom type, not inheriting from java.util.Map",
            "            // BUG: Diagnostic contains: dereferenced expression this.resolved is @Nullable",
            "            System.out.println(this.resolved.toString());",
            "        });",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void forEachOnIterable() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.List;",
            "import java.util.ArrayList;",
            "import org.jspecify.annotations.Nullable;",
            "public class Test {",
            "    private @Nullable Object f;",
            "    public void test1() {",
            "        if (this.f == null) {",
            "            throw new IllegalArgumentException();",
            "        }",
            "        List<Object> l = new ArrayList<>();",
            "        l.forEach(v -> System.out.println(v + this.f.toString()));",
            "        Iterable<Object> l2 = l;",
            "        l2.forEach(v -> System.out.println(v + this.f.toString()));",
            "        this.f = null;",
            "        // BUG: Diagnostic contains: dereferenced expression this.f is @Nullable",
            "        l2.forEach(v -> System.out.println(v + this.f.toString()));",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void removeIf() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.List;",
            "import java.util.ArrayList;",
            "import org.jspecify.annotations.Nullable;",
            "public class Test {",
            "    private @Nullable Object f;",
            "    public void test1() {",
            "        if (this.f == null) {",
            "            throw new IllegalArgumentException();",
            "        }",
            "        List<Object> l = new ArrayList<>();",
            "        l.removeIf(v -> this.f.toString().equals(v.toString()));",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void streamMethods() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.List;",
            "import java.util.ArrayList;",
            "import org.jspecify.annotations.Nullable;",
            "public class Test {",
            "    private @Nullable Object f;",
            "    public void test1() {",
            "        if (this.f == null) {",
            "            throw new IllegalArgumentException();",
            "        }",
            "        List<Object> l = new ArrayList<>();",
            "        // this.f being non-null gets propagated to all callback lambdas",
            "        l.stream().filter(v -> this.f.toString().equals(v.toString()))",
            "         .map(v -> this.f.toString())",
            "         .forEach(v -> System.out.println(this.f.hashCode() + v.toString()));",
            "    }",
            "}")
        .doTest();
  }
}
