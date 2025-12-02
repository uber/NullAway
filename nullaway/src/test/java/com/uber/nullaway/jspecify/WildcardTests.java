package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import org.junit.Ignore;
import org.junit.Test;

public class WildcardTests extends NullAwayTestsBase {

  @Ignore("https://github.com/uber/NullAway/issues/1360")
  @Test
  public void simpleWildcard() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "class Test { ",
            "  class Foo<T extends @Nullable Object> {}",
            "  <U> U nullableWildcard(Foo<? extends @Nullable U> foo) { throw new RuntimeException(); }",
            "  <U> U nonnullWildcard(Foo<? extends U> foo) { throw new RuntimeException(); }",
            "  void testNegative(Foo<@Nullable String> f) {",
            "    // this is legal since the wildcard upper bound is @Nullable",
            "    String s = nullableWildcard(f);",
            "    s.hashCode();",
            "  }",
            "  void testPositive(Foo<@Nullable String> f) {",
            "    // not legal since the wildcard upper bound is non-null",
            "    // BUG: Diagnostic contains: something about how f cannot be passed here",
            "    String s = nonnullWildcard(f);",
            "    s.hashCode();",
            "  }",
            "}")
        .doTest();
  }

  @Ignore("https://github.com/uber/NullAway/issues/1350")
  @Test
  public void genericMethodLambdaArgWildCard() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.*;",
            "import java.util.function.Function;",
            "@NullMarked",
            "class Test {",
            "    static <T, R> R invokeWithReturn(Function <? super T, ? extends @Nullable R> mapper) {",
            "        throw new RuntimeException();",
            "    }",
            "    static void test() {",
            "        // legal, should infer R -> Object but then the type of the lambda as ",
            "        //  Function<Object, @Nullable Object> via wildcard upper bound",
            "        Object x = invokeWithReturn(t -> null);",
            "    }",
            "}")
        .doTest();
  }

  /**
   * Extracted from Caffeine; exposed some subtle bugs in substitutions involving identity of {@code
   * Type} objects
   */
  @Test
  public void nullableWildcardFromCaffeine() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "public class Test {",
            "    public interface CacheLoader<K, V extends @Nullable Object> {}",
            "    static class JCacheLoaderAdapter<K, V> implements CacheLoader<K, @Nullable Expirable<V>> {}",
            "    static class Expirable<V> {}",
            "    static class Caffeine<K, V> {",
            "        public <K1 extends K, V1 extends @Nullable V> Object build(",
            "                CacheLoader<? super K1, V1> loader) {",
            "            throw new RuntimeException();",
            "        }",
            "    }",
            "    class Builder<K, V> {",
            "        Caffeine<Object, Object> caffeine = new Caffeine<>();",
            "        void test() {",
            "            JCacheLoaderAdapter<K, V> adapter = new JCacheLoaderAdapter<>();",
            "            caffeine.<K, @Nullable Expirable<V>>build(adapter);",
            "            // also works with inference",
            "            Object o = caffeine.build(adapter);",
            "        }",
            "    }",
            "}")
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList("-XepOpt:NullAway:OnlyNullMarked=true")));
  }

  private CompilationTestHelper makeHelperWithInferenceFailureWarning() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList(
                "-XepOpt:NullAway:OnlyNullMarked=true",
                "-XepOpt:NullAway:WarnOnGenericInferenceFailure=true")));
  }
}
