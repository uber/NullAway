package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
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
  public void wildcardCaptureParameters() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Foo<T extends @Nullable Object> {
                void set(T t) {}
              }
              static void testNullableExtendsBound(Foo<? extends @Nullable Object> f) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'null'
                f.set(null);
              }
              static void testNonNullExtendsBound(Foo<? extends Object> f) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'null'
                f.set(null);
              }
              static void testNullableSuperBound(Foo<? super @Nullable String> f) {
                // this is legal
                f.set(null);
              }
              static void testNonNullSuperBound(Foo<? super String> f) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'null'
                f.set(null);
              }
            }""")
        .doTest();
  }

  @Test
  public void wildcardCaptureReturns() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Foo<T extends @Nullable Object> {
                T get() { throw new RuntimeException(); }
              }
              static void testNullableExtendsBound(Foo<? extends @Nullable Object> f) {
                // BUG: Diagnostic contains: dereferenced expression 'f.get()' is @Nullable
                f.get().hashCode();
              }
              static void testNonNullExtendsBound(Foo<? extends Object> f) {
                // this is legal
                f.get().hashCode();
              }
              static void testNullableSuperBound(Foo<? super @Nullable String> f) {
                // BUG: Diagnostic contains: dereferenced expression 'f.get()' is @Nullable
                f.get().hashCode();
              }
              static void testNonNullSuperBound(Foo<? super String> f) {
                // BUG: Diagnostic contains: dereferenced expression 'f.get()' is @Nullable
                f.get().hashCode();
              }
            }""")
        .doTest();
  }

  @Test
  public void wildcardCaptureReturnWithTypeVariableUpperBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Foo<T extends @Nullable Object> {
                T get() { throw new RuntimeException(); }
              }
              static class NullableBound<U extends @Nullable Object> {
                void test(Foo<? extends U> f) {
                  // BUG: Diagnostic contains: dereferenced expression 'f.get()' is @Nullable
                  f.get().hashCode();
                }
              }
              static class NonNullBound<U> {
                void test(Foo<? extends U> f) {
                  // this is legal
                  f.get().hashCode();
                }
              }
            }""")
        .doTest();
  }

  @Test
  public void wildcardCaptureLocals() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Foo<T extends @Nullable Object> {
                T get() { throw new RuntimeException(); }
              }
              static void testNullableExtendsBound(Foo<? extends @Nullable Object> f) {
                Object x = f.get();
                // BUG: Diagnostic contains: dereferenced expression 'x' is @Nullable
                x.hashCode();
              }
              static void testNonNullExtendsBound(Foo<? extends Object> f) {
                Object x = f.get();
                // this is legal
                x.hashCode();
              }
              static void testNullableSuperBound(Foo<? super @Nullable String> f) {
                Object x = f.get();
                // BUG: Diagnostic contains: dereferenced expression 'x' is @Nullable
                x.hashCode();
              }
              static void testNonNullSuperBound(Foo<? super String> f) {
                Object x = f.get();
                // BUG: Diagnostic contains: dereferenced expression 'x' is @Nullable
                x.hashCode();
              }
            }""")
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
                // BUG: Diagnostic contains: inference failure: type variable T constrained to be both @NonNull and @Nullable
                field = get(nullableBox);
                field = get(nonNullBox);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void methodRefParameterExtendsWildcardToConcreteParameter() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Consumer<T extends @Nullable Object> {
                void accept(T t);
              }
              static void acceptNullable(@Nullable String s) {}
              static void acceptNonNull(String s) {}
              static <T extends @Nullable Object> void use(Consumer<? extends T> consumer) {}
              static void useNullable(Consumer<? extends @Nullable String> consumer) {}
              void test() {
                use(Test::acceptNullable);
                // BUG: Diagnostic contains: parameter s of referenced method is @NonNull
                useNullable(Test::acceptNonNull);
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
                    x.hashCode();
                }
            }
            """)
        .doTest();
  }

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

  @Test
  public void issue1522SelfContained() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              interface Function<T extends @Nullable Object, U extends @Nullable Object> {
                U apply(T t);
              }
              static class Optional<T> {
                public @Nullable T orElse(@Nullable T other) {
                    throw new RuntimeException();
                }
              }
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

  @Test
  public void issue1522SelfContainedWithMethodReference() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              interface Function<T extends @Nullable Object, U extends @Nullable Object> {
                U apply(T t);
              }
              static class Optional<T> {
                public @Nullable T orElse(@Nullable T other) {
                    throw new RuntimeException();
                }
              }
              static class Foo<T> {
                public final <V> Foo<V> mapNotNull(Function<? super T, ? extends @Nullable V> mapper) {
                  throw new RuntimeException();
                }
              }
              static <T> @Nullable T orElseNull(Optional<T> optional) {
                return optional.orElse(null);
              }
              static <T> Foo<T> after(Foo<Optional<T>> foo) {
                return foo.mapNotNull(Test::orElseNull);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void groundTargetTypePreservesNestedWildcards() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              interface Function<T extends @Nullable Object, R extends @Nullable Object> {
                R apply(T t);
              }
              static class Box<T extends @Nullable Object> {
                T get() {
                  throw new RuntimeException();
                }
              }
              static <R extends @Nullable Object> R invokeNested(
                  Function<Box<? super String>, R> mapper) {
                throw new RuntimeException();
              }
              static <R extends @Nullable Object> R invokeNestedWithUpperBound(
                  Function<Box<? extends String>, R> mapper) {
                throw new RuntimeException();
              }
              static <R extends @Nullable Object> R invokeTopLevelWildcard(
                  Function<? super Box<? super String>, R> mapper) {
                throw new RuntimeException();
              }
              static <R extends @Nullable Object> R invokeArray(
                  Function<Box<? super String>[], R> mapper) {
                throw new RuntimeException();
              }
              static void testNestedWildcard() {
                invokeNested(box -> {
                  // BUG: Diagnostic contains: dereferenced expression 'box.get()' is @Nullable
                  box.get().hashCode();
                  return null;
                });
                invokeNestedWithUpperBound(box -> {
                  // safe since the upper bound of the Box type variable is @NonNull String,
                  // so box.get() cannot be null
                  box.get().hashCode();
                  return null;
                });
              }
              static void testTopLevelWildcardBound() {
                invokeTopLevelWildcard(box -> {
                  // BUG: Diagnostic contains: dereferenced expression 'box.get()' is @Nullable
                  box.get().hashCode();
                  return null;
                });
              }
              static void testArrayWithNestedWildcard() {
                invokeArray(boxes -> {
                  // BUG: Diagnostic contains: dereferenced expression 'boxes[0].get()' is @Nullable
                  boxes[0].get().hashCode();
                  return null;
                });
              }
            }
            """)
        .doTest();
  }

  @Test
  public void groundTargetTypePreservesNestedWildcardsForMethodReferences() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              interface Function<T extends @Nullable Object, R extends @Nullable Object> {
                R apply(T t);
              }
              static class Box<T extends @Nullable Object> {}
              static <R extends @Nullable Object> R invokeExtendsNullable(
                  Function<Box<? extends @Nullable String>, R> mapper) {
                throw new RuntimeException();
              }
              static @Nullable Object needsBoxExtendsString(Box<? extends String> box) {
                return null;
              }
              static void test() {
                // BUG: Diagnostic contains: parameter type of referenced method is Box<? extends String>
                invokeExtendsNullable(Test::needsBoxExtendsString);
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
                static String id(String s) { return s; }
                static void callHashCode(Object o) { o.hashCode(); }
                static void doNothing(@Nullable Object o) {}
                static void testPositive(List<String> list) {
                    list.stream().map(Test::mapToNull).forEach(s -> {
                        // BUG: Diagnostic contains: dereferenced expression 's' is @Nullable
                        s.hashCode();
                    });
                    // BUG: Diagnostic contains: parameter o of referenced method is @NonNull, but parameter in functional interface method Test.Consumer.accept(T) is @Nullable
                    list.stream().map(Test::mapToNull).forEach(Test::callHashCode);
                }
                static void testNegative(List<String> list) {
                    list.stream().map(Test::mapToNull).forEach(s -> {
                        if (s != null) { s.hashCode(); }
                    });
                    list.stream().map(Test::mapToNull).forEach(Test::doNothing);
                    list.stream().map(Test::id).forEach(Test::callHashCode);
                }
            }""")
        .doTest();
  }

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

                public static <T extends @Nullable Object> Foo<T> ofNoWildcard(Foo<T> foo) {
                    return new Foo<>();
                }

                public Foo<T> or(Foo<? super T> other) {
                    return this;
                }
              }
              // We report an error here since we do not infer Foo<@Nullable Void> as the type of the Foo.of call;
              // javac itself has a similar inference limitation, see https://godbolt.org/z/Y875ahYMx
              // BUG: Diagnostic contains: incompatible types: Foo<Void> cannot be converted to Foo<@Nullable Void>
              static final Foo<@Nullable Void> FOO = Foo.of(new Foo<@Nullable Void>()).or(new Foo<@Nullable Void>());

              // This works due to the explicit type argument
              static final Foo<@Nullable Void> FOO2 = Foo.<@Nullable Void>of(new Foo<@Nullable Void>()).or(new Foo<@Nullable Void>());

              // This works since ofNoWildcard does not use a lower-bounded wildcard in its parameter type
              static final Foo<@Nullable Void> FOO3 = Foo.ofNoWildcard(new Foo<@Nullable Void>()).or(new Foo<@Nullable Void>());
            }""")
        .doTest();
  }

  @Test
  public void unboundWildcardTypeVarUnmarked() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NullUnmarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              @NullUnmarked
              interface Foo<V> {}
              Foo<?> test(Foo<@Nullable Void> foo) {
                // legal since Foo is @NullUnmarked, so its V type variable
                // is treated as having a @Nullable upper bound
                return foo;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableOnWildcard() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            import java.util.function.Function;
            @NullMarked
            class Test<K,V> {
              @Nullable V testPositive(@Nullable K k,
                Function<
                  // BUG: Diagnostic contains: illegal location for annotation
                  @Nullable ? super K,
                  // BUG: Diagnostic contains: illegal location for annotation
                  @NonNull ? extends V> function) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'k' where @NonNull is required
                return function.apply(k);
              }

              @Nullable V testNegative(@Nullable K k, Function<? super @Nullable K, ? extends @Nullable V> function) {
                return function.apply(k);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void conditionalExpr() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            package com.example;
            import org.jspecify.annotations.*;
            @NullMarked
            final class Test {
              interface ClassLike<T extends @Nullable Object> {
                String name();
              }
              static String guardedNullableWildcard(@Nullable ClassLike<?> maybeClass) {
                return (maybeClass != null ? maybeClass : fallback()).name();
              }
              static ClassLike<?> fallback() {
                throw new RuntimeException();
              }
            }
            """)
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
