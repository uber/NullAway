package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;

public class WildcardTests extends NullAwayTestsBase {

  /**
   * Regression for <a href="https://github.com/uber/NullAway/issues/2103">#2103</a>: restoring
   * annotations after inference with F-bounded wildcards must handle a recursive bound that refers
   * to a second type parameter without crashing.
   */
  @Test
  public void fBoundedWildcardWithSecondTypeParameterDoesNotCrash() {
    makeHelper()
        .addSourceLines(
            "Repro.java",
            """
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Repro {
              interface A<S extends A<S, T>, T> {}
              interface B<S extends B<S>> extends A<S, String> {}
              interface C<S extends C<S>> extends A<S, String> {}

              static <T> T pick(T a, T b, T c) {
                return a;
              }

              A<?, ?> repeatedSecondArgument(B<?> b, C<?> c) {
                return pick(b, c, c);
              }

              A<?, ?> repeatedFirstArgument(B<?> b, C<?> c) {
                return pick(b, b, c);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1909FBoundedWildcardReturnDoesNotCrash() {
    makeHelper()
        .addSourceLines(
            "Repro.java",
            """
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Repro {
              abstract static class Base<S extends Base<S>> {}
              abstract static class X<S extends X<S>> extends Base<S> {}
              abstract static class Y<S extends Y<S>> extends Base<S> {}

              static <T> T pick(T a, T b, T c) {
                return a;
              }

              static <T> T pick(T a, T b) {
                return a;
              }

              Base<?> repeatedSecondArgument(X<?> x, Y<?> y) {
                return pick(x, y, y);
              }

              Base<?> repeatedFirstArgument(X<?> x, Y<?> y) {
                return pick(x, y, x);
              }

              Base<?> twoArguments(X<?> x, Y<?> y) {
                return pick(x, y);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericCallOnWildcardReceiver() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              interface Box<T extends @Nullable Object> {
                <R extends @Nullable Object> R put(T value, R result);
              }

              void test(Box<? super String> box, Box<? extends Object> nonNullBox,
                  @Nullable String nullableValue) {
                // Substituting Box.T makes put's first parameter a top-level wildcard.
                String result = box.put("value", "result");
                result.length();
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                nonNullBox.put(null, "result").length();
                var nullableResult = box.put("value", nullableValue);
                // BUG: Diagnostic contains: dereferenced expression 'nullableResult' is @Nullable
                nullableResult.length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1934GenericCallWithSelfBoundedWildcardTarget() {
    makeHelper()
        .addSourceLines(
            "Repro.java",
            """
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Repro {
              abstract static class Base<SELF extends Base<SELF>> {}
              static class Impl<A> extends Base<Impl<A>> {}

              static <T> Impl<T> make() {
                throw new RuntimeException();
              }

              void m() {
                Base<?> b = make();
                Base<?> explicit = Repro.<String>make();
              }

              Base<?> returnValue() {
                return make();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void selfBoundedWildcardTargetPreservesInferenceConstraints() {
    makeHelper()
        .addSourceLines(
            "Repro.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Repro {
              abstract static class Base<SELF extends Base<SELF, ?>, V extends @Nullable Object> {}
              static class Impl<A extends @Nullable Object> extends Base<Impl<A>, A> {}

              static <T extends @Nullable Object> Impl<T> make(T value) {
                throw new RuntimeException();
              }

              void m() {
                Base<?, @Nullable String> nullable = make(null);
                Base<?, String> nonNull = make("value");
                // BUG: Diagnostic contains: inference failure: type variable T constrained to be both @NonNull and @Nullable
                Base<?, String> invalid = make(null);
              }

              Base<?, @Nullable String> nullableReturn() {
                return make(null);
              }

              Base<?, String> invalidReturn() {
                // BUG: Diagnostic contains: inference failure: type variable T constrained to be both @NonNull and @Nullable
                return make(null);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1897WildcardWithRawGenericBoundDoesNotCrash() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              static class InitActivity<T> {}

              abstract static class ActivityPlace<T extends InitActivity> {
                abstract T getActivity();
              }

              abstract static class AbstractTabPlace<T extends InitActivity<?>>
                  extends ActivityPlace<T> {}

              static Object getActivity(AbstractTabPlace<?> tabPlace) {
                // Regression for #1897: attempting to restore annotations from ActivityPlace's raw
                // InitActivity upper bound for T onto the captured InitActivity<?> return type
                // previously caused a crash.
                return tabPlace.getActivity();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nonNullTypeVariableThroughWildcardCapture() {
    // https://github.com/uber/NullAway/issues/1823
    // Reproducer from https://github.com/ben-manes/caffeine/issues/2004#issuecomment-5467003874
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.concurrent.ConcurrentMap;
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              interface Holder<V extends @Nullable Object> {
                // IMPORTANT: Explicit @NonNull annotation on these methods:
                @NonNull V value();
                ConcurrentMap<String, @NonNull V> asMap();
              }

              static void takesNonNull(Object value) {}

              static void explicitNullableArgument(Holder<@Nullable Object> holder) {
                takesNonNull(holder.value());
                holder.asMap().entrySet().forEach(e -> takesNonNull(e.getValue()));
              }

              static void nonNullBoundedWildcard(Holder<? extends Object> holder) {
                takesNonNull(holder.value());
                holder.asMap().entrySet().forEach(e -> takesNonNull(e.getValue()));
              }

              static void unboundedWildcard(Holder<?> holder) {
                takesNonNull(holder.value());
                holder.asMap().entrySet().forEach(e -> takesNonNull(e.getValue()));
              }

              static void nullableBoundedWildcard(Holder<? extends @Nullable Object> holder) {
                takesNonNull(holder.value());
                holder.asMap().entrySet().forEach(e -> takesNonNull(e.getValue()));
              }

              static void superBoundedWildcard(Holder<? super String> holder) {
                takesNonNull(holder.value());
                holder.asMap().entrySet().forEach(e -> takesNonNull(e.getValue()));
              }
            }
            """)
        .doTest();
  }

  /**
   * Checks that inherited {@code @NonNull V} returns remain non-null through unbounded and super
   * wildcards. Interleaving calls to an inherited plain {@code V} return checks that restoring
   * {@code @NonNull} does not mutate shared wildcard bounds: the plain return must remain nullable,
   * and subsequent explicitly non-null returns must still be accepted.
   */
  @Test
  public void nonNullWildcardMemberDoesNotChangeOtherInheritedMembers() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              interface Holder<V extends @Nullable Object> {
                @NonNull V nonNullValue();
                V value();
              }
              interface Child<V extends @Nullable Object> extends Holder<V> {}

              static void takesNonNull(Object value) {}

              static void unbounded(Child<?> holder) {
                takesNonNull(holder.nonNullValue());
                // BUG: Diagnostic contains: passing @Nullable parameter 'holder.value()'
                takesNonNull(holder.value());
              }

              static void superBounded(Child<? super String> holder) {
                takesNonNull(holder.nonNullValue());
                // BUG: Diagnostic contains: passing @Nullable parameter 'holder.value()'
                takesNonNull(holder.value());
              }
            }
            """)
        .doTest();
  }

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
  public void unboundedWildcardUsesSubstitutedDependentFormalBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              static class Pair<T extends @Nullable Object, U extends T> {}

              static void nonNullFirstArgument(Pair<String, ?> pair) {
                Pair<String, ? extends String> target = pair;
              }

              static void nullableFirstArgument(Pair<@Nullable String, ?> pair) {
                Pair<@Nullable String, ? extends @Nullable String> target = pair;
                // BUG: Diagnostic contains: incompatible types
                Pair<@Nullable String, ? extends String> invalid = pair;
              }

              static void capturedFirstArgument(Pair<?, ?> pair) {
                Pair<?, ? extends @Nullable Object> target = pair;
                // BUG: Diagnostic contains: incompatible types
                Pair<?, ? extends Object> invalid = pair;
              }

              @NullUnmarked
              static class UnmarkedPair<T, U extends T> {}

              static void capturedFirstArgumentFromUnmarkedCode(UnmarkedPair<?, ?> pair) {
                UnmarkedPair<?, ? extends @Nullable Object> target = pair;
                // BUG: Diagnostic contains: incompatible types
                UnmarkedPair<?, ? extends Object> invalid = pair;
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
  public void wildcardCaptureReadsClassLeadingIntersectionBounds() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;

            @NullMarked
            class Test {
              static class Base {}
              interface Marker {}
              interface NullableHolder<T extends @Nullable Base & @Nullable Marker> {
                T value();
                @NonNull T nonNullValue();
              }
              interface NonNullHolder<T extends Base & Marker> {
                T value();
              }

              static void takesNonNull(Object value) {}

              static void nullable(NullableHolder<?> holder) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'holder.value()'
                takesNonNull(holder.value());
                takesNonNull(holder.nonNullValue());
                // Restoring @NonNull on one member must not narrow the other member's capture.
                // BUG: Diagnostic contains: passing @Nullable parameter 'holder.value()'
                takesNonNull(holder.value());
              }

              static void nonNull(NonNullHolder<?> holder) {
                takesNonNull(holder.value());
                holder.value().hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void wildcardCaptureReadsInterfaceOnlyIntersectionBounds() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;

            @NullMarked
            class Test {
              interface First {}
              interface Second {}
              interface NullableHolder<T extends @Nullable First & @Nullable Second> {
                T value();
                @NonNull T nonNullValue();
              }
              interface NonNullHolder<T extends First & Second> {
                T value();
              }

              static void takesNonNull(Object value) {}

              static void nullable(NullableHolder<?> holder) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'holder.value()'
                takesNonNull(holder.value());
                holder.nonNullValue().hashCode();
                // BUG: Diagnostic contains: passing @Nullable parameter 'holder.value()'
                takesNonNull(holder.value());
                // BUG: Diagnostic contains: dereferenced expression 'holder.value()' is @Nullable
                holder.value().hashCode();
              }

              static void nonNull(NonNullHolder<?> holder) {
                holder.value().hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void intersectionCapturePreservesNestedNullabilityAfterNarrowing() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;

            @NullMarked
            class Test {
              interface Marker {}
              static class Box<E extends @Nullable Object> {
                E get() {
                  throw new RuntimeException();
                }
                Box<? extends @NonNull E> nonNullElements() {
                  throw new RuntimeException();
                }
              }
              interface NullableHolder<
                  T extends @Nullable Box<@Nullable String> & @Nullable Marker> {
                T value();
                @NonNull T nonNullValue();
              }
              interface NonNullHolder<T extends Box<String> & Marker> {
                T value();
              }

              static void takesNonNull(Object value) {}

              static void nullable(NullableHolder<?> holder) {
                var box = holder.nonNullValue();
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                takesNonNull(box.get());
                box.nonNullElements().get().length();
                // BUG: Diagnostic contains: dereferenced expression 'box.get()' is @Nullable
                box.get().length();
                Box<@Nullable String> compatible = holder.nonNullValue();
                // BUG: Diagnostic contains: incompatible types
                Box<String> incompatible = holder.nonNullValue();
                // BUG: Diagnostic contains: dereferenced expression 'holder.value()' is @Nullable
                holder.value().hashCode();
              }

              static void nonNull(NonNullHolder<?> holder) {
                holder.value().get().length();
                Box<String> compatible = holder.value();
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
                // BUG: Diagnostic contains: incompatible types
                return receiver.wildcardUpperTypeVariable();
              }

              Nested<? extends String> testWithSelf(Nested<? extends String> receiver) {
                // BUG: Diagnostic contains: incompatible types
                return receiver.self().wildcardUpperTypeVariable();
              }

              Nested<? extends String> testWithVar(Nested<? extends String> receiver) {
                var local = receiver;
                // BUG: Diagnostic contains: incompatible types
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
                // BUG: Diagnostic contains: incompatible types
                return receiver.wildcardUpperTypeVariable();
              }

              Nested<? extends Object> testWithSelf(Nested<?> receiver) {
                // BUG: Diagnostic contains: incompatible types
                return receiver.self().wildcardUpperTypeVariable();
              }

              Nested<? extends Object> testWithVar(Nested<?> receiver) {
                var local = receiver;
                // BUG: Diagnostic contains: incompatible types
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
  public void inferredCaptureRetainsOriginFormalNullabilityAcrossGenericTypes() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NullUnmarked;
            @NullMarked
            final class Test {
              // Source.E has a @NonNull upper bound.
              static final class Source<E> {}
              // Result.R has a @Nullable upper bound.
              @NullUnmarked
              static final class Result<R> {}
              static <X> Result<X> convert(Source<? extends X> source) {
                throw new RuntimeException();
              }
              static Result<? extends Object> exercise(Source<?> source) {
                // Inference succeeds with X as the capture of Source<?>. The capture keeps
                // Source.E's @NonNull upper bound when it moves into Result<X>.
                return convert(source);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferredCaptureBoundTakesPrecedenceOverDestinationFormalBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            final class Test {
              static final class Source<E> {}
              static final class Result<R extends @Nullable Object> {}
              static <X> Result<X> convert(Source<? extends X> source) {
                throw new RuntimeException();
              }
              static Result<? extends Object> exercise(Source<?> source) {
                // Inference gives the capture of Source<?> a @NonNull upper bound. Result.R's
                // declaration permits @Nullable arguments, but does not make this capture nullable.
                return convert(source);
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
              // unmarked, so NullAway treats U as having a @Nullable upper bound
              @NullUnmarked
              static final class Flow<U> {}
              static <V> Flux<V> asFlux(Flow<? extends V> flow) {
                throw new RuntimeException();
              }
              static Flux<?> convert(Object source) {
                // inference fails since for Flow<?>, the upper bound of the wildcard is @Nullable, but
                // asFlux requires V to be @NonNull
                // BUG: Diagnostic contains: inference failure: type variable V is constrained to be @Nullable
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
              static final class Flow<U> {}
              static <V> Flux<V>[] asFluxArray(Flow<? extends V> flow) {
                throw new RuntimeException();
              }
              static Flux<?>[] convert(Object source) {
                // inference fails since for Flow<?>, the upper bound of the wildcard is @Nullable, but
                // asFlux requires V to be @NonNull
                // BUG: Diagnostic contains: inference failure: type variable V is constrained to be @Nullable
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
  public void issue1842() {
    makeHelper()
        .addSourceLines(
            "Node.java",
            """
            package org.example;

            public class Node<N extends Node<?>> {}
            """)
        .addSourceLines(
            "Main.java",
            """
            package org.example;

            import java.util.List;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Main {
              static <T> T take(List<? extends Node<?>> in, T t) {
                return t;
              }

              static String test(List<Node<?>> nodes) {
                return take(nodes, "x");
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1851SourceOnly() {
    makeHelper()
        .addSourceLines(
            "Self.java",
            """
            package org.example;

            public class Self<S extends Self<? extends S>> {}
            """)
        .addSourceLines(
            "Test.java",
            """
            package org.example;

            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              static void consume(Self<?> value) {}

              static void exercise(Self<?> value) {
                consume(identity(value));
              }

              static <T> T identity(T value) {
                return value;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1846() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              interface Node<T extends Comparable<Integer>> {
                T get();
              }

              interface NullableNode<T extends @Nullable Comparable<Integer>> {
                T get();
              }

              static void takesObject(Object value) {}

              static void superBounded(Node<? super Integer> node) {
                takesObject(node.get());
              }

              static void nullableSuperBounded(NullableNode<? super Integer> node) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'node.get()'
                takesObject(node.get());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1991WildcardReceiversWithWildcardHandlingDisabled() {
    makeTestHelperWithArgs(
            List.of(
                "-XepOpt:NullAway:OnlyNullMarked=true",
                JSpecifyJavacConfig.JSPECIFY_MODE_FLAG,
                JSpecifyJavacConfig.ADD_TYPE_ANNOTATIONS_FLAG))
        .addSourceLines(
            "Test.java",
            """
            import java.util.ArrayList;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              interface Node<T extends Comparable<Integer>> {
                T get();
              }

              interface NullableNode<T extends @Nullable Comparable<Integer>> {
                T get();
              }

              interface ListNode<T extends List<@Nullable String>> {
                T get();
              }

              static void takesObject(Object value) {}

              static void takesList(List<String> value) {}

              static void superBounded(Node<? super Integer> node) {
                takesObject(node.get());
              }

              static void extendsBounded(Node<? extends Integer> node) {
                takesObject(node.get());
              }

              static void unbounded(Node<?> node) {
                takesObject(node.get());
              }

              static void nullableSuperBounded(NullableNode<? super Integer> node) {
                // without wildcard handling, no read through a wildcard of NullableNode is
                // reported as nullable
                takesObject(node.get());
              }

              static void superBoundedNullableElements(ListNode<? super ArrayList<String>> node) {
                // BUG: Diagnostic contains: incompatible types: List<@Nullable String>
                takesList(node.get());
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
  public void anInferenceResultFixedNonNullKeepsNullOutOfALowerBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              static class Box<E extends @Nullable Object> {
                E e;
                Box(E e) { this.e = e; }
                void set(E e) { this.e = e; }
                E get() { return e; }
              }
              static <E extends @Nullable Object> Box<E> id(Box<E> box) {
                return box;
              }
              static <E extends @Nullable Object> Box<E> wrap(E e) {
                return new Box<>(e);
              }
              static void leastSolutionOfANonNullArgument() {
                var inferred = wrap(new Object());
                // BUG: Diagnostic contains: incompatible types
                Box<? super @Nullable String> sink = inferred;
                sink.set(null);
                inferred.get().toString();
              }
              static void fixedByTheArgument(Box<Object> original) {
                var inferred = id(original);
                // BUG: Diagnostic contains: incompatible types
                Box<? super @Nullable String> sink = inferred;
                sink.set(null);
                original.get().toString();
              }
              void fixedByAProjection(Box<@NonNull T> original) {
                var inferred = id(original);
                // BUG: Diagnostic contains: incompatible types
                Box<? super @Nullable T> sink = inferred;
              }
            }
            """)
        .doTest();
  }

  /**
   * Where javac inferred a class type, such as {@code Object} for the least upper bound of {@code
   * T} and {@code String}, an inference variable that a {@code T} whose bound admits null reached
   * is {@code @Nullable}: no other nullness of {@code Object} holds a null {@code T}. Every reader
   * of the result sees the same {@code @Nullable}, so a list that accepts a null through {@code
   * sink} also reports the null read back from it. Where javac inferred {@code T} itself, the
   * result is {@code T} and is dereferenced without a report, as {@code t} is (#1727). A
   * {@code @NonNull} written on the variable's use keeps that use non-null.
   */
  @Test
  public void anInferenceResultInferredAsAClassTypeHoldsTheNullOfTheTypeVariable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              static <E extends @Nullable Object> List<E> of(E a, E b) {
                throw new UnsupportedOperationException();
              }
              static <U extends @Nullable Object> U pick(U a, U b) {
                return a;
              }
              static <U extends @Nullable Object> @NonNull U pickNonNull(U a, U b) {
                throw new UnsupportedOperationException();
              }
              static <U extends @Nullable Object> List<@NonNull U> ofNonNull(U a, U b) {
                throw new UnsupportedOperationException();
              }
              static void takeObjects(List<Object> objects) {}
              void throughALocal(T t) {
                var inferred = of(t, "x");
                List<? super @Nullable Object> sink = inferred;
                sink.add(null);
                // BUG: Diagnostic contains: incompatible types: List<@Nullable Object> cannot be converted to List<? extends Object>
                List<? extends Object> source = inferred;
                // BUG: Diagnostic contains: dereferenced expression 'inferred.get(0)' is @Nullable
                inferred.get(0).toString();
                // BUG: Diagnostic contains: incompatible types
                takeObjects(inferred);
              }
              void throughTheCall(T t) {
                List<? super @Nullable Object> sink = of(t, "x");
                // BUG: Diagnostic contains: dereferenced expression 'pick(t, "x")' is @Nullable
                pick(t, "x").toString();
                // BUG: Diagnostic contains: dereferenced expression 'of(t, "x").get(0)' is @Nullable
                of(t, "x").get(0).toString();
              }
              void explicitlyNonNull(T t) {
                pickNonNull(t, "x").toString();
                ofNonNull(t, "x").get(0).toString();
              }
              void inferredAsTheTypeVariable(T t) {
                pick(t, t).toString();
              }
            }
            """)
        .doTest();
  }

  /**
   * The {@code @Nullable} inferred for a class-type result reaches a lambda parameter, an
   * enhanced-for variable, and the receiver of a call on the result, which read the inferred type
   * rather than the call's return nullness.
   */
  @Test
  public void aClassTypeResultIsNullableInLambdasLoopsAndReceivers() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import java.util.function.Consumer;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              static class Box<E extends @Nullable Object> {
                E get() {
                  throw new UnsupportedOperationException();
                }
              }
              static <E extends @Nullable Object> List<E> of(E a, E b) {
                throw new UnsupportedOperationException();
              }
              static <U extends @Nullable Object> U pick(U a, U b) {
                return a;
              }
              static <E extends @Nullable Object> Box<E> wrap(E e) {
                throw new UnsupportedOperationException();
              }
              static <U extends @Nullable Object> void with(U a, U b, Consumer<U> c) {}
              void lambdaParameter(T t) {
                with(t, "x", u -> {
                  // BUG: Diagnostic contains: dereferenced expression 'u' is @Nullable
                  u.toString();
                });
              }
              void loopVariable(T t) {
                for (Object o : of(t, "x")) {
                  // BUG: Diagnostic contains: dereferenced expression 'o' is @Nullable
                  o.hashCode();
                }
              }
              void receiver(T t) {
                // BUG: Diagnostic contains: dereferenced expression 'wrap(pick(t, "x")).get()' is @Nullable
                wrap(pick(t, "x")).get().toString();
              }
            }
            """)
        .doTest();
  }

  /**
   * The {@code @Nullable} inferred for a class-type result reaches a wildcard in the result type
   * and a wildcard that a member of a diamond's class returns, where javac's type carries a capture
   * or a wildcard bound that the inferred nullness was never substituted into.
   */
  @Test
  public void aClassTypeResultIsNullableInsideAWildcard() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              static class Box<E extends @Nullable Object> {
                Box(E a, E b) {}
                Box<? extends E> self() {
                  throw new UnsupportedOperationException();
                }
                Box<? super E> sink() {
                  throw new UnsupportedOperationException();
                }
                E get() {
                  throw new UnsupportedOperationException();
                }
                void set(E e) {}
              }
              static <U extends @Nullable Object> Box<? extends U> wrap(U a, U b) {
                throw new UnsupportedOperationException();
              }
              static <U extends @Nullable Object> Box<? super U> sinkOf(U a, U b) {
                throw new UnsupportedOperationException();
              }
              void f(T t) {
                // BUG: Diagnostic contains: dereferenced expression 'wrap(t, "x").get()' is @Nullable
                wrap(t, "x").get().toString();
                sinkOf(t, "x").set(null);
                var box = new Box<>(t, "x");
                // BUG: Diagnostic contains: dereferenced expression 'box.self().get()' is @Nullable
                box.self().get().toString();
                box.sink().set(null);
                // BUG: Diagnostic contains: incompatible types
                Box<? extends Object> nonNull = box.self();
              }
            }
            """)
        .doTest();
  }

  /**
   * A class-type result is {@code @Nullable} only where the bound of the type variable that reached
   * it and the bound of the inference variable itself are explicitly nullable, directly or through
   * another type variable. Elsewhere it is non-null, the least solution: a type variable from
   * unannotated code and an unannotated callee are read optimistically, and a {@code @NonNull}
   * written in unannotated code still excludes null.
   */
  @Test
  public void aClassTypeResultIsNullableOnlyThroughExplicitlyNullableBounds() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NullUnmarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <E extends @Nullable Object> List<E> of(E a, E b) {
                throw new UnsupportedOperationException();
              }
              static <E extends F, F extends @Nullable Object> List<E> ofThroughAVariable(E a, E b) {
                throw new UnsupportedOperationException();
              }
              static <E extends F, F> List<E> ofThroughANonNullVariable(E a, E b) {
                throw new UnsupportedOperationException();
              }
              static <U extends @Nullable Object> U pick(U a, U b) {
                return a;
              }
              static <E> List<E> nonNullOf(E a, E b) {
                throw new UnsupportedOperationException();
              }
              @NullUnmarked
              static class Lib {
                static <E> List<E> unannotatedOf(E a, E b) {
                  throw new UnsupportedOperationException();
                }
                static <E extends @NonNull Object> List<E> nonNullOf(E a, E b) {
                  throw new UnsupportedOperationException();
                }
              }
              static <T extends @Nullable Object> void boundNullableThroughAVariable(T t) {
                // BUG: Diagnostic contains: dereferenced expression 'ofThroughAVariable(t, "x").get(0)' is @Nullable
                ofThroughAVariable(t, "x").get(0).toString();
              }
              static <R, T extends @Nullable R> void inferredTypeVariableWhoseBoundExcludesNull(
                  T t, R r) {
                // BUG: Diagnostic contains: dereferenced expression 'pick(t, r)' is @Nullable
                pick(t, r).toString();
              }
              static <R extends @Nullable Object, T extends R> void inferredTypeVariableWhoseBoundAdmitsNull(
                  T t, R r) {
                // a false negative: R takes no synthetic annotation, and dataflow reads it as non-null (#1727)
                pick(t, r).toString();
              }
              static <T extends @Nullable Object> void boundNonNullThroughAVariable(T t) {
                ofThroughANonNullVariable(t, "x").get(0).toString();
              }
              static <T extends @Nullable Object> void ownBoundExcludesNull(T t) {
                nonNullOf(t, "x").get(0).toString();
              }
              static <T extends @Nullable Object> void unannotatedCallee(T t) {
                Lib.unannotatedOf(t, "x").get(0).toString();
                var inferred = Lib.nonNullOf(t, "x");
                inferred.get(0).toString();
                // BUG: Diagnostic contains: incompatible types
                List<? super @Nullable Object> sink = inferred;
              }
              @NullUnmarked
              static class Unmarked<T> {
                @NullMarked
                void unannotatedTypeVariable(T t) {
                  var inferred = of(t, "x");
                  inferred.get(0).toString();
                  // BUG: Diagnostic contains: incompatible types
                  List<? super @Nullable Object> sink = inferred;
                }
                @NullMarked
                <N extends @Nullable Object> void nullableBoundInsideUnannotatedOutside(N n, T t) {
                  // BUG: Diagnostic contains: dereferenced expression 'pick(pick(n, "x"), t)' is @Nullable
                  pick(pick(n, "x"), t).toString();
                }
                @NullMarked
                <N extends @Nullable Object> void unannotatedInsideNullableBoundOutside(N n, T t) {
                  // BUG: Diagnostic contains: dereferenced expression 'pick(pick(t, "x"), n)' is @Nullable
                  pick(pick(t, "x"), n).toString();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aNullableTypeVariableFlowsThroughADeclaredBoundToAnotherInferenceVariable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import java.util.function.Function;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              static <R extends @Nullable Object> R direct(R a, R b) {
                return a;
              }
              static <R extends @Nullable Object, U extends R> R viaBound(U a, U b) {
                return a;
              }
              static <R extends @Nullable Object, U extends R> R viaBoundOne(U a) {
                return a;
              }
              static <Q extends @Nullable Object, R extends Q, U extends R> Q deep(U a, U b) {
                return a;
              }
              static <R extends @Nullable Object, U extends @Nullable R> R viaNullableBound(U a) {
                throw new UnsupportedOperationException();
              }
              static <R, U extends R> R viaNonNullBound(U a) {
                return a;
              }
              static <R extends @Nullable Object, U extends R> U viaBoundReturningU(U a, R r) {
                return a;
              }
              void sameVariable(T t) {
                // BUG: Diagnostic contains: dereferenced expression 'direct(t, "x")' is @Nullable
                direct(t, "x").hashCode();
              }
              void throughABound(T t) {
                // BUG: Diagnostic contains: dereferenced expression 'viaBound(t, "x")' is @Nullable
                viaBound(t, "x").hashCode();
              }
              static <R extends @Nullable Object> R directOne(R a) {
                return a;
              }
              void inferredAsTheTypeVariable(T t) {
                // false negatives: javac infers T for R, and dataflow reads T as non-null (#1727)
                directOne(t).hashCode();
                viaBoundOne(t).hashCode();
              }
              void throughAChainOfBounds(T t) {
                // BUG: Diagnostic contains: dereferenced expression 'deep(t, "x")' is @Nullable
                deep(t, "x").hashCode();
              }
              void explicitNullThroughABound() {
                // BUG: Diagnostic contains: dereferenced expression 'viaBoundOne(null)' is @Nullable
                viaBoundOne(null).hashCode();
              }
              void explicitNullThroughANullableBound() {
                viaNullableBound(null).hashCode();
              }
              void explicitNullThroughANonNullBound() {
                // BUG: Diagnostic contains: passing @Nullable parameter
                viaNonNullBound(null).hashCode();
              }
              Object throughABoundIntoANonNullTarget() {
                // BUG: Diagnostic contains: inference failure: type variable U constrained to be both @NonNull and @Nullable
                return viaBoundOne(null);
              }
              void notUpward(T t) {
                viaBoundReturningU("x", t).hashCode();
              }
              static <R extends @Nullable Object, U extends R> R fromNonNull(U u, List<U> v) {
                return u;
              }
              void throughABoundFromAVariableFixedNonNull(T t, List<Object> v) {
                // a null t still reaches R: passing t where U is @NonNull Object is not reported (#1727)
                // BUG: Diagnostic contains: dereferenced expression 'fromNonNull(t, v)' is @Nullable
                fromNonNull(t, v).hashCode();
              }
              static <U extends @Nullable Object, R extends @Nullable Object> R map(
                  U u, List<U> v, Function<U, R> f) {
                return f.apply(u);
              }
              void throughALambdaFromAVariableFixedNonNull(T t, List<Object> v) {
                // BUG: Diagnostic contains: dereferenced expression 'map(t, v, x -> x)' is @Nullable
                map(t, v, x -> x).hashCode();
              }
              static <R, U extends R, V extends @Nullable Object> V throughNonNullBound(
                  U a, U b, Function<R, V> f) {
                return f.apply(a);
              }
              static <R, V extends @Nullable Object> V directNonNullBound(
                  R a, R b, Function<R, V> f) {
                return f.apply(a);
              }
              void throughAVariableWhoseBoundExcludesNull(T t) {
                // BUG: Diagnostic contains: dereferenced expression 'directNonNullBound(t, "x", x -> x)' is @Nullable
                directNonNullBound(t, "x", x -> x).hashCode();
                // BUG: Diagnostic contains: dereferenced expression 'throughNonNullBound(t, "x", x -> x)' is @Nullable
                throughNonNullBound(t, "x", x -> x).hashCode();
              }
              static <R extends @Nullable Object, U extends @NonNull R> R viaNonNullAnnotatedBound(U a) {
                return a;
              }
              Object explicitNullThroughANonNullAnnotatedBound() {
                // BUG: Diagnostic contains: passing @Nullable parameter 'null'
                return viaNonNullAnnotatedBound(null);
              }
              static <R extends @Nullable Object, U extends List<? extends R>> R nestedBound(
                  U u, R r) {
                throw new UnsupportedOperationException();
              }
              static <R extends @Nullable Object> R nestedArgument(List<? extends R> l, R r) {
                throw new UnsupportedOperationException();
              }
              void throughABoundThatNestsTheVariable(List<T> l) {
                // BUG: Diagnostic contains: dereferenced expression 'nestedArgument(l, "x")' is @Nullable
                nestedArgument(l, "x").hashCode();
                // a false negative: inference takes no constraint from a bound that nests R (#NNNN)
                nestedBound(l, "x").hashCode();
              }
              Object throughAnUnmarkedBound() {
                // U extends R relates nothing in unannotated code, where the bound of U admits null
                return Legacy.choose("fallback", null);
              }
            }
            """)
        .addSourceLines(
            "ModeledDeclaredBound.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class ModeledDeclaredBound {
              // The test library model makes the bound of U nullable, so U extends R relates nothing.
              static <R, U extends R> R choose(R r, U u) {
                return r;
              }
              Object throughAModeledBound() {
                return choose("fallback", null);
              }
            }
            """)
        .addSourceLines(
            "Legacy.java",
            """
            import org.jspecify.annotations.NullUnmarked;
            @NullUnmarked
            class Legacy {
              static <R, U extends R> R choose(R r, U unused) {
                return r;
              }
            }
            """)
        .doTest();
  }

  /**
   * A generic method reference and a generic constructor are judged against the types javac
   * inferred for them, as a generic method call is, so the {@code @Nullable} inferred for {@code U}
   * on the referenced method and on the constructor is the one its parameters are checked with.
   */
  @Test
  public void aGenericMethodReferenceAndAGenericConstructorTakeTheInferredClassType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import java.util.function.Consumer;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              static class Box<E extends @Nullable Object> {
                <U extends @Nullable Object> Box(Box<? extends U> input, U hint) {}
              }
              static class Taker<E extends @Nullable Object> {
                <U extends @Nullable Object> Taker(U a, U b, Consumer<U> c) {}
              }
              class InnerTaker<E extends @Nullable Object> {
                <U extends @Nullable Object> InnerTaker(U a, U b) {}
              }
              static class Holder<E extends @Nullable Object> {
                Holder(E a, E b, Function<E, List<E>> function) {}
              }
              static <U extends @Nullable Object> void use(U a, U b, Function<U, List<U>> function) {}
              static <V extends @Nullable Object> List<V> singleton(V value) {
                throw new UnsupportedOperationException();
              }
              static <V> List<V> nonNullSingleton(V value) {
                throw new UnsupportedOperationException();
              }
              void methodReference(T t) {
                use(t, new Object(), Test::singleton);
              }
              static class GenericConstructor {
                <W> GenericConstructor(W w) {}
              }
              static <U extends @Nullable Object, R> R map(U a, U b, Function<U, R> function) {
                throw new UnsupportedOperationException();
              }
              void constructorReference(T t) {
                // W takes no inference constraint from a constructor reference, so it stays non-null
                // (#1468)
                // BUG: Diagnostic contains: parameter w of referenced method is @NonNull, but parameter in functional interface method java.util.function.Function.apply(@Nullable Object) is @Nullable
                map(t, new Object(), GenericConstructor::new);
              }
              void methodReferenceToANonNullVariable(T t) {
                // BUG: Diagnostic contains: parameter value of referenced method is @NonNull
                use(t, new Object(), Test::nonNullSingleton);
              }
              void lambdaPassedToAConstructor(T t) {
                new Taker<>(t, "x", u -> {
                  // BUG: Diagnostic contains: dereferenced expression 'u' is @Nullable
                  u.toString();
                });
              }
              void anonymousSubclassOfAnInnerClass(T t) {
                this.new InnerTaker<>(t, "x") {};
              }
              void methodReferenceIntoADiamond(T t) {
                var holder = new Holder<>(t, new Object(), Test::singleton);
              }
              void constructor(Box<T> input) {
                var result = new Box<>(input, new Object());
              }
              static class ListAndElement<E> {
                <W extends @Nullable Object> ListAndElement(List<W> ws, W w, E e) {}
              }
              static class Mapped<E extends @Nullable Object> {
                <W extends @Nullable Object> Mapped(W w, Function<W, E> f) {}
              }
              static <U extends @Nullable Object> U identity(U u) {
                return u;
              }
              void constructorTakingAListOfNullableElements(List<@Nullable String> l) {
                var c =
                    new ListAndElement<>(
                        l,
                        // a false positive: W of a generic constructor at a diamond is not inferred
                        // (#1825)
                        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                        null,
                        "x");
              }
              void constructorTakingAGenericMethodReference(@Nullable String s) {
                var c =
                    new Mapped<>(
                        // a false positive: W of a generic constructor at a diamond is not inferred
                        // (#1825)
                        // BUG: Diagnostic contains: passing @Nullable parameter 's' where @NonNull is required
                        s,
                        Test::identity);
              }
              void methodReferenceOutsideACall() {
                Function<String, List<String>> f = Test::singleton;
                f = (Test::singleton);
              }
              static class TwoLists<E> {
                <U> TwoLists(U a, U b) {}
              }
              static <V> V nonNullIdentity(V v) {
                return v;
              }
              <X> void takeFn(X x, Function<List<T>, List<@Nullable T>> f) {}
              void genericConstructorAtADiamondTakingTwoLists(List<T> a, List<@Nullable T> b) {
                // BUG: Diagnostic contains: incompatible types: List<@Nullable T> cannot be converted to List<T>
                var c = new TwoLists<>(a, b);
              }
              void genericMethodReferenceUnderAGenericCall() {
                // BUG: Diagnostic contains: referenced method returns List<T>, but functional interface method returns List<@Nullable T>
                takeFn("x", Test::nonNullIdentity);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aClassTypeResultIsNullableThroughNestedCallsOfOneMethod() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              static class Box<E extends @Nullable Object> {
                final E value;
                Box(E value) {
                  this.value = value;
                }
                E get() {
                  return value;
                }
              }
              static <U extends @Nullable Object> U pick(U a, U b) {
                return a;
              }
              void nestedDiamonds(T t) {
                new Box<>(new Box<>(t)).get().toString();
              }
              void nestedCallsOfOneMethod(T t) {
                // BUG: Diagnostic contains: dereferenced expression 'pick(pick(t, "x"), "y")' is @Nullable
                pick(pick(t, "x"), "y").toString();
              }
            }
            """)
        .doTest();
  }

  /**
   * Dataflow reads a {@code T} whose bound admits null as non-null whether or not the code checked
   * it (#1727), so inference cannot tell a checked {@code t} from an unchecked one, and a
   * class-type result is {@code @Nullable} after a null check or {@code requireNonNull} too. These
   * are false positives; an explicit type argument skips inference and avoids them.
   */
  @Test
  public void aNullCheckOfATypeVariableDoesNotReachInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Objects;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              static <U extends @Nullable Object> U pick(U a, U b) {
                return a;
              }
              void nullCheck(T t) {
                if (t != null) {
                  // BUG: Diagnostic contains: dereferenced expression 'pick(t, "x")' is @Nullable
                  pick(t, "x").toString();
                }
              }
              void requireNonNull(T t) {
                T checked = Objects.requireNonNull(t);
                // BUG: Diagnostic contains: dereferenced expression 'pick(checked, "x")' is @Nullable
                pick(checked, "x").toString();
              }
              void explicitTypeArgument(T t) {
                if (t != null) {
                  Test.<Object>pick(t, "x").toString();
                }
              }
            }
            """)
        .doTest();
  }

