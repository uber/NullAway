package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import org.junit.Test;

/** Regression coverage for full-type inference through nested calls and constructor variables. */
public class FullTypeInferenceRegressionTests extends NullAwayTestsBase {

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

  @Test
  public void conditionalMethodReferencesCheckNestedReturnAndParameterContracts() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Supplier<E> { E get(); }
              interface Consumer<E> { void accept(E value); }
              static List<@Nullable String> nullableElements() { throw new RuntimeException(); }
              static List<String> nonNullElements() { throw new RuntimeException(); }
              static void acceptsNullable(List<@Nullable String> values) {}
              static void acceptsNonNull(List<String> values) {}
              static void take(Supplier<List<String>> supplier) {}
              static void consume(Consumer<List<@Nullable String>> consumer) {}
              static <T> void infer(Supplier<T> supplier, T value) {}
              void test(boolean flag, List<String> values) {
                take(flag ? Test::nonNullElements : Test::nonNullElements);
                // BUG: Diagnostic contains: referenced method returns List<@Nullable String>
                take(flag ? Test::nullableElements : Test::nonNullElements);
                // BUG: Diagnostic contains: referenced method returns List<@Nullable String>
                take(flag ? Test::nonNullElements : (Test::nullableElements));
                consume(flag ? Test::acceptsNullable : Test::acceptsNullable);
                // BUG: Diagnostic contains: parameter type of referenced method is List<String>
                consume(flag ? Test::acceptsNonNull : Test::acceptsNullable);
                // BUG: Diagnostic contains: referenced method returns List<String>
                infer(flag ? Test::nullableElements : Test::nonNullElements, values);
                Supplier<List<String>> assigned =
                    // BUG: Diagnostic contains: referenced method returns List<@Nullable String>
                    flag ? Test::nullableElements : Test::nonNullElements;
                Supplier<List<String>> lambdas =
                    // BUG: Diagnostic contains: incompatible types
                    flag ? () -> nullableElements() : () -> nonNullElements();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void constructorOwnedVariablesRespectFixedClassArgumentsAndExplicitWitnesses() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Holder<E extends @Nullable Object> {
                <U extends E> Holder(U value) {}
                <A extends E, B extends A> Holder(B value, int unused) {}
              }
              static class Consumer {
                <U> Consumer(List<U> value) {}
              }
              void test(List<@Nullable String> nullable, List<String> nonNull, @Nullable String value) {
                new Holder<@Nullable String>(value);
                Holder<@Nullable String> diamond = new Holder<>(value);
                // BUG: Diagnostic contains: constrained to be both @NonNull and @Nullable
                Holder<String> invalidDiamond = new Holder<>(value);
                new Holder<@Nullable String>(value, 0);
                // BUG: Diagnostic contains: inference failure: type variable U
                new Holder<String>(value);
                // BUG: Diagnostic contains: inference failure: type variable A
                new Holder<String>(value, 0);
                new <List<@Nullable String>>Holder<@Nullable Object>(nullable);
                new <String>Consumer(nonNull);
                new <String>Consumer(nonNull) {};
                // BUG: Diagnostic contains: cannot be converted
                new <String>Consumer(nullable);
                // BUG: Diagnostic contains: cannot be converted
                new <String>Consumer(nullable) {};
                // BUG: Diagnostic contains: cannot be converted
                new Consumer(nullable);
                // BUG: Diagnostic contains: cannot be converted
                new Consumer(nullable) {};
              }
            }
            """)
        .doTest();
  }

  @Test
  public void lambdaLocalInitializersShareConstraintsWithoutSharingReadNullness() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Factory<R extends @Nullable Object> { R get(); }
              static <U extends @Nullable Object> U id(U value) { return value; }
              static <T extends @Nullable Object> T supply(Factory<T> factory) {
                return factory.get();
              }
              static void nullableLists(Factory<List<@Nullable String>> factory) {}
              static void nonNullLists(Factory<List<String>> factory) {}
              static void strings(Factory<String> factory) {}
              static void nullableStrings(Factory<@Nullable String> factory) {}
              static void nonNull(String value) {}
              void test(List<@Nullable String> values, @Nullable String value) {
                nullableLists(() -> {
                  var copy = id(values);
                  var alias = id(copy);
                  return alias;
                });
                List<@Nullable String> inferred = supply(() -> {
                  var copy = (id(values));
                  return id(copy);
                });
                nonNullLists(() -> {
                  var copy = id(values);
                  // BUG: Diagnostic contains: incompatible types
                  return copy;
                });
                strings(() -> {
                  var copy = id(value);
                  if (copy == null) { return ""; }
                  copy.length();
                  return copy;
                });
                nullableStrings(() -> {
                  var copy = id(value);
                  // BUG: Diagnostic contains: passing @Nullable parameter
                  nonNull(copy);
                  return copy;
                });
              }
            }
            """)
        .doTest();
  }

  /** Creates a helper for nullness-marked generic inference tests. */
  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(List.of("-XepOpt:NullAway:OnlyNullMarked=true")));
  }
}
