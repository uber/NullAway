package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Fails when NullAway changes how it treats a value whose type is a type variable, or a wildcard
 * captured against one.
 *
 * <p>Such a value carries the JSpecify {@code NO_CHANGE} nullness operator. It satisfies the same
 * type variable at a return, an argument, or a field assignment, and it stays unsafe to dereference
 * while that type variable has a nullable upper bound. An explicit {@code @Nullable}, declared on
 * the member or supplied by a library model, overrides that and is reported at every sink.
 *
 * <p>The green tests pin what NullAway already gets right. A fix for what it gets wrong can break
 * several of them without failing anything else, which is why they are here.
 *
 * <p>The ignored tests describe the two defects collected in issue #1727. A value obtained through
 * {@code ? extends T} is rejected at a sink that requires {@code T}, and a dereference of a value
 * typed by a type variable with a nullable upper bound goes unreported. The second defect involves
 * no wildcard. A fix turns those tests green and leaves the rest of this class alone.
 *
 * @see <a href="https://jspecify.dev/docs/spec/#nullness-operator">JSpecify: nullness operator</a>
 */
public class NullnessOperatorTests extends NullAwayTestsBase {

  @Test
  public void nullableMemberThroughWildcardIsRejectedAtReturn() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface NBox<R extends @Nullable Object> {
                @Nullable R get();
              }
              static <T extends @Nullable Object> T get(NBox<? extends T> box) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return box.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableMemberThroughWildcardIsRejectedAtArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface NBox<R extends @Nullable Object> {
                @Nullable R get();
              }
              static <T extends @Nullable Object> void sink(T value) {}
              static <T extends @Nullable Object> void pass(NBox<? extends T> box) {
                // BUG: Diagnostic contains: passing @Nullable parameter
                Test.<T>sink(box.get());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableMemberThroughWildcardIsRejectedAtFieldAssignment() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              interface NBox<R extends @Nullable Object> {
                @Nullable R get();
              }
              private T current;
              // Use Box<T> here so initialization does not exercise the #1727 false positive.
              Test(Box<T> initial) {
                current = initial.get();
              }
              void update(NBox<? extends T> box) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                current = box.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableMemberThroughTypeVariableIsRejectedAtReturn() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface NBox<R extends @Nullable Object> {
                @Nullable R get();
              }
              static <T extends @Nullable Object> T get(NBox<T> box) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return box.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void modeledNullableThroughWildcardIsRejectedAtReturn() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T get(Map<String, ? extends T> map) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return map.get("key");
              }
            }
            """)
        .doTest();
  }

  @Test
  public void modeledNullableThroughWildcardIsRejectedAtArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> void sink(T value) {}
              static <T extends @Nullable Object> void pass(Map<String, ? extends T> map) {
                // BUG: Diagnostic contains: passing @Nullable parameter
                Test.<T>sink(map.get("key"));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void modeledNullableThroughTypeVariableIsRejectedAtReturn() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T get(Map<String, T> map) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return map.get("key");
              }
            }
            """)
        .doTest();
  }

  /**
   * Repeats {@link WildcardTests#wildcardCaptureReturnWithTypeVariableUpperBound()} with the type
   * variable declared on the method rather than on the enclosing class, which reaches a different
   * substitution path.
   */
  @Test
  public void wildcardResultIsNotDereferenceable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> void dereference(Box<? extends T> box) {
                // BUG: Diagnostic contains: dereferenced expression 'box.get()' is @Nullable
                box.get().hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableWildcardBoundIsRejectedAtReturn() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> T get(Box<? extends @Nullable T> box) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return box.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nonNullReturnTargetRejectsWildcardResult() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> @NonNull T get(Box<? extends T> box) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return box.get();
              }
            }
            """)
        .doTest();
  }

  /**
   * Repeats {@link WildcardTests#wildcardCaptureLocals()} with a wildcard bounded by a type
   * variable rather than by {@code Object}.
   */
  @Test
  public void localHoldingWildcardResultIsNotDereferenceable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> void dereference(Box<? extends T> box) {
                T value = box.get();
                // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                value.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullAssignmentInvalidatesWildcardLocal() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> void sink(T value) {}
              static <T extends @Nullable Object> void pass(Box<? extends T> box) {
                T value = box.get();
                value = null;
                // Keep this regression guard after #1727 is fixed: assigning null must invalidate
                // any compatibility established by the wildcard result.
                // BUG: Diagnostic contains: passing @Nullable parameter
                Test.<T>sink(value);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void typeVariableResultIsAccepted() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> T get(Box<T> box) {
                return box.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nonNullBoundWildcardResultIsAccepted() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T> T get(Box<? extends T> box) {
                return box.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void concreteBoundWildcardResultIsAccepted() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static String get(Box<? extends String> box) {
                return box.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nestedWildcardResultIsAccepted() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> Box<T> get(Box<? extends Box<T>> box) {
                return box.get();
              }
            }
            """)
        .doTest();
  }

  @Ignore("https://github.com/uber/NullAway/issues/1727")
  @Test
  public void issue1727WildcardReturnFalsePositive() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> T unwrap(Box<? extends T> box) {
                return box.get();
              }
            }
            """)
        .doTest();
  }

  @Ignore("https://github.com/uber/NullAway/issues/1727")
  @Test
  public void issue1727WildcardLocalArgumentFalsePositive() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> void sink(T value) {}
              static <T extends @Nullable Object> void pass(Box<? extends T> box) {
                T value = box.get();
                Test.<T>sink(value);
              }
            }
            """)
        .doTest();
  }

  @Ignore("https://github.com/uber/NullAway/issues/1727")
  @Test
  public void issue1727EnhancedForLoopFalsePositive() {
    makeHelper()
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

  @Ignore("https://github.com/uber/NullAway/issues/1799")
  @Test
  public void issue1799EnhancedForLoopFunctionArgumentFalsePositive() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.LinkedHashMap;
            import java.util.Map;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              <E extends @Nullable Object, K, V extends @Nullable Object> Map<K, V> toMap(
                  Iterable<E> elems, Function<E, K> toKey, Function<E, V> toValue) {
                Map<K, V> map = new LinkedHashMap<>();
                for (E e : elems) {
                  map.put(toKey.apply(e), toValue.apply(e));
                }
                return map;
              }
            }
            """)
        .doTest();
  }

  @Ignore("https://github.com/uber/NullAway/issues/1727")
  @Test
  public void issue1727WildcardDirectArgumentFalsePositive() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> void sink(T value) {}
              static <T extends @Nullable Object> void pass(Box<? extends T> box) {
                Test.<T>sink(box.get());
              }
            }
            """)
        .doTest();
  }

  @Ignore("https://github.com/uber/NullAway/issues/1727")
  @Test
  public void issue1727WildcardFieldAssignmentFalsePositive() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              private final Box<? extends T> box;
              private T current;
              // On master, both assignments and the cascading initialization error are reported.
              // After #1727 is fixed, none of those diagnostics should remain.
              Test(Box<? extends T> box) {
                this.box = box;
                current = box.get();
              }
              void advance() {
                current = box.get();
              }
            }
            """)
        .doTest();
  }

  @Ignore("https://github.com/uber/NullAway/issues/1727")
  @Test
  public void issue1727IteratorFieldAssignmentFalsePositive() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Iterator;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              private final Iterator<? extends T> iterator;
              private T current;
              // On master, both assignments and the cascading initialization error are reported.
              // After #1727 is fixed, none of those diagnostics should remain.
              Test(Iterator<? extends T> iterator) {
                this.iterator = iterator;
                current = iterator.next();
              }
              void advance() {
                current = iterator.next();
              }
            }
            """)
        .doTest();
  }

  @Ignore("https://github.com/uber/NullAway/issues/1727")
  @Test
  public void issue1727AggregateFalsePositive() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Fn2<A extends @Nullable Object, S, R extends @Nullable Object> {
                R apply(A accumulator, S element);
              }
              static <S, A extends @Nullable Object> A aggregate(
                  Iterable<S> source, A seed, Fn2<A, S, ? extends A> function) {
                A result = seed;
                for (S element : source) {
                  result = function.apply(result, element);
                }
                return result;
              }
            }
            """)
        .doTest();
  }

  @Ignore("https://github.com/uber/NullAway/issues/1727")
  @Test
  public void issue1727LambdaReturnFalsePositive() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> Box<T> wrap(Box<? extends T> box) {
                return () -> box.get();
              }
            }
            """)
        .doTest();
  }

  @Ignore("https://github.com/uber/NullAway/issues/1727")
  @Test
  public void issue1727ArrayStoreFalsePositive() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> void store(
                  T[] values, Box<? extends T> box) {
                values[0] = box.get();
              }
            }
            """)
        .doTest();
  }

  @Ignore("https://github.com/uber/NullAway/issues/1727")
  @Test
  public void issue1727NullableBoundDereferenceFalseNegative() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Foo<T extends @Nullable Object> {
                int dereference(T t) {
                  // BUG: Diagnostic contains: dereferenced expression 't' is @Nullable
                  return t.hashCode();
                }
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
}