  /**
   * Where javac inferred an intersection type, such as the least upper bound of two classes that
   * implement {@code Runnable} and {@code Serializable}, the result carries no annotation, since
   * the root of an intersection takes the nullness of its elements, so it stays non-null even where
   * a {@code T} that admits null reached it.
   */
  @Test
  public void aClassTypeResultInferredAsAnIntersectionStaysNonNull() {
    // on JDK 17, javac refuses to annotate an intersection type, and NullAway fails on this source
    // whether or not this change is applied
    Assume.assumeTrue(Runtime.version().feature() > 17);
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.io.Serializable;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends Test.@Nullable A> {
              static class A implements Runnable, Serializable {
                public void run() {}
              }
              static class B implements Runnable, Serializable {
                public void run() {}
              }
              static <E extends @Nullable Object> E pick(E a, E b) {
                return a;
              }
              void intersection(T t) {
                pick(t, new B()).run();
              }
              void classType(T t) {
                // BUG: Diagnostic contains: dereferenced expression 'pick(t, new A())' is @Nullable
                pick(t, new A()).run();
              }
            }
            """)
        .doTest();
  }

  /**
   * Without wildcard handling, a capture flows into an inference variable as a type variable, so a
   * capture whose bound admits null is as parametric as a type variable {@code T} whose bound
   * admits null, and the call keeps it as javac inferred it.
   */
  @Test
  public void aCaptureWhoseBoundAdmitsNullStaysAsInferredWithWildcardHandlingDisabled() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-XepOpt:NullAway:OnlyNullMarked=true",
                    "-XepOpt:NullAway:HandleWildcardGenerics=false")))
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.BiPredicate;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              interface Action<R extends @Nullable Object> {
                R run() throws Exception;
              }
              static <R extends @Nullable Object> R call(Action<R> action) {
                throw new UnsupportedOperationException();
              }
              interface Seq<E extends @Nullable Object> extends Iterable<E> {
                Seq<E> seq();
                default <U extends @Nullable Object> boolean corresponds(
                    Iterable<U> that, BiPredicate<? super E, ? super U> predicate) {
                  return true;
                }
              }
              static <U extends @Nullable Object> U first(Iterable<U> elements) {
                throw new UnsupportedOperationException();
              }
              static <U extends @Nullable Object> U pick(U a, U b) {
                return a;
              }
              Seq<?> anySeq() {
                throw new UnsupportedOperationException();
              }
              Seq<? extends T> seqOfT() {
                throw new UnsupportedOperationException();
              }
              Seq<@Nullable String> nullableStrings() {
                throw new UnsupportedOperationException();
              }
              void castToAWildcardType() {
                call((Action<?>) () -> {
                  throw new Exception();
                });
              }
              void receiverOfAWildcardType(Seq<String> self, Seq<?> that) {
                self.seq().corresponds(that.seq(), (a, b) -> true);
              }
              void unboundedWildcard() {
                first(anySeq()).hashCode();
              }
              void wildcardBoundedByATypeVariable() {
                T t = first(seqOfT());
              }
              void wildcardBoundedByATypeVariableIntoAClassTypeResult() {
                // BUG: Diagnostic contains: dereferenced expression 'pick(first(seqOfT()), "x")' is @Nullable
                pick(first(seqOfT()), "x").toString();
              }
              void nullableTypeArgument() {
                // BUG: Diagnostic contains: dereferenced expression 'first(nullableStrings())' is @Nullable
                first(nullableStrings()).hashCode();
              }
            }
            """)
        .doTest();
  }

  /**
   * A value read through {@code ? extends T}, where the bound of {@code T} admits null, may be
   * null. A generic call that javac infers to return the capture of that wildcard returns such a
   * value too, as reading the element directly does.
   */
  @Test
  public void aCallReturningACaptureOfAWildcardBoundedByATypeVariableMayReturnNull() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              static class NullableBox<E extends @Nullable Object> implements Iterable<E> {
                public java.util.Iterator<E> iterator() {
                  throw new UnsupportedOperationException();
                }
              }
              static class NonNullBox<E> implements Iterable<E> {
                public java.util.Iterator<E> iterator() {
                  throw new UnsupportedOperationException();
                }
              }
              static class Box<E extends @Nullable Object> {
                E get() {
                  throw new UnsupportedOperationException();
                }
              }
              static <U extends @Nullable Object> U first(Iterable<U> it) {
                throw new UnsupportedOperationException();
              }
              static <U extends @Nullable Object> U get(Box<U> b) {
                throw new UnsupportedOperationException();
              }
              static <U extends @Nullable Object> U getThroughWildcard(Box<? extends U> b) {
                throw new UnsupportedOperationException();
              }
              static <U> U firstNonNull(Iterable<U> it) {
                throw new UnsupportedOperationException();
              }
              void readDirectly(Box<? extends T> b, List<? extends T> l) {
                // BUG: Diagnostic contains: dereferenced expression 'b.get()' is @Nullable
                b.get().toString();
                // BUG: Diagnostic contains: dereferenced expression 'l.get(0)' is @Nullable
                l.get(0).toString();
              }
              void throughAContainerWhoseParameterAdmitsNull(NullableBox<? extends T> b) {
                // BUG: Diagnostic contains: dereferenced expression 'first(b)' is @Nullable
                first(b).toString();
              }
              void throughAContainerWhoseParameterExcludesNull(NonNullBox<? extends T> b) {
                // BUG: Diagnostic contains: dereferenced expression 'first(b)' is @Nullable
                first(b).toString();
              }
              void throughTheSameContainer(Box<? extends T> b) {
                // BUG: Diagnostic contains: dereferenced expression 'get(b)' is @Nullable
                get(b).toString();
              }
              void throughAWildcardParameter(Box<? extends T> b) {
                // BUG: Diagnostic contains: dereferenced expression 'getThroughWildcard(b)' is @Nullable
                getThroughWildcard(b).toString();
              }
              void intoAVariableWhoseBoundExcludesNull(NullableBox<? extends T> b) {
                // the call that passes b for U goes unchecked (#2189), and the value still reads
                // through ? extends T
                // BUG: Diagnostic contains: dereferenced expression 'firstNonNull(b)' is @Nullable
                firstNonNull(b).toString();
              }
              void theTypeVariableItself(Box<T> b, NullableBox<T> n) {
                get(b).toString();
                first(n).toString();
              }
              void aWildcardWhoseBoundExcludesNull(Box<? extends String> b) {
                get(b).toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void
      aCallResultTypedAsACaptureOfAWildcardBoundedByATypeVariablePassesWhereThatCaptureIsExpected() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              static class Box<E extends @Nullable Object> {
                E get() {
                  throw new UnsupportedOperationException();
                }
              }
              static class Holder<W extends @Nullable Object> {
                Holder(W w) {}
              }
              static class Sink {
                <X extends @Nullable Object> void take(X x) {}
              }
              static <U extends @Nullable Object> U first(List<U> list) {
                throw new UnsupportedOperationException();
              }
              static <U extends @Nullable Object> U get(Box<U> b) {
                throw new UnsupportedOperationException();
              }
              static <U extends @Nullable Object> U getThroughWildcard(Box<? extends U> b) {
                throw new UnsupportedOperationException();
              }
              static <X extends @Nullable Object> void accept(X x) {}
              static <X extends @Nullable Object> X ret(X x) {
                return x;
              }
              @SafeVarargs
              static <X extends @Nullable Object> void acceptAll(X... xs) {}
              static <X extends @Nullable Object> void acceptTwo(X a, X b) {}
              static <A extends @Nullable Object, B extends @Nullable Object> B apply(
                  A a, Function<A, B> f) {
                return f.apply(a);
              }
              static <X> void acceptNonNull(X x) {}
              static <V extends @Nullable Object> V identity(V v) {
                return v;
              }
              void fromAList(List<? extends T> list) {
                accept(first(list));
              }
              void fromTheSameContainer(Box<? extends T> b) {
                accept(get(b));
              }
              void fromAWildcardParameter(Box<? extends T> b) {
                accept(getThroughWildcard(b));
              }
              void intoAGenericMethodWhoseResultIsUnused(List<? extends T> list) {
                ret(first(list));
              }
              void throughTwoNestedCalls(List<? extends T> list) {
                accept(ret(ret(first(list))));
              }
              void intoAnInstanceMethod(Sink sink, List<? extends T> list) {
                sink.take(first(list));
              }
              void intoVarargs(List<? extends T> list) {
                acceptAll(first(list));
              }
              void twoCapturesOfOneWildcardType(List<? extends T> a, List<? extends T> b) {
                acceptTwo(first(a), first(b));
              }
              void intoADiamondConstructor(List<? extends T> list) {
                new Holder<>(first(list));
              }
              void besideAMethodReference(List<? extends T> list) {
                apply(first(list), Test::identity);
              }
              void returnedAsTheTypeVariable(List<? extends T> list) {
                accept(returnFirst(list));
              }
              T returnFirst(List<? extends T> list) {
                return first(list);
              }
              void readByAMemberCall(Box<? extends T> b) {
                accept(b.get());
              }
              void intoAVariableWhoseBoundExcludesNull(List<? extends T> list) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'first(list)'
                acceptNonNull(first(list));
              }
              void dereferenced(List<? extends T> list) {
                // BUG: Diagnostic contains: dereferenced expression 'ret(first(list))' is @Nullable
                ret(first(list)).toString();
              }
            }
            """)
        .doTest();
  }

  /**
   * The JSpecify JDK models decide the bound of the inference variable: {@code Arrays.asList}
   * admits null and {@code List.of} does not.
   */
  @Test
  public void aClassTypeResultFromTheJdkFollowsTheModelsBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Arrays;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              void f(T t) {
                // BUG: Diagnostic contains: dereferenced expression 'Arrays.asList(t, "x").get(0)' is @Nullable
                Arrays.asList(t, "x").get(0).toString();
                List.of(t, "x").get(0).toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferenceCannotFitATypeVariableWhoseBoundAdmitsNullIntoANonNullVariable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.ArrayList;
            import java.util.List;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NullUnmarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static <U> U nonNullVariable(Box<? extends U> b) {
                throw new UnsupportedOperationException();
              }
              static <U extends @Nullable Object> U nullableVariable(Box<? extends U> b) {
                throw new UnsupportedOperationException();
              }
              static <U extends @Nullable Object> U intoAWildcardOfAFixedType(
                  Box<? extends @Nullable Object> b, U u) {
                return u;
              }
              static <U, V extends @Nullable Object> V pair(Box<? extends U> b, V v) {
                throw new UnsupportedOperationException();
              }
              static <T extends @Nullable Object> void intoNonNullVariable(Box<T> b) {
                // BUG: Diagnostic contains: inference failure: type variable U of Test.nonNullVariable is constrained to be @Nullable, but its upper bound requires it to be @NonNull; the type argument T passed for ? extends U admits null, since T of Test.intoNonNullVariable has a @Nullable upper bound
                Object o = nonNullVariable(b);
              }
              static <T extends @Nullable Object> void intoNonNullVariableThroughAWildcard(
                  Box<? extends T> b) {
                // a false negative: a wildcard bounded by T is not checked against the bound of U (#2189)
                Object o = nonNullVariable(b);
              }
              static class Holder<T extends @Nullable Object> {
                Box<? extends T> wildBox() {
                  throw new UnsupportedOperationException();
                }
                List<? extends T> wildList() {
                  throw new UnsupportedOperationException();
                }
              }
              static <T extends @Nullable Object> void intoNonNullVariableThroughACapture(
                  Holder<T> h) {
                // a false negative: a capture bounded by T is not checked against the bound of U (#2189)
                Object o = nonNullVariable(h.wildBox());
              }
              static class BoundedByANullableVariable<T extends @Nullable Object, X extends T> {
                Box<X> box() {
                  throw new UnsupportedOperationException();
                }
              }
              static <T extends @Nullable Object> void captureOfATypeParameterBoundedByANullableVariable(
                  BoundedByANullableVariable<T, ?> h) {
                // a false negative: a capture whose bound reaches T is not checked against the bound of U (#2189)
                Object o = nonNullVariable(h.box());
              }
              static class Set {
                <E extends @Nullable Object> void copyOf(java.util.Collection<E> in) {
                  // BUG: Diagnostic contains: type variable E of java.util.Set.copyOf is constrained to be @Nullable, but its upper bound requires it to be @NonNull; the type argument E passed for ? extends E admits null, since E of Test$Set.copyOf has a @Nullable upper bound
                  java.util.Set<E> copy = java.util.Set.copyOf(in);
                }
              }
              static <T extends @Nullable Object> void intoJdkCopyOfThroughACapture(Holder<T> h) {
                // a false negative: a capture bounded by T is not checked against the bound of E (#2189)
                List<? extends T> copy = List.copyOf(h.wildList());
              }
              static class Base<E extends @Nullable Object> {}
              static class Sub<X> extends Base<X> {}
              static class NonNullList<X> extends ArrayList<X> {}
              static <U> void intoBase(Base<? extends U> b) {}
              static <T extends @Nullable Object> void wildcardOfASubclassWhoseTypeParameterExcludesNull(
                  Sub<? extends T> s) {
                intoBase(s);
              }
              static <T extends @Nullable Object> void wildcardOfANonNullListIntoJdkCopyOf(
                  NonNullList<? extends T> nl) {
                List<? extends T> copy = List.copyOf(nl);
              }
              static class Pair<A extends @Nullable Object, B extends A> {}
              static <U> void intoSelfPair(Pair<U, ? extends U> p) {}
              static <S, X extends @Nullable S> void wildcardOfAPairWhoseSiblingIsNonNull(
                  Pair<S, ? extends X> p) {
                intoSelfPair(p);
              }
              static <X extends @Nullable Object> void wildcardOfAPairWhoseSiblingAdmitsNull(
                  Pair<X, ? extends X> p) {
                // a false negative: a wildcard bounded by X is not checked against the bound of U (#2189)
                intoSelfPair(p);
              }
              static <T extends @Nullable Object> void intoNullableVariable(Box<T> b) {
                nullableVariable(b).toString();
              }
              static <T extends @Nullable Object> void intoAWildcardThatIsNotAnInferenceVariable(
                  Box<T> b) {
                Object o = intoAWildcardOfAFixedType(b, "x");
              }
              static <T> void nonNullBoundIntoNonNullVariable(Box<T> b) {
                Object o = nonNullVariable(b);
              }
              static <A extends @Nullable Object, B extends @Nullable Object> B apply(
                  A a, java.util.function.Function<A, B> f) {
                throw new UnsupportedOperationException();
              }
              static <T extends @Nullable Object> void intoJdkCopyOfThroughAMethodReference(
                  List<T> in) {
                // a false negative: T reaches copyOf through another generic call (#1947)
                List<T> copy = apply(in, List::copyOf);
              }
              static <T extends @Nullable Object> void intoJdkCopyOfInsideALambda(List<T> in) {
                // a false negative: T reaches copyOf through another generic call (#1947)
                List<T> copy = apply(in, l -> List.copyOf(l));
              }
              static <T extends @Nullable Object> void bareTypeVariableIntoJdkListOf(T t) {
                // a false negative: only a ? extends U formal constrains U against a T that admits null (#1932)
                List<T> list = List.of(t);
              }
              static <U extends @Nullable Object> List<U> copy(Box<? extends U> b) {
                throw new UnsupportedOperationException();
              }
              static <T extends @Nullable Object> void intoNullableVariableThatTheContextMakesNonNull(
                  Box<T> b) {
                // a false negative: the assignment, not the bound of U, makes U non-null (#1945)
                List<Object> c = copy(b);
              }
              static <T extends @Nullable Object> void intoNonNullVariableByAnExplicitTypeArgument(
                  Box<T> b) {
                // a false negative: only a type argument annotated @Nullable is checked against the bound of U (#1946)
                Object o = Test.<T>nonNullVariable(b);
              }
              static <T extends @Nullable Object> void nonNullTypeVariableIntoNonNullVariable(
                  Box<@NonNull T> b) {
                Object o = nonNullVariable(b);
              }
              static <T extends @Nullable Object> void failureDiscardsTheOtherVariablesOfTheCall(
                  Box<T> b) {
                // BUG: Diagnostic contains: inference failure: type variable U of Test.pair is constrained to be @Nullable
                pair(b,
                        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                        null)
                    .hashCode();
              }
              @NullUnmarked
              static class Legacy {
                static <E> List<E> identity(List<E> xs) {
                  return xs;
                }
              }
              static <T extends @Nullable Object> void intoJdkCopyOfThroughAnUnmarkedCall(
                  List<T> in) {
                var inferred = Legacy.identity(in);
                // BUG: Diagnostic contains: inference failure: type variable E of List.copyOf is constrained to be @Nullable
                List<T> copy = List.copyOf(inferred);
              }
              static <T extends @Nullable Object> void intoJdkCopyOfNestingAnUnmarkedCall(
                  List<T> in) {
                // a false negative: the unmarked call's result reaches copyOf without a local (#1947)
                List<T> copy = List.copyOf(Legacy.identity(in));
              }
              static <V extends @Nullable Object> List<V> markedIdentity(List<V> xs) {
                return xs;
              }
              static <T extends @Nullable Object> void intoJdkCopyOfNestingAMarkedCall(
                  List<T> in) {
                // a false negative: the marked call's result reaches copyOf without a local (#1947)
                List<T> copy = List.copyOf(markedIdentity(in));
              }
              static <T extends @Nullable Object> void intoJdkCopyOfThroughADiamond(List<T> in) {
                // a false negative: the diamond's result reaches copyOf without a local (#1947)
                List<T> copy = List.copyOf(new ArrayList<>(in));
              }
              static <T extends @Nullable Object> void intoJdkCopyOf(List<T> in) {
                // BUG: Diagnostic contains: inference failure: type variable E of List.copyOf is constrained to be @Nullable, but its upper bound requires it to be @NonNull; the type argument T passed for ? extends E admits null, since T of Test.intoJdkCopyOf has a @Nullable upper bound
                List<T> copy = List.copyOf(in);
              }
              static <E extends @Nullable Object> void sameNameAsTheVariableOfCopyOf(List<E> in) {
                // BUG: Diagnostic contains: type variable E of List.copyOf is constrained to be @Nullable, but its upper bound requires it to be @NonNull; the type argument E passed for ? extends E admits null, since E of Test.sameNameAsTheVariableOfCopyOf has a @Nullable upper bound
                List<E> copy = List.copyOf(in);
              }
              static void genericMethodOfAnAnonymousClass() {
                new Object() {
                  <E extends @Nullable Object> void copy(List<E> in) {
                    // BUG: Diagnostic contains: since E of Test$1.copy has a @Nullable upper bound
                    List<E> copy = List.copyOf(in);
                  }
                };
              }
              static class GenericConstructor<C extends @Nullable Object> {
                <W> GenericConstructor(Box<? extends W> b) {}
              }
              static <T extends @Nullable Object> void intoAGenericConstructor(Box<T> b) {
                // BUG: Diagnostic contains: type variable W of the constructor of GenericConstructor is constrained to be @Nullable
                GenericConstructor<String> c = new GenericConstructor<>(b);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void anInferenceFailureNamesTheTypeVariableWhoseBoundIsNullable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              void classTypeVariable(List<T> in) {
                // BUG: Diagnostic contains: the type argument T passed for ? extends E admits null, since T of Test has a @Nullable upper bound
                List<T> copy = List.copyOf(in);
              }
              static <R extends @Nullable Object, S extends R> void boundedByATypeVariable(
                  List<S> in) {
                // BUG: Diagnostic contains: the type argument S passed for ? extends E admits null, since S of Test.boundedByATypeVariable extends R of Test.boundedByATypeVariable, which has a @Nullable upper bound
                List<S> copy = List.copyOf(in);
              }
              static <Q extends @Nullable Object, R extends Q, S extends R>
                  void boundedThroughTwoTypeVariables(List<S> in) {
                // BUG: Diagnostic contains: since S of Test.boundedThroughTwoTypeVariables extends Q of Test.boundedThroughTwoTypeVariables, which has a @Nullable upper bound
                List<S> copy = List.copyOf(in);
              }
              <S extends T> void boundedByTheClassTypeVariable(List<S> in) {
                // BUG: Diagnostic contains: since S of Test.boundedByTheClassTypeVariable extends T of Test, which has a @Nullable upper bound
                List<S> copy = List.copyOf(in);
              }
              static <R extends @Nullable Object, S extends @NonNull R> void boundedByANonNullUse(
                  List<S> in) {
                List<S> copy = List.copyOf(in);
              }
              static class Source {
                static class Box {
                  static <U> List<U> copy(List<? extends U> in) {
                    throw new UnsupportedOperationException();
                  }
                }
              }
              static class Target {
                static class Box {
                  static <R extends @Nullable Object, U extends R> void copy(List<U> in) {
                    // BUG: Diagnostic contains: type variable U of Test$Source$Box.copy is constrained to be @Nullable, but its upper bound requires it to be @NonNull; the type argument U passed for ? extends U admits null, since U of Test$Target$Box.copy extends R of Test$Target$Box.copy, which has a @Nullable upper bound
                    List<U> copy = Source.Box.copy(in);
                  }
                }
              }
              static class Witness {
                static class Box {
                  static <U extends @Nullable Object, S extends U> void copy(List<S> in) {
                    // BUG: Diagnostic contains: type variable U of Test$Source$Box.copy is constrained to be @Nullable, but its upper bound requires it to be @NonNull; the type argument S passed for ? extends U admits null, since S of Test$Witness$Box.copy extends U of Test$Witness$Box.copy, which has a @Nullable upper bound
                    List<S> copy = Source.Box.copy(in);
                  }
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aTypeVariableWithANullableBoundChangesNoReportWithJSpecifyModeAlone() {
    makeTestHelperWithArgs(
            List.of(
                "-XepOpt:NullAway:OnlyNullMarked=true",
                JSpecifyJavacConfig.JSPECIFY_MODE_FLAG,
                JSpecifyJavacConfig.ADD_TYPE_ANNOTATIONS_FLAG))
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import java.util.Objects;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              static <U extends @Nullable Object> U pick(U a, U b) {
                return a;
              }
              static <E extends @Nullable Object> List<E> of(E a, E b) {
                throw new UnsupportedOperationException();
              }
              static class C<E> {
                <U> C(U a, U b) {}
              }
              static <U> void m(U a, U b) {}
              static <V> V identity(V v) {
                return v;
              }
              <X> void takeFn(X x, Function<List<T>, List<@Nullable T>> f) {}
              static class Mapped<E extends @Nullable Object> {
                <W extends @Nullable Object> Mapped(W w, Function<W, E> f) {}
              }
              static class ListAndElement<E> {
                <W extends @Nullable Object> ListAndElement(List<W> ws, W w, E e) {}
              }
              static <U extends @Nullable Object> U nullableIdentity(U u) {
                return u;
              }
              static <A extends @Nullable Object> void apply(A a, Function<A, A> f) {}
              static <R extends @Nullable Object, U extends R> R viaBound(U a, U b) {
                return a;
              }
              static <R extends @Nullable Object, U extends R> R viaBoundOne(U a) {
                return a;
              }
              void typeVariable(T t) {
                pick(t, "x").toString();
              }
              void typeVariableThroughADeclaredBound(T t) {
                viaBound(t, "x").toString();
              }
              void nullThroughADeclaredBound() {
                viaBoundOne(null).toString();
              }
              void typeVariableAfterRequireNonNull(T t) {
                T checked = Objects.requireNonNull(t);
                pick(checked, "x").toString();
              }
              void typeVariableInAnEnhancedFor(T t) {
                for (Object o : of(t, "x")) {
                  o.toString();
                }
              }
              void nullableArgument(@Nullable String s) {
                // BUG: Diagnostic contains: dereferenced expression 'pick(s, "x")' is @Nullable
                pick(s, "x").toString();
              }
              void genericConstructorAtADiamond(List<T> a, List<@Nullable T> b) {
                var c = new C<>(a, b);
              }
              void genericMethod(List<T> a, List<@Nullable T> b) {
                // BUG: Diagnostic contains: incompatible types: List<@Nullable T> cannot be converted to List<T>
                m(a, b);
              }
              void genericMethodReferenceUnderAGenericCall() {
                takeFn("x", Test::identity);
              }
              void genericMethodReferenceTakingANullableArgument(@Nullable String s) {
                apply(s, Test::nullableIdentity);
              }
              void genericConstructorTakingAListOfNullableElements(List<@Nullable String> l) {
                var c =
                    new ListAndElement<>(
                        // a false positive that JSpecifyExperimental removes
                        // BUG: Diagnostic contains: incompatible types: List<@Nullable String> cannot be converted to List<W>
                        l,
                        // a false positive: W of a generic constructor at a diamond is not inferred
                        // (#1825)
                        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                        null,
                        "x");
              }
              void genericMethodReferenceUnderADiamond(@Nullable String s) {
                var mapped =
                    new Mapped<>(
                        // a false positive: W of a generic constructor at a diamond is not inferred
                        // (#1825)
                        // BUG: Diagnostic contains: passing @Nullable parameter 's' where @NonNull is required
                        s,
                        // a false positive that JSpecifyExperimental removes
                        // BUG: Diagnostic contains: parameter u of referenced method is @NonNull
                        Test::nullableIdentity);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void
      aCaptureOfAWildcardBoundedByATypeVariableChangesNoReportWithoutJSpecifyExperimental() {
    makeTestHelperWithArgs(
            List.of(
                "-XepOpt:NullAway:OnlyNullMarked=true",
                JSpecifyJavacConfig.JSPECIFY_MODE_FLAG,
                JSpecifyJavacConfig.ADD_TYPE_ANNOTATIONS_FLAG,
                "-XepOpt:NullAway:HandleWildcardGenerics=true"))
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              static class Box<E extends @Nullable Object> {
                E get() {
                  throw new UnsupportedOperationException();
                }
              }
              static <U extends @Nullable Object> U first(List<U> list) {
                throw new UnsupportedOperationException();
              }
              static <X extends @Nullable Object> X ret(X x) {
                return x;
              }
              static <X extends @Nullable Object> void accept(X x) {}
              static void nonNullParameter(Object o) {}
              void callResultThroughAGenericCallDereferenced(List<? extends T> list) {
                ret(first(list)).toString();
              }
              void callResultIntoAGenericParameter(List<? extends T> list) {
                accept(first(list));
              }
              void memberCallResultIntoAGenericParameter(Box<? extends T> b) {
                accept(b.get());
              }
              Object memberCallResultReturnedThroughAGenericCall(Box<? extends T> b) {
                return ret(b.get());
              }
              T memberCallResultReturnedAsTheTypeVariable(Box<? extends T> b) {
                return ret(b.get());
              }
              void memberCallResultIntoANonNullParameter(Box<? extends T> b) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'b.get()'
                nonNullParameter(b.get());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aTypeVariableWithANullableBoundFailsNoInferenceWithoutJSpecifyExperimental() {
    makeTestHelperWithArgs(
            List.of(
                "-XepOpt:NullAway:OnlyNullMarked=true",
                JSpecifyJavacConfig.JSPECIFY_MODE_FLAG,
                JSpecifyJavacConfig.ADD_TYPE_ANNOTATIONS_FLAG,
                "-XepOpt:NullAway:HandleWildcardGenerics=true",
                "-XepOpt:NullAway:WarnOnGenericInferenceFailure=true"))
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              static class Box<E extends @Nullable Object> {}
              static <U> U nonNullVariable(Box<? extends U> b) {
                throw new UnsupportedOperationException();
              }
              static <U extends @Nullable Object> U pick(U a, U b) {
                return a;
              }
              static class GenericConstructor<E> {
                <W> GenericConstructor(Box<? extends W> b) {}
              }
              void typeVariable(Box<T> b) {
                Object o = nonNullVariable(b);
              }
              void typeVariableIntoADiamond(Box<T> b) {
                var c = new GenericConstructor<>(b);
              }
              void typeVariableIntoAClassTypeResult(T t) {
                pick(t, "x").toString();
              }
              void nullableTypeArgument(Box<@Nullable String> b) {
                // BUG: Diagnostic contains: inference failure
                Object o = nonNullVariable(b);
              }
            }
            """)
        .doTest();
  }
}
