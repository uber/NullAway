package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import org.junit.Test;

public class GenericLambdaTests extends NullAwayTestsBase {

  /**
   * Method references returned from expression or block lambdas retain nested target nullability,
   * accepting nullable list elements and rejecting methods that require non-null elements.
   */
  @Test
  public void methodReferencesReturnedFromLambdasPreserveNestedTargets() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.List;
            import java.util.function.Consumer;
            import java.util.function.Supplier;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Factory<E> { Consumer<List<@Nullable E>> create(); }
              static void nullableElements(List<@Nullable String> values) {}
              static void nonNullElements(List<String> values) {}
              static <E extends @Nullable Object> void genericNullableElements(
                  List<@Nullable E> values) {}
              static <E> void genericNonNullElements(List<E> values) {}
              Factory<String> compatibleExpression() {
                return () -> Test::nullableElements;
              }
              Factory<String> compatibleBlock() {
                return () -> { return Test::nullableElements; };
              }
              Factory<String> compatibleParenthesizedExpression() {
                return () -> ((Test::nullableElements));
              }
              Factory<String> compatibleParenthesizedBlock() {
                return () -> { return ((Test::nullableElements)); };
              }
              Factory<String> incompatibleExpression() {
                // BUG: Diagnostic contains: parameter type of referenced method
                return () -> Test::nonNullElements;
              }
              Factory<String> incompatibleBlock() {
                // BUG: Diagnostic contains: parameter type of referenced method
                return () -> { return Test::nonNullElements; };
              }
              Factory<String> compatibleGenericExpression() {
                return () -> Test::genericNullableElements;
              }
              Factory<String> compatibleGenericBlock() {
                return () -> { return Test::genericNullableElements; };
              }
              Factory<String> incompatibleGenericExpression() {
                // BUG: Diagnostic contains: inference failure
                return () -> Test::genericNonNullElements;
              }
              Factory<String> incompatibleGenericBlock() {
                // BUG: Diagnostic contains: inference failure
                return () -> { return Test::genericNonNullElements; };
              }
              Supplier<Consumer<List<@Nullable String>>> compatibleSupplierExpression() {
                return () -> Test::nullableElements;
              }
              Supplier<Consumer<List<@Nullable String>>> compatibleSupplierBlock() {
                return () -> { return Test::nullableElements; };
              }
              Supplier<Consumer<List<@Nullable String>>> incompatibleSupplierExpression() {
                // BUG: Diagnostic contains: parameter type of referenced method
                return () -> Test::nonNullElements;
              }
              Supplier<Consumer<List<@Nullable String>>> incompatibleSupplierBlock() {
                // BUG: Diagnostic contains: parameter type of referenced method
                return () -> { return Test::nonNullElements; };
              }
            }
            """)
        .doTest();
  }

  /**
   * Returned lambdas and method references preserve nullable type-variable uses in their target
   * types, including through parentheses and wildcard bounds, so unsafe dereferences and
   * incompatible parameter types are reported.
   */
  @Test
  public void returnedPolyExpressionsPreserveNullableTypeVariableTargets() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.List;
            import java.util.function.Consumer;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends CharSequence> {
              void nonNullElements(List<T> values) {}
              void nullableElements(List<@Nullable T> values) {}
              Consumer<List<T>> nonNullLambda() {
                return values -> values.get(0).length();
              }
              Consumer<List<@Nullable T>> nullableLambda() {
                // BUG: Diagnostic contains: dereferenced expression
                return values -> values.get(0).length();
              }
              Consumer<List<@Nullable T>> parenthesizedNullableLambda() {
                // BUG: Diagnostic contains: dereferenced expression
                return ((values -> values.get(0).length()));
              }
              Consumer<? super List<@Nullable T>> wildcardNullableLambda() {
                // BUG: Diagnostic contains: dereferenced expression
                return values -> values.get(0).length();
              }
              Consumer<List<@Nullable T>> compatibleExplicitLambda() {
                return (List<@Nullable T> values) -> {};
              }
              Consumer<List<@Nullable T>> incompatibleExplicitLambda() {
                // BUG: Diagnostic contains: Parameter has type
                return (List<T> values) -> {};
              }
              Function<? super @Nullable T, ? extends String> wildcardNullableParameter() {
                // BUG: Diagnostic contains: dereferenced expression
                return value -> value.toString();
              }
              Consumer<List<@Nullable T>> compatibleReference() {
                return this::nullableElements;
              }
              Consumer<List<@Nullable T>> incompatibleReference() {
                // BUG: Diagnostic contains: parameter type of referenced method
                return this::nonNullElements;
              }
              Consumer<List<@Nullable T>> parenthesizedIncompatibleReference() {
                // BUG: Diagnostic contains: parameter type of referenced method
                return ((this::nonNullElements));
              }
              Consumer<? super List<@Nullable T>> wildcardIncompatibleReference() {
                // BUG: Diagnostic contains: parameter type of referenced method
                return this::nonNullElements;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void explicitLambdaParameterAnnotationsControlBodyNullness() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.List;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            class Test {
              interface Consumer<T extends @Nullable Object> { void accept(T value); }

              Consumer<Object> instanceConsumer;
              {
                instanceConsumer = (@Nullable Object parameter) -> {
                  // BUG: Diagnostic contains: dereferenced expression
                  parameter.toString();
                };
              }
              static Consumer<Object> staticConsumer;
              static {
                staticConsumer = (@Nullable Object parameter) -> {
                  // BUG: Diagnostic contains: dereferenced expression
                  parameter.toString();
                };
              }
              Consumer<List<@Nullable String>> nestedTypeArgument() {
                return (List<@Nullable String> parameter) -> { parameter.size(); };
              }

              Consumer<@Nullable Object> nullableParameterOnNullableTarget() {
                return (@Nullable Object parameter) -> {
                  // BUG: Diagnostic contains: dereferenced expression
                  parameter.toString();
                };
              }
              Consumer<Object> nullableParameterOnNonNullTarget() {
                return (@Nullable Object parameter) -> {
                  // BUG: Diagnostic contains: dereferenced expression
                  parameter.toString();
                };
              }
              Consumer<Object> nullCheckRefinesType() {
                return (@Nullable Object parameter) -> {
                  if (parameter != null) {
                    parameter.toString();
                  }
                };
              }
              Consumer<Object> nonNullParameter() {
                return (@NonNull Object parameter) -> { parameter.toString(); };
              }
              Consumer<Object> unannotatedParameter() {
                return (Object parameter) -> { parameter.toString(); };
              }
              Consumer<@Nullable Object> implicitlyNullableParameter() {
                return parameter -> {
                  // BUG: Diagnostic contains: dereferenced expression
                  parameter.toString();
                };
              }
              Consumer<Object> declarationAnnotation() {
                return (@javax.annotation.Nullable Object parameter) -> {
                  // BUG: Diagnostic contains: dereferenced expression
                  parameter.toString();
                };
              }
              interface ArrayConsumer { void accept(String @Nullable [] value); }
              ArrayConsumer nullableArray() {
                return (String @Nullable [] parameter) -> {
                  // BUG: Diagnostic contains: dereferenced expression
                  int length = parameter.length;
                };
              }
              interface NullableElementConsumer { void accept(@Nullable String[] value); }
              NullableElementConsumer nullableElements() {
                return (@Nullable String[] parameter) -> {
                  int length = parameter.length;
                  // BUG: Diagnostic contains: dereferenced expression
                  parameter[0].toString();
                };
              }
            }
            """)
        .doTest();
  }

  @Test
  public void explicitLambdaParameterAnnotationsInInitializersStandardMode() {
    makeTestHelperWithArgs(Arrays.asList("-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.List;
            import java.util.function.Consumer;
            import org.jspecify.annotations.Nullable;
            class Test {
              Consumer<Object> instanceConsumer;
              {
                instanceConsumer = (@Nullable Object parameter) -> {
                  // BUG: Diagnostic contains: dereferenced expression
                  parameter.toString();
                };
              }
              static Consumer<Object> staticConsumer;
              static {
                staticConsumer = (@Nullable Object parameter) -> {
                  // BUG: Diagnostic contains: dereferenced expression
                  parameter.toString();
                };
              }
              Consumer<List<@Nullable String>> nestedTypeArgument() {
                return (List<@Nullable String> parameter) -> { parameter.size(); };
              }
            }
            """)
        .doTest();
  }

  @Test
  public void lambdaAssignedToLocalRespectsExtendsWildcardBoundNullness() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.function.Supplier;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              void test() {
                Supplier<? extends @Nullable Object> supplier = () -> null;
                // BUG: Diagnostic contains: returning @Nullable
                Supplier<? extends Object> nonNullSupplier = () -> null;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void lambdaArgumentUsesAnnotatedTypeVar() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.function.Function;
            @NullMarked
            class Test {
              static class Foo<T extends @Nullable CharSequence> {
                T doSomething(Function<@Nullable T, T> f) {
                  return f.apply(null);
                }
              }
              void test(Foo<String> foo) {
                foo.doSomething(
                    t -> {
                      // BUG: Diagnostic contains: dereferenced expression 't' is @Nullable
                      return "length: " + t.length();
                    });
              }
            }
            """)
        .doTest();
  }

  @Test
  public void lambdaAssignedToFieldOrLocalUsesAnnotatedTypeVar() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.function.Function;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable CharSequence> {
                @Nullable Function<@Nullable T, T> field;
                void assignField(Box<String> box) {
                  box.field = t -> {
                    // BUG: Diagnostic contains: dereferenced expression 't' is @Nullable
                    t.length();
                    return t;
                  };
                }
                void assignLocal() {
                  Function<@Nullable T, T> local =
                      t -> {
                        // BUG: Diagnostic contains: dereferenced expression 't' is @Nullable
                        t.length();
                        return t;
                      };
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void parenthesizedPolyExpressionAssignedToFieldUsesAnnotatedTypeVar() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.function.Function;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable CharSequence> {
                @Nullable Function<@Nullable T, T> field;
              }
              static String requireNonNull(String s) {
                return s;
              }
              static @Nullable String acceptNullable(@Nullable String s) {
                return s;
              }
              void assignLambda(Box<String> box) {
                box.field = (t -> {
                  // BUG: Diagnostic contains: dereferenced expression 't' is @Nullable
                  t.length();
                  return "";
                });
              }
              void assignDoublyParenthesizedLambda(Box<String> box) {
                box.field = ((t -> {
                  // BUG: Diagnostic contains: dereferenced expression 't' is @Nullable
                  t.length();
                  return "";
                }));
              }
              void assignMethodRef(Box<String> box) {
                // BUG: Diagnostic contains: parameter s of referenced method is @NonNull
                box.field = Test::requireNonNull;
              }
              void assignParenthesizedMethodRef(Box<String> box) {
                // BUG: Diagnostic contains: parameter s of referenced method is @NonNull
                box.field = (Test::requireNonNull);
              }
              void assignNullableReturningMethodRef(Box<String> box) {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                box.field = Test::acceptNullable;
              }
              void assignParenthesizedNullableReturningMethodRef(Box<String> box) {
                // BUG: Diagnostic contains: referenced method returns @Nullable
                box.field = (Test::acceptNullable);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void lambdaReturnedUsesAnnotatedTypeVar() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.function.Function;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable CharSequence> {
                Function<@Nullable T, T> make() {
                  return t -> {
                    // BUG: Diagnostic contains: dereferenced expression 't' is @Nullable
                    t.length();
                    return t;
                  };
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void explicitlyAnnotatedLambdaArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.function.Function;
            @NullMarked
            class Test {
              static class Foo<T extends @Nullable CharSequence> {
                T doSomething(Function<@Nullable T, T> f) {
                  return f.apply(null);
                }
              }
              void testPositive(Foo<String> foo) {
                foo.doSomething(
                    // BUG: Diagnostic contains: parameter t is @NonNull, but parameter in functional interface method
                    (String t) -> {
                      return "length: " + t.length();
                    });
              }
              void testNegative(Foo<String> foo) {
                foo.doSomething(
                    (@Nullable String t) -> {
                      return "hello";
                    });
              }
            }
            """)
        .doTest();
  }

  @Test
  public void explicitlyTypedLambdaParameterTypeArguments() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.List;
            import java.util.function.BiConsumer;
            import java.util.function.Consumer;
            @NullMarked
            class Test {
              interface NullableElementsFi {
                void apply(List<@Nullable String> list);
              }
              interface NonNullElementsFi {
                void apply(List<String> list);
              }
              interface RawListFi {
                @SuppressWarnings("rawtypes")
                void apply(List list);
              }
              static void take(Consumer<List<@Nullable String>> consumer) {}
              @SuppressWarnings("rawtypes")
              void testRaw() {
                Consumer rawTarget = (Object o) -> {};
                RawListFi rawParameter = (List list) -> {};
              }
              void test() {
                // BUG: Diagnostic contains: Parameter has type List<String>, but overridden method has parameter type List<@Nullable String>
                NullableElementsFi nonNullElements = (List<String> list) -> {};
                NullableElementsFi nullableElements = (List<@Nullable String> list) -> {};
                NullableElementsFi implicit = list -> {};
                // BUG: Diagnostic contains: Parameter has type List<@Nullable String>, but overridden method has parameter type List<String>
                NonNullElementsFi invariant = (List<@Nullable String> list) -> {};
                // BUG: Diagnostic contains: Parameter has type List<String>, but overridden method has parameter type List<@Nullable String>
                Consumer<List<@Nullable String>> local = (List<String> list) -> {};
                Consumer<List<@Nullable String>> localMatching = (List<@Nullable String> list) -> {};
                // BUG: Diagnostic contains: Parameter has type List<String>, but overridden method has parameter type List<@Nullable String>
                Consumer<? super List<@Nullable String>> wildcard = (List<String> list) -> {};
                Consumer<? super List<@Nullable String>> wildcardMatching = (List<@Nullable String> list) -> {};
                // BUG: Diagnostic contains: Parameter has type List<String>, but overridden method has parameter type List<@Nullable String>
                Consumer<? extends List<@Nullable String>> extendsWildcard = (List<String> list) -> {};
                Consumer<? extends List<@Nullable String>> extendsMatching = (List<@Nullable String> list) -> {};
                BiConsumer<List<String>, List<@Nullable String>> second =
                    // BUG: Diagnostic contains: Parameter has type List<String>, but overridden method has parameter type List<@Nullable String>
                    (List<String> first, List<String> list) -> {};
                BiConsumer<List<String>, List<@Nullable String>> bothMatching =
                    (List<String> first, List<@Nullable String> list) -> {};
                // BUG: Diagnostic contains: Parameter has type List<String>, but overridden method has parameter type List<@Nullable String>
                take((List<String> list) -> {});
                take((List<@Nullable String> list) -> {});
              }
            }
            """)
        .doTest();
  }

  @Test
  public void explicitlyTypedLambdaParameterTypeArgumentsInferGenericMethodTypeArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.List;
            import java.util.function.Consumer;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> void take(Consumer<List<T>> consumer) {}
              static <T> void takeNonNull(Consumer<List<T>> consumer) {}
              static <T extends @Nullable Object> void takeWith(List<T> list, Consumer<List<T>> consumer) {}
              static <T extends @Nullable Object> void takeValue(T value, Consumer<T> consumer) {}
              void test(List<@Nullable String> nullableElements, List<String> nonNullElements) {
                take((List<@Nullable String> list) -> {});
                take((List<String> list) -> {});
                // BUG: Diagnostic contains: Parameter has type List<@Nullable String>, but overridden method has parameter type List<String>
                takeNonNull((List<@Nullable String> list) -> {});
                takeWith(nullableElements, (List<@Nullable String> list) -> {});
                takeWith(nonNullElements, (List<String> list) -> {});
                // BUG: Diagnostic contains: Parameter has type List<@Nullable String>, but overridden method has parameter type List<String>
                takeWith(nonNullElements, (List<@Nullable String> list) -> {});
                // BUG: Diagnostic contains: parameter s is @NonNull, but parameter in functional interface method
                takeValue(null, (String s) -> {});
              }
              interface RawListFi {
                @SuppressWarnings("rawtypes")
                void apply(List list);
              }
              static <T> void takeRaw(T value, RawListFi consumer) {}
              @SuppressWarnings("rawtypes")
              void testRaw() {
                takeRaw("", (List list) -> {});
              }
            }
            """)
        .doTest();
  }

  /**
   * Type-argument nullness of an explicit lambda parameter is compared only against a marked
   * functional interface method, as for the parameters of an overriding method.
   */
  @Test
  public void explicitlyTypedLambdaParameterTypeArgumentsCheckedOnlyAgainstMarkedInterface() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NullUnmarked;
            import org.jspecify.annotations.Nullable;
            import java.util.List;
            @NullMarked
            class Test {
              @NullUnmarked
              interface UnmarkedFi {
                void apply(List<String> list);
              }
              interface MarkedFi {
                void apply(List<String> list);
              }
              void unmarked() {
                UnmarkedFi nullableElements = (List<@Nullable String> list) -> {};
              }
              void marked() {
                // BUG: Diagnostic contains: Parameter has type List<@Nullable String>, but overridden method has parameter type List<String>
                MarkedFi nullableElements = (List<@Nullable String> list) -> {};
              }
            }
            """)
        .doTest();
  }

  @Test
  public void explicitlyTypedLambdaParameterTypeArgumentsOutsideJSpecifyMode() {
    makeTestHelperWithArgs(Arrays.asList("-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.List;
            @NullMarked
            class Test {
              interface NullableElementsFi {
                void apply(List<@Nullable String> list);
              }
              void test() {
                NullableElementsFi nonNullElements = (List<String> list) -> {};
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1448() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package org.example;

            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
                interface Func<T> {
                    void apply( @Nullable T t );
                }

                class Bar {
                    Func<Integer> baz = ( @Nullable Integer i ) -> {};
                }
            }
            """)
        .doTest();
  }

  @Test
  public void varAndGenericInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.concurrent.CompletableFuture;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            final class Test {
                static void reproduce() {
                    var future = future();
                    run(() -> future.join());
                }
                private static CompletableFuture<?> future() {
                    return new CompletableFuture<>();
                }
                private static <T extends @Nullable Object> T run(Action<T> action) {
                    return action.run();
                }
                private interface Action<T extends @Nullable Object> {
                    T run();
                }
            }
            """)
        .doTest();
  }

  @Test
  public void varAndGenericInferenceWithNonNullUpperBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.concurrent.CompletableFuture;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NonNull;
            @NullMarked
            final class Test {
                static void reproduce() {
                    var future = future();
                    // BUG: Diagnostic contains: inference failure: type variable T is constrained to be @Nullable, but its upper bound requires it to be @NonNull
                    run(() -> future.join());
                }
                private static CompletableFuture<?> future() {
                    return new CompletableFuture<>();
                }
                private static <T> T run(Action<T> action) {
                    return action.run();
                }
                private interface Action<T> {
                    T run();
                }
            }
            """)
        .doTest();
  }

  @Test
  public void optionalOfNullableRefinesNullableFunctionResult() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.List;
            import java.util.Optional;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              public static <T> Optional<String> convert(
                  final T value, final Function<T, @Nullable List<String>> extractor) {
                return Optional.ofNullable(extractor.apply(value))
                    .map(list -> Integer.toString(list.size()));
              }
            }
            """)
        .doTest();
  }

  /**
   * Type-argument nullness of a method reference passed as an argument is compared only against a
   * marked functional interface method, as the arguments of a call to an unmarked method are not
   * checked. JDK functional interfaces are still checked.
   */
  @Test
  public void methodReferenceArgumentTypeArgumentsCheckedOnlyAgainstMarkedInterface() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NullUnmarked;
            import org.jspecify.annotations.Nullable;
            import java.util.List;
            import java.util.function.Consumer;
            import java.util.function.Supplier;
            @NullMarked
            class Test {
              @NullUnmarked
              interface UnmarkedConsumer {
                void apply(List<String> list);
              }
              @NullUnmarked
              interface UnmarkedSupplier {
                List<String> get();
              }
              @NullUnmarked
              interface UnmarkedFn<T> {
                void apply(T t);
              }
              interface MarkedConsumer {
                void apply(List<String> list);
              }
              interface MarkedSupplier {
                List<String> get();
              }
              static void takeNullableElements(List<@Nullable String> list) {}
              static List<@Nullable String> returnNullableElements() {
                throw new RuntimeException();
              }
              static void useUnmarkedConsumer(UnmarkedConsumer c) {}
              static void useUnmarkedSupplier(UnmarkedSupplier s) {}
              static void useUnmarkedFn(UnmarkedFn<List<String>> f) {}
              static void useMarkedConsumer(MarkedConsumer c) {}
              static void useMarkedSupplier(MarkedSupplier s) {}
              static void useJdkConsumer(Consumer<List<String>> c) {}
              static void useJdkSupplier(Supplier<List<String>> s) {}
              void unmarkedParameter() {
                useUnmarkedConsumer(Test::takeNullableElements);
              }
              void unmarkedReturn() {
                useUnmarkedSupplier(Test::returnNullableElements);
              }
              void unmarkedGenericParameter() {
                useUnmarkedFn(Test::takeNullableElements);
              }
              void assignUnmarkedParameter() {
                UnmarkedConsumer c = Test::takeNullableElements;
              }
              void assignUnmarkedReturn() {
                UnmarkedSupplier s = Test::returnNullableElements;
              }
              UnmarkedConsumer returnUnmarkedParameter() {
                return Test::takeNullableElements;
              }
              UnmarkedSupplier returnUnmarkedReturn() {
                return Test::returnNullableElements;
              }
              void markedParameter() {
                // BUG: Diagnostic contains: parameter type of referenced method is List<@Nullable String>, but parameter in functional interface method has type List<String>
                useMarkedConsumer(Test::takeNullableElements);
              }
              void markedReturn() {
                // BUG: Diagnostic contains: referenced method returns List<@Nullable String>, but functional interface method
                useMarkedSupplier(Test::returnNullableElements);
              }
              void jdkParameter() {
                // BUG: Diagnostic contains: parameter type of referenced method is List<@Nullable String>, but parameter in functional interface method has type List<String>
                useJdkConsumer(Test::takeNullableElements);
              }
              void jdkReturn() {
                // BUG: Diagnostic contains: referenced method returns List<@Nullable String>, but functional interface method
                useJdkSupplier(Test::returnNullableElements);
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

  @Test
  public void lambdaReturnTypeArgumentsAreCheckedOnlyAgainstMarkedInterface() {
    makeHelper()
        .addSourceLines(
            "UnmarkedListSupplier.java",
            """
            package com.example.unmarked;
            import java.util.List;
            public interface UnmarkedListSupplier {
              List<String> get();
            }
            """)
        .addSourceLines(
            "MarkedListSupplier.java",
            """
            package com.uber;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            public interface MarkedListSupplier {
              List<String> get();
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.example.unmarked.UnmarkedListSupplier;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              void expressionBodyToUnmarkedInterface(List<@Nullable String> l) {
                UnmarkedListSupplier s = () -> l;
              }
              void blockBodyToUnmarkedInterface(List<@Nullable String> l) {
                UnmarkedListSupplier s = () -> { return l; };
              }
              void expressionBodyToMarkedInterface(List<@Nullable String> l) {
                // BUG: Diagnostic contains: incompatible types: List<@Nullable String> cannot be converted to List<String>
                MarkedListSupplier s = () -> l;
              }
              void blockBodyToMarkedInterface(List<@Nullable String> l) {
                // BUG: Diagnostic contains: incompatible types: List<@Nullable String> cannot be converted to List<String>
                MarkedListSupplier s = () -> { return l; };
              }
              void matchingTypeArgumentsToMarkedInterface(List<String> l) {
                MarkedListSupplier s = () -> l;
              }
            }
            """)
        .doTest();
  }
}
