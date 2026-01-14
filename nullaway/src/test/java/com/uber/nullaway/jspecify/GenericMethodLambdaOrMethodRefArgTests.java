package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import org.junit.Test;

public class GenericMethodLambdaOrMethodRefArgTests extends NullAwayTestsBase {

  @Test
  public void lambdaReturnsGenericMethodCall() {
    makeHelperWithInferenceFailureWarning()
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
                    // BUG: Diagnostic contains: dereferenced expression y is @Nullable
                    y.hashCode();
                    // Block-bodied with parenthesized return
                    Object x_block_paren = invokeWithReturn(() -> { return (genericMethod("value"));});
                    Object y_block_paren = invokeWithReturn(() -> { return (genericMethod(null));});
                    // legal, should infer x_block_paren is a @NonNull String
                    x_block_paren.hashCode();
                    // BUG: Diagnostic contains: dereferenced expression y_block_paren is @Nullable
                    y_block_paren.hashCode();
                    // Expression-bodied
                    Object x_expr = invokeWithReturn(() -> genericMethod("value"));
                    Object y_expr = invokeWithReturn(() -> genericMethod(null));
                    // legal, should infer x_expr is a @NonNull String
                    x_expr.hashCode();
                    // BUG: Diagnostic contains: dereferenced expression y_expr is @Nullable
                    y_expr.hashCode();
                    // Expression-bodied with parenthesized return
                    Object x_expr_paren = invokeWithReturn(() -> (genericMethod("value")));
                    Object y_expr_paren = invokeWithReturn(() -> (genericMethod(null)));
                    // legal, should infer x_expr_paren is a @NonNull String
                    x_expr_paren.hashCode();
                    // BUG: Diagnostic contains: dereferenced expression y_expr_paren is @Nullable
                    y_expr_paren.hashCode();
                    Object x2 = invokeWithReturn(() ->{ Object y2 = null; return y2;});
                    // BUG: Diagnostic contains: dereferenced expression x2 is @Nullable
                    x2.hashCode();
                }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1294_lambdaArguments() {
    makeHelperWithInferenceFailureWarning()
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
    makeHelperWithInferenceFailureWarning()
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
                    // BUG: Diagnostic contains: dereferenced expression x is @Nullable
                    x.hashCode();
                }
            }
            """)
        .doTest();
  }

  @Test
  public void lambdaConditionalExprBody() {
    makeHelperWithInferenceFailureWarning()
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
                // BUG: Diagnostic contains: dereferenced expression t is @Nullable
                t.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void lambdaWithReturnStmtNullable() {
    makeHelperWithInferenceFailureWarning()
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
                // BUG: Diagnostic contains: dereferenced expression t is @Nullable
                t.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void lambdaReturnEnclosingLocal() {
    makeHelperWithInferenceFailureWarning()
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
                // BUG: Diagnostic contains: dereferenced expression t is @Nullable
                t.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void lambdaAccessingNonFinalField() {
    makeHelperWithInferenceFailureWarning()
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
                // BUG: Diagnostic contains: dereferenced expression t is @Nullable
                t.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nestedLambdaFromSpring() {
    makeHelperWithInferenceFailureWarning()
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
    makeHelperWithInferenceFailureWarning()
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
    makeHelperWithInferenceFailureWarning()
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
  public void methodRefWithDifferentReturnNullability() {
    makeHelperWithInferenceFailureWarning()
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
    makeHelperWithInferenceFailureWarning()
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
  public void inferFromMethodRef() {
    makeHelperWithInferenceFailureWarning()
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
                // BUG: Diagnostic contains: dereferenced expression s2 is @Nullable
                s2.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferFromUnboundMethodRefWithParams() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.function.BiFunction;
            @NullMarked
            class Test {
              interface Foo {
                default String take(String s) {
                  return s;
                }
                default @Nullable String takeNullable(@Nullable String s) {
                  return null;
                }
              }
              private <T extends @Nullable Object> T create(BiFunction<Foo, String, T> factory) {
                return factory.apply(new Foo() {}, "x");
              }
              private <T extends @Nullable Object> T createWithNullable(BiFunction<@Nullable Foo, String, T> factory) {
                return factory.apply(null, "x");
              }
              void test() {
                String s1 = create(Foo::take);
                s1.hashCode(); // should be legal
                String s2 = create(Foo::takeNullable);
                // BUG: Diagnostic contains: dereferenced expression s2 is @Nullable
                s2.hashCode();
                // BUG: Diagnostic contains:
                String s3 = createWithNullable(Foo::take);
              }
            }
            """)
        .doTest();
  }

  private CompilationTestHelper makeHelperWithInferenceFailureWarning() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList(
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:WarnOnGenericInferenceFailure=true")));
  }
}
