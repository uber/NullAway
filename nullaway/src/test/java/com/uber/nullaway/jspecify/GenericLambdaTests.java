package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import org.junit.Test;

public class GenericLambdaTests extends NullAwayTestsBase {

  @Test
  public void explicitLambdaParameterAnnotationsControlBodyNullness() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            class Test {
              interface Consumer<T extends @Nullable Object> { void accept(T value); }

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

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList("-XepOpt:NullAway:AnnotatedPackages=com.uber")));
  }
}
