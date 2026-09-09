package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import java.util.List;
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
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable String, required String
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
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable String, required String
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
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable String, required String
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
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable String, required String
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
                // BUG: Diagnostic contains: incompatible nullability: found Test.@Nullable Bar<Test.Baz<String>>, required Test.Bar<Test.Baz<String>>
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
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable String, required String
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
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable String, required String
                nonnullField = f;
              }
              Foo<? extends @Nullable String> nullableReturn(Foo<? extends @Nullable String> f) {
                return f;
              }
              Foo<? extends String> nonnullReturn(Foo<? extends @Nullable String> f) {
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable String, required String
                return f;
              }
              void testLocal(Foo<? extends @Nullable String> f) {
                Foo<? extends @Nullable String> ok = f;
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable String, required String
                Foo<? extends String> bad = f;
                var f2 = f;
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable String, required String
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
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable Object, required Object
                Foo<? extends Object> badFromNonnullSuper = nonnullSuperFoo;
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable Object, required Object
                Foo<? extends Object> badFromNullableSuper = nullableSuperFoo;
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable Object, required Object
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
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable Object, required Object
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
  public void wildcardCaptureReturnPreservesNestedNullability() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static class Holder<T extends @Nullable Object> {
                T get() {
                  throw new RuntimeException();
                }
              }
              static void test(Holder<? extends Box<@Nullable String>> holder) {
                // BUG: Diagnostic contains: incompatible types
                Box<String> box = holder.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void annotationRestoredFromUpperBoundToCapturedWildcard() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Nested<T extends @Nullable Object> {
                Nested<T> self() {
                  return this;
                }
                Nested<? extends @Nullable T> wildcardUpperTypeVariable() {
                  throw new RuntimeException();
                }
              }

              Nested<? extends String> testDirect(Nested<? extends String> receiver) {
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable String, required String
                return receiver.wildcardUpperTypeVariable();
              }

              Nested<? extends String> testWithSelf(Nested<? extends String> receiver) {
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable String, required String
                return receiver.self().wildcardUpperTypeVariable();
              }

              Nested<? extends String> testWithVar(Nested<? extends String> receiver) {
                var local = receiver;
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable String, required String
                return local.wildcardUpperTypeVariable();
              }

              Nested<? extends @Nullable String> testWithVarSafe(Nested<? extends String> receiver) {
                var local = receiver;
                // safe
                return local.wildcardUpperTypeVariable();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void annotationRestoredFromUpperBoundToCapturedUnboundedWildcard() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Nested<T extends @Nullable Object> {
                Nested<T> self() {
                  return this;
                }
                Nested<? extends @Nullable T> wildcardUpperTypeVariable() {
                  throw new RuntimeException();
                }
              }

              Nested<? extends Object> testDirect(Nested<?> receiver) {
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable Object, required Object
                return receiver.wildcardUpperTypeVariable();
              }

              Nested<? extends Object> testWithSelf(Nested<?> receiver) {
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable Object, required Object
                return receiver.self().wildcardUpperTypeVariable();
              }

              Nested<? extends Object> testWithVar(Nested<?> receiver) {
                var local = receiver;
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable Object, required Object
                return local.wildcardUpperTypeVariable();
              }

              Nested<? extends @Nullable Object> testCompatible(Nested<?> receiver) {
                return receiver.wildcardUpperTypeVariable();
              }
            }
            """)
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
  public void issue1715() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface I1<X> {}
              interface I2<X, Y extends I1<X>> {
                Y get();
              }
              I1<Object> crash(I2<Object, ?> i2) {
                var i2var = i2;
                return i2var.get();
              }

              interface NullableI1<X extends @Nullable Object> {}
              interface NullableI2<
                  X extends @Nullable Object, Y extends NullableI1<@Nullable X>> {
                Y get();
              }
              NullableI1<Object> incompatible(NullableI2<Object, ?> i2) {
                var i2var = i2;
                // BUG: Diagnostic contains: incompatible types
                return i2var.get();
              }
            }
            """)
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
    makeHelper()
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
    makeHelper()
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
    makeHelper()
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
    makeHelper()
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
    makeHelper()
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
    makeHelper()
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
    makeHelper()
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
    makeHelper()
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
    makeHelper()
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
    makeHelper()
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
    makeHelper()
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
                    // BUG: Diagnostic contains: parameter o of referenced method is @NonNull, but parameter in functional interface method Test.Consumer.accept(@Nullable String) is @Nullable
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
    makeHelper()
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
              // BUG: Diagnostic contains: incompatible nullability: a type argument must match exactly; found Void, required @Nullable Void
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
    makeHelper()
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
  public void capturedSuperWildcardReturnCheckedForContainment() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<T extends @Nullable Object> {}
              static <T extends @Nullable Object> Box<T> identity(Box<T> box) {
                return box;
              }
              void test(
                  Box<? super String> nonNullBoundBox,
                  Box<? super @Nullable String> nullableBoundBox) {
                Box<? super String> ok = identity(nullableBoundBox);
                // BUG: Diagnostic contains: incompatible types
                Box<? super @Nullable String> bad = identity(nonNullBoundBox);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void capturedSuperWildcardReturnCheckedRecursively() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<T extends @Nullable Object> {}
              interface Nested<T extends @Nullable Object> {}
              static <T extends @Nullable Object> Box<T> identity(Box<T> box) {
                return box;
              }
              void test(Box<? super Nested<String>> box) {
                // BUG: Diagnostic contains: incompatible types
                Box<? super Nested<@Nullable String>> bad = identity(box);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void capturedLhsWithFBoundedTypeParametersDoesNotRecurse() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
              interface Value<V extends Value<V>> {}
              interface Store<S extends Store<S>> {}
              interface Transfer<V extends Value<V>, S extends Store<S>> {}
              static class Analysis<
                  V extends Value<V>,
                  S extends Store<S>,
                  T extends Transfer<V, S>> {
                Analysis(T transfer) {}
              }
              static Analysis<?, ?, ?> create(Transfer<?, ?> transfer) {
                return new Analysis<>(transfer);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void annotationRestorationForUnboundedWildcardWithFBoundedFormalDoesNotRecurse() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
              interface Value<V extends Value<V>> {}
              interface Store<S extends Store<S>> {}
              interface Transfer<V extends Value<V>, S extends Store<S>> {}
              static class Analysis<
                  V extends Value<V>,
                  S extends Store<S>,
                  T extends Transfer<V, S>> {}
              static class Cache<K, V> {
                V getUnchecked(K key) {
                  throw new UnsupportedOperationException();
                }
              }
              final Cache<Object, Analysis<?, ?, ?>> cache = new Cache<>();
              void test(Object key) {
                Analysis<?, ?, ?> analysis = cache.getUnchecked(key);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void annotationRestorationForFBoundedWildcardPreservesInferredNullableTypeArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Value<V extends Value<V>> {}
              interface Store<S extends Store<S>> {}
              interface Transfer<V extends Value<V>, S extends Store<S>> {}
              static class Analysis<
                  V extends Value<V>,
                  S extends Store<S>,
                  T extends Transfer<V, S>,
                  R extends @Nullable Object> {
                R value() {
                  throw new UnsupportedOperationException();
                }
              }
              static <R extends @Nullable Object> Analysis<?, ?, ?, R> make(R value) {
                throw new UnsupportedOperationException();
              }
              void test() {
                make(new Object()).value().hashCode();
                // BUG: Diagnostic contains: dereferenced expression 'make(null).value()' is @Nullable
                make(null).value().hashCode();
              }
            }
            """)
        .doTest();
  }

  /** ensures we avoid a crash related to wildcards when wildcard handling is disabled */
  @Test
  public void methodRefParameterSuperWildcardWithHandlingDisabled() {
    makeTestHelperWithArgs(
            List.of(
                "-XepOpt:NullAway:OnlyNullMarked=true",
                JSpecifyJavacConfig.JSPECIFY_MODE_FLAG,
                JSpecifyJavacConfig.ADD_TYPE_ANNOTATIONS_FLAG))
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            final class Test {
                static void reproduce() {
                    getOrThrow(Test::throwAsUncheckedException);
                }
                private static void getOrThrow(Function<? super Exception, RuntimeException> exceptionTransformer) {
                }
                private static RuntimeException throwAsUncheckedException(Throwable throwable) {
                    return new RuntimeException(throwable);
                }
            }
            """)
        .doTest();
  }

  /** reduced from a crasher found when checking junit */
  @Test
  public void methodRefReturnUnboundedWildcard() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package repro;
            import java.util.concurrent.FutureTask;
            import java.util.function.Supplier;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            final class Test {
                private final FutureTask<@Nullable Object> task;
                Test(Supplier<?> delegate) {
                    this.task = new FutureTask<>(delegate::get);
                }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableOnWildcard() {
    makeHelper()
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
  public void weirdErrorMessageReducedFromSpring() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NullUnmarked;
            @NullMarked
            final class Test {
              static final class Flux<T> {}
              @NullUnmarked
              static final class Flow<T> {}
              static <T> Flux<T> asFlux(Flow<? extends T> flow) {
                throw new RuntimeException();
              }
              static Flux<?> convert(Object source) {
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable Object, required Object
                return asFlux((Flow<?>) source);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void identicalLookingWildcardNestedInArrayErrorMessage() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NullUnmarked;
            @NullMarked
            final class Test {
              static final class Flux<T> {}
              @NullUnmarked
              static final class Flow<T> {}
              static <T> Flux<T>[] asFluxArray(Flow<? extends T> flow) {
                throw new RuntimeException();
              }
              static Flux<?>[] convert(Object source) {
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable Object, required Object
                return asFluxArray((Flow<?>) source);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void lambdaInferenceUsesGenericInstanceMethodReceiverType() {
    makeHelper()
        .addSourceLines(
            "Repro.java",
            """
            import java.util.List;
            import java.util.function.Function;
            import org.jspecify.annotations.*;
            @NullMarked
            final class Repro {
                static List<?> readValues(List<String> inputs) {
                    return inputs.stream()
                            // no error here: the lambda returns @Nullable Object, so we
                            // should infer type variable R of Stream.map to be @Nullable Object
                            .map(input -> nullableBox().getOrThrow(RuntimeException::new))
                            .toList();
                }
                private static Box<@Nullable Object> nullableBox() {
                    return new Box<>(null);
                }
                private record Box<V extends @Nullable Object>(V value) {
                    <E extends Exception> V getOrThrow(Function<? super Exception, E> exceptionTransformer) throws E {
                        return value;
                    }
                }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1671() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.reactive.function.client.WebClient;
            import reactor.core.publisher.Mono;
            import tools.jackson.databind.JsonNode;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            public class Test {

              public static Mono<String> testJSpecify() {
                return WebClient.create()
                    .post()
                    .uri("https://example.com")
                    .retrieve()
                    .toEntity(JsonNode.class)
                    .mapNotNull(ResponseEntity::getBody)
                    .mapNotNull(jsonNode -> jsonNode.get("access_token").asString())
                    .switchIfEmpty(Mono.error(() -> new IllegalStateException("Unable to get access token")));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1760() {
    makeHelper()
        .addSourceLines(
            "Main.java",
            """
            package org.example;

            import java.util.List;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Main {
              abstract static class Settings<S extends Settings<? extends S>> {}

              static List<? extends Settings<?>> pass(List<? extends Settings<?>> in) {
                return in;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableTypeParameterEnhancedForLoopWithWildcardHandlingDisabled() {
    makeTestHelperWithArgs(
            List.of(
                "-XepOpt:NullAway:OnlyNullMarked=true",
                JSpecifyJavacConfig.JSPECIFY_MODE_FLAG,
                JSpecifyJavacConfig.ADD_TYPE_ANNOTATIONS_FLAG))
        .addSourceLines(
            "Repro.java",
            """
            import java.util.Collection;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Repro<E extends @Nullable Object> {

              boolean add(E element) {
                return false;
              }

              boolean addAll(Collection<? extends E> elements) {
                boolean changed = false;
                for (E element : elements) {
                  if (add(element)) {
                    changed = true;
                  }
                }
                return changed;
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

  @Test
  public void aTypeVariableWhoseBoundAdmitsNullFailsANonNullWildcardRequirement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static void takeNonNull(Box<? extends Object> b) {}
              static <T extends @Nullable Object> void test(Box<T> b) {
                // BUG: Diagnostic contains: incompatible types: Box<T> cannot be converted to Box<? extends Object>
                takeNonNull(b);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aTypeVariableWithANonNullBoundMeetsANonNullWildcardRequirement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static void takeNonNull(Box<? extends Object> b) {}
              static <T> void test(Box<T> b) {
                takeNonNull(b);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aTypeVariableWhoseBoundAdmitsNullMeetsANullableWildcardRequirement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static void takeNullable(Box<? extends @Nullable Object> b) {}
              static <T extends @Nullable Object> void test(Box<T> b) {
                takeNullable(b);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aNullableWrittenOnATypeVariableUseFailsANonNullWildcardRequirement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static void takeNonNull(Box<? extends Object> b) {}
              static <T> void test(Box<@Nullable T> b) {
                // BUG: Diagnostic contains: incompatible nullability: found @Nullable T, required Object
                takeNonNull(b);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aNullableWrittenOnATypeVariableUseMeetsANullableWildcardRequirement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static void takeNullable(Box<? extends @Nullable Object> b) {}
              static <T> void test(Box<@Nullable T> b) {
                takeNullable(b);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aTypeVariableMeetsAWildcardBoundedByThatSameTypeVariable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static <T extends @Nullable Object> Box<? extends T> test(Box<T> b) {
                return b;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aTypeVariableMeetsAWildcardBoundedByThatSameTypeVariableUnderInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.concurrent.CompletableFuture;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            interface Test<V extends @Nullable Object> {
              V load();
              default CompletableFuture<? extends V> asyncLoad() {
                return CompletableFuture.supplyAsync(() -> load());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aTypeVariableWhoseBoundAdmitsNullMeetsAnUnboundedWildcardRequirement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static void takeAny(Box<?> b) {}
              static <T extends @Nullable Object> void test(Box<T> b) {
                takeAny(b);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aTypeVariableDeclaredInUnannotatedCodeFailsANonNullWildcardRequirement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NullUnmarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static void takeNonNull(Box<? extends Object> b) {}
              @NullUnmarked
              static class Holder<T> {
                @NullMarked
                void test(Box<T> b) {
                  // BUG: Diagnostic contains: incompatible types: Box<T> cannot be converted to Box<? extends Object>
                  takeNonNull(b);
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aTypeVariableBoundedByAnotherWhoseBoundAdmitsNullFailsANonNullWildcardRequirement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static void takeNonNull(Box<? extends Object> b) {}
              static <T extends @Nullable Object, S extends T> void test(Box<S> b) {
                // BUG: Diagnostic contains: incompatible types: Box<S> cannot be converted to Box<? extends Object>
                takeNonNull(b);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aTypeVariableMeetsAWildcardBoundedByTheVariableItExtends() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static class Holder<T extends @Nullable Object> {
                void takeExtendsT(Box<? extends T> b) {}
                <S extends T> void test(Box<S> b) {
                  takeExtendsT(b);
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aNonNullWrittenOnATypeVariableUseMeetsANonNullWildcardRequirement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static void takeNonNull(Box<? extends Object> b) {}
              static <T extends @Nullable Object> void test(Box<@NonNull T> b) {
                takeNonNull(b);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aNullableWrittenOnATypeVariableUseFailsAWildcardBoundedByThatVariable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static class Holder<T extends @Nullable Object> {
                void takeExtendsT(Box<? extends T> b) {}
                void test(Box<@Nullable T> b) {
                  // BUG: Diagnostic contains: incompatible nullability: found @Nullable T, required T
                  takeExtendsT(b);
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aTypeVariableThatAdmitsNullFailsAWildcardBoundedByOneThatDoesNot() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static class Holder<T> {
                void takeExtendsT(Box<? extends T> b) {}
                <S extends @Nullable T> void test(Box<S> b) {
                  // BUG: Diagnostic contains: incompatible types: Box<S> cannot be converted to Box<? extends T>
                  takeExtendsT(b);
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aTypeVariableThatAdmitsNoNullMeetsAWildcardBoundedByOneThatDoesNot() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static class Holder<T> {
                void takeExtendsT(Box<? extends T> b) {}
                <S extends T> void test(Box<S> b) {
                  takeExtendsT(b);
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aCapturedTypeArgumentMeetsANullnessAnnotatedTypeVariableRequirement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import java.util.Set;
            import java.util.concurrent.CompletableFuture;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            interface Test<K, V extends @Nullable Object> {
              Map<? extends K, ? extends @NonNull V> loadAll(Set<? extends K> keys);
              default CompletableFuture<? extends Map<? extends K, ? extends @NonNull V>> asyncLoadAll(
                  Set<? extends K> keys) {
                return CompletableFuture.supplyAsync(() -> loadAll(keys));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aCapturedTypeArgumentMeetsABareTypeVariableRequirement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            interface Test<K, V extends @Nullable Object> {
              Map<? extends K, ? extends V> loadAll();
              default Map<? extends K, ? extends V> pass() {
                return loadAll();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aTypeVariableThatMayBeNullFailsANonNullAnnotatedWildcardRequirement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static class Holder<T extends @Nullable Object> {
                void takeExtendsNonNullT(Box<? extends @NonNull T> b) {}
                void test(Box<T> b) {
                  // BUG: Diagnostic contains: incompatible types: Box<T> cannot be converted to Box<? extends T>
                  takeExtendsNonNullT(b);
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void anOverrideThatWidensANonNullProjectionInItsReturnTypeIsReported() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            interface Test<V extends @Nullable Object> {
              List<? extends @NonNull V> get();
              static <V extends @Nullable Object> Test<V> make() {
                return new Test<>() {
                  // BUG: Diagnostic contains: mismatched type parameter nullability
                  @Override public List<V> get() {
                    throw new UnsupportedOperationException();
                  }
                };
              }
            }
            """)
        .doTest();
  }

  @Test
  public void
      aWildcardActualBoundedByATypeVariableThatAdmitsNullFailsANonNullWildcardRequirement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static void takeNonNull(Box<? extends Object> b) {}
              static <T extends @Nullable Object> void test(Box<? extends T> b) {
                // BUG: Diagnostic contains: incompatible types: Box<? extends T> cannot be converted to Box<? extends Object>
                takeNonNull(b);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void unboundedWildcardErrorMessageSuggestsExplicitBound() {
    makeHelper()
        .expectErrorMessage(
            "ISSUE_1822",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable Object, required Object
                        found:    List<?>
                                       ^
                        required: Collection<? extends Object>
                                                       ^^^^^^
                        path: Collection type argument E -> wildcard upper bound
                        note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                              parameter E of List
                        did you mean List<? extends Object>?
                    """))
        .addSourceLines(
            "Test.java",
            """
            import java.util.ArrayList;
            import java.util.Collection;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              Collection<Object> copy(List<?> list) {
                List<Object> result = new ArrayList<>();
                // BUG: Diagnostic matches: ISSUE_1822
                result.addAll(list);
                return result;
              }
              Collection<Object> copyNonNull(List<? extends Object> list) {
                List<Object> result = new ArrayList<>();
                result.addAll(list);
                return result;
              }
              static void takeNullable(Collection<? extends @Nullable Object> c) {}
              void nullableTarget(List<?> list) {
                takeNullable(list);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void noSuggestionWhenAnExplicitBoundMismatchRemains() {
    makeHelper()
        .expectErrorMessage(
            "EXPLICIT_BOUND_REMAINS",
            message ->
                message.contains(
                        """
                    incompatible nullability: 2 mismatches between source and target types
                        found:    Map<?, ? extends @Nullable Object>
                                      ^            ^^^^^^^^^^^^^^^^
                                      1            2
                        required: Map<? extends Object, ? extends Object>
                                                ^^^^^^            ^^^^^^
                                                1                 2
                     \s
                        1. path: Map type argument K -> wildcard upper bound
                           found:    @Nullable Object
                           required: Object
                           note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                                 parameter K of Map
                     \s
                        2. path: Map type argument V -> wildcard upper bound
                           found:    @Nullable Object
                           required: Object
                    """)
                    && !message.contains("did you mean"))
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void take(Map<? extends Object, ? extends Object> m) {}
              void test(Map<?, ? extends @Nullable Object> m) {
                // BUG: Diagnostic matches: EXPLICIT_BOUND_REMAINS
                take(m);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void suggestionSurvivesAMismatchInTheHarmlessDirection() {
    makeHelper()
        .expectErrorMessage(
            "HARMLESS_DIRECTION",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable Object, required Object
                        found:    Box<?, ?>
                                      ^
                        required: Box<? extends Object, ? extends @Nullable Object>
                                                ^^^^^^
                        path: Box type argument T -> wildcard upper bound
                        note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                              parameter T of Box
                        did you mean Box<? extends Object, ?>?
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object, U> {}
              static void take(Box<? extends Object, ? extends @Nullable Object> b) {}
              void test(Box<?, ?> b) {
                // BUG: Diagnostic matches: HARMLESS_DIRECTION
                take(b);
              }
              void suggestedFormIsAccepted(Box<? extends Object, ?> b) {
                take(b);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void suggestionSurvivesASuperBoundedArgumentTheTargetAlsoDeclaresSuper() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
              static void take(Map<? extends Object, ? super String> m) {}
              void suggestedFormIsAccepted(Map<? extends Object, ? super String> m) {
                take(m);
              }
              void test(Map<?, ? super String> m) {
                // BUG: Diagnostic contains: did you mean Map<? extends Object, ? super String>?
                take(m);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void noSuggestionWhenBoundsDifferBelowTheTopLevel() {
    makeHelper()
        .expectErrorMessage(
            "BOUNDS_DIFFER_NESTED",
            message ->
                message.contains(
                        """
                    incompatible nullability: found @Nullable Object, required Object
                        found:    Holder<?>
                                         ^
                        required: Holder<? extends List<Object>>
                                                        ^^^^^^
                        path: Holder type argument E -> wildcard upper bound -> List type argument E
                    """)
                    && !message.contains("did you mean"))
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Holder<E extends List<@Nullable Object>> {}
              static void take(Holder<? extends List<Object>> h) {}
              void test(Holder<?> h) {
                // BUG: Diagnostic matches: BOUNDS_DIFFER_NESTED
                take(h);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void noSuggestionWhenAConcreteTypeArgumentAlsoMismatches() {
    makeHelper()
        .expectErrorMessage(
            "CONCRETE_ARGUMENT",
            message ->
                message.contains(
                        """
                    incompatible nullability: 2 mismatches between source and target types
                        found:    Map<?, @Nullable String>
                                      ^  ^^^^^^^^^^^^^^^^
                                      1  2
                        required: Map<? extends Object, String>
                                                ^^^^^^  ^^^^^^
                                                1       2
                     \s
                        1. path: Map type argument K -> wildcard upper bound
                           found:    @Nullable Object
                           required: Object
                           note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                                 parameter K of Map
                     \s
                        2. path: Map type argument V
                           found:    @Nullable String
                           required: String
                    """)
                    && !message.contains("did you mean"))
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void take(Map<? extends Object, String> m) {}
              void test(Map<?, @Nullable String> m) {
                // BUG: Diagnostic matches: CONCRETE_ARGUMENT
                take(m);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void noSuggestionWhenTheRewrittenTypeHoldsACapture() {
    makeHelper()
        .expectErrorMessage(
            "REWRITE_HOLDS_CAPTURE",
            message ->
                message.contains(
                        """
                    incompatible nullability: found @Nullable Object, required Object
                        found:    Map<List<?>, @Nullable capture of ?>
                                           ^
                        required: Map<? extends List<? extends Object>, ?>
                                                               ^^^^^^
                        path: Map type argument K -> wildcard upper bound -> List type argument E -> wildcard upper bound
                        note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                              parameter E of List
                    """)
                    && !message.contains("did you mean"))
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> Map<List<?>, T> make(List<T> l) {
                throw new RuntimeException();
              }
              static void take(Map<? extends List<? extends Object>, ?> m) {}
              void test(List<?> l) {
                // BUG: Diagnostic matches: REWRITE_HOLDS_CAPTURE
                take(make(l));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void caretSkipsATypeArgumentTheTargetAccepts() {
    makeHelper()
        .expectErrorMessage(
            "CARET_SKIPS_ACCEPTED",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable Object, required Object
                        found:    Map<? extends String, ? extends @Nullable Object>
                                                                  ^^^^^^^^^^^^^^^^
                        required: Map<? extends Object, ? extends Object>
                                                                  ^^^^^^
                        path: Map type argument V -> wildcard upper bound
                    """))
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void take(Map<? extends Object, ? extends Object> m) {}
              void accepted(Map<? extends String, ? extends Object> m) {
                take(m);
              }
              void test(Map<? extends String, ? extends @Nullable Object> m) {
                // BUG: Diagnostic matches: CARET_SKIPS_ACCEPTED
                take(m);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void bothPositionsAreMarkedWhereOneInstanceFillsTwoTypeArguments() {
    makeHelper()
        .expectErrorMessage(
            "SHARED_INSTANCE",
            message ->
                message.contains(
                    """
                    incompatible nullability: 2 mismatches between source and target types
                        found:    Map<capture of ?, capture of ?>
                                      ^^^^^^^^^^^^  ^^^^^^^^^^^^
                                      1             2
                        required: Map<? extends Object, ? extends Object>
                                                ^^^^^^            ^^^^^^
                                                1                 2
                     \s
                        1. path: Map type argument K -> wildcard upper bound
                           found:    @Nullable Object
                           required: Object
                           note: the source wildcard is the type argument for type parameter E of List
                     \s
                        2. path: Map type argument V -> wildcard upper bound
                           found:    @Nullable Object
                           required: Object
                           note: the source wildcard is the type argument for type parameter E of List
                    """))
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> Map<T, T> duplicate(List<T> x) {
                throw new RuntimeException();
              }
              static void take(Map<? extends Object, ? extends Object> m) {}
              void test(List<?> l) {
                // BUG: Diagnostic matches: SHARED_INSTANCE
                take(duplicate(l));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void caretNamesTheConcreteArgumentWhenEveryWildcardIsAccepted() {
    makeHelper()
        .expectErrorMessage(
            "CONCRETE_AMONG_ACCEPTED",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable String, required String
                        found:    Map<? extends String, @Nullable String>
                                                        ^^^^^^^^^^^^^^^^
                        required: Map<? extends Object, String>
                                                        ^^^^^^
                        path: Map type argument V
                    """))
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void take(Map<? extends Object, String> m) {}
              void accepted(Map<? extends String, String> m) {
                take(m);
              }
              void test(Map<? extends String, @Nullable String> m) {
                // BUG: Diagnostic matches: CONCRETE_AMONG_ACCEPTED
                take(m);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void caretMarksTheFailingArgumentWhereTheTargetRepeatsAType() {
    makeHelper()
        .expectErrorMessage(
            "REPEATED_ARGUMENT",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable String, required String
                        found:    Three<? extends String, String, @Nullable String>
                                                                  ^^^^^^^^^^^^^^^^
                        required: Three<? extends Object, String, String>
                                                                  ^^^^^^
                        path: Three type argument C
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Three<A, B, C extends @Nullable Object> {}
              static void take(Three<? extends Object, String, String> t) {}
              void test(Three<? extends String, String, @Nullable String> t) {
                // BUG: Diagnostic matches: REPEATED_ARGUMENT
                take(t);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void onlyTheFailingArgumentIsNamedBesideAWildcardTheTargetAccepts() {
    makeHelper()
        .expectErrorMessage(
            "ACCEPTED_BESIDE_FAILING",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable String, required String
                        found:    Three<?, String, @Nullable String>
                                                   ^^^^^^^^^^^^^^^^
                        required: Three<? extends @Nullable Object, String, String>
                                                                            ^^^^^^
                        path: Three type argument C
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Three<A, B, C extends @Nullable Object> {}
              static void take(Three<? extends @Nullable Object, String, String> t) {}
              void test(Three<?, String, @Nullable String> t) {
                // BUG: Diagnostic matches: ACCEPTED_BESIDE_FAILING
                take(t);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void suggestionRewritesEveryMismatchingWildcard() {
    makeHelper()
        .expectErrorMessage(
            "REWRITES_EVERY_WILDCARD",
            message ->
                message.contains(
                    """
                    incompatible nullability: 2 mismatches between source and target types
                        found:    Map<?, ?>
                                      ^  ^
                                      1  2
                        required: Map<? extends Object, ? extends Object>
                                                ^^^^^^            ^^^^^^
                                                1                 2
                     \s
                        1. path: Map type argument K -> wildcard upper bound
                           found:    @Nullable Object
                           required: Object
                           note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                                 parameter K of Map
                     \s
                        2. path: Map type argument V -> wildcard upper bound
                           found:    @Nullable Object
                           required: Object
                           note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                                 parameter V of Map
                     \s
                        did you mean Map<? extends Object, ? extends Object>?
                    """))
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
              static void take(Map<? extends Object, ? extends Object> m) {}
              void suggestedFormIsAccepted(Map<? extends Object, ? extends Object> m) {
                take(m);
              }
              void test(Map<?, ?> m) {
                // BUG: Diagnostic matches: REWRITES_EVERY_WILDCARD
                take(m);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void noSuggestionWhenOneMismatchCannotBeRewritten() {
    makeHelper()
        .expectErrorMessage(
            "SUPER_ARGUMENT_REMAINS",
            message ->
                message.contains(
                        """
                    incompatible nullability: 2 mismatches between source and target types
                        found:    Map<?, ? super String>
                                      ^  ^^^^^^^^^^^^^^
                                      1  2
                        required: Map<? extends Object, ? extends Object>
                                                ^^^^^^            ^^^^^^
                                                1                 2
                     \s
                        1. path: Map type argument K -> wildcard upper bound
                           found:    @Nullable Object
                           required: Object
                           note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                                 parameter K of Map
                     \s
                        2. path: Map type argument V -> wildcard upper bound
                           found:    @Nullable Object
                           required: Object
                           note: the source ? super String has no explicit upper bound, so its upper bound is inherited
                                 from type parameter V of Map
                    """)
                    && !message.contains("did you mean"))
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
              static void take(Map<? extends Object, ? extends Object> m) {}
              void test(Map<?, ? super String> m) {
                // BUG: Diagnostic matches: SUPER_ARGUMENT_REMAINS
                take(m);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void suggestionLeavesAWildcardTheTargetAllowsToBeNullable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void take(Map<? extends Object, ? extends @Nullable Object> m) {}
              void suggestedFormIsAccepted(Map<? extends Object, ?> m) {
                take(m);
              }
              void test(Map<?, ?> m) {
                // BUG: Diagnostic contains: did you mean Map<? extends Object, ?>?
                take(m);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void superWildcardArgumentIsMarkedAndGetsNoSuggestion() {
    makeHelper()
        .expectErrorMessage(
            "SUPER_WILDCARD",
            message ->
                message.contains(
                        """
                    incompatible nullability: found @Nullable Object, required Object
                        found:    List<? super String>
                                       ^^^^^^^^^^^^^^
                        required: Collection<? extends Object>
                                                       ^^^^^^
                        path: Collection type argument E -> wildcard upper bound
                        note: the source ? super String has no explicit upper bound, so its upper bound is inherited from
                              type parameter E of List
                    """)
                    && !message.contains("did you mean"))
        .addSourceLines(
            "Test.java",
            """
            import java.util.Collection;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
              static void take(Collection<? extends Object> c) {}
              void test(List<? super String> list) {
                // BUG: Diagnostic matches: SUPER_WILDCARD
                take(list);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void unboundedWildcardSuggestionInsideAnArrayElement() {
    makeHelper()
        .expectErrorMessage(
            "SUGGESTION_IN_ARRAY",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable Object, required Object
                        found:    List<?> []
                                       ^
                        required: Collection<? extends Object> []
                                                       ^^^^^^
                        path: array element -> Collection type argument E -> wildcard upper bound
                        note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                              parameter E of List
                        did you mean List<? extends Object> []?
                    """))
        .addSourceLines(
            "Test.java",
            """
            import java.util.Collection;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
              static void takeArray(Collection<? extends Object>[] c) {}
              void suggestedFormIsAccepted(List<? extends Object>[] lists) {
                takeArray(lists);
              }
              void test(List<?>[] lists) {
                // BUG: Diagnostic matches: SUGGESTION_IN_ARRAY
                takeArray(lists);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void unboundedWildcardSuggestionInsideANestedTypeArgument() {
    makeHelper()
        .expectErrorMessage(
            "SUGGESTION_IN_NESTED_ARGUMENT",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable Object, required Object
                        found:    List<List<?>>
                                            ^
                        required: Collection<? extends List<? extends Object>>
                                                                      ^^^^^^
                        path: Collection type argument E -> wildcard upper bound -> List type argument E
                              -> wildcard upper bound
                        note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                              parameter E of List
                        did you mean List<List<? extends Object>>?
                    """))
        .addSourceLines(
            "Test.java",
            """
            import java.util.Collection;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
              static void takeNested(Collection<? extends List<? extends Object>> c) {}
              void suggestedFormIsAccepted(List<List<? extends Object>> lists) {
                takeNested(lists);
              }
              void test(List<List<?>> lists) {
                // BUG: Diagnostic matches: SUGGESTION_IN_NESTED_ARGUMENT
                takeNested(lists);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void unboundedWildcardSuggestionInAnEnclosingTypeArgument() {
    makeHelper()
        .expectErrorMessage(
            "SUGGESTION_IN_ENCLOSING_ARGUMENT",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable Object, required Object
                        found:    Outer<?>.Inner
                                        ^
                        required: Outer<? extends Object>.Inner
                                                  ^^^^^^
                        path: enclosing type -> Outer type argument T -> wildcard upper bound
                        note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                              parameter T of Outer
                        did you mean Outer<? extends Object>.Inner?
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Outer<T extends @Nullable Object> {
                class Inner {}
              }
              static void take(Outer<? extends Object>.Inner inner) {}
              void suggestedFormIsAccepted(Outer<? extends Object>.Inner inner) {
                take(inner);
              }
              void test(Outer<?>.Inner inner) {
                // BUG: Diagnostic matches: SUGGESTION_IN_ENCLOSING_ARGUMENT
                take(inner);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void suggestsTheRequiredTypeAtAVariableDeclaration() {
    makeHelper()
        .expectErrorMessage(
            "TARGET_FIX",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable String, required String
                        found:    Map<? extends String, @Nullable String>
                                                        ^^^^^^^^^^^^^^^^
                        required: Map<? extends Object, String>
                                                        ^^^^^^
                        path: Map type argument V
                        consider changing the required type to:
                          Map<? extends Object, @Nullable String>
                    """))
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              Map<? extends Object, @Nullable String> suggestedFormIsAccepted(
                  Map<? extends String, @Nullable String> m) {
                Map<? extends Object, @Nullable String> accepted = m;
                return accepted;
              }
              Map<? extends Object, String> test(Map<? extends String, @Nullable String> m) {
                // BUG: Diagnostic matches: TARGET_FIX
                Map<? extends Object, String> declared = m;
                return declared;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void noSuggestedRequiredTypeAtACallSite() {
    makeHelper()
        .expectErrorMessage(
            "CALL_SITE",
            message ->
                message.contains(
                        "incompatible nullability: found @Nullable String, required String")
                    && !message.contains("consider changing the required type"))
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void take(Map<? extends Object, String> m) {}
              void test(Map<? extends String, @Nullable String> m) {
                // BUG: Diagnostic matches: CALL_SITE
                take(m);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void listsAtMostFiveMismatchesAndSaysHowManyMoreThereAre() {
    makeHelper()
        .expectErrorMessage(
            "SIX_MISMATCHES",
            message ->
                message.contains(
                    """
                    incompatible nullability: 6 mismatches between source and target types
                        found:    Six<?, ?, ?, ?, ?, ?>
                                      ^  ^  ^  ^  ^
                                      1  2  3  4  5
                        required: Six<? extends Object, ? extends Object, ? extends Object, ? extends Object, ? extends Object, ? extends Object>
                                                ^^^^^^            ^^^^^^            ^^^^^^            ^^^^^^            ^^^^^^
                                                1                 2                 3                 4                 5
                     \s
                        1. path: Six type argument A -> wildcard upper bound
                           found:    @Nullable Object
                           required: Object
                           note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                                 parameter A of Six
                     \s
                        2. path: Six type argument B -> wildcard upper bound
                           found:    @Nullable Object
                           required: Object
                           note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                                 parameter B of Six
                     \s
                        3. path: Six type argument C -> wildcard upper bound
                           found:    @Nullable Object
                           required: Object
                           note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                                 parameter C of Six
                     \s
                        4. path: Six type argument D -> wildcard upper bound
                           found:    @Nullable Object
                           required: Object
                           note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                                 parameter D of Six
                     \s
                        5. path: Six type argument E -> wildcard upper bound
                           found:    @Nullable Object
                           required: Object
                           note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                                 parameter E of Six
                     \s
                        and 1 more, not listed
                     \s
                        did you mean Six<? extends Object, ? extends Object, ? extends Object, ? extends Object, ? extends Object, ? extends Object>?
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Six<
                  A extends @Nullable Object,
                  B extends @Nullable Object,
                  C extends @Nullable Object,
                  D extends @Nullable Object,
                  E extends @Nullable Object,
                  F extends @Nullable Object> {}
              static void take(
                  Six<
                      ? extends Object,
                      ? extends Object,
                      ? extends Object,
                      ? extends Object,
                      ? extends Object,
                      ? extends Object> six) {}
              void test(Six<?, ?, ?, ?, ?, ?> six) {
                // BUG: Diagnostic matches: SIX_MISMATCHES
                take(six);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void invariantTypeArgumentIsReportedWhenTheSourceIsLessNullableThanRequired() {
    makeHelper()
        .expectErrorMessage(
            "REVERSE_INVARIANCE",
            message ->
                !message.contains("consider changing the required type")
                    && message.contains(
                        """
                    incompatible nullability: a type argument must match exactly; found String, required @Nullable String
                        found:    List<String>
                                       ^^^^^^
                        required: List<@Nullable String>
                                       ^^^^^^^^^^^^^^^^
                        path: List type argument E
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              void test(java.util.List<String> source) {
                // BUG: Diagnostic matches: REVERSE_INVARIANCE
                java.util.List<@Nullable String> target = source;
                target.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void adjacentTypeArgumentsThatBothMismatchAreNumberedSideBySide() {
    makeHelper()
        .expectErrorMessage(
            "ADJACENT_MISMATCHES",
            message ->
                message.contains(
                    """
                    incompatible nullability: 2 mismatches between source and target types
                        found:    Map<@Nullable String, @Nullable String>
                                      ^^^^^^^^^^^^^^^^  ^^^^^^^^^^^^^^^^
                                      1                 2
                        required: Map<String, String>
                                      ^^^^^^  ^^^^^^
                                      1       2
                     \s
                        1. path: Map type argument K
                           found:    @Nullable String
                           required: String
                     \s
                        2. path: Map type argument V
                           found:    @Nullable String
                           required: String
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void take(java.util.Map<String, String> m) {}
              void test(java.util.Map<@Nullable String, @Nullable String> m) {
                // BUG: Diagnostic matches: ADJACENT_MISMATCHES
                take(m);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void onlyTheInnermostDifferingNodeIsMarkedInsideAWildcardBound() {
    makeHelper()
        .expectErrorMessage(
            "MINIMAL_SPAN",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable String, required String
                        found:    List<? extends List<@Nullable String>>
                                                      ^^^^^^^^^^^^^^^^
                        required: List<? extends List<String>>
                                                      ^^^^^^
                        path: List type argument E -> wildcard upper bound -> List type argument E
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void take(java.util.List<? extends java.util.List<String>> l) {}
              void test(java.util.List<? extends java.util.List<@Nullable String>> l) {
                // BUG: Diagnostic matches: MINIMAL_SPAN
                take(l);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mismatchesAtDifferentDepthsEachGetTheirOwnPath() {
    makeHelper()
        .expectErrorMessage(
            "DIFFERENT_DEPTHS",
            message ->
                message.contains(
                    """
                    incompatible nullability: 2 mismatches between source and target types
                        found:    Map<@Nullable String, List<? extends Collection<@Nullable Integer>>>
                                      ^^^^^^^^^^^^^^^^                            ^^^^^^^^^^^^^^^^^
                                      1                                           2
                        required: Map<String, List<? extends Collection<Integer>>>
                                      ^^^^^^                            ^^^^^^^
                                      1                                 2
                     \s
                        1. path: Map type argument K
                           found:    @Nullable String
                           required: String
                     \s
                        2. path: Map type argument V -> List type argument E -> wildcard upper bound
                                 -> Collection type argument E
                           found:    @Nullable Integer
                           required: Integer
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void take(java.util.Map<String, java.util.List<? extends java.util.Collection<Integer>>> m) {}
              void test(
                  java.util.Map<@Nullable String, java.util.List<? extends java.util.Collection<@Nullable Integer>>> m) {
                // BUG: Diagnostic matches: DIFFERENT_DEPTHS
                take(m);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void twoMismatchesInsideOneTypeArgumentSharePathPrefix() {
    makeHelper()
        .expectErrorMessage(
            "SHARED_PREFIX",
            message ->
                message.contains(
                    """
                    incompatible nullability: 2 mismatches between source and target types
                        found:    Map<String, Map<@Nullable String, @Nullable Integer>>
                                                  ^^^^^^^^^^^^^^^^  ^^^^^^^^^^^^^^^^^
                                                  1                 2
                        required: Map<String, Map<String, Integer>>
                                                  ^^^^^^  ^^^^^^^
                                                  1       2
                     \s
                        1. path: Map type argument V -> Map type argument K
                           found:    @Nullable String
                           required: String
                     \s
                        2. path: Map type argument V -> Map type argument V
                           found:    @Nullable Integer
                           required: Integer
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void take(java.util.Map<String, java.util.Map<String, Integer>> m) {}
              void test(java.util.Map<String, java.util.Map<@Nullable String, @Nullable Integer>> m) {
                // BUG: Diagnostic matches: SHARED_PREFIX
                take(m);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void pathRepeatsTheTypeParameterNameAtEveryLevelThatSharesIt() {
    makeHelper()
        .expectErrorMessage(
            "REPEATED_PARAMETER_NAME",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable String, required String
                        found:    List<Collection<List<@Nullable String>>>
                                                       ^^^^^^^^^^^^^^^^
                        required: List<Collection<List<String>>>
                                                       ^^^^^^
                        path: List type argument E -> Collection type argument E -> List type argument E
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void take(java.util.List<java.util.Collection<java.util.List<String>>> l) {}
              void test(java.util.List<java.util.Collection<java.util.List<@Nullable String>>> l) {
                // BUG: Diagnostic matches: REPEATED_PARAMETER_NAME
                take(l);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void pathNamesTheTypeParameterTheDeclarationDeclared() {
    makeHelper()
        .expectErrorMessage(
            "USER_DEFINED_PARAMETER_NAME",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable String, required String
                        found:    Result<String, @Nullable String>
                                                 ^^^^^^^^^^^^^^^^
                        required: Result<String, String>
                                                 ^^^^^^
                        path: Result type argument Failure
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Result<Success extends @Nullable Object, Failure extends @Nullable Object> {}
              static void take(Result<String, String> r) {}
              void test(Result<String, @Nullable String> r) {
                // BUG: Diagnostic matches: USER_DEFINED_PARAMETER_NAME
                take(r);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void anExplicitlyWrittenNullableBoundDrawsNoInheritedBoundNote() {
    makeHelper()
        .expectErrorMessage(
            "EXPLICIT_NULLABLE_BOUND",
            message ->
                message.contains(
                        """
                    incompatible nullability: found @Nullable String, required String
                        found:    List<? extends @Nullable String>
                                                 ^^^^^^^^^^^^^^^^
                        required: List<? extends String>
                                                 ^^^^^^
                        path: List type argument E -> wildcard upper bound
                    """)
                    && !message.contains("note:"))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void take(java.util.List<? extends String> l) {}
              void test(java.util.List<? extends @Nullable String> l) {
                // BUG: Diagnostic matches: EXPLICIT_NULLABLE_BOUND
                take(l);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aLowerBoundDifferenceFallsBackToThePlainIncompatibleTypesMessage() {
    makeHelper()
        .expectErrorMessage(
            "LOWER_BOUND_FALLBACK",
            message ->
                message.contains(
                        """
                    incompatible types: List<? super String> cannot be converted to List<? super @Nullable String>
                    """)
                    && !message.contains("path:"))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void takeNonNullLower(java.util.List<? super String> l) {}
              static void takeNullableLower(java.util.List<? super @Nullable String> l) {}
              void nullableSourceLowerBound(java.util.List<? super @Nullable String> l) {
                takeNonNullLower(l);
              }
              void nonNullSourceLowerBound(java.util.List<? super String> l) {
                // BUG: Diagnostic matches: LOWER_BOUND_FALLBACK
                takeNullableLower(l);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void noInheritedBoundNoteWhereTheUseStatesItsOwnNullness() {
    makeHelper()
        .expectErrorMessage(
            "NULLABLE_TYPE_VARIABLE_USE",
            message ->
                !message.contains("note:")
                    && message.contains(
                        """
                        incompatible nullability: found @Nullable T, required Object
                            found:    Box<@Nullable T>
                                          ^^^^^^^^^^^
                            required: Box<? extends Object>
                                                    ^^^^^^
                            path: Box type argument T -> wildcard upper bound
                        """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static void takeNonNull(Box<? extends Object> b) {}
              static <T> void theBoundAloneIsAccepted(Box<T> b) {
                takeNonNull(b);
              }
              static <T> void test(Box<@Nullable T> b) {
                // BUG: Diagnostic matches: NULLABLE_TYPE_VARIABLE_USE
                takeNonNull(b);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aTypeVariableWithANullableBoundIsReportedBesideAMismatchingSibling() {
    makeHelper()
        .expectErrorMessage(
            "TYPE_VARIABLE_ARGUMENT",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable String, required String
                        found:    Pair<T, @Nullable String>
                                          ^^^^^^^^^^^^^^^^
                        required: Pair<? extends Object, String>
                                                         ^^^^^^
                        path: Pair type argument B
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Pair<A extends @Nullable Object, B extends @Nullable Object> {}
              static void take(Pair<? extends Object, String> p) {}
              static <T extends @Nullable Object> void test(Pair<T, @Nullable String> p) {
                // BUG: Diagnostic matches: TYPE_VARIABLE_ARGUMENT
                take(p);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aMismatchInsideAnArrayElementTypeArgumentIsMarked() {
    makeHelper()
        .expectErrorMessage(
            "ARRAY_ELEMENT_ARGUMENT",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable String, required String
                        found:    List<@Nullable String> []
                                       ^^^^^^^^^^^^^^^^
                        required: List<String> []
                                       ^^^^^^
                        path: array element -> List type argument E
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void take(java.util.List<String>[] l) {}
              void test(java.util.List<@Nullable String>[] l) {
                // BUG: Diagnostic matches: ARRAY_ELEMENT_ARGUMENT
                take(l);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aNullableArrayComponentTypeIsMarkedInsideATypeArgument() {
    makeHelper()
        .expectErrorMessage(
            "NULLABLE_COMPONENT",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable String, required String
                        found:    List<@Nullable String []>
                                       ^^^^^^^^^^^^^^^^
                        required: List<String []>
                                       ^^^^^^
                        path: List type argument E -> array element
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void take(java.util.List<String[]> l) {}
              void test(java.util.List<@Nullable String[]> l) {
                // BUG: Diagnostic matches: NULLABLE_COMPONENT
                take(l);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aNullableArrayTypeIsMarkedInsideATypeArgument() {
    makeHelper()
        .expectErrorMessage(
            "NULLABLE_ARRAY",
            message ->
                message.contains(
                    """
                    incompatible nullability: found String @Nullable [], required String []
                        found:    List<String @Nullable []>
                                       ^^^^^^^^^^^^^^^^^^^
                        required: List<String []>
                                       ^^^^^^^^^
                        path: List type argument E
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void take(java.util.List<String[]> l) {}
              void test(java.util.List<String @Nullable []> l) {
                // BUG: Diagnostic matches: NULLABLE_ARRAY
                take(l);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void pathKeepsTheEnclosingTypeApartFromTheInnerType() {
    makeHelper()
        .expectErrorMessage(
            "ENCLOSING_AND_INNER",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable Integer, required Integer
                        found:    Outer<String>.Inner<@Nullable Integer>
                                                      ^^^^^^^^^^^^^^^^^
                        required: Outer<String>.Inner<Integer>
                                                      ^^^^^^^
                        path: Inner type argument U
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Outer<T extends @Nullable Object> {
                class Inner<U extends @Nullable Object> {}
              }
              static void take(Outer<String>.Inner<Integer> i) {}
              void test(Outer<String>.Inner<@Nullable Integer> i) {
                // BUG: Diagnostic matches: ENCLOSING_AND_INNER
                take(i);
              }
            }
            """)
        .doTest();
  }

  // b.Result extends a.Result, so the assignment reaches NullAway and both sides print as
  // "Result".
  @Test
  public void simpleNamesAloneDoNotShowThatTheTwoTypesComeFromDifferentPackages() {
    makeHelper()
        .addSourceLines(
            "a/Result.java",
            """
            package a;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            public class Result<T extends @Nullable Object> {}
            """)
        .addSourceLines(
            "b/Result.java",
            """
            package b;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            public class Result<T extends @Nullable Object> extends a.Result<T> {}
            """)
        .expectErrorMessage(
            "SAME_SIMPLE_NAME",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable String, required String
                        found:    Result<@Nullable String>
                        found as: Result<@Nullable String>
                                         ^^^^^^^^^^^^^^^^
                        required: Result<String>
                                         ^^^^^^
                        path: Result type argument T
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void take(a.Result<String> r) {}
              void test(b.Result<@Nullable String> r) {
                // BUG: Diagnostic matches: SAME_SIMPLE_NAME
                take(r);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aRawSourceTypeIsAcceptedWithNoMismatchToReport() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
              @SuppressWarnings("rawtypes")
              void test(List raw) {
                List<String> typed = raw;
                typed.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void theSuggestedRequiredTypeIsTheDeclaredTypeNotAnInstantiatedSupertype() {
    makeHelper()
        .expectErrorMessage(
            "DECLARED_NOT_SUPERTYPE",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable String, required String
                        found:    Sub<@Nullable String>
                        found as: List<@Nullable String>
                                       ^^^^^^^^^^^^^^^^
                        required: List<String>
                                       ^^^^^^
                        path: List type argument E
                        consider changing the required type to:
                          List<@Nullable String>
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Sub<T extends @Nullable Object> extends java.util.ArrayList<T> {}
              void test(Sub<@Nullable String> s) {
                // BUG: Diagnostic matches: DECLARED_NOT_SUPERTYPE
                java.util.List<String> l = s;
                l.hashCode();
              }
            }
            """)
        .doTest();
  }

  // The printed types carry no @Tainted: dropping it is what the printer does, and what the repair
  // may not do, so the two methods below differ in the message only by the repair line.
  @Test
  public void noSourceRepairWhereItsPrintedFormWouldDropAnnotations() {
    makeHelper()
        .expectErrorMessage(
            "SOURCE_REPAIR_DROPS_ANNOTATIONS",
            message ->
                !message.contains("did you mean")
                    && message.contains(
                        """
                        incompatible nullability: found @Nullable Object, required Object
                            found:    Map<?, String>
                                          ^
                            required: Map<? extends Object, String>
                                                    ^^^^^^
                            path: Map type argument K -> wildcard upper bound
                            note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                                  parameter K of Map
                        """))
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
              @interface Tainted {}
              static void take(Map<? extends Object, String> m) {}
              void withoutTheAnnotationTheRepairIsOffered(Map<?, String> m) {
                // BUG: Diagnostic contains: did you mean Map<? extends Object, String>?
                take(m);
              }
              void test(Map<?, @Tainted String> m) {
                // BUG: Diagnostic matches: SOURCE_REPAIR_DROPS_ANNOTATIONS
                take(m);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void noSuggestedRequiredTypeWhereItsPrintedFormWouldDropAnnotations() {
    makeHelper()
        .expectErrorMessage(
            "OTHER_ANNOTATIONS",
            message ->
                !message.contains("consider changing the required type")
                    && message.contains(
                        """
                    incompatible nullability: found @Nullable String, required String
                        found:    Map<String, @Nullable String>
                                              ^^^^^^^^^^^^^^^^
                        required: Map<String, String>
                                              ^^^^^^
                        path: Map type argument V
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
              @interface Tainted {}
              void test(java.util.Map<@Tainted String, @Nullable String> m) {
                // BUG: Diagnostic matches: OTHER_ANNOTATIONS
                java.util.Map<@Tainted String, String> declared = m;
                declared.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void theSourceRepairIsStillOfferedWhereTheRequiredTypeIsNotEditable() {
    makeHelper()
        .expectErrorMessage(
            "SOURCE_REPAIR_AT_CALL",
            message ->
                message.contains(
                        """
                    incompatible nullability: found @Nullable Object, required Object
                        found:    List<?>
                                       ^
                        required: Collection<? extends Object>
                                                       ^^^^^^
                        path: Collection type argument E -> wildcard upper bound
                        note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                              parameter E of List
                        did you mean List<? extends Object>?
                    """)
                    && !message.contains("consider changing the required type"))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void take(java.util.Collection<? extends Object> c) {}
              void test(java.util.List<?> l) {
                // BUG: Diagnostic matches: SOURCE_REPAIR_AT_CALL
                take(l);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void noSuggestedRequiredTypeAtAnAssignmentToAnExistingVariable() {
    makeHelper()
        .expectErrorMessage(
            "ASSIGNMENT_TARGET",
            message ->
                message.contains(
                        """
                    incompatible nullability: found @Nullable String, required String
                        found:    Map<? extends String, @Nullable String>
                                                        ^^^^^^^^^^^^^^^^
                        required: Map<? extends Object, String>
                                                        ^^^^^^
                        path: Map type argument V
                    """)
                    && !message.contains("consider changing the required type"))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void take(java.util.Map<? extends Object, String> m) {}
              void test(java.util.Map<? extends String, @Nullable String> m) {
                java.util.Map<? extends Object, String> declared = someMap();
                // BUG: Diagnostic matches: ASSIGNMENT_TARGET
                declared = m;
                declared.hashCode();
              }
              static java.util.Map<? extends Object, String> someMap() {
                throw new RuntimeException();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void noSuggestedRequiredTypeAtAReturnStatement() {
    makeHelper()
        .expectErrorMessage(
            "RETURN_TARGET",
            message ->
                message.contains(
                        """
                    incompatible nullability: found @Nullable String, required String
                        found:    Map<? extends String, @Nullable String>
                                                        ^^^^^^^^^^^^^^^^
                        required: Map<? extends Object, String>
                                                        ^^^^^^
                        path: Map type argument V
                    """)
                    && !message.contains("consider changing the required type"))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              java.util.Map<? extends Object, String> test(java.util.Map<? extends String, @Nullable String> m) {
                // BUG: Diagnostic matches: RETURN_TARGET
                return m;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void listsFiveMismatchesWithoutSayingThereAreMore() {
    makeHelper()
        .expectErrorMessage(
            "FIVE_MISMATCHES",
            message ->
                message.contains(
                        """
                    incompatible nullability: 5 mismatches between source and target types
                        found:    Five<?, ?, ?, ?, ?>
                                       ^  ^  ^  ^  ^
                                       1  2  3  4  5
                        required: Five<? extends Object, ? extends Object, ? extends Object, ? extends Object, ? extends Object>
                                                 ^^^^^^            ^^^^^^            ^^^^^^            ^^^^^^            ^^^^^^
                                                 1                 2                 3                 4                 5
                     \s
                        1. path: Five type argument A -> wildcard upper bound
                           found:    @Nullable Object
                           required: Object
                           note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                                 parameter A of Five
                     \s
                        2. path: Five type argument B -> wildcard upper bound
                           found:    @Nullable Object
                           required: Object
                           note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                                 parameter B of Five
                     \s
                        3. path: Five type argument C -> wildcard upper bound
                           found:    @Nullable Object
                           required: Object
                           note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                                 parameter C of Five
                     \s
                        4. path: Five type argument D -> wildcard upper bound
                           found:    @Nullable Object
                           required: Object
                           note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                                 parameter D of Five
                     \s
                        5. path: Five type argument E -> wildcard upper bound
                           found:    @Nullable Object
                           required: Object
                           note: the source ? has no explicit upper bound, so its upper bound is inherited from type
                                 parameter E of Five
                     \s
                        did you mean Five<? extends Object, ? extends Object, ? extends Object, ? extends Object, ? extends Object>?
                    """)
                    && !message.contains("more, not listed"))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Five<
                  A extends @Nullable Object,
                  B extends @Nullable Object,
                  C extends @Nullable Object,
                  D extends @Nullable Object,
                  E extends @Nullable Object> {}
              static void take(
                  Five<
                      ? extends Object,
                      ? extends Object,
                      ? extends Object,
                      ? extends Object,
                      ? extends Object> five) {}
              void test(Five<?, ?, ?, ?, ?> five) {
                // BUG: Diagnostic matches: FIVE_MISMATCHES
                take(five);
              }
            }
            """)
        .doTest();
  }

  // Sub and Base declare the same type parameter with different nullness bounds, so the two
  // unbounded wildcards print alike and differ only in the bound each inherits.
  @Test
  public void theNoteStatesTheBoundAndNamesNoTypeParameterAcrossAView() {
    makeHelper()
        .expectErrorMessage(
            "PROVENANCE_ACROSS_VIEW",
            message ->
                !message.contains("type parameter")
                    && message.contains(
                        """
                        incompatible nullability: found @Nullable Object, required Object
                            found:    Sub<?>
                            found as: Base<?>
                                           ^
                            required: Base<?>
                                           ^
                            path: Base type argument B -> wildcard upper bound
                            note: the source wildcard has an implicit @Nullable Object upper bound
                        """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Base<B> {}
              static class Sub<S extends @Nullable Object> extends Base<S> {}
              static void take(Base<?> b) {}
              void test(Sub<?> s) {
                // BUG: Diagnostic matches: PROVENANCE_ACROSS_VIEW
                take(s);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aPathAcrossAViewReachesIntoWhateverTheSubclassPassed() {
    makeHelper()
        .expectErrorMessage(
            "NO_WRITTEN_ARGUMENT",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable Object, required Object
                        found:    Indirect<?>
                        found as: Base<List<?>>
                                            ^
                        required: Base<? extends List<? extends Object>>
                                                                ^^^^^^
                        path: Base type argument B -> wildcard upper bound -> List type argument E -> wildcard upper bound
                        note: the source wildcard has an implicit @Nullable Object upper bound
                    """))
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Base<B> {}
              static class Indirect<X extends @Nullable Object> extends Base<List<X>> {}
              static void take(Base<? extends List<? extends Object>> b) {}
              void test(Indirect<?> i) {
                // BUG: Diagnostic matches: NO_WRITTEN_ARGUMENT
                take(i);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void theFallbackMessageStatesTheSubtypeRelationBetweenTheTwoTypes() {
    makeHelper()
        .expectErrorMessage(
            "SUBTYPE_CLAUSE",
            message ->
                message.contains(
                    """
                    incompatible types: Sub<? super String> cannot be converted to Base<? super @Nullable String> (Sub<? super String> is a subtype of Base<? super String>)
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Base<T extends @Nullable Object> {}
              static class Sub<T extends @Nullable Object> extends Base<T> {}
              static void take(Base<? super @Nullable String> b) {}
              void test(Sub<? super String> s) {
                // BUG: Diagnostic matches: SUBTYPE_CLAUSE
                take(s);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aMismatchInsideAWildcardBoundIsMarkedOnTheViewedSource() {
    makeHelper()
        .expectErrorMessage(
            "VIEWED_WILDCARD_BOUND",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable String, required String
                        found:    Sub<? extends List<@Nullable String>>
                        found as: Base<? extends List<@Nullable String>>
                                                      ^^^^^^^^^^^^^^^^
                        required: Base<? extends List<String>>
                                                      ^^^^^^
                        path: Base type argument T -> wildcard upper bound -> List type argument E
                    """))
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Base<T extends @Nullable Object> {}
              static class Sub<T extends @Nullable Object> extends Base<T> {}
              static void take(Base<? extends List<String>> b) {}
              void test(Sub<? extends List<@Nullable String>> s) {
                // BUG: Diagnostic matches: VIEWED_WILDCARD_BOUND
                take(s);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aMismatchInsideAnArrayElementIsMarkedOnTheViewedSource() {
    makeHelper()
        .expectErrorMessage(
            "VIEWED_ARRAY_ELEMENT",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable String, required String
                        found:    Sub<@Nullable String []>
                        found as: Base<@Nullable String []>
                                       ^^^^^^^^^^^^^^^^
                        required: Base<String []>
                                       ^^^^^^
                        path: Base type argument T -> array element
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Base<T extends @Nullable Object> {}
              static class Sub<T extends @Nullable Object> extends Base<T> {}
              static void take(Base<String[]> b) {}
              void test(Sub<@Nullable String[]> s) {
                // BUG: Diagnostic matches: VIEWED_ARRAY_ELEMENT
                take(s);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aCapturedWildcardIsMarkedOnTheViewedSourceAndKeepsItsProvenance() {
    makeHelper()
        .expectErrorMessage(
            "VIEWED_CAPTURE",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable Object, required Object
                        found:    Sub<capture of ?>
                        found as: Base<capture of ?>
                                       ^^^^^^^^^^^^
                        required: Base<? extends Object>
                                                 ^^^^^^
                        path: Base type argument T -> wildcard upper bound
                        note: the source wildcard is the type argument for type parameter T of Sub
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Base<T extends @Nullable Object> {}
              static class Sub<T extends @Nullable Object> extends Base<T> {}
              static void take(Base<? extends Object> b) {}
              static Sub<?> make() {
                throw new RuntimeException();
              }
              void test() {
                // BUG: Diagnostic matches: VIEWED_CAPTURE
                take(make());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void twoUnboundedWildcardsThatDifferOnlyInTheirInheritedBounds() {
    makeHelper()
        .expectErrorMessage(
            "INHERITED_BOUNDS_DIFFER",
            message ->
                message.contains(
                    """
                    incompatible nullability: found @Nullable Object, required Object
                        found:    Sub<?>
                        found as: Base<?>
                                       ^
                        required: Base<?>
                                       ^
                        path: Base type argument T -> wildcard upper bound
                        note: the source wildcard has an implicit @Nullable Object upper bound
                    """))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Base<T> {}
              static class Sub<T extends @Nullable Object> extends Base<T> {}
              static void take(Base<?> b) {}
              void test(Sub<?> s) {
                // BUG: Diagnostic matches: INHERITED_BOUNDS_DIFFER
                take(s);
              }
            }
            """)
        .doTest();
  }
}
