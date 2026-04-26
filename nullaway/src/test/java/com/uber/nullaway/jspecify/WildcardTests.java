package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import org.junit.Ignore;
import org.junit.Test;

public class WildcardTests extends NullAwayTestsBase {

  @Test
  public void simpleWildcardNoInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              class Foo<T extends @Nullable Object> {}
              String nullableWildcard(Foo<? extends @Nullable String> foo) { throw new RuntimeException(); }
              String nonnullWildcard(Foo<? extends String> foo) { throw new RuntimeException(); }
              void testNegative(Foo<@Nullable String> f, Foo<String> f2) {
                // this is legal since the wildcard upper bound is @Nullable
                String s = nullableWildcard(f);
                // also legal
                String s2 = nullableWildcard(f2);
              }
              void testPositive(Foo<@Nullable String> f, Foo<String> f2) {
                // not legal since the wildcard upper bound is non-null
                // BUG: Diagnostic contains: incompatible types: Test.Foo<@Nullable String> cannot be converted to Test.Foo<? extends String>
                String s = nonnullWildcard(f);
                // legal
                String s2 = nonnullWildcard(f2);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void simpleWildcard() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              class Foo<T extends @Nullable Object> {}
              <U> U nullableWildcard(Foo<? extends @Nullable U> foo) { throw new RuntimeException(); }
              <U> U nonnullWildcard(Foo<? extends U> foo) { throw new RuntimeException(); }
              void testNegative(Foo<@Nullable String> f) {
                // this is legal since the wildcard upper bound is @Nullable
                String s = nullableWildcard(f);
                s.hashCode();
              }
              void testPositive(Foo<@Nullable String> f) {
                // not legal since the wildcard upper bound is non-null
                // BUG: Diagnostic contains: incompatible types: Test.Foo<@Nullable String> cannot be converted to Test.Foo<? extends String>
                String s = nonnullWildcard(f);
                s.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nestedTypeArgsInWildcardBoundNoInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              class Foo<T extends @Nullable Object> {}
              class Bar<T extends @Nullable Object> {}
              String nullableWildcard(Foo<? extends Bar<@Nullable String>> foo) {
                throw new RuntimeException();
              }
              String nonnullWildcard(Foo<? extends Bar<String>> foo) {
                throw new RuntimeException();
              }
              void testNegative(Foo<Bar<@Nullable String>> f) {
                String s = nullableWildcard(f);
                s.hashCode();
              }
              void testPositive(Foo<Bar<@Nullable String>> f) {
                // BUG: Diagnostic contains: incompatible types: Test.Foo<Test.Bar<@Nullable String>> cannot be converted to Test.Foo<? extends Test.Bar<String>>
                String s = nonnullWildcard(f);
                s.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void deeplyNestedTypeArgsInWildcardBoundNoInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              class Foo<T extends @Nullable Object> {}
              class Bar<T extends @Nullable Object> {}
              class Baz<T extends @Nullable Object> {}
              String nullableWildcard(Foo<? extends Bar<Baz<@Nullable String>>> foo) {
                throw new RuntimeException();
              }
              String nonnullWildcard(Foo<? extends Bar<Baz<String>>> foo) {
                throw new RuntimeException();
              }
              void testNegative(Foo<Bar<Baz<@Nullable String>>> f) {
                String s = nullableWildcard(f);
                s.hashCode();
              }
              void testPositive(Foo<Bar<Baz<@Nullable String>>> f) {
                // BUG: Diagnostic contains: incompatible types: Test.Foo<Test.Bar<Test.Baz<@Nullable String>>> cannot be converted to Test.Foo<? extends Test.Bar<Test.Baz<String>>>
                String s = nonnullWildcard(f);
                s.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void intermediateNestedTypeArgsInWildcardBoundNoInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              class Foo<T extends @Nullable Object> {}
              class Bar<T extends @Nullable Object> {}
              class Baz<T extends @Nullable Object> {}
              String nullableWildcard(Foo<? extends @Nullable Bar<Baz<String>>> foo) {
                throw new RuntimeException();
              }
              String nonnullWildcard(Foo<? extends Bar<Baz<String>>> foo) {
                throw new RuntimeException();
              }
              void testNegative(Foo<@Nullable Bar<Baz<String>>> f) {
                String s = nullableWildcard(f);
                s.hashCode();
              }
              void testPositive(Foo<@Nullable Bar<Baz<String>>> f) {
                // BUG: Diagnostic contains: incompatible types: Test.Foo<Test.@Nullable Bar<Test.Baz<String>>> cannot be converted to Test.Foo<? extends Test.Bar<Test.Baz<String>>>
                String s = nonnullWildcard(f);
                s.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void wildcardActualArgumentNoInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              class Foo<T extends @Nullable Object> {}
              String nullableWildcard(Foo<? extends @Nullable String> foo) {
                throw new RuntimeException();
              }
              String nonnullWildcard(Foo<? extends String> foo) {
                throw new RuntimeException();
              }
              void testNegative(Foo<? extends @Nullable String> f) {
                String s = nullableWildcard(f);
                s.hashCode();
              }
              void testPositive(Foo<? extends @Nullable String> f) {
                // BUG: Diagnostic contains: incompatible types: Test.Foo<? extends @Nullable String> cannot be converted to Test.Foo<? extends String>
                String s = nonnullWildcard(f);
                s.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void wildcardCheckingForReturnsAndAssignments() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              class Foo<T extends @Nullable Object> {}
              Foo<? extends String> nonnullField;
              Foo<? extends @Nullable String> nullableField;
              Test(Foo<? extends @Nullable String> f) {
                nullableField = f;
                // BUG: Diagnostic contains: incompatible types: Test.Foo<? extends @Nullable String> cannot be converted to Test.Foo<? extends String>
                nonnullField = f;
              }
              Foo<? extends @Nullable String> nullableReturn(Foo<? extends @Nullable String> f) {
                return f;
              }
              Foo<? extends String> nonnullReturn(Foo<? extends @Nullable String> f) {
                // BUG: Diagnostic contains: incompatible types: Test.Foo<? extends @Nullable String> cannot be converted to Test.Foo<? extends String>
                return f;
              }
              void testLocal(Foo<? extends @Nullable String> f) {
                Foo<? extends @Nullable String> ok = f;
                // BUG: Diagnostic contains: incompatible types: Test.Foo<? extends @Nullable String> cannot be converted to Test.Foo<? extends String>
                Foo<? extends String> bad = f;
                var f2 = f;
                // BUG: Diagnostic contains: incompatible types: Test.Foo<? extends @Nullable String> cannot be converted to Test.Foo<? extends String>
                Foo<? extends String> bad2 = f2;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void wildcardSuperFormalNoInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              class Foo<T extends @Nullable Object> {}
              void testLocals(
                  Foo<Object> objectFoo,
                  Foo<@Nullable Object> nullableObjectFoo,
                  Foo<@Nullable String> nullableStringFoo,
                  Foo<? super String> nonnullSuperFoo,
                  Foo<? super @Nullable String> nullableSuperFoo) {
                Foo<? super String> nonnullSuperLocal = nullableObjectFoo;
                Foo<? super @Nullable String> nullableSuperLocal = nullableObjectFoo;
                Foo<? super @Nullable String> nullableSuperLocal2 = nullableStringFoo;
                // BUG: Diagnostic contains: incompatible types: Test.Foo<Object> cannot be converted to Test.Foo<? super @Nullable String>
                Foo<? super @Nullable String> nullableSuperLocal3 = objectFoo;
                Foo<? super String> nonnullSuperFromNullableSuper = nullableSuperFoo;
                // BUG: Diagnostic contains: incompatible types: Test.Foo<? super String> cannot be converted to Test.Foo<? super @Nullable String>
                Foo<? super @Nullable String> nullableSuperFromNonnullSuper = nonnullSuperFoo;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void superOrUnboundedWildcardAssignedToExtendsBoundedWildcard() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              class Foo<T extends @Nullable Object> {}
              void testLocals(
                  Foo<? super String> nonnullSuperFoo,
                  Foo<? super @Nullable String> nullableSuperFoo,
                  Foo<?> unboundedFoo) {
                Foo<? extends @Nullable Object> fromNonnullSuper = nonnullSuperFoo;
                Foo<? extends @Nullable Object> fromNullableSuper = nullableSuperFoo;
                Foo<? extends @Nullable Object> fromUnbounded = unboundedFoo;
                // BUG: Diagnostic contains: incompatible types: Test.Foo<? super String> cannot be converted to Test.Foo<? extends Object>
                Foo<? extends Object> badFromNonnullSuper = nonnullSuperFoo;
                // BUG: Diagnostic contains: incompatible types: Test.Foo<? super @Nullable String> cannot be converted to Test.Foo<? extends Object>
                Foo<? extends Object> badFromNullableSuper = nullableSuperFoo;
                // BUG: Diagnostic contains: incompatible types: Test.Foo<?> cannot be converted to Test.Foo<? extends Object>
                Foo<? extends Object> badFromUnbounded = unboundedFoo;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void unboundedWildcardFormalWithNonNullTypeParameterBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              class NonNullBoundFoo<T extends Object> {}
              void testLocals(
                  NonNullBoundFoo<Object> nonnullObjectFoo,
                  NonNullBoundFoo<? extends Object> nonnullExtendsObjectFoo,
                  NonNullBoundFoo<? extends @Nullable Object> nullableExtendsObjectWithNonnullBoundFoo,
                  NonNullBoundFoo<? super String> nonnullSuperStringFoo) {
                NonNullBoundFoo<?> fromNonnull = nonnullObjectFoo;
                NonNullBoundFoo<?> fromNonnullExtends = nonnullExtendsObjectFoo;
                // BUG: Diagnostic contains: incompatible types: Test.NonNullBoundFoo<? extends @Nullable Object> cannot be converted to Test.NonNullBoundFoo<?>
                NonNullBoundFoo<?> fromNullableExtends = nullableExtendsObjectWithNonnullBoundFoo;
                NonNullBoundFoo<?> fromSuper = nonnullSuperStringFoo;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void unboundedWildcardFormalWithNullableTypeParameterBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              class NullableBoundFoo<T extends @Nullable Object> {}
              void testLocals(
                  NullableBoundFoo<Object> nonnullObjectWithNullableBoundFoo,
                  NullableBoundFoo<@Nullable Object> nullableObjectFoo,
                  NullableBoundFoo<? extends Object> nonnullExtendsObjectWithNullableBoundFoo,
                  NullableBoundFoo<? extends @Nullable Object> nullableExtendsObjectFoo,
                  NullableBoundFoo<? super String> nullableBoundSuperStringFoo) {
                NullableBoundFoo<?> fromNonnull = nonnullObjectWithNullableBoundFoo;
                NullableBoundFoo<?> fromNullable = nullableObjectFoo;
                NullableBoundFoo<?> fromNonnullExtends = nonnullExtendsObjectWithNullableBoundFoo;
                NullableBoundFoo<?> fromNullableExtends = nullableExtendsObjectFoo;
                NullableBoundFoo<?> fromSuper = nullableBoundSuperStringFoo;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void wildcardSuperBoundsAndInference() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<V> {
              public interface BiFunction<T extends @Nullable Object, U extends @Nullable Object, R extends @Nullable Object> {
                  R apply(T t, U u);
              }
              void test1(BiFunction<Object, ? super @Nullable V, ? extends @Nullable V> f) {
                BiFunction<Object, ? super V, ? extends @Nullable V> g = f;
              }
              static <T, U, R> BiFunction<? super T, ? super U, ? extends @Nullable R> id(
                  BiFunction<? super T, ? super U, ? extends @Nullable R> f) {
                return f;
              }
              void test2(BiFunction<? super String, ? super @Nullable V, ? extends @Nullable V> f) {
                BiFunction<? super String, ? super V, ? extends @Nullable V> g = id(f);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void superWildcardToConcreteTypeVariable() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<T extends @Nullable Object> {}
              static <T extends @Nullable Object> void take(Box<T> box) {}
              static <T extends @Nullable Object> T get(Box<T> box) {
                throw new RuntimeException();
              }
              Object field = new Object();
              void test(Box<? super @Nullable String> nullableBox, Box<? super String> nonNullBox) {
                take(nullableBox);
                take(nonNullBox);
                // TODO we need to report an additional error besides inference failure here
                // See https://github.com/uber/NullAway/issues/1551
                // BUG: Diagnostic contains: Failed to infer type argument nullability
                field = get(nullableBox);
                field = get(nonNullBox);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericMethodLambdaArgWildCard() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            import java.util.function.Function;
            @NullMarked
            class Test {
                static <T, R> R invokeWithReturn(Function <? super T, ? extends @Nullable R> mapper) {
                    throw new RuntimeException();
                }
                static void test() {
                    // legal, should infer R -> Object but then the type of the lambda as
                    //  Function<Object, @Nullable Object> via wildcard upper bound
                    Object x = invokeWithReturn(t -> null);
                }
            }
            """)
        .doTest();
  }

  @Ignore("https://github.com/uber/NullAway/issues/1522")
  @Test
  public void issue1522() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            import java.util.function.Function;
            import java.util.Optional;
            @NullMarked
            class Test {
              static class Foo<T> {
                public final <V> Foo<V> mapNotNull(Function<? super T, ? extends @Nullable V> mapper) {
                  throw new RuntimeException();
                }
              }
              static <T> Foo<T> after(Foo<Optional<T>> foo) {
                return foo.mapNotNull(x -> x.orElse(null));
              }
            }
            """)
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
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            public class Test {
                public interface CacheLoader<K, V extends @Nullable Object> {}
                static class JCacheLoaderAdapter<K, V> implements CacheLoader<K, @Nullable Expirable<V>> {}
                static class Expirable<V> {}
                static class Caffeine<K, V> {
                    public <K1 extends K, V1 extends @Nullable V> Object build(
                            CacheLoader<? super K1, V1> loader) {
                        throw new RuntimeException();
                    }
                }
                class Builder<K, V> {
                    Caffeine<Object, Object> caffeine = new Caffeine<>();
                    void test() {
                        JCacheLoaderAdapter<K, V> adapter = new JCacheLoaderAdapter<>();
                        caffeine.<K, @Nullable Expirable<V>>build(adapter);
                        // also works with inference
                        Object o = caffeine.build(adapter);
                    }
                }
            }
            """)
        .doTest();
  }

  @Test
  public void mapStreamValuesToNullable() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
                interface List<T extends @Nullable Object> {
                    Stream<T> stream();
                }
                interface Stream<T extends @Nullable Object> {
                    <R extends @Nullable Object> Stream<R> map(Function<? super T, ? extends R> mapper);
                    void forEach(Consumer<? super T> action);
                }
                interface Function<T extends @Nullable Object, R extends @Nullable Object> {
                    R apply(T t);
                }
                interface Consumer<T extends @Nullable Object> {
                    void accept(T t);
                }
                static @Nullable String mapToNull(String s) {
                    return null;
                }
                static void test(List<String> list) {
                    // legal, should infer R -> @Nullable String
                    list.stream().map(Test::mapToNull).forEach(s -> {
                        // BUG: Diagnostic contains: dereferenced expression s is @Nullable
                        s.hashCode();
                    });
                }
            }""")
        .doTest();
  }

  @Ignore("https://github.com/uber/NullAway/issues/1500")
  @Test
  public void issue1500() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              static class Foo<T extends @Nullable Object> {
                public static <T extends @Nullable Object> Foo<T> of(Foo<? super T> foo) {
                    return new Foo<>();
                }

                public Foo<T> or(Foo<? super T> other) {
                    return this;
                }
              }
              static final Foo<@Nullable Void> FOO = Foo.of(new Foo<@Nullable Void>()).or(new Foo<@Nullable Void>());
            }""")
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
