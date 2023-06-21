package com.uber.nullaway;

import org.junit.Test;

public class NullAwayFunctionalInterfaceNullabilityTests extends NullAwayTestsBase {

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
}
