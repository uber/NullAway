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

  @Test
  public void testFinalFieldNullabilityPropagatesToInnerContexts() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Foo {",
            " @Nullable public final Bar bar;",
            " @Nullable public Bar mutableBar;",
            " public Foo(@Nullable Bar bar) {",
            "   this.bar = bar;",
            "   this.mutableBar = bar;",
            " }",
            "}")
        .addSourceLines(
            "Bar.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Bar {",
            " @Nullable public final Foo foo;",
            " @Nullable public Foo mutableFoo;",
            " public Bar(@Nullable Foo foo) {",
            "   this.foo = foo;",
            "   this.mutableFoo = foo;",
            " }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.google.common.base.Preconditions;",
            "import java.util.ArrayList;",
            "import java.util.function.Predicate;",
            "import java.util.function.Function;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  @Nullable private final Foo foo;",
            "  @Nullable private Foo mutableFoo;",
            "  public Test(@Nullable Foo foo) {",
            "    this.foo = foo;",
            "    this.mutableFoo = foo;",
            "  }",
            "  public Predicate<String> testReadFinalFromLambdaNoCheck() {",
            "    // BUG: Diagnostic contains: dereferenced expression foo is @Nullable",
            "    return (s -> foo.toString().equals(s));",
            "  }",
            "  public Predicate<String> testReadFinalFromLambdaAfterCheck() {",
            "    Preconditions.checkNotNull(foo);",
            "    // safe!",
            "    return (s -> foo.toString().equals(s));",
            "  }",
            "  public Predicate<String> testReadMutableFromLambdaAfterCheck() {",
            "    Preconditions.checkNotNull(mutableFoo);",
            "    // BUG: Diagnostic contains: dereferenced expression mutableFoo is @Nullable",
            "    return (s -> mutableFoo.toString().equals(s));",
            "  }",
            "  public Function<String, Predicate<String>> testReadFinalFromLambdaAfterCheckDeepContext() {",
            "    Preconditions.checkNotNull(foo);",
            "    // safe!",
            "    return (s1 -> (s2 -> foo.toString().equals(s1 + s2)));",
            "  }",
            "  public Predicate<String> testReadFinalFromLambdaAfterCheckDeepAP() {",
            "    Preconditions.checkNotNull(foo);",
            "    Preconditions.checkNotNull(foo.bar);",
            "    Preconditions.checkNotNull(foo.bar.foo);",
            "    // safe!",
            "    return (s -> foo.bar.foo.toString().equals(s));",
            "  }",
            "  public Predicate<String> testReadFinalFromLambdaAfterCheckDeepAPIncomplete() {",
            "    Preconditions.checkNotNull(foo);",
            "    Preconditions.checkNotNull(foo.bar);",
            "    // BUG: Diagnostic contains: dereferenced expression foo.bar.foo is @Nullable",
            "    return (s -> foo.bar.foo.toString().equals(s));",
            "  }",
            "  public Predicate<String> testReadMutableFromLambdaAfterCheckDeepAP1() {",
            "    Preconditions.checkNotNull(foo);",
            "    Preconditions.checkNotNull(foo.mutableBar);",
            "    Preconditions.checkNotNull(foo.mutableBar.foo);",
            "    // BUG: Diagnostic contains: dereferenced expression foo.mutableBar is @Nullable",
            "    return (s -> foo.mutableBar.foo.toString().equals(s));",
            "  }",
            "  public Predicate<String> testReadMutableFromLambdaAfterCheckDeepAP2() {",
            "    Preconditions.checkNotNull(foo);",
            "    Preconditions.checkNotNull(foo.bar);",
            "    Preconditions.checkNotNull(foo.bar.mutableFoo);",
            "    // BUG: Diagnostic contains: dereferenced expression foo.bar.mutableFoo is @Nullable",
            "    return (s -> foo.bar.mutableFoo.toString().equals(s));",
            "  }",
            "  public boolean testReadFinalFromLambdaAfterCheckLocalClass(String s) {",
            "    Preconditions.checkNotNull(foo);",
            "    // safe!",
            "    class Inner {",
            "       public Inner() { }",
            "       public boolean doTest(String s) { return foo.toString().equals(s); }",
            "    }",
            "    return (new Inner()).doTest(s);",
            "  }",
            "  public boolean testReadFinalFromLambdaCheckBeforeUseLocalClass(String s) {",
            "    class Inner {",
            "       public Inner() { }",
            "       // At the time of declaring this, foo is not known to be non-null!",
            "       public boolean doTest(String s)  {",
            "         // BUG: Diagnostic contains: dereferenced expression foo is @Nullable",
            "         return foo.toString().equals(s);",
            "       }",
            "    }",
            "    // Technically safe, but hard to reason about: needs to be aware of *when* doTest() can be",
            "    // called which is generally _beyond_ NullAway.",
            "    Preconditions.checkNotNull(foo);",
            "    return (new Inner()).doTest(s);",
            "  }",
            "  public boolean testReadMutableFromLambdaAfterCheckLocalClass(String s) {",
            "    Preconditions.checkNotNull(mutableFoo);",
            "    class Inner {",
            "       public Inner() { }",
            "       public boolean doTest(String s) {",
            "         // BUG: Diagnostic contains: dereferenced expression mutableFoo is @Nullable",
            "         return mutableFoo.toString().equals(s);",
            "       }",
            "       public boolean doTestSafe(String s) {",
            "         Preconditions.checkNotNull(mutableFoo);",
            "         return mutableFoo.toString().equals(s);",
            "       }",
            "    }",
            "    if (s.length() % 2 == 0) {",
            "      return (new Inner()).doTest(s);",
            "    } else {",
            "       // safe!",
            "      return (new Inner()).doTestSafe(s);",
            "    }",
            "  }",
            "  public boolean testReadFinalFromLambdaAfterCheckLocalClassWithNameCollision(String s) {",
            "    Preconditions.checkNotNull(foo);",
            "    class Inner {",
            "       @Nullable private Foo foo;",
            "       public Inner() { this.foo = null; }",
            "       public boolean doTest(String s)  {",
            "         // BUG: Diagnostic contains: dereferenced expression foo is @Nullable",
            "         return foo.toString().equals(s);",
            "       }",
            "       public boolean doTestSafe(String s)  {",
            "         // TODO: Technically safe, but it would need recognizing Test.this.[...] as the same AP as",
            "         //       that from the closure.",
            "         // BUG: Diagnostic contains: dereferenced expression Test.this.foo is @Nullable",
            "         return Test.this.foo.toString().equals(s);",
            "       }",
            "    }",
            "    if (s.length() % 2 == 0) {",
            "      return (new Inner()).doTest(s);",
            "    } else {",
            "      return (new Inner()).doTestSafe(s);",
            "    }",
            "  }",
            "}")
        .doTest();
  }
}
