package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import org.junit.Ignore;
import org.junit.Test;

public class GenericMethodLambdaOrMethodRefArgTests extends NullAwayTestsBase {

  @Test
  public void unmarkedMethodReferenceTargetsDoNotReportNestedInferenceFailures() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NullUnmarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              @NullUnmarked interface UnmarkedFn<T> { void apply(T value); }
              interface MarkedFn<T extends @Nullable Object> { void apply(T value); }
              @NullUnmarked interface ExplicitNullableFn {
                @Nullable Object apply(@Nullable Object value);
              }
              static <T> void nonNullElements(List<T> values) {}
              static <T extends @Nullable Object> T identity(T value) { return value; }
              static void acceptUnmarked(UnmarkedFn<List<@Nullable String>> function) {}
              static void acceptMarked(MarkedFn<List<@Nullable String>> function) {}
              static void acceptExplicitNullable(ExplicitNullableFn function) {}
              void unmarkedArgument() { acceptUnmarked(Test::nonNullElements); }
              void unmarkedLocal() {
                UnmarkedFn<List<@Nullable String>> function = Test::nonNullElements;
              }
              UnmarkedFn<List<@Nullable String>> unmarkedReturn() {
                return Test::nonNullElements;
              }
              void explicitNullableSignatureStillInfers() { acceptExplicitNullable(Test::identity); }
              void markedArgument() {
                // BUG: Diagnostic contains: inference failure
                acceptMarked(Test::nonNullElements);
              }
              void markedLocal() {
                // BUG: Diagnostic contains: inference failure
                MarkedFn<List<@Nullable String>> function = Test::nonNullElements;
              }
              MarkedFn<List<@Nullable String>> markedReturn() {
                // BUG: Diagnostic contains: inference failure
                return Test::nonNullElements;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue2063InlineGenericMethodReference() {
    checkInlineGenericMethodReference(
        "@NullMarked interface Publisher<T extends @Nullable Object> {}");
  }

  @Test
  public void issue2063InlineGenericMethodReferenceWithUnmarkedPublisher() {
    checkInlineGenericMethodReference("@NullUnmarked interface Publisher<T> {}");
  }

  /** Checks the wildcard inference reproducer with either a marked or an unmarked publisher. */
  private void checkInlineGenericMethodReference(String publisherDeclaration) {
    makeHelper()
        .addSourceLines(
            "Repro.java",
            """
            package com.uber;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NullUnmarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Repro {
              %s
              static class Mono<T extends @Nullable Object> implements Publisher<T> {
                static <T extends @Nullable Object> Mono<T> from(Publisher<? extends T> source) {
                  return new Mono<>();
                }
              }
              Function<Publisher<?>, Object> assigned = Mono::from;
              static void register(Function<Publisher<?>, Object> adapter) {}
              static <Unused> void registerGeneric(Function<Publisher<?>, Object> adapter) {}
              static class Adapter {
                Adapter(Function<Publisher<?>, Object> adapter) {}
              }
              void inlineReference() { register(Mono::from); }
              void parenthesizedReference() { register((Mono::from)); }
              void constructorArgument() { new Adapter(Mono::from); }
              void explicitTypeArgument() { register(Mono::<@Nullable Object>from); }
              void lambda() { register(source -> Mono.from(source)); }
              void assignedReference() {
                Function<Publisher<?>, Object> adapter = Mono::from;
                register(adapter);
              }
              void genericEnclosingCall() { registerGeneric(Mono::from); }
              void explicitEnclosingTypeArgument() { Repro.<Object>registerGeneric(Mono::from); }
            }
            """
                .formatted(publisherDeclaration))
        .doTest();
  }

  @Test
  public void genericMethodReferenceInferenceAgainstKnownTarget() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.function.BiFunction;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static class Factory {
                <T extends @Nullable Object> Box<T> box(T value) { return new Box<>(); }
              }
              static <T extends @Nullable Object> Box<T> box(T value) { return new Box<>(); }
              static <T> Box<T> nonNullBox(T value) { return new Box<>(); }
              @SafeVarargs
              static <T extends @Nullable Object> Box<T> varargsBox(T... values) {
                return new Box<>();
              }
              static void acceptsNonNull(Function<String, Box<String>> function) {}
              static void acceptsNullable(
                  Function<@Nullable String, Box<@Nullable String>> function) {}
              static void acceptsBoth(Function<String, Box<String>> first,
                  Function<@Nullable String, Box<@Nullable String>> second) {}
              static void acceptsUnbound(
                  BiFunction<Factory, @Nullable String, Box<@Nullable String>> function) {}
              static void acceptsArray(
                  Function<@Nullable String[], Box<@Nullable String>> function) {}
              static void acceptsIncompatible(Function<@Nullable String, Box<String>> function) {}
              void nonNullInputAndReturn() { acceptsNonNull(Test::box); }
              void nullableInputAndReturn() { acceptsNullable(Test::box); }
              void nonNullBoundWithNonNullInput() { acceptsNonNull(Test::nonNullBox); }
              void boundReference(Factory factory) { acceptsNullable(factory::box); }
              void unboundReference() { acceptsUnbound(Factory::box); }
              void variableArityReference() { acceptsNullable(Test::varargsBox); }
              void fixedArityReference() { acceptsArray(Test::varargsBox); }
              void referencesAreInferredSeparately() {
                acceptsBoth(Test::box, Test::box);
              }
              void incompatibleInputAndReturn() {
                // BUG: Diagnostic contains: inference failure: type variable T constrained to be both
                acceptsIncompatible(Test::box);
              }
              void nonNullBoundWithNullableInput() {
                // BUG: Diagnostic contains: inference failure: type variable T is constrained to be @Nullable
                acceptsNullable(Test::nonNullBox);
              }
              void explicitTypeArgumentIsPreserved() {
                // BUG: Diagnostic contains: referenced method
                acceptsNullable(Test::<String>box);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericMethodReferenceInferenceAcrossContexts() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.List;
            import java.util.function.Function;
            import java.util.function.Supplier;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> List<T> identity(List<T> values) {
                return values;
              }
              static void acceptNullable(
                  Function<List<@Nullable String>, List<@Nullable String>> function) {}
              static void acceptIncompatible(
                  Function<List<@Nullable String>, List<String>> function) {}
              static class Adapter {
                Adapter(Function<List<@Nullable String>, List<String>> function) {}
              }

              Function<List<String>, List<String>> nonNullField = Test::identity;
              Function<List<@Nullable String>, List<@Nullable String>> nullableField = Test::identity;
              // BUG: Diagnostic contains: inference failure: type variable T constrained to be both
              Function<List<@Nullable String>, List<String>> incompatibleField = Test::identity;

              void nonNullLocal() {
                Function<List<String>, List<String>> function = Test::identity;
              }
              void nullableLocal() {
                Function<List<@Nullable String>, List<@Nullable String>> function = Test::identity;
              }
              void incompatibleLocal() {
                // BUG: Diagnostic contains: inference failure: type variable T constrained to be both
                Function<List<@Nullable String>, List<String>> function = Test::identity;
              }
              void nullableAssignment() {
                nullableField = (Test::identity);
              }
              void incompatibleAssignment() {
                // BUG: Diagnostic contains: inference failure: type variable T constrained to be both
                incompatibleField = (Test::identity);
              }
              Function<List<String>, List<String>> nonNullReturn() {
                return Test::identity;
              }
              Function<List<@Nullable String>, List<@Nullable String>> nullableReturn() {
                return (Test::identity);
              }
              Function<List<@Nullable String>, List<String>> incompatibleReturn() {
                // BUG: Diagnostic contains: inference failure: type variable T constrained to be both
                return (Test::identity);
              }
              static <T> Function<List<@Nullable T>, List<@Nullable T>> nullableTypeVariableReturn() {
                return Test::identity;
              }
              static <T> Function<List<@Nullable T>, List<T>> incompatibleTypeVariableReturn() {
                // BUG: Diagnostic contains: inference failure: type variable T constrained to be both
                return Test::identity;
              }
              Supplier<Function<List<@Nullable String>, List<@Nullable String>>> nullableLambdaReturn() {
                return () -> Test::identity;
              }
              Supplier<Function<List<@Nullable String>, List<String>>> incompatibleLambdaReturn() {
                // BUG: Diagnostic contains: inference failure: type variable T constrained to be both
                return () -> Test::identity;
              }
              void nullableArgument() {
                acceptNullable(Test::identity);
              }
              void incompatibleArgument() {
                // BUG: Diagnostic contains: inference failure: type variable T constrained to be both
                acceptIncompatible(Test::identity);
              }
              void incompatibleConstructorArgument() {
                // BUG: Diagnostic contains: inference failure: type variable T constrained to be both
                new Adapter(Test::identity);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nonGenericMethodReferenceNestedNullabilityAcrossContexts() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.function.Consumer;
            import java.util.function.Supplier;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static List<@Nullable String> nullableValues() { return new ArrayList<>(); }
              static void nonNullValues(List<String> values) {}
              static void acceptNullableSupplier(Supplier<List<@Nullable String>> supplier) {}
              static void acceptNonNullSupplier(Supplier<List<String>> supplier) {}
              static void acceptNullableConsumer(Consumer<List<@Nullable String>> consumer) {}
              static void acceptNonNullConsumer(Consumer<List<String>> consumer) {}

              void compatibleLocals() {
                Supplier<List<@Nullable String>> supplier = Test::nullableValues;
                Consumer<List<String>> consumer = Test::nonNullValues;
              }
              void incompatibleSupplierLocal() {
                // BUG: Diagnostic contains: referenced method returns List<@Nullable String>
                Supplier<List<String>> supplier = Test::nullableValues;
              }
              void incompatibleConsumerLocal() {
                // BUG: Diagnostic contains: parameter type of referenced method is List<String>
                Consumer<List<@Nullable String>> consumer = Test::nonNullValues;
              }
              Supplier<List<@Nullable String>> compatibleSupplierReturn() {
                return Test::nullableValues;
              }
              Supplier<List<String>> incompatibleSupplierReturn() {
                // BUG: Diagnostic contains: referenced method returns List<@Nullable String>
                return Test::nullableValues;
              }
              Consumer<List<String>> compatibleConsumerReturn() {
                return Test::nonNullValues;
              }
              Consumer<List<@Nullable String>> incompatibleConsumerReturn() {
                // BUG: Diagnostic contains: parameter type of referenced method is List<String>
                return Test::nonNullValues;
              }
              void compatibleArguments() {
                acceptNullableSupplier(Test::nullableValues);
                acceptNonNullConsumer(Test::nonNullValues);
              }
              void incompatibleSupplierArgument() {
                // BUG: Diagnostic contains: referenced method returns List<@Nullable String>
                acceptNonNullSupplier(Test::nullableValues);
              }
              void incompatibleConsumerArgument() {
                // BUG: Diagnostic contains: parameter type of referenced method is List<String>
                acceptNullableConsumer(Test::nonNullValues);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void voidCompatibleLambdaDiscardsNullableGenericResult() {
    makeHelper()
        .addSourceLines(
            "Repro.java",
            """
            package repro;

            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            final class Repro {
              interface Executable {
                void execute();
              }

              interface Processor<T extends @Nullable Object> {
                T process();
              }

              interface Action<T extends @Nullable Object> {
                void accept(List<T> values);
              }

              static <E extends Throwable> E assertThrows(Class<E> expectedType, Executable executable) {
                throw new UnsupportedOperationException();
              }

              static <T extends @Nullable Object> T invoke(Processor<T> processor) {
                return processor.process();
              }

              static <T extends @Nullable Object> T requireNonNullArgument(T value, Object required) {
                return value;
              }

              static <T extends @Nullable Object> T evaluate(Processor<T> processor) {
                return processor.process();
              }

              static <T extends @Nullable Object> T choose(Action<T> action) {
                throw new UnsupportedOperationException();
              }

              static <T extends @Nullable Object> @Nullable T fromNullableList(
                  List<@Nullable T> values) {
                return null;
              }

              static void expressionBody() {
                assertThrows(RuntimeException.class, () -> invoke(() -> null));
              }

              static void blockBody() {
                assertThrows(RuntimeException.class, () -> { invoke(() -> null); });
              }

              static void standaloneLambda() {
                Executable executable = () -> invoke(() -> null);
              }

              static void discardedNonGenericCall(Processor<@Nullable Object> processor) {
                assertThrows(RuntimeException.class, () -> processor.process());
              }

              static @Nullable String inferFromDiscardedCallArguments() {
                return choose(values -> fromNullableList(values));
              }

              static void discardedResultStillChecksArguments() {
                // BUG: Diagnostic contains: passing @Nullable parameter 'null'
                assertThrows(RuntimeException.class, () -> requireNonNullArgument(null, null));
              }

              static @Nullable Object nullableReturn() {
                return evaluate(() -> invoke(() -> null));
              }

              static Object nonNullReturn() {
                // BUG: Diagnostic contains: inference failure: type variable T constrained to be both @NonNull and @Nullable
                return evaluate(() -> invoke(() -> null));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void modeledBoundPreservedForSeparateInferenceInLambdaBody() {
    makeHelper()
        .addSourceLines(
            "NestedAnnots.java",
            """
            package com.uber.lib.unannotated;
            import java.util.List;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class NestedAnnots<T> {
              // The test library model makes T's upper bound nullable.
              NestedAnnots(T value, Function<List<T>, Integer> function) {}
              static <U extends @Nullable Object> List<U> append(List<U> values, U value) {
                return values;
              }
              static void test() {
                // The receiver call starts a separate inference problem while the lambda
                // parameter still contains the constructor's unsolved fresh T.
                new NestedAnnots<>(null, values -> append(values, null).size());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1919ImplicitLambdaParameterPreservesNestedNullness() {
    makeHelper()
        .addSourceLines(
            "Example.java",
            """
            package com.uber;

            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            public final class Example {
              public static final class Box<T extends @Nullable Object> {
                public <R extends @Nullable Object> Box<R> flatMap(
                    Mapper<? super T, ? extends Box<R>> mapper) {
                  throw new UnsupportedOperationException();
                }
              }

              @FunctionalInterface
              public interface Mapper<P extends @Nullable Object, R extends @Nullable Object> {
                R apply(P value);
              }

              static void acceptsNullable(Box<@Nullable String> box) {}

              static void implicitParameter(Box<Box<@Nullable String>> nested) {
                // Should infer R -> @Nullable String, so flat has type Box<@Nullable String>
                var flat = nested.flatMap(box -> box);
                acceptsNullable(flat);
              }

              static void implicitParameterNonNull(Box<Box<String>> nested) {
                var flat = nested.flatMap(box -> box);
                // BUG: Diagnostic contains: incompatible types: Box<String> cannot be converted to Box<@Nullable String>
                acceptsNullable(flat);
              }

              static void blockBody(Box<Box<@Nullable String>> nested) {
                // Should infer R -> @Nullable String, so flat has type Box<@Nullable String>
                var flat = nested.flatMap(box -> { return box; });
                acceptsNullable(flat);
              }

              static void localCopy(Box<Box<@Nullable String>> nested) {
                // Should infer R -> @Nullable String, so flat has type Box<@Nullable String>
                var flat = nested.flatMap(box -> { var copy = box; return copy; });
                acceptsNullable(flat);
              }

              static <P extends @Nullable Object, R extends @Nullable Object> Box<R> map(
                  Box<P> input, Mapper<Box<P>, Box<R>> mapper) {
                throw new UnsupportedOperationException();
              }

              static void localCopyWithInferenceVariable(Box<@Nullable String> box) {
                var result = map(box, value -> { var copy = value; return copy; });
                acceptsNullable(result);
              }

              static void nestedInference(Box<Box<Box<@Nullable String>>> nested) {
                var flat = nested.flatMap(
                    outer -> outer.flatMap(inner -> outer.flatMap(ignored -> inner)));
                acceptsNullable(flat);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericCallInLambdaVarInitializerPreservesNestedNullness() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {
                T get() { throw new UnsupportedOperationException(); }
              }
              interface Mapper<P extends @Nullable Object, R extends @Nullable Object> {
                R apply(P value);
              }
              static <P extends @Nullable Object, R extends @Nullable Object> Box<R> map(
                  Box<P> input, Mapper<Box<P>, Box<R>> mapper) {
                throw new UnsupportedOperationException();
              }
              static <U extends @Nullable Object> Box<U> id(Box<U> input) { return input; }
              static void test(Box<@Nullable String> input) {
                var result = map(input, value -> {
                  var copy = id(value);
                  // BUG: Diagnostic contains: dereferenced expression 'copy.get()' is @Nullable
                  copy.get().length();
                  return copy;
                });
              }
            }
            """)
        .doTest();
  }

  @Test
  public void lambdaReturnsGenericMethodCall() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
                static interface Supplier<T extends @Nullable Object> {
                    T get();
                }
                static <R extends @Nullable Object> R invokeWithReturn(Supplier<R> supplier) {
                    return supplier.get();
                }
                static <U extends @Nullable Object> U genericMethod(U var){
                     return var;
                }
                static void test() {
                    Object x = invokeWithReturn(() -> { return genericMethod("value");});
                    Object y = invokeWithReturn(() -> { return genericMethod(null);});
                    // legal, should infer x is a @NonNull String
                    x.hashCode();
                    // BUG: Diagnostic contains: dereferenced expression 'y' is @Nullable
                    y.hashCode();
                    // Block-bodied with parenthesized return
                    Object x_block_paren = invokeWithReturn(() -> { return (genericMethod("value"));});
                    Object y_block_paren = invokeWithReturn(() -> { return (genericMethod(null));});
                    // legal, should infer x_block_paren is a @NonNull String
                    x_block_paren.hashCode();
                    // BUG: Diagnostic contains: dereferenced expression 'y_block_paren' is @Nullable
                    y_block_paren.hashCode();
                    // Expression-bodied
                    Object x_expr = invokeWithReturn(() -> genericMethod("value"));
                    Object y_expr = invokeWithReturn(() -> genericMethod(null));
                    // legal, should infer x_expr is a @NonNull String
                    x_expr.hashCode();
                    // BUG: Diagnostic contains: dereferenced expression 'y_expr' is @Nullable
                    y_expr.hashCode();
                    // Expression-bodied with parenthesized return
                    Object x_expr_paren = invokeWithReturn(() -> (genericMethod("value")));
                    Object y_expr_paren = invokeWithReturn(() -> (genericMethod(null)));
                    // legal, should infer x_expr_paren is a @NonNull String
                    x_expr_paren.hashCode();
                    // BUG: Diagnostic contains: dereferenced expression 'y_expr_paren' is @Nullable
                    y_expr_paren.hashCode();
                    Object x2 = invokeWithReturn(() ->{ Object y2 = null; return y2;});
                    // BUG: Diagnostic contains: dereferenced expression 'x2' is @Nullable
                    x2.hashCode();
                }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1294_lambdaArguments() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Foo {
             public interface Callback<T extends @Nullable Object> {
               void onResult(T thing);
             }
             public static <T extends @Nullable Object> Callback<T> wrap(Callback<T> thing) {
               return thing;
             }
             public static void test() {
               Callback<@Nullable String> ret1 = wrap(s -> {});
               // BUG: Diagnostic contains: dereferenced expression
               Callback<@Nullable String> ret2 = wrap(s -> { s.hashCode(); });
               Callback<@Nullable String> ret3 = wrap(s -> { if (s != null) s.hashCode(); });
               Callback<String> ret4 = wrap(s -> { s.hashCode(); });
               }
            }
            """)
        .doTest();
  }

  @Test
  public void supplierLambdaInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
                static interface Supplier<T extends @Nullable Object> {
                    T get();
                }
                static <R> void invoke(Supplier<@Nullable R> supplier) {}
                static <R extends @Nullable Object> R invokeWithReturn(Supplier<R> supplier) {
                    return supplier.get();
                }
                static void test() {
                    // legal, should infer R -> @Nullable Object
                    invoke(() -> null);
                    // legal, should infer R -> @Nullable Object
                    Object x = invokeWithReturn(() -> null);
                    // BUG: Diagnostic contains: dereferenced expression 'x' is @Nullable
                    x.hashCode();
                }
            }
            """)
        .doTest();
  }

  @Test
  public void supplierMethodRefInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
                static interface Supplier<T extends @Nullable Object> {
                    T get();
                }
                static <R> void invoke(Supplier<@Nullable R> supplier) {}
                static <R extends @Nullable Object> R invokeWithReturn(Supplier<R> supplier) {
                    return supplier.get();
                }
                static @Nullable Object returnsNull() {
                    return null;
                }
                static void test() {
                    // legal, should infer R -> @Nullable Object
                    invoke(Test::returnsNull);
                    // legal, should infer R -> @Nullable Object
                    Object x = invokeWithReturn(Test::returnsNull);
                    // BUG: Diagnostic contains: dereferenced expression 'x' is @Nullable
                    x.hashCode();
                }
            }
            """)
        .doTest();
  }

  @Test
  public void lambdaConditionalExprBody() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            import java.util.function.Function;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T run(Function<String,T> f) {
                return f.apply("");
              }
              static void test() {
                String t = run(s -> s == null ? null : s);
                // BUG: Diagnostic contains: dereferenced expression 't' is @Nullable
                t.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void lambdaWithReturnStmtNullable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            import java.util.function.Function;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T run(Function<String,T> f) {
                return f.apply("");
              }
              static void test() {
                String t = run(s -> { String result = null; return result; });
                // BUG: Diagnostic contains: dereferenced expression 't' is @Nullable
                t.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void lambdaReturnEnclosingLocal() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            import java.util.function.Function;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T run(Function<String,T> f) {
                return f.apply("");
              }
              static void test() {
                final String result = null;
                String t = run(s -> result);
                // BUG: Diagnostic contains: dereferenced expression 't' is @Nullable
                t.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void lambdaAccessingNonFinalField() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            import java.util.function.Function;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T run(Function<String,T> f) {
                return f.apply("");
              }
              static class Holder { @Nullable String f; }
              static void test() {
                final Holder x = new Holder();
                x.f = "value";
                // Since f is not final, we won't propagate facts about it into the lambda
                // So, we should infer T -> @Nullable String
                String t = run(s -> x.f);
                // BUG: Diagnostic contains: dereferenced expression 't' is @Nullable
                t.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nestedLambdaFromSpring() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import java.util.function.Function;
            import java.util.function.Supplier;
            @NullMarked
            public class Test {
                public interface Publisher<T> {}
                public static final class Flux<T> implements Publisher<T> {
                    public static <T> Flux<T> defer(Supplier<? extends Publisher<T>> supplier) {
                        return new Flux<>();
                    }
                    public static <T> Flux<T> from(Publisher<? extends T> publisher) {
                        return new Flux<>();
                    }
                    public <R> Flux<R> map(Function<? super T, ? extends R> mapper) {
                        return new Flux<>();
                    }
                    public Flux<T> doOnDiscard() {
                        return this;
                    }
                }
                public static <T> Flux<T> skipUntilByteCount(Publisher<T> publisher) {
                    return Flux.defer(() ->
                        Flux.from(publisher)
                                .map(buffer -> buffer)
                    ).doOnDiscard();
                }
            }
            """)
        .doTest();
  }

  @Test
  public void lambdaWithRawType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.Consumer;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              static <T extends @Nullable Object> T register(Consumer consumer, T... others) {
                throw new UnsupportedOperationException("TODO");
              }
                void use() {
                    register((t) -> {}, "a");
                }
            }
            """)
        .doTest();
  }

  @Test
  public void methodRefWithMatchingReturnNullability() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.function.Function;
            @NullMarked
            class Test {
              interface Foo {
                default @Nullable String get() {
                  return null;
                }
              }
              @Nullable String createWrapper() {
                return create(Foo::get);
              }
              private <T> @Nullable T create(Function<Foo, @Nullable T> factory) {
                return factory.apply(new Foo() {});
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1808ClassCastStreamPipeline() {
    makeHelper()
        .addSourceLines(
            "Tester.java",
            """
            import java.io.Serializable;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            public class Tester {
              public static void main(String[] args) {
                List<Serializable> list = List.of("Hello", 100);
                List<String> stringsLongerThanThree = list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(s -> s.length() > 3)
                    .toList();
                System.out.println(stringsLongerThanThree);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void classCastMethodRefUsesInferredNullableParameter() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              static <T extends @Nullable Object> String applyTo(T value, Function<T, String> f) {
                return f.apply(value);
              }

              void test(Object nonNull, @Nullable Object nullable) {
                applyTo(nonNull, String.class::cast);
                // The inferred T permits null, but the function promises a non-null String.
                // BUG: Diagnostic contains: referenced method returns @Nullable, but functional interface method java.util.function.Function.apply(@Nullable Object) returns @NonNull
                applyTo(nullable, String.class::cast);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void unboundGetOrDefaultMethodRefInfersNonNullReturn() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              interface Lookup<R extends @Nullable Object> {
                R apply(Map<String, String> map, @Nullable Object key, String defaultValue);
              }

              static <R extends @Nullable Object> R lookup(
                  Lookup<R> f, Map<String, String> map, @Nullable Object key, String defaultValue) {
                return f.apply(map, key, defaultValue);
              }

              String test(Map<String, String> map, @Nullable Object key) {
                // Both mapped values and the default are non-null; the key may be null.
                return lookup(Map::getOrDefault, map, key, "fallback");
              }
            }
            """)
        .doTest();
  }

  @Test
  public void boundGetOrDefaultMethodRefReturnNullness() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.HashMap;
            import java.util.Map;
            import java.util.function.BiFunction;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              static class Values<V extends @Nullable Object, K> extends HashMap<K, V> {}
              static <T> T identity(T value) { return value; }

              BiFunction<String, String, String> nonNull(Map<String, String> map) {
                return map::getOrDefault;
              }
              BiFunction<String, String, String> inherited(Values<String, String> map) {
                return map::getOrDefault;
              }
              BiFunction<String, String, String> inferredReceiver(Map<String, String> map) {
                return identity(map)::getOrDefault;
              }
              BiFunction<String, String, String> nullableValues(Map<String, @Nullable String> map) {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                return map::getOrDefault;
              }
              BiFunction<String, String, String> inheritedNullableValues(
                  Values<@Nullable String, String> map) {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                return map::getOrDefault;
              }
              BiFunction<String, String, String> inferredNullableReceiver(
                  Map<String, @Nullable String> map) {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                return identity(map)::getOrDefault;
              }
              BiFunction<String, @Nullable String, String> nullableDefault(Map<String, String> map) {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                return map::getOrDefault;
              }
              BiFunction<String, String, @Nullable String> nullableResult(
                  Map<String, @Nullable String> map) {
                return map::getOrDefault;
              }
              BiFunction<String, @Nullable String, @Nullable String> nullableDefaultAndResult(
                  Map<String, String> map) {
                return map::getOrDefault;
              }
              BiFunction<String, String, Object> wildcardValues(Map<String, ? super String> map) {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                return map::getOrDefault;
              }
              BiFunction<String, String, Object> rawMap(Map map) {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                return map::getOrDefault;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void unboundGetOrDefaultMethodRefReturnNullness() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              interface Lookup<V extends @Nullable Object, D extends @Nullable Object,
                  R extends @Nullable Object> {
                R apply(Map<String, V> map, String key, D defaultValue);
              }
              Lookup<String, String, String> nonNull() {
                return Map::getOrDefault;
              }
              Lookup<@Nullable String, String, String> nullableValues() {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                return Map::getOrDefault;
              }
              Lookup<String, @Nullable String, String> nullableDefault() {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                return Map::getOrDefault;
              }
              Lookup<@Nullable String, String, @Nullable String> nullableResult() {
                return Map::getOrDefault;
              }
              Lookup<String, @Nullable String, @Nullable String> nullableDefaultAndResult() {
                return Map::getOrDefault;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void getOrDefaultMethodRefsWithNullableDeclarationDefault() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import javax.annotation.Nullable;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              interface BoundLookup { String apply(String key, @Nullable String defaultValue); }
              interface UnboundLookup {
                String apply(Map<String, String> map, String key, @Nullable String defaultValue);
              }

              BoundLookup bound(Map<String, String> map) {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                return map::getOrDefault;
              }

              UnboundLookup unbound() {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                return Map::getOrDefault;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void getOrDefaultMethodRefWithTypeVariableInputs() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import java.util.function.BiFunction;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              <V extends String> BiFunction<String, V, String> nonNullBound(Map<String, V> map) {
                return map::getOrDefault;
              }
              <V extends @Nullable String> BiFunction<String, @NonNull V, String> nonNullUses(
                  Map<String, @NonNull V> map) {
                return map::getOrDefault;
              }
              <V extends @Nullable String> BiFunction<String, @NonNull V, String> unresolvedValues(
                  Map<String, V> map) {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                return map::getOrDefault;
              }
              <V extends @Nullable String> void readUnresolvedValues(
                  Map<String, V> map, @NonNull V defaultValue) {
                // Direct reads of a bare V retain their existing non-null treatment.
                map.getOrDefault("key", defaultValue).length();
              }
              <D extends @Nullable String> BiFunction<String, D, String> unresolvedDefault(
                  Map<String, String> map) {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                return map::getOrDefault;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void getOrDefaultMethodRefsInferNullableReturns() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import java.util.function.BiFunction;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              interface Lookup<R extends @Nullable Object> {
                R apply(Map<String, @Nullable String> map, String key, String defaultValue);
              }
              static <R extends @Nullable Object> R unbound(
                  Lookup<R> f, Map<String, @Nullable String> map) {
                return f.apply(map, "key", "fallback");
              }
              static <R extends @Nullable Object> R bound(BiFunction<String, String, R> f) {
                return f.apply("key", "fallback");
              }
              void test(Map<String, @Nullable String> nullableMap, Map<String, String> nonNullMap) {
                // BUG: Diagnostic contains: dereferenced expression
                unbound(Map::getOrDefault, nullableMap).length();
                // BUG: Diagnostic contains: dereferenced expression
                bound(nullableMap::getOrDefault).length();
                bound(nonNullMap::getOrDefault).length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void objectsToStringMethodRefReturnFollowsDefaultNullness() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Objects;
            import java.util.function.BiFunction;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              BiFunction<@Nullable Object, String, String> nonNullDefault() {
                return Objects::toString;
              }

              BiFunction<@Nullable Object, @Nullable String, String> nullableDefault() {
                // Objects.toString(null, null) returns null.
                // BUG: Diagnostic contains: referenced method returns @Nullable, but functional interface method java.util.function.BiFunction.apply(@Nullable Object,@Nullable String) returns @NonNull
                return Objects::toString;
              }

              BiFunction<@Nullable Object, @Nullable String, @Nullable String> nullableResult() {
                return Objects::toString;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void classCastStreamPipelineInfersNullableElements() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.stream.Stream;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(Stream<@Nullable Object> input) {
                // Class.cast preserves a null element; no expected return type supplies R.
                input.map(String.class::cast)
                    // BUG: Diagnostic contains: dereferenced expression 's' is @Nullable
                    .filter(s -> s.length() > 3);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void classCastStreamPipelineInfersNullableWildcardElements() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.stream.Stream;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(Stream<? extends @Nullable Object> input) {
                input.map(String.class::cast)
                    // BUG: Diagnostic contains: dereferenced expression 's' is @Nullable
                    .filter(s -> s.length() > 3);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void classCastStreamPipelineInfersNonNullWildcardElements() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.stream.Stream;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              void test(Stream<? extends Object> input) {
                input.map(String.class::cast).filter(s -> s.length() > 3);
              }
            }
            """)
        .doTest();
  }

  /** Pins the existing conservative behavior for raw functional-interface targets. */
  @Test
  public void classCastMethodRefWithRawTarget() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              @SuppressWarnings("rawtypes")
              Function rawFunction() {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                return String.class::cast;
              }
            }
            """)
        .doTest();
  }

  /** Pins the existing conservative behavior for varargs functional-interface methods. */
  @Test
  public void classCastMethodRefWithVarargsFunctionalInterface() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              interface Caster { String apply(Object... values); }

              Caster varargsFunction() {
                // BUG: Diagnostic contains: referenced method returns @Nullable, but functional interface method Test.Caster.apply(Object []) returns @NonNull
                return String.class::cast;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void modeledConstructorReferenceProducesNonNullStreamElements() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.ConditionalReturns;
            import java.util.stream.Stream;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              Stream<ConditionalReturns> test(Stream<@Nullable Object> input) {
                // Constructor allocations remain non-null even with a nullable library model.
                return input.map(ConditionalReturns::new);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void closerRegisterMethodReferenceFollowsArgumentNullness() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.google.common.io.Closer;
            import java.io.Closeable;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              Function<Closeable, Closeable> nonNullInput(Closer closer) {
                return closer::register;
              }

              Function<@Nullable Closeable, Closeable> nullableInput(Closer closer) {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                return closer::register;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void optionalOrElseMethodReferenceFollowsArgumentNullness() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Optional;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              Function<String, String> nonNullInput(Optional<String> optional) {
                return optional::orElse;
              }

              Function<@Nullable String, String> nullableInput(Optional<String> optional) {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                return optional::orElse;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void classCastMethodRefWithNullableDeclarationParameter() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import javax.annotation.Nullable;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              interface Caster { String apply(@Nullable Object value); }
              interface NullableResult { @Nullable String apply(@Nullable Object value); }

              Caster invalidFunction() {
                // BUG: Diagnostic contains: referenced method returns @Nullable, but functional interface method Test.Caster.apply(Object) returns @NonNull
                return String.class::cast;
              }

              NullableResult nullableResult() {
                return String.class::cast;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void classCastMethodRefWithUnresolvedArgumentNullness() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              <T extends @Nullable Object> Function<T, String> invalidFunction() {
                // BUG: Diagnostic contains: referenced method returns @Nullable, but functional interface method java.util.function.Function.apply(T) returns @NonNull
                return String.class::cast;
              }

              <T extends @Nullable Object> Function<T, @Nullable String> nullableFunction() {
                return String.class::cast;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void classCastMethodRefWithNonNullTypeVariableArgument() {
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
              <T> Function<T, String> nonNullBound() {
                return String.class::cast;
              }

              <T extends @Nullable Object> Function<@NonNull T, String> nonNullUse() {
                return String.class::cast;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void classCastMethodRefWithNonNullArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.BiFunction;
            import java.util.function.Function;
            import java.util.stream.Stream;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              Stream<String> methodReference(Stream<Object> input) {
                return input.map(String.class::cast);
              }

              Stream<String> lambda(Stream<Object> input) {
                return input.map(value -> String.class.cast(value));
              }

              Stream<@Nullable String> nullableMethodReference(
                  Stream<@Nullable Object> input) {
                return input.map(String.class::cast);
              }

              Function<Object, String> nonNullFunction() {
                return String.class::cast;
              }

              Function<@Nullable Object, @Nullable String> nullableFunction() {
                return String.class::cast;
              }

              Function<@Nullable Object, String> invalidFunction() {
                // BUG: Diagnostic contains: referenced method returns @Nullable, but functional interface method java.util.function.Function.apply(@Nullable Object) returns @NonNull
                return String.class::cast;
              }

              BiFunction<Class<String>, Object, String> unboundNonNullFunction() {
                return Class::cast;
              }

              BiFunction<Class<String>, @Nullable Object, @Nullable String>
                  unboundNullableFunction() {
                return Class::cast;
              }

              BiFunction<Class<String>, @Nullable Object, String> invalidUnboundFunction() {
                // BUG: Diagnostic contains: referenced method returns @Nullable, but functional interface method java.util.function.BiFunction.apply(Class<String>,@Nullable Object) returns @NonNull
                return Class::cast;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void methodRefWithDifferentReturnNullability() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.function.Function;
            @NullMarked
            class Test {
              interface Foo {
                default String get() {
                  throw new RuntimeException();
                }
                default @Nullable String getNullable() {
                  return null;
                }
              }
              private <T> @Nullable T create(Function<Foo, @Nullable T> factory) {
                return factory.apply(new Foo() {});
              }
              private <T> T createNonNull(Function<Foo, T> factory) {
                return factory.apply(new Foo() {});
              }
              String testPositive() {
                // BUG: Diagnostic contains: referenced method returns @Nullable, but functional interface method
                return createNonNull(Foo::getNullable);
              }
              @Nullable String testNegative() {
                // Expecting a Function returning @Nullable and Foo::get returns @NonNull, which is safe
                return create(Foo::get);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void methodRefWithArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Callback<T extends @Nullable Object> {
                void onResult(T thing);
              }
              void receiver(Callback<@Nullable Object> value) {
              }
              void target(@Nullable Object thing) {
              }
              void targetNonNullParam(Object thing) {
              }
              public <T extends @Nullable Object> Callback<T> makeCancelable(Callback<T> callback) {
                return callback;
              }
              void testNegative() {
                receiver(makeCancelable(this::target));
              }
              void testPositive() {
                // BUG: Diagnostic contains: incompatible types: Callback<Object> cannot be converted to Callback<@Nullable Object>
                receiver(makeCancelable(this::targetNonNullParam));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferFromUnboundMethodRef() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.function.Function;
            @NullMarked
            class Test {
              interface Foo {
                default String create() {
                  return "hi";
                }
                default @Nullable String createNullable() {
                  return null;
                }
              }
              private <T extends @Nullable Object> T create(Function<Foo, T> factory) {
                return factory.apply(new Foo() {});
              }
              private <T extends @Nullable Object> T createWithNullable(Function<@Nullable Foo, T> factory) {
                return factory.apply(null);
              }
              void test() {
                String s1 = create(Foo::create);
                s1.hashCode(); // should be legal
                String s2 = create(Foo::createNullable);
                // BUG: Diagnostic contains: dereferenced expression 's2' is @Nullable
                s2.hashCode();
                // BUG: Diagnostic contains: parameter of functional interface method java.util.function.Function.apply(@Nullable Foo) is @Nullable
                String s3 = createWithNullable(Foo::create);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1431_methodRefTypesIntegratedWithGenericInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.function.Function;
            @NullMarked
            class Test {
              interface Foo {
                default String get() {
                  throw new RuntimeException();
                }
                default @Nullable String getNullable() {
                  return null;
                }
              }
              private <T extends @Nullable Object> T create(Function<Foo, T> factory) {
                return factory.apply(new Foo() {});
              }
              void test() {
                String s1 = create(Foo::get);
                s1.hashCode(); // should be legal
                String s2 = create(Foo::getNullable);
                // BUG: Diagnostic contains: dereferenced expression 's2' is @Nullable
                s2.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1431_methodRefTypesIntegratedWithGenericInference_multipleArgs() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.function.Function;
            @NullMarked
            class Test {
              interface Foo {
                default String get() {
                  throw new RuntimeException();
                }
                default @Nullable String getNullable() {
                  return null;
                }
              }
              private <T extends @Nullable Object> T createBoth(
                  Function<Foo, T> firstFactory, Function<Foo, T> secondFactory) {
                return firstFactory.apply(new Foo() {});
              }
              void test() {
                String s = createBoth(Foo::get, Foo::getNullable);
                // BUG: Diagnostic contains: dereferenced expression 's' is @Nullable
                s.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void methodRefsAndGenericInferenceNested() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.function.Function;
            @NullMarked
            class Test {
              class Foo<T extends @Nullable Object> {}
              interface Consumer<U extends @Nullable Object> {
                void accept(U u);
              }
              static <T extends @Nullable Object> void takeMultiple(
                  Consumer<T> c1, Consumer<T> c2, T t) {
              }
              static void takeNonNull(String s) {}
              static void takeNullable(@Nullable String s) {}
              void testNegative1(String c) {
                takeMultiple(Test::takeNonNull, Test::takeNullable, c);
              }
              void testPositive1(@Nullable String c) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'c'
                takeMultiple(Test::takeNonNull, Test::takeNonNull, c);
              }
              static void takeNonNullNested(Foo<String> f) {}
              static void takeNullableNested(Foo<@Nullable String> f) {}
              void testNegative2(Foo<String> f1, Foo<@Nullable String> f2) {
                takeMultiple(Test::takeNonNullNested, Test::takeNonNullNested, f1);
                takeMultiple(Test::takeNullableNested, Test::takeNullableNested, f2);
              }
              void testPositive2(Foo<String> f1) {
                // BUG: Diagnostic contains: parameter type of referenced method is Test.Foo<@Nullable String>, but parameter in functional interface method has type Test.Foo<String>, which has mismatched type parameter nullability
                takeMultiple(Test::takeNonNullNested, Test::takeNullableNested, f1);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void methodRefsAndGenericInferenceNestedReturn() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              class Foo<T extends @Nullable Object> {}
              interface Supplier<U extends @Nullable Object> {
                U get();
              }
              static <T extends @Nullable Object> T pick(
                  Supplier<T> s1, Supplier<T> s2) {
                return s1.get();
              }
              static Foo<String> makeNonNullNested() {
                throw new RuntimeException();
              }
              static Foo<@Nullable String> makeNullableNested() {
                throw new RuntimeException();
              }
              void testPositive(Foo<String> f1) {
                // BUG: Diagnostic contains: referenced method returns Test.Foo<@Nullable String>, but functional interface method returns Test.Foo<String>, which has mismatched type parameter nullability
                Foo<String> f2 = pick(Test::makeNonNullNested, Test::makeNullableNested);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferFromBoundMethodRefReturn() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Supplier<T extends @Nullable Object> {
                T get();
              }
              class Foo<U extends @Nullable Object> {
                U make() {
                  throw new RuntimeException();
                }
              }
              private <V extends @Nullable Object> V create(Supplier<V> factory) {
                return factory.get();
              }
              private <V extends @Nullable Object> V createMulti(Supplier<V> factory1, Supplier<V> factory2) {
                return factory1.get();
              }
              void testCreate(Foo<String> foo, Foo<@Nullable String> fooNullable) {
                String s1 = create(foo::make);
                s1.hashCode(); // should be legal
                String s2 = create(fooNullable::make);
                // BUG: Diagnostic contains: dereferenced expression 's2' is @Nullable
                s2.hashCode();
                // this is ok; we can infer V -> @Nullable String; foo::make returns a @NonNull String,
                // which is compatible
                String s3 = createMulti(foo::make, fooNullable::make);
                // BUG: Diagnostic contains: dereferenced expression 's3' is @Nullable
                s3.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferFromBoundMethodRefParam() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Consumer<T extends @Nullable Object> {
                void accept(T thing);
              }
              class Foo<U extends @Nullable Object> {
                void take(U thing) {
                }
              }
              private <V extends @Nullable Object> void consume(Consumer<V> consumer, V value) {
                consumer.accept(value);
              }
              private <V extends @Nullable Object> void consumeMulti(Consumer<V> consumer1, Consumer<V> consumer2, V value) {
                consumer1.accept(value);
                consumer2.accept(value);
              }
              void testConsume(Foo<String> foo, Foo<@Nullable String> fooNullable, String sNonNull, @Nullable String sNullable) {
                consume(foo::take, sNonNull); // should be legal
                // BUG: Diagnostic contains: passing @Nullable parameter 'sNullable' where @NonNull is required
                consume(foo::take, sNullable);
                consume(fooNullable::take, sNullable); // should be legal
                consume(fooNullable::take, sNonNull); // should be legal
                // this is ok; we can infer V -> @NonNull String; foo::take takes a @Nullable String,
                // which is compatible
                consumeMulti(foo::take, fooNullable::take, sNonNull);
                // BUG: Diagnostic contains: passing @Nullable parameter 'sNullable' where @NonNull is required
                consumeMulti(foo::take, fooNullable::take, sNullable);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferFromStaticMethodRefParam() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Consumer<T extends @Nullable Object> {
                void accept(T thing);
              }
              static class Util {
                static void take(String thing) {
                }
                static <U extends @Nullable Object> void takeGeneric(U thing) {
                }
              }
              static class Box<T extends @Nullable Object> {
                static <U extends @Nullable Object> void takeGeneric(U thing) {
                }
              }
              private <V extends @Nullable Object> void consume(Consumer<V> consumer, V value) {
                consumer.accept(value);
              }
              void testConsume(String sNonNull, @Nullable String sNullable) {
                consume(Util::take, sNonNull); // should be legal
                // BUG: Diagnostic contains: passing @Nullable parameter 'sNullable' where @NonNull is required
                consume(Util::take, sNullable);
                consume(Util::takeGeneric, sNonNull); // should be legal
                consume(Util::takeGeneric, sNullable); // should also be legal
                consume(Box::<String>takeGeneric, sNonNull); // should be legal
                // BUG: Diagnostic contains: passing @Nullable parameter 'sNullable' where @NonNull is required
                consume(Box::<String>takeGeneric, sNullable);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void refToMethodTakingArray() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Consumer<T extends @Nullable Object> {
                void accept(T thing);
              }
              static class Util {
                static void take(String[] thing) {
                }
                static void takeNullable(String @Nullable [] thing) {
                }
                static void takeNullableContents(@Nullable String [] thing) {
                }
              }
              private <V extends @Nullable Object> void consume(Consumer<V> consumer, V value) {
                consumer.accept(value);
              }
              void testConsume(String[] sNonNull, String @Nullable [] sNullable, @Nullable String [] sNullableContents) {
                consume(Util::take, sNonNull); // should be legal
                // BUG: Diagnostic contains: passing @Nullable parameter 'sNullable' where @NonNull is required
                consume(Util::take, sNullable);
                // BUG: Diagnostic contains: parameter type of referenced method is String [], but parameter in functional interface method has type @Nullable String [], which has mismatched type parameter nullability
                consume(Util::take, sNullableContents);
                consume(Util::takeNullable, sNonNull); // legal due to covariant array subtyping
                consume(Util::takeNullable, sNullable); // should be legal, since the array itself is non-null
                // BUG: Diagnostic contains: parameter type of referenced method is String @Nullable [], but parameter in functional interface method has type @Nullable String [], which has mismatched type parameter nullability
                consume(Util::takeNullable, sNullableContents);
                consume(Util::takeNullableContents, sNonNull); // legal due to array subtyping
                // BUG: Diagnostic contains: passing @Nullable parameter 'sNullable' where @NonNull is required
                consume(Util::takeNullableContents, sNullable);
                consume(Util::takeNullableContents, sNullableContents); // should be legal
              }
            }
            """)
        .doTest();
  }

  @Test
  public void refToVarargsPassingArray() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Consumer<T extends @Nullable Object> {
                void accept(T thing);
              }
              static class Util {
                static void take(String... thing) {
                }
                static void takeNullable(String @Nullable... thing) {
                }
                static void takeNullableArgs(@Nullable String... thing) {
                }
              }
              private <V extends @Nullable Object> void consume(Consumer<V> consumer, V value) {
                consumer.accept(value);
              }
              void testConsume(String[] sNonNull, String @Nullable [] sNullable, @Nullable String [] sNullableContents) {
                consume(Util::take, sNonNull); // should be legal
                // BUG: Diagnostic contains: passing @Nullable parameter 'sNullable' where @NonNull is required
                consume(Util::take, sNullable);
                // BUG: Diagnostic contains: parameter type of referenced method is String [], but parameter in functional interface method has type @Nullable String [], which has mismatched type parameter nullability
                consume(Util::take, sNullableContents);
                consume(Util::takeNullable, sNonNull); // legal due to covariant array subtyping
                consume(Util::takeNullable, sNullable); // should be legal, since the array itself is non-null
                // BUG: Diagnostic contains: parameter type of referenced method is String @Nullable [], but parameter in functional interface method has type @Nullable String [], which has mismatched type parameter nullability
                consume(Util::takeNullable, sNullableContents);
                consume(Util::takeNullableArgs, sNonNull); // legal due to array subtyping
                // BUG: Diagnostic contains: passing @Nullable parameter 'sNullable' where @NonNull is required
                consume(Util::takeNullableArgs, sNullable);
                consume(Util::takeNullableArgs, sNullableContents); // should be legal
              }
            }
            """)
        .doTest();
  }

  @Test
  public void refToVarargsPassIndividualArgs() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Consumer<T extends @Nullable Object> {
                void accept(T thing);
              }
              interface TwoConsumer<T extends @Nullable Object, U extends @Nullable Object> {
                void accept(T thing1, U thing2);
              }
              static class Util {
                static void take(String... thing) {
                }
                static void takeNullableArgs(@Nullable String... thing) {
                }
                static void takeIntAndStrings(Integer i, String... other) {
                }
                static void takeIntAndNullableStrings(Integer i, @Nullable String... other) {
                }
              }
              private <V extends @Nullable Object> void consume(Consumer<V> consumer, V value) {
                consumer.accept(value);
              }
              void testConsume(String sNonNull, @Nullable String sNullable) {
                consume(Util::take, sNonNull); // should be legal
                // BUG: Diagnostic contains: passing @Nullable parameter 'sNullable' where @NonNull is required
                consume(Util::take, sNullable);
                consume(Util::takeNullableArgs, sNonNull);
                consume(Util::takeNullableArgs, sNullable);
              }
              private <V extends @Nullable Object, W extends @Nullable Object> void twoConsume(TwoConsumer<V,W> consumer, V value1, W value2) {
                consumer.accept(value1, value2);
              }
              void testTwoConsume(String sNonNull, @Nullable String sNullable, Integer i) {
                twoConsume(Util::takeIntAndStrings, i, sNonNull); // should be legal
                // BUG: Diagnostic contains: passing @Nullable parameter 'sNullable' where @NonNull is required
                twoConsume(Util::takeIntAndStrings, i, sNullable);
                twoConsume(Util::takeIntAndNullableStrings, i, sNonNull);
                twoConsume(Util::takeIntAndNullableStrings, i, sNullable);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void unboundVarargsMethodRefWithNoVarargsArguments() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.BiFunction;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              static class Receiver {
                String join(String prefix, String... values) { return prefix; }
              }

              static <R extends @Nullable Object> R invoke(
                  BiFunction<Receiver, String, R> f, Receiver receiver) {
                return f.apply(receiver, "prefix");
              }

              String test(Receiver receiver) {
                // The first function parameter is the receiver, leaving zero varargs arguments.
                return invoke(Receiver::join, receiver);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void unboundVarargsMethodRefWithArrayArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              static class Receiver {
                String join(String prefix, String... values) { return prefix; }
              }
              interface Joiner<V extends @Nullable Object, R extends @Nullable Object> {
                R apply(Receiver receiver, String prefix, V values);
              }

              static <V extends @Nullable Object, R extends @Nullable Object> R invoke(
                  Joiner<V, R> f, Receiver receiver, V values) {
                return f.apply(receiver, "prefix", values);
              }

              String test(Receiver receiver, String[] values) {
                // Fixed-arity adaptation passes the third function parameter as the varargs array.
                return invoke(Receiver::join, receiver, values);
              }

              String nullableArray(Receiver receiver, String @Nullable [] values) {
                // The referenced method declares a non-null varargs array parameter.
                // BUG: Diagnostic contains: passing @Nullable parameter 'values' where @NonNull is required
                return invoke(Receiver::join, receiver, values);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void varargsDifferentFunctionalInterfaceArities() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Supplier<T extends @Nullable Object> {
                T get();
              }
              interface Function<T extends @Nullable Object, R extends @Nullable Object> {
                R apply(T thing);
              }
              interface BiFunction<T extends @Nullable Object, U extends @Nullable Object, R extends @Nullable Object> {
                R apply(T t, U u);
              }
              static class Util {
                static String fun(String... thing) {
                  throw new RuntimeException();
                }
              }
              private static <U extends @Nullable Object> U takeSupplier(Supplier<U> consumer) {
                throw new RuntimeException();
              }
              private static <U extends @Nullable Object, V extends @Nullable Object> V takeFunction(Function<U,V> function) {
                throw new RuntimeException();
              }
              private static <U extends @Nullable Object, V extends @Nullable Object, W extends @Nullable Object> W takeBiFunction(BiFunction<U,V,W> function) {
                throw new RuntimeException();
              }
              void test() {
                String s1 = takeSupplier(Util::fun);
                String s2 = Test.<String,String>takeFunction(Util::fun);
                String s3 = Test.<String,String,String>takeBiFunction(Util::fun);
              }
            }
            """)
        .doTest();
  }

  @Ignore("we need to handle interactions between inference and unmarked code; TODO open issue")
  @Test
  public void inferFromMethodRefToUnmarked() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              interface Consumer<T extends @Nullable Object> {
                void accept(T thing);
              }
              @NullUnmarked
              static class Util {
                static void take(String thing) {
                }
                static void takeRestrictive(@NonNull String string) {}
              }
              private <V extends @Nullable Object> void consume(Consumer<V> consumer, V value) {
                consumer.accept(value);
              }
              void testConsume(String sNonNull, @Nullable String sNullable) {
                // should be legal
                consume(Util::take, sNonNull);
                // should also be legal, since we treat unmarked code as potentially accepting @Nullable
                consume(Util::take, sNullable);
                // should not be legal due to restrictive annotation
                // BUG: Diagnostic contains: passing @Nullable parameter 'sNullable' where @NonNull is required
                consume(Util::takeRestrictive, sNullable);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void methodRefsToOneGenericMethodInOneCallAreInferredSeparately() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T id(T value) {
                return value;
              }
              static <T extends @Nullable Object, R extends @Nullable Object> R apply(
                  Function<T, R> function, T value) {
                return function.apply(value);
              }
              static <T extends @Nullable Object, U extends @Nullable Object> T first(
                  T first, U second) {
                return first;
              }
              void test(String s, @Nullable String n) {
                first(apply(Test::id, s), apply(Test::id, n)).length();
                // BUG: Diagnostic contains: dereferenced expression
                first(apply(Test::id, n), apply(Test::id, s)).length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void recursiveMethodReferenceKeepsCallerTypeVariableFixed() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.function.BiFunction;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> Object recursive(T value, Object other) {
                class Local {
                  <U extends @Nullable Object> void accept(
                      BiFunction<@Nullable String, T, Object> f, U ignored) {}
                }
                new Local().accept(Test::recursive, value);
                return other;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferFromGenericInstanceMethodRef() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Consumer<T extends @Nullable Object> {
                void accept(T thing);
              }
              static class Box<T extends @Nullable Object> {
                <U extends @Nullable Object> void takeGeneric(U thing) {}
              }
              private static <V extends @Nullable Object> void consume(Consumer<V> consumer, V value) {
                consumer.accept(value);
              }
              void testConsume(Box<Integer> box, String sNonNull, @Nullable String sNullable) {
                consume(box::takeGeneric, sNonNull); // should be legal
                consume(box::takeGeneric, sNullable); // should be legal also
              }
              void testConsumeExplicitTypeArgs(Box<Integer> box, String sNonNull, @Nullable String sNullable) {
                Test.<String>consume(box::takeGeneric, sNonNull); // should be legal
                // BUG: Diagnostic contains: passing @Nullable parameter 'sNullable' where @NonNull is required
                Test.<String>consume(box::takeGeneric, sNullable);
                // BUG: Diagnostic contains: parameter thing of referenced method is @NonNull, but parameter in functional interface method
                Test.<@Nullable String>consume(box::<String>takeGeneric, sNullable);
              }
            }
            """)
        .doTest();
  }

  /**
   * Testing that we allow a correct usage of streams on Map entries; a similar case arose during
   * integration testing
   */
  @Test
  public void mapStream() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.Map;
            import java.util.stream.Collectors;
            @NullMarked
            class Test {
              static Map<String,String> test(Map<String,String> map) {
                return map.entrySet().stream()
                    .collect(
                        Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1795MapEntryKeyMethodReference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.HashMap;
            import java.util.Map;
            import java.util.Map.Entry;
            import org.jspecify.annotations.Nullable;
            class Test {
              static void test() {
                Map<String, @Nullable String> map = new HashMap<>();
                map.put("foo", "bar");
                map.entrySet().stream()
                    .map(entry -> entry.getKey())
                    .forEach(System.out::println);
                map.entrySet().stream()
                    .map(Entry::getKey)
                    .forEach(System.out::println);
                map.entrySet().stream()
                    .map(Map.Entry::getKey)
                    .forEach(System.out::println);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void unboundGenericMethodReferenceQualifierTypeArguments() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {
                T get() {
                  throw new UnsupportedOperationException();
                }
                Wrapper<T> getWrapped() {
                  throw new UnsupportedOperationException();
                }
              }
              static class Wrapper<T extends @Nullable Object> {}
              static void acceptNonNull(Function<Box<String>, String> function) {}
              static void acceptNullable(
                  Function<Box<@Nullable String>, @Nullable String> function) {}
              static void acceptNullableReceiverWithMismatchedNestedReturn(
                  Function<Box<@Nullable String>, Wrapper<String>> function) {}
              static void test() {
                acceptNonNull(Box::get);
                acceptNullable(Box::get);
                // BUG: Diagnostic contains: referenced method returns Wrapper<@Nullable String>
                acceptNullableReceiverWithMismatchedNestedReturn(Box::getWrapped);
                // BUG: Diagnostic contains: mismatched type parameter nullability
                acceptNullable(Box<String>::get);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void unboundGenericMethodReferenceParameterTypes() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.BiConsumer;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {
                void put(T t) {}
              }
              static BiConsumer<Box<@Nullable String>, @Nullable String> f() {
                return Box::put;
              }
              static BiConsumer<Box<String>, @Nullable String> incompatible() {
                // BUG: Diagnostic contains: parameter t of referenced method is @NonNull
                return Box::put;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void annotatedUnboundMethodRefQualifier() {
    // A qualified name crashes javac while it parses on JDK 26 through 28; see JDK-8391567.
    int feature = Runtime.version().feature();
    String qualifiedNameInvocation =
        feature < 26 || feature >= 29 ? "takeNullable(@A com.uber.Test.Box::list);" : "";
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            import java.util.List;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              @Target(ElementType.TYPE_USE)
              @interface A {}

              static class Box<T extends @Nullable Object> {
                List<T> list() {
                  throw new RuntimeException();
                }
              }

              static void takeNullable(Function<Box<@Nullable String>, List<@Nullable String>> f) {}

              static void takeNonNull(Function<Box<String>, List<String>> f) {}

              static void test() {
                takeNullable(@A Box::list);
                %s
                // BUG: Diagnostic contains: referenced method returns List<@Nullable String>
                takeNonNull(@A Box<@Nullable String>::list);
              }
            }
            """
                .formatted(qualifiedNameInvocation))
        .doTest();
  }

  @Test
  public void issue1528() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.Map;
            import java.util.function.Function;
            import java.util.stream.Stream;
            @NullMarked
            class Test {
              <T> Test and(Stream<T> stream, Function<T, @Nullable Pair> mapper) {
                return this;
              }
              static class Pair {
                private final String name;
                private final @Nullable String value;
                private Pair(String name, @Nullable String value) {
                  this.name = name;
                  this.value = value;
                }
                static Pair fromMapEntry(Map.Entry<String, @Nullable String> entry) {
                  return new Pair(entry.getKey(), entry.getValue());
                }
                static Pair fromMapEntryWildcard(Map.Entry<String, ? extends @Nullable String> entry) {
                  return new Pair(entry.getKey(), entry.getValue());
                }
              }
              Test and(Map<String, String> map) {
                // BUG: Diagnostic contains: parameter type of referenced method is Entry<String, @Nullable String>
                return and(map.entrySet().stream(), Pair::fromMapEntry);
              }
              Test andWildcard(Map<String, String> map) {
                return and(map.entrySet().stream(), Pair::fromMapEntryWildcard);
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
                }
            }""")
        .doTest();
  }

  @Test
  public void streamMapNullableTest() {
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
                static void test(List<String> list) {
                    list.stream().map(Test::mapToNull).forEach(s -> {
                        // BUG: Diagnostic contains: dereferenced expression 's' is @Nullable
                        s.hashCode();
                    });
                }
            }""")
        .doTest();
  }

  @Test
  public void genericMethodMethodRefWildCard() {
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
                static @Nullable String m(Integer i) { throw new RuntimeException(); }
                static void test() {
                    String x = invokeWithReturn(Test::m);
                }
            }""")
        .doTest();
  }

  /**
   * This is primarily a test to ensure we avoid the crash from
   * https://github.com/uber/NullAway/issues/1680. It may not be a minimal reproducer for the issue.
   */
  @Test
  public void issue1680NestedLambdaInGenericMethod() {
    makeHelper()
        .addSourceLines(
            "repro/package-info.java",
            """
            @NullMarked
            package repro;

            import org.jspecify.annotations.NullMarked;
            """)
        // This otherwise unused compilation unit is required to reproduce the crash. See
        // https://github.com/uber/NullAway/issues/1680.
        .addSourceLines(
            "repro/Aaa.java",
            """
            package repro;

            class Aaa {}
            """)
        .addSourceLines(
            "repro/Bbb.java",
            """
            package repro;

            import java.util.function.Consumer;
            import java.util.function.Function;

            class Bbb {
              record Attr(String name, String value) {}

              record Tag(String code) {}

              interface Box<T> {}

              interface Executable {
                void execute() throws Throwable;
              }

              static class MapperException extends Exception {}

              static class Ctx {
                abstract static class Builder<C extends Ctx, B extends Builder<C, B>> {
                  abstract B self();

                  B attribute(Attr a) {
                    return self();
                  }
                }
              }

              static class DomainException extends RuntimeException {
                static DomainException notValid(
                    Consumer<Ctx.Builder<?, ? extends Ctx.Builder<?, ?>>> contextCustomizer,
                    Throwable throwable) {
                  throw new AssertionError();
                }
              }

              static class Factory {
                <T> Box<T> create(
                    String name,
                    String rawValue,
                    Tag tag,
                    Function<MapperException, DomainException> exceptionMapper) {
                  throw new AssertionError();
                }
              }

              private static final Tag TAG = new Tag("t");
              private final Factory factory = new Factory();

              void trigger() {
                var name = "n";
                var value = "v";
                var exception =
                    assertThrows(
                        DomainException.class,
                        () -> factory.create(name, value, TAG, e -> map(e, name, value)));
                // BUG: Diagnostic contains: passing @Nullable parameter 'exception.getCause()'
                assertInstanceOf(MapperException.class, exception.getCause());
              }

              private static <T extends Throwable> T assertThrows(
                  Class<T> expectedType, Executable executable) {
                throw new AssertionError();
              }

              private static <T> T assertInstanceOf(Class<T> expectedType, Object actualValue) {
                throw new AssertionError();
              }

              private static DomainException map(MapperException e, String name, String value) {
                return DomainException.notValid(
                    contextCustomizer -> contextCustomizer.attribute(new Attr(name, value)), e);
              }
            }
            """)
        .doTest();
  }

  /** Reduced from a crash observed when building JUnit on JDK 27; testing that we do not crash */
  @Test
  public void crasherOnJdk27() {
    makeHelper()
        .addSourceLines(
            "Repro.java",
            """
            package repro;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Repro {
                void f(C c) {
                    E e = c.e();
                    g((B b, B.I<@Nullable Void> i) -> {
                        b.f(i, e);
                        return null;
                    });
                }
                static <T extends @Nullable Object> void g(A<T> a) {
                }
                interface A<T extends @Nullable Object> {
                    T f(B b, B.I<T> i);
                }
                interface B {
                    void f(I<@Nullable Void> i, E e);
                    interface I<T extends @Nullable Object> {
                    }
                }
                interface C {
                    E e();
                }
                interface E {
                }
            }""")
        .doTest();
  }

  @Test
  public void lambdaReturnUsesNullableInvocationTargetType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              static void accept(Function<String, @Nullable String> mapper) {}

              void expressionBody() {
                accept(unused -> null);
              }

              void blockBody() {
                accept(unused -> {
                  return null;
                });
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericMethodCallQualifierForMethodReference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.Consumer;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              interface Flow {
                Flow handle(Consumer<Object> consumer);
              }

              static <T> Consumer<T> id(Consumer<T> consumer) {
                return consumer;
              }

              static <T> @Nullable Consumer<T> nullableId(Consumer<T> consumer) {
                return null;
              }

              static Flow methodReferenceArgument(Flow flow, Consumer<Object> consumer) {
                return flow.handle(id(consumer)::accept);
              }

              static Flow parenthesizedMethodReferenceArgument(
                  Flow flow, Consumer<Object> consumer) {
                return flow.handle((id(consumer)::accept));
              }

              static Flow nullableQualifier(Flow flow, Consumer<Object> consumer) {
                // BUG: Diagnostic contains: dereferenced expression 'nullableId(consumer)' is @Nullable
                return flow.handle(nullableId(consumer)::accept);
              }
            }
            """)
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList("-XepOpt:NullAway:AnnotatedPackages=com.uber")));
  }
}
