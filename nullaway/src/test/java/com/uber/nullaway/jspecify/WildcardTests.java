package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;

public class WildcardTests extends NullAwayTestsBase {

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
  public void aTypeVariableIsJudgedByItsBoundAgainstAConcreteWildcardRequirement() {
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
              static void takeNullable(Box<? extends @Nullable Object> b) {}
              static void takeAny(Box<?> b) {}
              static <T extends @Nullable Object> void nullableBoundIntoNonNull(Box<T> b) {
                // BUG: Diagnostic contains: incompatible types: Box<T> cannot be converted to Box<? extends Object>
                takeNonNull(b);
              }
              static <T> void nonNullBoundIntoNonNull(Box<T> b) {
                takeNonNull(b);
              }
              static <T extends @Nullable Object> void nullableBoundIntoNullable(Box<T> b) {
                takeNullable(b);
              }
              static <T extends @Nullable Object> void nullableBoundIntoUnbounded(Box<T> b) {
                takeAny(b);
              }
              static class NonNullParameter<X> {}
              static void takeAnyNonNullParameter(NonNullParameter<?> b) {}
              static <T extends @Nullable Object> void nullableBoundIntoUnboundedNonNullParameter(
                  NonNullParameter<T> b) {
                takeAnyNonNullParameter(b);
              }
              static <T extends @Nullable Object> boolean nullableBoundIntoClassOfAnything(
                  Class<?> other, Class<T> type) {
                return other.isAssignableFrom(type);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullnessWrittenOnATypeVariableUseOverridesItsBound() {
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
              static void takeNonNull(Box<? extends Object> b) {}
              static void takeNullable(Box<? extends @Nullable Object> b) {}
              static <T> void nullableUseIntoNonNull(Box<@Nullable T> b) {
                // BUG: Diagnostic contains: incompatible types: Box<@Nullable T> cannot be converted to Box<? extends Object>
                takeNonNull(b);
              }
              static <T> void nullableUseIntoNullable(Box<@Nullable T> b) {
                takeNullable(b);
              }
              static <T extends @Nullable Object> void nonNullUseIntoNonNull(Box<@NonNull T> b) {
                takeNonNull(b);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aBoundReachedThroughAnotherVariableOrAWildcardDecidesAgainstAConcreteRequirement() {
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
              static void takeNonNull(Box<? extends Object> b) {}
              static <T extends @Nullable Object, S extends T> void variableOverNullableBound(
                  Box<S> b) {
                // BUG: Diagnostic contains: incompatible types: Box<S> cannot be converted to Box<? extends Object>
                takeNonNull(b);
              }
              static <T, S extends T> void variableOverNonNullBound(Box<S> b) {
                takeNonNull(b);
              }
              static <T extends @Nullable Object, S extends @NonNull T>
                  void nonNullVariableOverNullableBound(Box<S> b) {
                takeNonNull(b);
              }
              static <T extends @Nullable Object> void wildcardOverNullableBound(Box<? extends T> b) {
                // BUG: Diagnostic contains: incompatible types: Box<? extends T> cannot be converted to Box<? extends Object>
                takeNonNull(b);
              }
              static <T> void wildcardOverNonNullBound(Box<? extends T> b) {
                takeNonNull(b);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aTypeVariableDeclaredInUnannotatedCodeAdmitsNullOnlyWhenItsBoundSaysSo() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NullUnmarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static void takeNonNull(Box<? extends Object> b) {}
              @NullUnmarked
              static class Unannotated<T> {
                @NullMarked
                void unannotatedDeclarationIntoNonNull(Box<T> b) {
                  takeNonNull(b);
                }
                @NullMarked
                <U extends T> void variableOverADefaultBound(Box<U> b) {
                  takeNonNull(b);
                }
              }
              @NullUnmarked
              static class UnannotatedNullableBound<T extends @Nullable Object> {
                @NullMarked
                void explicitNullableBoundIntoNonNull(Box<T> b) {
                  // BUG: Diagnostic contains: incompatible types: Box<T> cannot be converted to Box<? extends Object>
                  takeNonNull(b);
                }
                @NullMarked
                <U extends T> void variableOverAnExplicitNullableBound(Box<U> b) {
                  // BUG: Diagnostic contains: incompatible types: Box<U> cannot be converted to Box<? extends Object>
                  takeNonNull(b);
                }
              }
              static class Annotated<T> {
                void annotatedDeclarationIntoNonNull(Box<T> b) {
                  takeNonNull(b);
                }
              }
              @NullUnmarked
              static class UnannotatedNonNullBound<T extends @NonNull Object> {
                @NullMarked
                void explicitNonNullBoundIntoNonNull(Box<T> b) {
                  takeNonNull(b);
                }
                @NullMarked
                <U extends T> void variableOverAnExplicitNonNullBound(Box<U> b) {
                  takeNonNull(b);
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aTypeVariableIsJudgedByItsBoundAgainstAWildcardWithALowerBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {
                void set(E e) {}
              }
              static class NullableBound<T extends @Nullable Object> {
                void takeSuperT(Box<? super T> b, T t) {
                  b.set(t);
                }
                void nonNullArgument(Box<Object> b, T t) {
                  // BUG: Diagnostic contains: incompatible types: Box<Object> cannot be converted to Box<? super T>
                  takeSuperT(b, t);
                }
                void nonNullLowerBound(Box<? super Object> b, T t) {
                  // BUG: Diagnostic contains: incompatible types: Box<? super Object> cannot be converted to Box<? super T>
                  takeSuperT(b, t);
                }
                void sameVariable(Box<T> b, T t) {
                  takeSuperT(b, t);
                }
                void nullableUse(Box<@Nullable T> b, T t) {
                  takeSuperT(b, t);
                }
                void nullableArgument(Box<@Nullable Object> b, T t) {
                  takeSuperT(b, t);
                }
              }
              static class NonNullBound<T> {
                void takeSuperT(Box<? super T> b, T t) {
                  b.set(t);
                }
                void nonNullArgument(Box<Object> b, T t) {
                  takeSuperT(b, t);
                }
                void nonNullLowerBound(Box<? super Object> b, T t) {
                  takeSuperT(b, t);
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void theTypeArgumentsOfATypeVariableBoundAreComparedAgainstAConcreteRequirement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static void takeNonNullElements(Box<? extends List<String>> b) {}
              static <S extends List<@Nullable String>> void nullableElements(Box<S> b) {
                // BUG: Diagnostic contains: incompatible types: Box<S> cannot be converted to Box<? extends List<String>>
                takeNonNullElements(b);
              }
              static <S extends List<String>> void nonNullElements(Box<S> b) {
                takeNonNullElements(b);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void anIntersectionBoundAdmitsNullOnlyWhereEveryElementDoes() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.io.Serializable;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NullUnmarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static void takeNonNull(Box<? extends Object> b) {}
              static <T extends @Nullable Object & @Nullable Serializable> void everyElementNullable(
                  Box<T> b) {
                // BUG: Diagnostic contains: incompatible types: Box<T> cannot be converted to Box<? extends Object>
                takeNonNull(b);
              }
              static <T extends @Nullable Object & Serializable> void oneElementNonNull(Box<T> b) {
                takeNonNull(b);
              }
              @NullUnmarked
              static class Unannotated {
                @NullMarked
                static <T extends @Nullable Object & @Nullable Serializable> void everyElementNullable(
                    Box<T> b) {
                  // BUG: Diagnostic contains: incompatible types: Box<T> cannot be converted to Box<? extends Object>
                  takeNonNull(b);
                }
                @NullMarked
                static <T extends @Nullable Object & @NonNull Serializable> void oneElementNonNull(
                    Box<T> b) {
                  takeNonNull(b);
                }
                @NullMarked
                static <T extends @Nullable Object & Serializable> void oneElementBare(Box<T> b) {
                  takeNonNull(b);
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aTypeVariableIsComparedAsWrittenAgainstAWildcardBoundedByATypeVariable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NullUnmarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static class NullableBound<T extends @Nullable Object> {
                void takeExtendsT(Box<? extends T> b) {}
                Box<? extends T> sameVariable(Box<T> b) {
                  return b;
                }
                <S extends T> void variableThatExtendsIt(Box<S> b) {
                  takeExtendsT(b);
                }
                void nullableUse(Box<@Nullable T> b) {
                  // BUG: Diagnostic contains: incompatible types: Box<@Nullable T> cannot be converted to Box<? extends T>
                  takeExtendsT(b);
                }
                <S extends @Nullable T> void variableThatAdmitsNullBeyondIt(Box<S> b) {
                  // BUG: Diagnostic contains: incompatible types: Box<S> cannot be converted to Box<? extends T>
                  takeExtendsT(b);
                }
                <S extends @Nullable T, R extends S> void variableReachingItThroughANullableLink(
                    Box<R> b) {
                  // BUG: Diagnostic contains: incompatible types: Box<R> cannot be converted to Box<? extends T>
                  takeExtendsT(b);
                }
                <S extends @NonNull T> void variableReachingItThroughANonNullLink(Box<S> b) {
                  takeExtendsT(b);
                }
                <S extends @Nullable T> void nonNullUseOfAVariableThatAdmitsNull(Box<@NonNull S> b) {
                  takeExtendsT(b);
                }
                <S extends @NonNull T, R extends @Nullable S> void nullableLinkAboveANonNullLink(
                    Box<R> b) {
                  // BUG: Diagnostic contains: incompatible types: Box<R> cannot be converted to Box<? extends T>
                  takeExtendsT(b);
                }
                void takeExtendsNullableT(Box<? extends @Nullable T> b) {}
                void nullableUseIntoNullableProjection(Box<@Nullable T> b) {
                  takeExtendsNullableT(b);
                }
                @NullUnmarked
                class Unannotated<S extends T> {
                  @NullMarked
                  void variableDeclaredInUnannotatedCode(Box<S> b) {
                    takeExtendsT(b);
                  }
                }
                @NullUnmarked
                class UnannotatedNullableLink<S extends @Nullable T> {
                  @NullMarked
                  void explicitNullableLinkDeclaredInUnannotatedCode(Box<S> b) {
                    // BUG: Diagnostic contains: incompatible types: Box<S> cannot be converted to Box<? extends T>
                    takeExtendsT(b);
                  }
                }
                @NullUnmarked
                class UnannotatedNonNullLink<S extends @NonNull T> {
                  @NullMarked
                  void explicitNonNullLinkDeclaredInUnannotatedCode(Box<S> b) {
                    takeExtendsT(b);
                  }
                }
              }
              static class NonNullBound<T> {
                void takeExtendsT(Box<? extends T> b) {}
                <S extends @Nullable T> void variableThatAdmitsNull(Box<S> b) {
                  // BUG: Diagnostic contains: incompatible types: Box<S> cannot be converted to Box<? extends T>
                  takeExtendsT(b);
                }
                <S extends T> void variableThatAdmitsNoNull(Box<S> b) {
                  takeExtendsT(b);
                }
                <S extends @NonNull T, R extends @Nullable S> void nullableLinkAboveANonNullLink(
                    Box<R> b) {
                  // BUG: Diagnostic contains: incompatible types: Box<R> cannot be converted to Box<? extends T>
                  takeExtendsT(b);
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aTypeVariableThatMayBeNullFailsANonNullProjectionOfATypeVariable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NullUnmarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static class NullableBound<T extends @Nullable Object> {
                void takeExtendsNonNullT(Box<? extends @NonNull T> b) {}
                void bareUse(Box<T> b) {
                  // BUG: Diagnostic contains: incompatible types: Box<T> cannot be converted to Box<? extends T>
                  takeExtendsNonNullT(b);
                }
                void wildcardUse(Box<? extends T> b) {
                  // BUG: Diagnostic contains: incompatible types: Box<? extends T> cannot be converted to Box<? extends T>
                  takeExtendsNonNullT(b);
                }
              }
              static class NonNullBound<T> {
                void takeExtendsNonNullT(Box<? extends @NonNull T> b) {}
                void bareUse(Box<T> b) {
                  takeExtendsNonNullT(b);
                }
              }
              @NullUnmarked
              static class UnannotatedDefaultBound<T> {
                @NullMarked
                Box<? extends @NonNull T> bareUse(Box<T> b) {
                  return b;
                }
              }
              @NullUnmarked
              static class UnannotatedNullableBound<T extends @Nullable Object> {
                @NullMarked
                Box<? extends @NonNull T> bareUse(Box<T> b) {
                  // BUG: Diagnostic contains: incompatible types: Box<T> cannot be converted to Box<? extends T>
                  return b;
                }
              }
              @NullUnmarked
              static class UnannotatedNonNullBound<T extends @NonNull Object> {
                @NullMarked
                Box<? extends @NonNull T> bareUse(Box<T> b) {
                  return b;
                }
              }
            }
            """)
        .doTest();
  }

  /**
   * Resolving the bound of the requirement as well as the actual's once reported a false positive
   * on this shape, taken from Caffeine's {@code CacheLoader.asyncReload}: {@code
   * CompletableFuture.supplyAsync} infers {@code CompletableFuture<V>} against {@code
   * CompletableFuture<? extends V>}.
   */
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
  public void anInferredTypeArgumentMeetsAWildcardBoundedByTheSameTypeVariable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.ArrayList;
            import java.util.Collection;
            import java.util.Collections;
            import java.util.HashSet;
            import java.util.List;
            import java.util.Set;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              static <E extends @Nullable Object> List<E> copyOf(Collection<? extends E> c) {
                throw new UnsupportedOperationException();
              }
              static <E extends @Nullable Object> List<E> copyNonNull(
                  Collection<? extends @NonNull E> c) {
                throw new UnsupportedOperationException();
              }
              void genericMethod(List<T> in) {
                List<T> copy = copyOf(in);
              }
              void writtenNonNullProjection(List<T> in) {
                // BUG: Diagnostic contains: incompatible types
                List<T> copy = copyNonNull(in);
              }
              void diamond(List<T> in) {
                Set<T> set = new HashSet<>(in);
              }
              static <U extends @Nullable Object> void methodTypeVariable(List<U> in) {
                List<U> copy = new ArrayList<>(in);
                List<? extends @Nullable Object> view = Collections.unmodifiableList(in);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferenceAddsNoReportAgainstAConcreteWildcard() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import java.util.Map;
            import java.util.concurrent.CompletableFuture;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<V extends @Nullable Object> {
              static class Box<E extends @Nullable Object> {
                Box(E e) {}
              }
              static <U extends @Nullable Object> U id(U u) {
                return u;
              }
              static <U extends @Nullable Object> void takeWithValue(U u, Box<? extends U> b) {}
              List<? extends @NonNull V> nonNullElements() {
                throw new UnsupportedOperationException();
              }
              Map<String, ? extends @NonNull V> nonNullValues() {
                throw new UnsupportedOperationException();
              }
              List<? extends Object> captureThroughGenericMethod() {
                return id(nonNullElements());
              }
              Box<? extends List<? extends Object>> captureThroughDiamond() {
                return new Box<>(nonNullElements());
              }
              CompletableFuture<? extends Map<String, ? extends Object>> captureThroughLambda() {
                return CompletableFuture.supplyAsync(() -> nonNullValues());
              }
              static <T extends @Nullable Object> void boundInferredFromAnotherArgument(Box<T> b) {
                takeWithValue("x", b);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void anInferenceVariableFixedByTheTargetExcludesNullAndOneLeftToTheArgumentDoesNot() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.ArrayList;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static <U extends @Nullable Object> Box<U> idBox(Box<U> b) {
                return b;
              }
              static <T extends @Nullable Object> void variableFixedByAWildcardTarget(Box<T> b) {
                // not reported, a known false negative (#1945): the target fixes U as non-null, and
                // the argument Box<T> is compared with Box<@NonNull U> by annotation only
                Box<? extends Object> x = idBox(b);
              }
              static <U extends @Nullable Object> U first(Box<? extends U> b) {
                throw new UnsupportedOperationException();
              }
              static <T extends @Nullable Object> Object variableFixedByTheReturnTarget(Box<T> b) {
                // BUG: Diagnostic contains: incompatible types: Box<T> cannot be converted to Box<? extends T>
                return first(b);
              }
              static <T extends @Nullable Object> void variableLeftToTheArgumentThenDereferenced(
                  Box<T> b) {
                first(b).toString();
              }
              static <T extends @Nullable Object> void variableFixedByTheTarget(List<T> in) {
                // BUG: Diagnostic contains: incompatible types
                List<Object> copy = new ArrayList<>(in);
              }
              static <T extends @Nullable Object> void variableLeftToTheArgument(List<T> in) {
                List<T> copy = new ArrayList<>(in);
              }
            }
            """)
        .doTest();
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
   * A type argument inference left to a type variable, where javac inferred that type variable, is
   * judged as that type variable, whose declared bound admits null. A null written through {@code
   * sink} would otherwise reach the {@code Box<T>} passed in, which holds no null when {@code T} is
   * {@code Object}.
   */
  @Test
  public void anInferenceResultLeftToATypeVariableIsJudgedAsThatTypeVariable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.ArrayList;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              static class Box<E extends @Nullable Object> {
                void set(E e) {}
              }
              static <E extends @Nullable Object> Box<E> id(Box<E> box) {
                return box;
              }
              static <E extends @Nullable Object> Box<Box<E>> nest(Box<E> box) {
                throw new UnsupportedOperationException();
              }
              void intoTheSameTypeVariable(Box<T> original) {
                var inferred = id(original);
                Box<? super T> sink = inferred;
                Box<? extends @Nullable Object> source = inferred;
              }
              void intoANullableLowerBound(Box<T> original) {
                var inferred = id(original);
                // BUG: Diagnostic contains: incompatible types
                Box<? super @Nullable T> sink = inferred;
                sink.set(null);
              }
              void intoANonNullUpperBound(Box<T> original) {
                var inferred = id(original);
                // BUG: Diagnostic contains: incompatible types
                Box<? extends Object> source = inferred;
              }
              void throughADiamond(List<T> original) {
                var inferred = new ArrayList<>(original);
                List<? super T> sink = inferred;
                // BUG: Diagnostic contains: incompatible types
                List<? super @Nullable T> nullableSink = inferred;
              }
              void inANestedTypeArgument(Box<T> original) {
                var inferred = nest(original);
                Box<? extends Box<? super T>> sink = inferred;
                // BUG: Diagnostic contains: incompatible types
                Box<? extends Box<? super @Nullable T>> nullableSink = inferred;
              }
              void ofACapturedWildcard(Box<? super @Nullable String> original) {
                var inferred = id(original);
                Box<? super @Nullable String> sink = inferred;
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
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              static class Box<E extends @Nullable Object> {
                <U extends @Nullable Object> Box(Box<? extends U> input, U hint) {}
              }
              static class Holder<E extends @Nullable Object> {
                Holder(E a, E b, Function<E, List<E>> function) {}
              }
              static <U extends @Nullable Object> void use(U a, U b, Function<U, List<U>> function) {}
              static <V extends @Nullable Object> List<V> singleton(V value) {
                throw new UnsupportedOperationException();
              }
              void methodReference(T t) {
                use(t, new Object(), Test::singleton);
              }
              void methodReferenceIntoADiamond(T t) {
                var holder = new Holder<>(t, new Object(), Test::singleton);
              }
              void constructor(Box<T> input) {
                var result = new Box<>(input, new Object());
              }
            }
            """)
        .doTest();
  }

  /**
   * A generic method reference outside a call that needs inference infers the nullness of its type
   * variables against the functional-interface type it is assigned to, the nested nullability of
   * that type's parameters included, or takes the type arguments written on it, as a generic call
   * does.
   */
  @Test
  public void aGenericMethodReferenceOutsideInferenceInfersAgainstItsTarget() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import java.util.function.BiConsumer;
            import java.util.function.BiFunction;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <U extends @Nullable Object> List<U> singleton(U value) {
                throw new UnsupportedOperationException();
              }
              static <U extends @Nullable Object> List<@Nullable U> nullableSingleton(U value) {
                throw new UnsupportedOperationException();
              }
              static <U> U id(U value) {
                return value;
              }
              static <U extends @Nullable Object> U nullableId(U value) {
                return value;
              }
              static class Box<E extends @Nullable Object> {
                Box(E element) {}
                void apply(Function<List<E>, List<E>> f) {}
                void applyToTwo(BiFunction<List<E>, List<E>, List<E>> f) {}
              }
              static <A extends @Nullable Object, B extends A> A upcast(B value) {
                return value;
              }
              @SafeVarargs
              static <U> U first(U... values) {
                return values[0];
              }
              @SafeVarargs
              static <U extends @Nullable Object> List<U> listOf(U... elements) {
                throw new UnsupportedOperationException();
              }
              static void use(Function<String, List<? extends Object>> f) {}
              static void useNullable(Function<@Nullable String, List<@Nullable String>> f) {}
              void inferred() {
                use(Test::singleton);
              }
              void inferredFromANullableTarget() {
                useNullable(Test::singleton);
                Function<@Nullable String, @Nullable String> f = Test::nullableId;
              }
              static void nonNullElements(String... elements) {}
              static void nullableElements(@Nullable String... elements) {}
              void inferredForVarargs() {
                BiFunction<@Nullable String, @Nullable String, List<@Nullable String>> f = Test::listOf;
                BiConsumer<String, @Nullable String> nullable = Test::nullableElements;
                // BUG: Diagnostic contains: parameter elements of referenced method is @NonNull
                BiConsumer<String, @Nullable String> nonNull = Test::nonNullElements;
              }
              void inferredFromATargetWithNestedNullability() {
                var box = new Box<>(null);
                box.apply(Test::id);
                box.applyToTwo(Test::first);
              }
              void aVariableBoundedByAnother() {
                Function<@Nullable String, @Nullable String> nullable = Test::upcast;
                // BUG: Diagnostic contains: parameter value of referenced method is @NonNull
                Function<@Nullable String, String> nonNull = Test::upcast;
              }
              void aNullableTargetIntoANonNullVariable() {
                // BUG: Diagnostic contains: parameter value of referenced method is @NonNull
                Function<@Nullable String, @Nullable String> f = Test::id;
              }
              void inferredInAnAssignment() {
                Function<String, List<? extends Object>> f = Test::singleton;
              }
              void inferredWithNullableElements() {
                // BUG: Diagnostic contains: referenced method returns List<@Nullable String>
                use(Test::nullableSingleton);
              }
              void explicitNonNull() {
                use(Test::<String>singleton);
              }
              void explicitNullable() {
                // BUG: Diagnostic contains: referenced method returns List<@Nullable String>
                use(Test::<@Nullable String>singleton);
              }
            }
            """)
        .doTest();
  }

  /**
   * A generic constructor called without a diamond infers the nullness of its own type variables,
   * or takes the type arguments written on the call, as a generic method call does.
   */
  @Test
  public void aGenericConstructorWithoutADiamondInfersItsTypeVariables() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              static class Box<E extends @Nullable Object> {}
              static class Consumer {
                <U extends @Nullable Object> Consumer(Box<? extends U> input) {}
              }
              static class GenericConsumer<E> {
                <U extends @Nullable Object> GenericConsumer(Box<? extends U> input) {}
              }
              static class Pipe {
                <U extends @Nullable Object> Pipe(Box<? extends U> input, Box<? super U> output) {}
              }
              static class Holder {
                <U> Holder(U input) {}
              }
              static class StrictConsumer {
                <U> StrictConsumer(Box<? extends U> input) {}
              }
              static class ListHolder {
                <U> ListHolder(List<U> input) {}
              }
              static <E extends @Nullable Object> List<E> list(E element) {
                throw new UnsupportedOperationException();
              }
              void inferredOnANonGenericClass(Box<T> input) {
                new Consumer(input);
              }
              void inferredWithExplicitClassTypeArguments(Box<T> input) {
                new GenericConsumer<String>(input);
              }
              void inferredIntoANullableOutput(Box<T> input, Box<@Nullable Object> output) {
                new Pipe(input, output);
              }
              void inferredIntoANonNullOutput(Box<T> input, Box<Object> output) {
                // U is inferred @NonNull from output, and the message prints Box<? extends @NonNull T>
                // without the annotation (#1828)
                // BUG: Diagnostic contains: Box<T> cannot be converted to Box<? extends T>
                new Pipe(input, output);
              }
              void explicitTypeVariable(Box<T> input) {
                new <T>Consumer(input);
              }
              void explicitNonNull(Box<T> input) {
                // BUG: Diagnostic contains: Box<T> cannot be converted to Box<? extends Object>
                new <Object>Consumer(input);
              }
              void aNullableTypeVariableIntoANonNullVariable(Box<T> input) {
                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                new StrictConsumer(input);
              }
              void aCapturedNullableBoundIntoANonNullVariable(Box<? extends @Nullable String> input) {
                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                new StrictConsumer(input);
              }
              void inferredWithNestedNullability() {
                var input = list(null);
                new Holder(input);
              }
              void inferredWithNestedNullabilityIntoANonNullVariable() {
                var input = list(null);
                // BUG: Diagnostic contains: List<@Nullable Object> cannot be converted to List<Object>
                new ListHolder(input);
              }
            }
            """)
        .doTest();
  }

  /**
   * The nested nullability NullAway determines for an argument replaces the one javac drops from
   * its instantiation of the callee's type variable, for a method, a constructor, and an anonymous
   * class, at a plain, a varargs, and a wildcard parameter, in an enclosing type such as {@code
   * Outer<E>} of {@code Outer<E>.Inner}, and from whichever argument at an occurrence of the
   * variable carries it, a subtype such as {@code ArrayList} included, and an array whose
   * components admit null taking an array of non-null ones. A {@code List<U>} at the same position
   * still rejects the {@code @Nullable} element, since {@code U} excludes null, and two arguments
   * that disagree on it are reported.
   */
  @Test
  public void anArgumentKeepsItsNestedNullabilityInEveryShapeOfGenericCall() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.ArrayList;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <E extends @Nullable Object> List<E> list(E element) {
                throw new UnsupportedOperationException();
              }
              static <E extends @Nullable Object> ArrayList<E> arrayList(E element) {
                throw new UnsupportedOperationException();
              }
              static <E extends @Nullable Object> List<List<@Nullable E>> nested(E element) {
                throw new UnsupportedOperationException();
              }
              static class Outer<E extends @Nullable Object> {
                class Inner {}
              }
              static <E extends @Nullable Object> Outer<E>.Inner inner(E element) {
                throw new UnsupportedOperationException();
              }
              static <U> void plain(U input) {}
              static <U> void pair(U first, U second) {}
              static <U> void varargs(U... inputs) {}
              static <U> void wildcard(List<? extends U> input) {}
              static <U> void varargsOfLists(List<U>... inputs) {}
              static <U> void wildcardOfLists(List<? extends List<U>> input) {}
              static class Plain {
                <U> Plain(U input) {}
              }
              static class Pair {
                <U> Pair(U first, U second) {}
              }
              static class Varargs {
                <U> Varargs(U... inputs) {}
              }
              static class Wildcard {
                <U> Wildcard(List<? extends U> input) {}
              }
              static class VarargsOfLists {
                <U> VarargsOfLists(List<U>... inputs) {}
              }
              static class WildcardOfLists {
                <U> WildcardOfLists(List<? extends List<U>> input) {}
              }
              void methods() {
                var input = list(null);
                plain(input);
                plain(inner(null));
                varargs(input, input);
                wildcard(nested("x"));
              }
              void constructors() {
                var input = list(null);
                new Plain(input);
                new Plain(inner(null));
                new Varargs(input, input);
                new Varargs();
                new Wildcard(nested("x"));
              }
              void anonymousClasses() {
                var input = list(null);
                new Plain(input) {};
                new Plain(inner(null)) {};
                new Varargs(input) {};
                new Wildcard(nested("x")) {};
              }
              void aSubtypeBeforeTheCallSiteType() {
                var subtype = arrayList(null);
                var input = list(null);
                pair(subtype, input);
                varargs(subtype, input);
                new Pair(subtype, input);
                new Varargs(subtype, input);
              }
              void arraysOfNonNullAndNullableComponents(String[] nonNull, @Nullable String[] nullable) {
                varargs(nonNull, nullable);
                varargs(nullable, nonNull);
                new Varargs(nonNull, nullable);
                new Varargs(nullable, nonNull);
              }
              void arraysOfASubtypeComponent(Number[] numbers, @Nullable Integer[] integers) {
                pair(numbers, integers);
                pair(integers, numbers);
                new Pair(numbers, integers);
                new Pair(integers, numbers);
              }
              void arraysNullableAtDifferentDimensions(
                  String[][] nonNull, @Nullable String[][] innermost, String[] @Nullable [] inner) {
                pair(nonNull, innermost);
                new Pair(nonNull, innermost);
                new Pair(innermost, nonNull);
                new Pair(inner, innermost);
                new Pair(innermost, inner);
              }
              void argumentsThatDisagree(List<@Nullable String> nullable, List<String> nonNull) {
                // BUG: Diagnostic contains: List<String> cannot be converted to List<@Nullable String>
                new Pair(nullable, nonNull);
              }
              void methodsIntoANonNullElement() {
                var input = list(null);
                // BUG: Diagnostic contains: incompatible types: List<@Nullable Object> cannot be converted to List<Object>
                varargsOfLists(input);
                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                wildcardOfLists(nested("x"));
              }
              void constructorsIntoANonNullElement() {
                var input = list(null);
                // BUG: Diagnostic contains: incompatible types: List<@Nullable Object> cannot be converted to List<Object>
                new VarargsOfLists(input);
                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                new WildcardOfLists(nested("x"));
              }
              void anonymousClassesIntoANonNullElement() {
                var input = list(null);
                // BUG: Diagnostic contains: incompatible types: List<@Nullable Object> cannot be converted to List<Object>
                new VarargsOfLists(input) {};
                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                new WildcardOfLists(nested("x")) {};
              }
            }
            """)
        .doTest();
  }

  /**
   * The bound {@code E} of {@code <U extends E>} takes the type argument that the receiver of a
   * generic method, or the type a constructor constructs, fixes for {@code E}, so {@code U} admits
   * null only where that type argument does. A diamond call that infers {@code E} ties {@code U} to
   * it, as a method call ties {@code B} to {@code A} in {@code <A, B extends A>}, except where
   * nested calls of one declaration share the variables. A wildcard type argument and a declaration
   * in unannotated code leave the bound to the declaration.
   */
  @Test
  public void aMethodTypeVariableBoundedByAClassTypeVariableTakesItsTypeArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import java.util.function.BiFunction;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NullUnmarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static class Holder<E extends @Nullable Object> {
                Holder() {}
                <U extends E> Holder(U element) {}
                <U extends E> Holder(Box<U> box, int unused) {}
                <U extends E> void set(U element) {}
                <U extends E> U pass(U element) {
                  return element;
                }
                <A extends E, B extends A> Holder(B element, String unused) {}
                <A extends E, B extends A> A passThrough(B element) {
                  return element;
                }
              }
              static <A extends @Nullable Object, B extends A> List<A> wrap(B element) {
                throw new UnsupportedOperationException();
              }
              @NullUnmarked
              static class Unannotated<E> {
                <U extends E> U pass(U element) {
                  return element;
                }
                static <A, B extends A> List<A> wrap(B element) {
                  throw new UnsupportedOperationException();
                }
              }
              static <E extends @Nullable Object> List<E> list(E element) {
                throw new UnsupportedOperationException();
              }
              void nullableTypeArgument(Holder<@Nullable String> holder, @Nullable String value) {
                new Holder<@Nullable String>(value);
                holder.set(value);
              }
              void nonNullTypeArgument(Holder<String> holder, String value) {
                new Holder<String>(value);
                holder.set(value);
              }
              void aWildcardTypeArgument(Holder<? super @Nullable String> holder, @Nullable String value) {
                holder.set(value);
                holder.set(null);
              }
              void aDeclarationInUnannotatedCode(Unannotated<String> unannotated, @Nullable String value) {
                String result = unannotated.pass(value);
                List<String> wrapped = Unannotated.wrap(value);
              }
              void anImplicitThisInAnAnonymousClass(@Nullable String value) {
                new Holder<@Nullable String>() {
                  void call() {
                    set(value);
                  }
                };
                new Holder<String>() {
                  void call() {
                    // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                    set(value);
                  }
                };
              }
              void aBoundReachedThroughAnotherVariable(
                  Holder<String> nonNullHolder, @Nullable String value) {
                new Holder<@Nullable String>(value, "");
                // BUG: Diagnostic contains: inference failure: type variable A is constrained to be @Nullable
                new Holder<String>(value, "");
                // BUG: Diagnostic contains: parameter element of referenced method is @NonNull
                Function<@Nullable String, @Nullable String> nonNull = nonNullHolder::passThrough;
              }
              void aMethodReference(
                  Holder<@Nullable String> nullableHolder, Holder<String> nonNullHolder) {
                Function<@Nullable String, @Nullable String> nullable = nullableHolder::pass;
                BiFunction<Holder<@Nullable String>, @Nullable String, @Nullable String> unbound =
                    Holder::pass;
                // BUG: Diagnostic contains: parameter element of referenced method is @NonNull
                Function<@Nullable String, @Nullable String> nonNull = nonNullHolder::pass;
                BiFunction<Holder<String>, @Nullable String, @Nullable String> nonNullUnbound =
                    // BUG: Diagnostic contains: parameter element of referenced method is @NonNull
                    Holder::pass;
              }
              void nestedCallsOfOneMethodWithDifferentTypeArguments(
                  Holder<List<@Nullable String>> outer, Holder<@Nullable String> inner, @Nullable String value) {
                outer.pass(list(inner.pass(value)));
              }
              void aMethodVariableBoundedByAnother(@Nullable String value) {
                List<@Nullable String> nullable = wrap(value);
                // BUG: Diagnostic contains: constrained to be both @NonNull and @Nullable
                List<String> nonNull = wrap(value);
              }
              void nestedCallsSharingTheVariables(@Nullable String value) {
                List<List<@Nullable String>> nested = wrap(wrap(value));
              }
              void aDiamondCall(@Nullable String value) {
                Holder<@Nullable String> nullable = new Holder<>(value);
                // BUG: Diagnostic contains: constrained to be both @NonNull and @Nullable
                Holder<String> nonNull = new Holder<>(value);
              }
              void aNullableValueIntoANonNullTypeArgument(
                  Holder<String> holder, @Nullable String value, Box<@Nullable String> box) {
                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                new Holder<String>(value);
                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                new Holder<String>(box, 0);
                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                holder.set(value);
              }
            }
            """)
        .doTest();
  }

  /**
   * The implicit receiver of a call is the innermost enclosing class that has the method as a
   * member, as javac resolves it, and an anonymous class supplies its supertype as the class
   * instance creation writes it, so {@code E} takes the type argument that class gives it. A
   * private method is not a member of a subclass, so it binds to the declaring class.
   */
  @Test
  public void anImplicitReceiverIsTheInnermostClassWithTheMethodAsAMember() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Base<E extends @Nullable Object> {
                void put(E element) {}
                <U extends E> void set(U element) {}
              }
              static class Other<F extends @Nullable Object> {}
              static class WithPrivate<E extends @Nullable Object> {
                E value;
                WithPrivate(E value) {
                  this.value = value;
                }
                private E get() {
                  return value;
                }
                void fromAnAnonymousSubclass() {
                  new WithPrivate<@Nullable String>(null) {
                    void call() {
                      // a private method binds to the declaring class's E, not to @Nullable String
                      get().hashCode();
                    }
                  };
                }
              }
              static class NullableSub extends Base<@Nullable String> {
                void fromAnAnonymousClassOfAnotherType(@Nullable String value) {
                  new Other<String>() {
                    void call() {
                      put(value);
                      set(value);
                    }
                  };
                }
              }
              static class NonNullSub extends Base<String> {
                void fromAnAnonymousClassOfAnotherType(@Nullable String value) {
                  new Other<String>() {
                    void call() {
                      // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                      put(value);
                    }
                  };
                }
              }
              void fromTheAnonymousClass(@Nullable String value) {
                new Base<@Nullable String>() {
                  void call() {
                    put(value);
                    set(value);
                    Runnable r = () -> set(value);
                  }
                };
              }
              void fromAClassNestedInTheAnonymousClass(@Nullable String value) {
                new Base<@Nullable String>() {
                  class Inner {
                    void call() {
                      put(value);
                      set(value);
                    }
                  }
                };
              }
            }
            """)
        .doTest();
  }

  /**
   * A generic method reference that constraint generation reaches through a lambda keeps its type
   * variables while inference for the enclosing call runs, as one passed directly does, so {@code
   * T} is inferred {@code @Nullable} from the target or from {@code value}.
   */
  @Test
  public void aGenericMethodReferenceReturnedFromALambdaLeavesInferenceToTheCall() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.Function;
            import java.util.function.Supplier;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <U extends @Nullable Object> U id(U u) {
                return u;
              }
              static <U> U idNonNull(U u) {
                return u;
              }
              static <T extends @Nullable Object> Supplier<Function<T, T>> make(
                  Supplier<Function<T, T>> s) {
                return s;
              }
              static <T extends @Nullable Object> void use(Supplier<Function<T, T>> f, T x) {}
              void expressionBody() {
                Supplier<Function<@Nullable String, @Nullable String>> s = make(() -> Test::id);
              }
              void blockBody() {
                Supplier<Function<@Nullable String, @Nullable String>> s =
                    make(() -> {
                      return Test::id;
                    });
              }
              void beside(@Nullable String value) {
                use(() -> Test::id, value);
              }
              void inAConditional(boolean b, @Nullable String value) {
                use(() -> b ? Test::id : Test::id, value);
              }
              void besideANonNullReference(@Nullable String value) {
                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                use(() -> Test::idNonNull, value);
              }
            }
            """)
        .doTest();
  }

  /**
   * A call whose inference fails is checked with the type javac inferred, carrying the explicit
   * annotations of the callee's declared parameters, so {@code output} has to accept a null.
   */
  @Test
  public void aFailedInferenceKeepsTheDeclaredAnnotations() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static <U> void copy(Box<? extends U> input, Box<? super @Nullable U> output) {}
              void intoNullableElements(Box<@Nullable String> input, Box<@Nullable String> output) {
                // BUG: Diagnostic contains: inference failure
                copy(input, output);
              }
              void intoNonNullElements(Box<@Nullable String> input, Box<String> output) {
                // BUG: Diagnostic contains: Box<String> cannot be converted to Box<? super @Nullable String>
                copy(input, output);
              }
            }
            """)
        .doTest();
  }

  /**
   * Nested calls of one generic class or method share its type variable during inference, so the
   * class-type result of the outer call cannot take the {@code @Nullable} that the inner call's
   * argument implies. Such a result stays non-null, the least solution.
   */
  @Test
  public void aTypeVariableSharedByNestedCallsLeavesAClassTypeResultNonNull() {
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
                pick(pick(t, "x"), "y").toString();
              }
            }
            """)
        .doTest();
  }

  /**
   * Dataflow reads a {@code T} whose bound admits null as non-null whether or not the code checked
   * it (#1727), so inference cannot tell a checked {@code t} from an unchecked one, and a
   * class-type result is {@code @Nullable} after a null check too. This is a false positive.
   */
  @Test
  public void aNullCheckOfATypeVariableDoesNotReachInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              static <U extends @Nullable Object> U pick(U a, U b) {
                return a;
              }
              void f(T t) {
                if (t != null) {
                  // BUG: Diagnostic contains: dereferenced expression 'pick(t, "x")' is @Nullable
                  pick(t, "x").toString();
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
              void f(T t) {
                pick(t, new B()).run();
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
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
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
              static <U> U nonNullVariableWithValue(Box<? extends U> b, U u) {
                return u;
              }
              static <T extends @Nullable Object> void intoNonNullVariable(Box<T> b) {
                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                Object o = nonNullVariable(b);
              }
              static <T extends @Nullable Object> void intoNullableVariable(Box<T> b) {
                Object o = nullableVariable(b);
              }
              static <T> void nonNullBoundIntoNonNullVariable(Box<T> b) {
                Object o = nonNullVariable(b);
              }
              static <T extends @Nullable Object> void intoNonNullVariableFixedByAnotherArgument(
                  Box<T> b) {
                // BUG: Diagnostic contains: incompatible types: Box<T> cannot be converted to Box<? extends Object>
                Object o = nonNullVariableWithValue(b, new Object());
              }
              static <T extends @Nullable Object> void intoJdkCopyOf(List<T> in) {
                // BUG: Diagnostic contains: inference failure: type variable E is constrained to be @Nullable
                List<T> copy = List.copyOf(in);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aCapturedTypeVariableIsNotJudgedByItsBound() {
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
            class Test {
              static class Box<E extends @Nullable Object> {}
              interface ConcreteRequirement<T extends @Nullable Object> {
                Box<? extends T> get();
                default Box<? extends Object> pass() {
                  return get();
                }
              }
              interface BareRequirement<K, V extends @Nullable Object> {
                Map<? extends K, ? extends V> loadAll();
                default Map<? extends K, ? extends V> pass() {
                  return loadAll();
                }
              }
              interface NonNullProjectionOfAnIntermediateVariable<
                  V extends @Nullable Object, S extends @Nullable V> {
                Map<String, ? extends @NonNull S> loadAll();
                default CompletableFuture<? extends Map<String, ? extends V>> loadAllAsync() {
                  return CompletableFuture.supplyAsync(() -> loadAll());
                }
              }
              interface NonNullProjectionRequirement<K, V extends @Nullable Object> {
                Map<? extends K, ? extends @NonNull V> loadAll(Set<? extends K> keys);
                default CompletableFuture<? extends Map<? extends K, ? extends @NonNull V>> asyncLoadAll(
                    Set<? extends K> keys) {
                  return CompletableFuture.supplyAsync(() -> loadAll(keys));
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aGenericMethodOverrideMeetsAWildcardBoundedByTheOverriddenMethodTypeVariable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.Function;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              interface Mapper<T extends @Nullable Object> {
                <R extends @Nullable Object> Mapper<R> map(Function<? super T, ? extends R> f);
                <V extends @Nullable Object> Box<? extends V> wrap(V v);
              }
              abstract static class Impl<T extends @Nullable Object> implements Mapper<T> {
                @Override
                public abstract <R extends @Nullable Object> Mapper<R> map(
                    Function<? super T, ? extends R> f);
                @Override
                public abstract <V extends @Nullable Object> Box<V> wrap(V v);
              }
              abstract static class NonNullProjection<T extends @Nullable Object> implements Mapper<T> {
                @Override
                public abstract <R extends @Nullable Object> Mapper<R> map(
                    // BUG: Diagnostic contains: mismatched type parameter nullability
                    Function<? super T, ? extends @NonNull R> f);
                @Override
                public abstract <V extends @Nullable Object> Box<V> wrap(V v);
              }
              abstract static class Narrowed<T extends @Nullable Object> implements Mapper<T> {
                @Override
                // BUG: Diagnostic contains: Method type variable R has a non-null upper bound
                public abstract <R>
                    // a report on the parameter's own line would fail the test as unexpected
                    Mapper<R> map(
                        Function<? super T, ? extends R> f);
                @Override
                public abstract <V extends @Nullable Object> Box<V> wrap(V v);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void anOverrideReturnTypeMustKeepANonNullProjection() {
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
              static <V extends @Nullable Object> Test<V> widened() {
                return new Test<>() {
                  // BUG: Diagnostic contains: mismatched type parameter nullability
                  @Override public List<V> get() {
                    throw new UnsupportedOperationException();
                  }
                };
              }
              static <V extends @Nullable Object> Test<V> kept() {
                return new Test<>() {
                  @Override public List<@NonNull V> get() {
                    throw new UnsupportedOperationException();
                  }
                };
              }
            }
            """)
        .doTest();
  }
}
