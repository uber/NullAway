package com.uber.nullaway;

import java.util.Arrays;
import org.junit.Test;

public class FunctionalInterfaceNullabilityTests extends NullAwayTestsBase {

  @Test
  public void multipleTypeParametersInstantiation() {
    defaultCompilationHelper
        .addSourceLines(
            "NullableFunction.java",
            "package com.uber.unannotated;", // As if a third-party lib, since override is invalid
            "import javax.annotation.Nullable;",
            "import java.util.function.Function;",
            "@FunctionalInterface",
            "public interface NullableFunction<F, T> extends Function<F, T> {",
            "  @Override",
            "  @Nullable",
            "  T apply(@Nullable F input);",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import java.util.function.Function;",
            "import com.uber.unannotated.NullableFunction;",
            "class Test {",
            "  private static void takesNullableFunction(NullableFunction<String, String> nf) { }",
            "  private static void takesNonNullableFunction(Function<String, String> f) { }",
            "  private static void passesNullableFunction() {",
            "    takesNullableFunction(s -> { return null; });",
            "  }",
            "  private static void passesNullableFunctionToNonNull() {",
            "    takesNonNullableFunction(s -> { return null; });",
            "  }",
            "}")
        .addSourceLines(
            "TestGuava.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "import com.uber.unannotated.NullableFunction;",
            "class TestGuava {",
            "  private static void takesNullableFunction(NullableFunction<String, String> nf) { }",
            "  private static void takesNonNullableFunction(Function<String, String> f) { }",
            "  private static void passesNullableFunction() {",
            "    takesNullableFunction(s -> { return null; });",
            "  }",
            "  private static void passesNullableFunctionToNonNull() {",
            "    // BUG: Diagnostic contains: returning @Nullable expression",
            "    takesNonNullableFunction(s -> { return null; });",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void futuresFunctionLambdas() {
    // See FluentFutureHandler
    defaultCompilationHelper
        .addSourceLines(
            "TestGuava.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import com.google.common.base.Function;",
            "import com.google.common.util.concurrent.FluentFuture;",
            "import com.google.common.util.concurrent.Futures;",
            "import com.google.common.util.concurrent.ListenableFuture;",
            "import java.util.concurrent.Executor;",
            "class TestGuava {",
            "  private static ListenableFuture<@Nullable String> fluentFutureCatching(Executor executor) {",
            "    return FluentFuture",
            "        .from(Futures.immediateFuture(\"hi\"))",
            "        .catching(Throwable.class, e -> { return null; }, executor);",
            "  }",
            "  private static ListenableFuture<@Nullable String> fluentFutureCatchingAsync(Executor executor) {",
            "    return FluentFuture",
            "        .from(Futures.immediateFuture(\"hi\"))",
            "        .catchingAsync(Throwable.class, e -> { return null; }, executor);",
            "  }",
            "  private static ListenableFuture<@Nullable String> fluentFutureTransform(Executor executor) {",
            "    return FluentFuture",
            "        .from(Futures.immediateFuture(\"hi\"))",
            "        .transform(s -> { return null; }, executor);",
            "  }",
            "  private static ListenableFuture<@Nullable String> fluentFutureTransformAsync(Executor executor) {",
            "    return FluentFuture",
            "        .from(Futures.immediateFuture(\"hi\"))",
            "        .transformAsync(s -> { return null; }, executor);",
            "  }",
            "  private static ListenableFuture<String> fluentFutureTransformNoNull(Executor executor) {",
            "    return FluentFuture",
            "        .from(Futures.immediateFuture(\"hi\"))",
            "        // Should be an error when we have full generics support, false-negative for now",
            "        .transform(s -> { return s; }, executor);",
            "  }",
            "  private static ListenableFuture<String> fluentFutureUnsafe(Executor executor) {",
            "    return FluentFuture",
            "        .from(Futures.immediateFuture(\"hi\"))",
            "        // Should be an error when we have full generics support, false-negative for now",
            "        .transform(s -> { return null; }, executor);",
            "  }",
            "  private static ListenableFuture<@Nullable String> futuresTransform(Executor executor) {",
            "    return Futures",
            "        .transform(Futures.immediateFuture(\"hi\"), s -> { return null; }, executor);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void extraFuturesClassesLambda() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:ExtraFuturesClasses=com.uber.extra.MyFutures"))
        .addSourceLines(
            "MyFutures.java",
            "package com.uber.extra;",
            "import com.google.common.base.Function;",
            "import com.google.common.util.concurrent.Futures;",
            "import com.google.common.util.concurrent.ListenableFuture;",
            "import java.util.concurrent.Executor;",
            "public class MyFutures {",
            "  public static <V> ListenableFuture<V> transform(ListenableFuture<V> future, Function<V, V> function, Executor executor) {",
            "    return Futures.transform(future, function, executor);",
            "  }",
            "}")
        .addSourceLines(
            "TestMyFutures.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import com.google.common.base.Function;",
            "import com.google.common.util.concurrent.FluentFuture;",
            "import com.google.common.util.concurrent.Futures;",
            "import com.google.common.util.concurrent.ListenableFuture;",
            "import java.util.concurrent.Executor;",
            "class TestGuava {",
            "  private static void takeFn(Function<String, String> f) { }",
            "  private static void passToAnnotatedFunction() {",
            "    // Normally we get an error since Guava Functions are modeled to have a @NonNull return",
            "    // BUG: Diagnostic contains: returning @Nullable expression from method",
            "    takeFn(s -> { return null; });",
            "  }",
            "  private static void passToExtraFuturesClass(ListenableFuture<String> f, Executor e) {",
            "    // here we do not expect an error since MyFutures is in the extra futures classes",
            "    com.uber.extra.MyFutures.transform(f, u -> { return null; }, e);",
            "  }",
            "}")
        .doTest();
  }
}
