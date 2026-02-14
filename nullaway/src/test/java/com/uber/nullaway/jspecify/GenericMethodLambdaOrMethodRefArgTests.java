package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import org.junit.Ignore;
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
  public void inferFromUnboundMethodRef() {
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
                // BUG: Diagnostic contains: dereferenced expression s2 is @Nullable
                s2.hashCode();
                // BUG: Diagnostic contains: unbound instance method reference
                String s3 = createWithNullable(Foo::create);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferFromBoundMethodRefReturn() {
    makeHelperWithInferenceFailureWarning()
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
                // BUG: Diagnostic contains: dereferenced expression s2 is @Nullable
                s2.hashCode();
                // this is ok; we can infer V -> @Nullable String; foo::make returns a @NonNull String,
                // which is compatible
                String s3 = createMulti(foo::make, fooNullable::make);
                // BUG: Diagnostic contains: dereferenced expression s3 is @Nullable
                s3.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferFromBoundMethodRefParam() {
    makeHelperWithInferenceFailureWarning()
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
    makeHelperWithInferenceFailureWarning()
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
    makeHelperWithInferenceFailureWarning()
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
                // TODO should be illegal; file issue
                consume(Util::take, sNullableContents);
                consume(Util::takeNullable, sNonNull); // legal due to covariant array subtyping
                consume(Util::takeNullable, sNullable); // should be legal, since the array itself is non-null
                // TODO should be illegal; file issue
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
    makeHelperWithInferenceFailureWarning()
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
                // TODO should be illegal; file issue
                consume(Util::take, sNullableContents);
                consume(Util::takeNullable, sNonNull); // legal due to covariant array subtyping
                consume(Util::takeNullable, sNullable); // should be legal, since the array itself is non-null
                // TODO should be illegal; file issue
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
    makeHelperWithInferenceFailureWarning()
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
                static void takeNullableArgs(@Nullable String... thing) {
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
            }
            """)
        .doTest();
  }

  @Test
  public void varargsDifferentFunctionalInterfaceArities() {
    makeHelperWithInferenceFailureWarning()
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
    makeHelperWithInferenceFailureWarning()
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
  public void inferFromGenericInstanceMethodRef() {
    makeHelperWithInferenceFailureWarning()
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
    makeHelperWithInferenceFailureWarning()
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
                }
            }""")
        .doTest();
  }

  @Ignore("https://github.com/uber/NullAway/issues/1462")
  @Test
  public void streamMapNullableTest() {
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
                static void test(List<String> list) {
                    list.stream().map(Test::mapToNull).forEach(s -> {
                        // BUG: Diagnostic contains: dereferenced expression s is @Nullable
                        s.hashCode();
                    });
                }
            }""")
        .doTest();
  }

  @Test
  public void genericMethodMethodRefWildCard() {
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
                static @Nullable String m(Integer i) { throw new RuntimeException(); }
                static void test() {
                    String x = invokeWithReturn(Test::m);
                }
            }""")
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
