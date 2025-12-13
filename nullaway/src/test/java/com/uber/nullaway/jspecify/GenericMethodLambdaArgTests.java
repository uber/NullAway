package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import org.junit.Test;

public class GenericMethodLambdaArgTests extends NullAwayTestsBase {

  @Test
  public void lambdaReturnsGenericMethodCall() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "class Test {",
            "    static interface Supplier<T extends @Nullable Object> {",
            "        T get();",
            "    }",
            "    static <R extends @Nullable Object> R invokeWithReturn(Supplier<R> supplier) {",
            "        return supplier.get();",
            "    }",
            "    static <U extends @Nullable Object> U genericMethod(U var){",
            "         return var;",
            "    }",
            "    static void test() {",
            "        Object x = invokeWithReturn(() -> { return genericMethod(\"value\");});",
            "        Object y = invokeWithReturn(() -> { return genericMethod(null);});",
            "        // legal, should infer x is a @NonNull String",
            "        x.hashCode();",
            "        // BUG: Diagnostic contains: dereferenced expression y is @Nullable",
            "        y.hashCode();",
            "        // Block-bodied with parenthesized return",
            "        Object x_block_paren = invokeWithReturn(() -> { return (genericMethod(\"value\"));});",
            "        Object y_block_paren = invokeWithReturn(() -> { return (genericMethod(null));});",
            "        // legal, should infer x_block_paren is a @NonNull String",
            "        x_block_paren.hashCode();",
            "        // BUG: Diagnostic contains: dereferenced expression y_block_paren is @Nullable",
            "        y_block_paren.hashCode();",
            "        // Expression-bodied",
            "        Object x_expr = invokeWithReturn(() -> genericMethod(\"value\"));",
            "        Object y_expr = invokeWithReturn(() -> genericMethod(null));",
            "        // legal, should infer x_expr is a @NonNull String",
            "        x_expr.hashCode();",
            "        // BUG: Diagnostic contains: dereferenced expression y_expr is @Nullable",
            "        y_expr.hashCode();",
            "        // Expression-bodied with parenthesized return",
            "        Object x_expr_paren = invokeWithReturn(() -> (genericMethod(\"value\")));",
            "        Object y_expr_paren = invokeWithReturn(() -> (genericMethod(null)));",
            "        // legal, should infer x_expr_paren is a @NonNull String",
            "        x_expr_paren.hashCode();",
            "        // BUG: Diagnostic contains: dereferenced expression y_expr_paren is @Nullable",
            "        y_expr_paren.hashCode();",
            "        // TODO",
            "        // Object x2 = invokeWithReturn(() ->{ Object y2 = null; return y2;});",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void issue1294_lambdaArguments() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "class Foo {",
            " public interface Callback<T extends @Nullable Object> {",
            "   void onResult(T thing);",
            " }",
            " public static <T extends @Nullable Object> Callback<T> wrap(Callback<T> thing) {",
            "   return thing;",
            " }",
            " public static void test() {",
            "   Callback<@Nullable String> ret1 = wrap(s -> {});",
            // we should get an error at the s.hashCode() call.
            "   // BUG: Diagnostic contains: dereferenced expression",
            "   Callback<@Nullable String> ret2 = wrap(s -> { s.hashCode(); });",
            "   Callback<@Nullable String> ret3 = wrap(s -> { if (s != null) s.hashCode(); });",
            "   Callback<String> ret4 = wrap(s -> { s.hashCode(); });",
            "   }",
            "}")
        .doTest();
  }

  @Test
  public void supplierLambdaInference() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "class Test {",
            "    static interface Supplier<T extends @Nullable Object> {",
            "        T get();",
            "    }",
            "    static <R> void invoke(Supplier<@Nullable R> supplier) {}",
            "    static <R extends @Nullable Object> R invokeWithReturn(Supplier<R> supplier) {",
            "        return supplier.get();",
            "    }",
            "    static void test() {",
            "        // legal, should infer R -> @Nullable Object, but inference can't handle yet",
            "        invoke(() -> null);",
            "        // legal, should infer R -> @Nullable Object, but inference can't handle yet",
            "        Object x = invokeWithReturn(() -> null);",
            "        // BUG: Diagnostic contains: dereferenced expression x is @Nullable",
            "        x.hashCode();",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void lambdaConditionalExprBody() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.*;",
            "import java.util.function.Function;",
            "@NullMarked",
            "class Test {",
            "  static <T extends @Nullable Object> T run(Function<String,T> f) {",
            "    return f.apply(\"\");",
            "  }",
            "  static void test() {",
            "    String t = run(s -> s == null ? null : s);",
            "    // BUG: Diagnostic contains: dereferenced expression t is @Nullable",
            "    t.hashCode();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void lambdaWithReturnStmtNullable() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.*;",
            "import java.util.function.Function;",
            "@NullMarked",
            "class Test {",
            "  static <T extends @Nullable Object> T run(Function<String,T> f) {",
            "    return f.apply(\"\");",
            "  }",
            "  static void test() {",
            "    String t = run(s -> { String result = null; return result; });",
            "    // BUG: Diagnostic contains: dereferenced expression t is @Nullable",
            "    t.hashCode();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nestedLambdaFromSpring() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.NullMarked;",
            "import java.util.function.Function;",
            "import java.util.function.Supplier;",
            "@NullMarked",
            "public class Test {",
            "    public interface Publisher<T> {}",
            "    public static final class Flux<T> implements Publisher<T> {",
            "        public static <T> Flux<T> defer(Supplier<? extends Publisher<T>> supplier) {",
            "            return new Flux<>();",
            "        }",
            "        public static <T> Flux<T> from(Publisher<? extends T> publisher) {",
            "            return new Flux<>();",
            "        }",
            "        public <R> Flux<R> map(Function<? super T, ? extends R> mapper) {",
            "            return new Flux<>();",
            "        }",
            "        public Flux<T> doOnDiscard() {",
            "            return this;",
            "        }",
            "    }",
            "    public static <T> Flux<T> skipUntilByteCount(Publisher<T> publisher) {",
            "        return Flux.defer(() ->",
            "            Flux.from(publisher)",
            "                    .map(buffer -> buffer)",
            "        ).doOnDiscard();",
            "    }",
            "}")
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
