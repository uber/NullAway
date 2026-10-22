package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import org.junit.Test;

/**
 * Specification tests for {@code @PolyNull}-like library models.
 *
 * <p>These tests cover named functional-interface values, lambda and method-reference inference,
 * inferred and explicit method type arguments, and propagation through {@code var} locals.
 *
 * @see <a href="https://github.com/uber/NullAway/issues/1616">NullAway issue #1616</a>
 */
public class PolyNullLibraryModelsTests extends NullAwayTestsBase {

  @Test
  public void modeledLambdaParameterUsesInferredPolyNullness() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(Object nonNull, @Nullable Object nullable) {
                PolyNullMethods.withMapper(nonNull, value -> value.toString());
                // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                PolyNullMethods.withMapper(nullable, value -> value.toString());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void failedGenericInferenceDoesNotAddPolyNullDereferenceError() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(@Nullable String seed) {
                // BUG: Diagnostic contains: inference failure
                Object result = PolyNullMethods.boundedGenericObject(seed, () -> "fallback");
                result.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void outputOnlyModelPreservesDeclaredNullableContents() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              void test() {
                // The provider's stale parameter location does not apply to this zero-argument
                // method. Without a modeled input, keep the declared nullable list contents.
                // BUG: Diagnostic contains: dereferenced expression 'PolyNullMethods.outputOnly().get(0)' is @Nullable
                PolyNullMethods.outputOnly().get(0).hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void polyNullModelsAreDisabledWithoutExperimentalOptIn() {
    makeTestHelperWithArgs(
            List.of(
                "-XepOpt:NullAway:OnlyNullMarked=true",
                JSpecifyJavacConfig.JSPECIFY_MODE_FLAG,
                JSpecifyJavacConfig.ADD_TYPE_ANNOTATIONS_FLAG))
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(List<Object> nonNull, List<@Nullable Object> nullable) {
                PolyNullMethods.first(nonNull, nonNull).hashCode();

                // Without PolyNull, both parameters retain their declared List<Object> types.
                // BUG: Diagnostic contains: incompatible types: List<@Nullable Object> cannot be converted to List<Object>
                PolyNullMethods.first(nullable, nullable);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void builtInPolyNullModelsAreDisabledWithoutExperimentalOptIn() {
    makeTestHelperWithArgs(
            List.of(
                "-XepOpt:NullAway:OnlyNullMarked=true",
                JSpecifyJavacConfig.JSPECIFY_MODE_FLAG,
                JSpecifyJavacConfig.ADD_TYPE_ANNOTATIONS_FLAG,
                "-XepOpt:NullAway:JSpecifyJDKModels=true"))
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import java.util.Optional;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              void test(Map<String, String> map, Optional<String> optional) {
                // Without PolyNull, the JDK model rejects this nullable callback result.
                // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type
                map.computeIfAbsent("key", unused -> null);
                // The legacy dataflow rule treats the inferred Function result as non-null.
                map.get("key").hashCode();

                // BUG: Diagnostic contains: dereferenced expression 'optional.orElseGet(() -> null)' is @Nullable
                optional.orElseGet(() -> null).hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void polyNullModelsDoNotRequireJSpecifyJdkModels() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of(
                    "-XepOpt:NullAway:OnlyNullMarked=true",
                    "-XepOpt:NullAway:JSpecifyJDKModels=false")))
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              private Object field = new Object();

              void test(@Nullable Object nullable) {
                // BUG: Diagnostic contains: linked nullness for call to genericObject constrained to be both @NonNull and @Nullable
                field = PolyNullMethods.genericObject(nullable, () -> nullable);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void optionalOrElseGetWithNamedSupplier() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Optional;
            import java.util.function.Supplier;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(
                  Optional<String> optional,
                  Supplier<String> nonNullSupplier,
                  Supplier<@Nullable String> nullableSupplier) {
                optional.orElseGet(nonNullSupplier).hashCode();

                // BUG: Diagnostic contains: dereferenced expression 'optional.orElseGet(nullableSupplier)' is @Nullable
                optional.orElseGet(nullableSupplier).hashCode();

                var nonNullResult = optional.orElseGet(nonNullSupplier);
                nonNullResult.hashCode();

                var nullableResult = optional.orElseGet(nullableSupplier);
                // BUG: Diagnostic contains: dereferenced expression 'nullableResult' is @Nullable
                nullableResult.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void optionalOrElseGetWithWildcardReceiver() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Optional;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              void direct(Optional<? extends String> optional) {
                // Throwing callbacks are valid for any capture and never return null.
                optional.orElseGet(() -> { throw new IllegalStateException(); }).hashCode();
                // BUG: Diagnostic contains: dereferenced expression 'optional.orElseGet(() -> null)' is @Nullable
                optional.orElseGet(() -> null).hashCode();

                // Applying the result qualifier must not change the receiver's shared bound.
                optional.get().hashCode();
              }

              void local(Optional<? extends String> optional) {
                String result = optional.orElseGet(() -> null);
                // BUG: Diagnostic contains: dereferenced expression 'result' is @Nullable
                result.hashCode();
              }

              void inferredLocal(Optional<? extends String> optional) {
                var result = optional.orElseGet(() -> null);
                // BUG: Diagnostic contains: dereferenced expression 'result' is @Nullable
                result.hashCode();
              }

              void unbounded(Optional<?> optional) {
                optional.orElseGet(() -> { throw new IllegalStateException(); }).hashCode();
                // BUG: Diagnostic contains: dereferenced expression 'optional.orElseGet(() -> null)' is @Nullable
                optional.orElseGet(() -> null).hashCode();
                optional.get().hashCode();

                var result = optional.orElseGet(() -> null);
                // BUG: Diagnostic contains: dereferenced expression 'result' is @Nullable
                result.hashCode();
              }

              void lowerBounded(Optional<? super String> optional) {
                // BUG: Diagnostic contains: dereferenced expression 'optional.orElseGet(() -> null)' is @Nullable
                optional.orElseGet(() -> null).hashCode();
                optional.get().hashCode();
                optional.orElseGet(() -> "value").hashCode();

                var result = optional.orElseGet(() -> null);
                // BUG: Diagnostic contains: dereferenced expression 'result' is @Nullable
                result.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void optionalOrElseGetWithCapturedReceiver() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Optional;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              static <T> Optional<T> identity(Optional<T> optional) {
                return optional;
              }

              void test(Optional<? extends String> optional) {
                // Throwing callbacks are valid for any capture and never return null.
                identity(optional).orElseGet(() -> { throw new IllegalStateException(); }).hashCode();
                // Generic inference captures the wildcard before the PolyNull model is applied.
                var result = identity(optional).orElseGet(() -> null);
                // BUG: Diagnostic contains: dereferenced expression 'result' is @Nullable
                result.hashCode();
              }

              void unbounded(Optional<?> optional) {
                identity(optional).orElseGet(() -> { throw new IllegalStateException(); }).hashCode();
                // BUG: Diagnostic contains: dereferenced expression 'identity(optional).orElseGet(() -> null)' is @Nullable
                identity(optional).orElseGet(() -> null).hashCode();
                optional.get().hashCode();
              }

              void lowerBounded(Optional<? super String> optional) {
                // BUG: Diagnostic contains: dereferenced expression 'identity(optional).orElseGet(() -> null)' is @Nullable
                identity(optional).orElseGet(() -> null).hashCode();
                optional.get().hashCode();
                identity(optional).orElseGet(() -> "value").hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void optionalOrElseGetUsesFieldAssignmentTarget() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Optional;
            import java.util.function.Supplier;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              private String field = "initial";

              void test(
                  Optional<String> optional,
                  Supplier<String> nonNullSupplier,
                  Supplier<@Nullable String> nullableSupplier) {
                field = optional.orElseGet(nonNullSupplier);

                // BUG: Diagnostic contains: linked nullness for call to orElseGet constrained to be both @NonNull and @Nullable
                field = optional.orElseGet(nullableSupplier);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void optionalOrElseGetPreservesErrorsWithoutInferenceWarnings() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of(
                    "-XepOpt:NullAway:OnlyNullMarked=true",
                    "-XepOpt:NullAway:WarnOnGenericInferenceFailure=false")))
        .addSourceLines(
            "Test.java",
            """
            import java.util.Optional;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              private String field = "initial";

              void assign(Optional<String> optional) {
                field = optional.orElseGet(() -> "fallback");
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                field = optional.orElseGet(() -> null);
              }

              String returnNullable(Optional<String> optional) {
                // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type
                return optional.orElseGet(() -> null);
              }

              String returnNonNull(Optional<String> optional) {
                return optional.orElseGet(() -> "fallback");
              }
            }
            """)
        .doTest();
  }

  @Test
  public void optionalOrElseGetUsesParenthesizedResultTarget() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Optional;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              String nullableResult(Optional<String> optional) {
                // BUG: Diagnostic contains: linked nullness for call to orElseGet constrained to be both @NonNull and @Nullable
                return ((optional.orElseGet(() -> null)));
              }

              String nonNullResult(Optional<String> optional) {
                return ((optional.orElseGet(() -> "value")));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapComputeIfAbsentPreservesErrorsWithoutInferenceWarnings() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of(
                    "-XepOpt:NullAway:OnlyNullMarked=true",
                    "-XepOpt:NullAway:WarnOnGenericInferenceFailure=false")))
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              private String field = "initial";

              void assign(Map<String, String> map) {
                field = map.computeIfAbsent("nonNull", unused -> "value");
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                field = map.computeIfAbsent("nullable", unused -> null);
              }

              String nullableResult(Map<String, String> map) {
                // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type
                return map.computeIfAbsent("nullable", unused -> null);
              }

              String nonNullResult(Map<String, String> map) {
                return map.computeIfAbsent("nonNull", unused -> "value");
              }
            }
            """)
        .doTest();
  }

  @Test
  public void polyNullInferenceFailureDoesNotAddOrdinaryNullnessErrors() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Optional;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              private String field = "initial";

              void assign(Optional<String> optional) {
                field =
                    // BUG: Diagnostic contains: linked nullness for call to orElseGet
                    optional.orElseGet(() -> null);
              }

              String returnNullable(Optional<String> optional) {
                return
                    // BUG: Diagnostic contains: linked nullness for call to orElseGet
                    optional.orElseGet(() -> null);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericPolyNullPreservesErrorsWithoutInferenceWarnings() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of(
                    "-XepOpt:NullAway:OnlyNullMarked=true",
                    "-XepOpt:NullAway:WarnOnGenericInferenceFailure=false")))
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              private Object field = new Object();

              void assign(Object seed) {
                field = PolyNullMethods.genericObject(seed, () -> "fallback");
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                field = PolyNullMethods.genericObject(seed, () -> null);
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                field = PolyNullMethods.<Object, Object>genericObject(seed, () -> null);
              }

              Object returnNullable(Object seed) {
                // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type
                return PolyNullMethods.genericObject(seed, () -> null);
              }

              Object returnExplicitNullable(Object seed) {
                // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type
                return PolyNullMethods.<Object, Object>genericObject(seed, () -> null);
              }

              Object returnNonNull(Object seed) {
                return PolyNullMethods.genericObject(seed, () -> "fallback");
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapComputeIfAbsentWithNamedFunction() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(
                  Map<String, String> map,
                  Function<String, String> nonNullFunction,
                  Function<String, @Nullable String> nullableFunction) {
                map.computeIfAbsent("key", nonNullFunction).hashCode();

                // BUG: Diagnostic contains: dereferenced expression 'map.computeIfAbsent("key", nullableFunction)' is @Nullable
                map.computeIfAbsent("key", nullableFunction).hashCode();

                var nonNullResult = map.computeIfAbsent("key", nonNullFunction);
                nonNullResult.hashCode();

                var nullableResult = map.computeIfAbsent("key", nullableFunction);
                // BUG: Diagnostic contains: dereferenced expression 'nullableResult' is @Nullable
                nullableResult.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapComputeIfAbsentWithWildcardReceiver() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              void direct(Map<String, ? extends String> map) {
                // Throwing callbacks are valid for any capture and never return null.
                map.computeIfAbsent("nonNull", unused -> { throw new IllegalStateException(); }).hashCode();
                // BUG: Diagnostic contains: dereferenced expression 'map.computeIfAbsent("key", unused -> null)' is @Nullable
                map.computeIfAbsent("key", unused -> null).hashCode();

                // A null callback result does not establish that the key is present.
                // BUG: Diagnostic contains: dereferenced expression
                map.get("key").hashCode();
              }

              void local(Map<String, ? extends String> map) {
                String result = map.computeIfAbsent("key", unused -> null);
                // BUG: Diagnostic contains: dereferenced expression 'result' is @Nullable
                result.hashCode();
              }

              void inferredLocal(Map<String, ? extends String> map) {
                var result = map.computeIfAbsent("key", unused -> null);
                // BUG: Diagnostic contains: dereferenced expression 'result' is @Nullable
                result.hashCode();
              }

              void unbounded(Map<String, ?> map) {
                map.computeIfAbsent("nonNull", unused -> { throw new IllegalStateException(); }).hashCode();
                // BUG: Diagnostic contains: dereferenced expression 'map.computeIfAbsent("key", unused -> null)' is @Nullable
                map.computeIfAbsent("key", unused -> null).hashCode();

                var result = map.computeIfAbsent("key", unused -> null);
                // BUG: Diagnostic contains: dereferenced expression 'result' is @Nullable
                result.hashCode();
              }

              void lowerBounded(Map<String, ? super String> map) {
                // BUG: Diagnostic contains: dereferenced expression 'map.computeIfAbsent("key", unused -> null)' is @Nullable
                map.computeIfAbsent("key", unused -> null).hashCode();

                var result = map.computeIfAbsent("key", unused -> null);
                // BUG: Diagnostic contains: dereferenced expression 'result' is @Nullable
                result.hashCode();

                map.computeIfAbsent("nonNull", unused -> "value").hashCode();
                // A non-null result does not establish that another key maps to a non-null value.
                // BUG: Diagnostic contains: dereferenced expression
                map.get("other").hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapComputeIfAbsentWithCapturedReceiver() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              static <V extends @Nullable Object> Map<String, V> identity(Map<String, V> map) {
                return map;
              }

              void test(Map<String, ? super String> map) {
                // BUG: Diagnostic contains: dereferenced expression 'identity(map).computeIfAbsent("nullable", unused -> null)' is @Nullable
                identity(map).computeIfAbsent("nullable", unused -> null).hashCode();
                identity(map).computeIfAbsent("nonNull", unused -> "value").hashCode();

                // Neither call establishes that another key maps to a non-null value.
                // BUG: Diagnostic contains: dereferenced expression
                map.get("other").hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapComputeIfAbsentModelAppliesToOverride() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.HashMap;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(
                  HashMap<String, String> map,
                  Function<String, String> nonNullFunction,
                  Function<String, @Nullable String> nullableFunction) {
                map.computeIfAbsent("key", nonNullFunction).hashCode();

                // BUG: Diagnostic contains: dereferenced expression 'map.computeIfAbsent("key", nullableFunction)' is @Nullable
                map.computeIfAbsent("key", nullableFunction).hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapComputeIfAbsentWithLambdaAndVarResult() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              void test(Map<String, String> map) {
                var nonNullResult = map.computeIfAbsent("key", unused -> "value");
                nonNullResult.hashCode();

                var nullableResult = map.computeIfAbsent("key", unused -> null);
                // BUG: Diagnostic contains: dereferenced expression 'nullableResult' is @Nullable
                nullableResult.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapComputeIfAbsentOverridesNullableMapValueType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(Map<String, @Nullable String> map) {
                map.computeIfAbsent("foo", unused -> "bar").hashCode();

                var result = map.computeIfAbsent("foo", unused -> "bar");
                result.hashCode();

                // BUG: Diagnostic contains: dereferenced expression 'map.computeIfAbsent("foo", unused -> null)' is @Nullable
                map.computeIfAbsent("foo", unused -> null).hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapComputeIfAbsentUsesFieldAssignmentTarget() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              private String field = "initial";

              void test(Map<String, @Nullable String> map) {
                field = map.computeIfAbsent("nonNull", unused -> "value");

                // BUG: Diagnostic contains: linked nullness for call to computeIfAbsent constrained to be both @NonNull and @Nullable
                field = map.computeIfAbsent("nullable", unused -> null);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapComputeIfAbsentWithMethodReference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              static String nonNullMapping(String unused) {
                return "value";
              }

              static @Nullable String nullableMapping(String unused) {
                return null;
              }

              void test(Map<String, String> map) {
                map.computeIfAbsent("key", Test::nonNullMapping).hashCode();

                // BUG: Diagnostic contains: dereferenced expression 'map.computeIfAbsent("key", Test::nullableMapping)' is @Nullable
                map.computeIfAbsent("key", Test::nullableMapping).hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapComputeIfAbsentEstablishesNonNullEntryForNullableValueType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(Map<String, @Nullable String> map) {
                map.computeIfAbsent("k", unused -> "value");
                map.get("k").hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapComputeIfAbsentWithNullableFunctionDoesNotEstablishKeyPresence() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(Map<String, String> map, Function<String, @Nullable String> function) {
                // BUG: Diagnostic contains: dereferenced expression 'map.computeIfAbsent("k", function)' is @Nullable
                map.computeIfAbsent("k", function).hashCode();

                // BUG: Diagnostic contains: dereferenced expression 'map.get("k")' is @Nullable
                map.get("k").hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapComputeIfAbsentAndKeyPresence() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void testPresent(Map<String, String> map) {
                map.computeIfAbsent("k", unused -> "hello");
                map.get("k").hashCode();
              }

              void testMayNotBePresent(Map<String, String> map) {
                map.computeIfAbsent("k", unused -> null);
                // key may be absent, so map.get("k") may still return null
                // BUG: Diagnostic contains: dereferenced expression 'map.get("k")' is @Nullable
                map.get("k").hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void customModelLinksBothListParametersToReturn() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(
                  List<Object> firstNonNull,
                  List<Object> secondNonNull,
                  List<@Nullable Object> firstNullable,
                  List<@Nullable Object> secondNullable) {
                PolyNullMethods.first(firstNonNull, secondNonNull).hashCode();

                // BUG: Diagnostic contains: dereferenced expression 'PolyNullMethods.first(firstNullable, secondNullable)' is @Nullable
                PolyNullMethods.first(firstNullable, secondNullable).hashCode();

                var nonNullResult = PolyNullMethods.first(firstNonNull, secondNonNull);
                nonNullResult.hashCode();

                var nullableResult = PolyNullMethods.first(firstNullable, secondNullable);
                // BUG: Diagnostic contains: dereferenced expression 'nullableResult' is @Nullable
                nullableResult.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void customLibraryModelRejectsIncompatibleInvariantArguments() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(List<Object> nonNull, List<@Nullable Object> nullable) {
                // BUG: Diagnostic contains: linked nullness for call to first constrained to be both @NonNull and @Nullable
                PolyNullMethods.first(nonNull, nullable);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericMethodResolvesNullnessWithExplicitTypeArguments() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(String nonNull, @Nullable String nullable) {
                PolyNullMethods.<String, String>genericFirst(nonNull, () -> nonNull).hashCode();
                PolyNullMethods.<@Nullable String, @Nullable String>genericFirst(
                    nonNull, () -> nonNull).hashCode();
                PolyNullMethods.<String, @Nullable String>genericFirst(nonNull, () -> nonNull).hashCode();
                PolyNullMethods.<@Nullable String, String>genericFirst(nonNull, () -> nonNull).hashCode();

                var nullableSupplierResult =
                    PolyNullMethods.<String, @Nullable String>genericFirst(nonNull, () -> nullable);
                // BUG: Diagnostic contains: dereferenced expression 'nullableSupplierResult' is @Nullable
                nullableSupplierResult.hashCode();

                var nullableArgumentResult =
                    PolyNullMethods.<@Nullable String, String>genericFirst(nullable, () -> nonNull);
                // BUG: Diagnostic contains: dereferenced expression 'nullableArgumentResult' is @Nullable
                nullableArgumentResult.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericMethodInfersTypeArgumentsAndPolyNullTogether() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(String nonNull, @Nullable String nullable) {
                PolyNullMethods.genericFirst(nonNull, () -> nonNull).hashCode();

                // BUG: Diagnostic contains: dereferenced expression 'PolyNullMethods.genericFirst(nullable, () -> nullable)' is @Nullable
                PolyNullMethods.genericFirst(nullable, () -> nullable).hashCode();

                var nonNullResult = PolyNullMethods.genericFirst(nonNull, () -> nonNull);
                nonNullResult.hashCode();

                var nullableResult = PolyNullMethods.genericFirst(nullable, () -> nullable);
                // BUG: Diagnostic contains: dereferenced expression 'nullableResult' is @Nullable
                nullableResult.hashCode();

                // BUG: Diagnostic contains: dereferenced expression 'PolyNullMethods.genericFirst(nonNull, () -> nullable)' is @Nullable
                PolyNullMethods.genericFirst(nonNull, () -> nullable).hashCode();

                // BUG: Diagnostic contains: dereferenced expression 'PolyNullMethods.genericFirst(nullable, () -> nonNull)' is @Nullable
                PolyNullMethods.genericFirst(nullable, () -> nonNull).hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nestedPolyNullResultAffectsOuterInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              static <T extends @Nullable Object> T identity(T value) {
                return value;
              }

              void test(String nonNull, @Nullable String nullable) {
                // The modeled return is Object, so only the joint PolyNull constraints can
                // propagate its nullable result into identity's inferred type argument.
                var result = identity(PolyNullMethods.genericObject(nonNull, () -> nullable));
                // BUG: Diagnostic contains: dereferenced expression 'result' is @Nullable
                result.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void repeatedGenericPolyNullCallsAreInferredSeparately() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              static <T extends @Nullable Object, U extends @Nullable Object> T chooseFirst(T t, U u) {
                return t;
              }

              void test(String nonNull, @Nullable String nullable) {
                var nonNullResult = chooseFirst(
                    PolyNullMethods.genericFirst(nonNull, () -> nonNull),
                    PolyNullMethods.genericFirst(nullable, () -> nullable));
                nonNullResult.hashCode();

                var nullableResult = chooseFirst(
                    PolyNullMethods.genericFirst(nullable, () -> nullable),
                    PolyNullMethods.genericFirst(nonNull, () -> nonNull));
                // BUG: Diagnostic contains: dereferenced expression 'nullableResult' is @Nullable
                nullableResult.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericMethodUsesFieldAssignmentTargetForPolyNull() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              private Object field = new Object();

              void test(Object nonNull, @Nullable Object nullable) {
                field = PolyNullMethods.genericObject(nonNull, () -> nonNull);

                // BUG: Diagnostic contains: linked nullness for call to genericObject constrained to be both @NonNull and @Nullable
                field = PolyNullMethods.genericObject(nullable, () -> nullable);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericMethodInfersPolyNullFromLambdasAndMethodReferences() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              static String nonNullValue() {
                return "value";
              }

              static @Nullable String nullableValue() {
                return null;
              }

              void test() {
                PolyNullMethods.genericFromSuppliers(() -> "first", () -> "second").hashCode();

                // BUG: Diagnostic contains: dereferenced expression 'PolyNullMethods.genericFromSuppliers(() -> null, () -> null)' is @Nullable
                PolyNullMethods.genericFromSuppliers(() -> null, () -> null).hashCode();

                PolyNullMethods.genericFromSuppliers(
                    Test::nonNullValue, Test::nonNullValue).hashCode();

                // BUG: Diagnostic contains: dereferenced expression 'PolyNullMethods.genericFromSuppliers(Test::nullableValue, Test::nullableValue)' is @Nullable
                PolyNullMethods.genericFromSuppliers(Test::nullableValue, Test::nullableValue).hashCode();

                // BUG: Diagnostic contains: dereferenced expression 'PolyNullMethods.genericFromSuppliers(() -> "first", () -> null)' is @Nullable
                PolyNullMethods.genericFromSuppliers(() -> "first", () -> null).hashCode();

                // BUG: Diagnostic contains: dereferenced expression 'PolyNullMethods.genericFromSuppliers(Test::nonNullValue, Test::nullableValue)' is @Nullable
                PolyNullMethods.genericFromSuppliers(Test::nonNullValue, Test::nullableValue).hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void fixedParameterModelsApplyAtPolyNullLocationWithoutExperimentalOptIn() {
    makeTestHelperWithArgs(
            List.of(
                "-XepOpt:NullAway:OnlyNullMarked=true",
                JSpecifyJavacConfig.JSPECIFY_MODE_FLAG,
                JSpecifyJavacConfig.ADD_TYPE_ANNOTATIONS_FLAG))
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullOverrides.NullableOverride;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(NullableOverride library, Object nonNull, @Nullable Object nullable) {
                // Without PolyNull, the fixed nullable model admits a nullable first argument.
                Object result = library.top(nullable, nullable, nonNull, () -> nonNull);
                // BUG: Diagnostic contains: dereferenced expression 'result' is @Nullable
                result.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void polyNullOverridesFixedVarargsArrayModel() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(
                  Object[] nonNullArray, Object @Nullable [] nullableArray, @Nullable Object nullable) {
                PolyNullMethods.varargsArray(() -> "value", nonNullArray).hashCode();
                // PolyNull admits a nullable array despite the fixed non-null model of the array,
                // and links the array's nullness to the result.
                Object result = PolyNullMethods.varargsArray(() -> "value", nullableArray);
                // BUG: Diagnostic contains: dereferenced expression 'result' is @Nullable
                result.hashCode();
                // The PolyNull location covers the array, not its elements, which stay non-null.
                // BUG: Diagnostic contains: passing @Nullable parameter 'nullable'
                PolyNullMethods.varargsArray(() -> "value", nullable);
                // A top-level location on a parameter before the varargs is not the array, and
                // PolyNull overrides the fixed non-null model of that parameter.
                Object linked = PolyNullMethods.linkedBeforeVarargs(nullable, () -> "value");
                // BUG: Diagnostic contains: dereferenced expression 'linked' is @Nullable
                linked.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inheritedPolyNullOverridesFixedTopLevelModels() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullOverrides.NullableOverride;
            import com.uber.lib.unannotated.PolyNullOverrides.NonNullOverride;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              private Object field = new Object();

              void test(NullableOverride nullableModel, NonNullOverride nonNullModel,
                  Object nonNull, @Nullable Object nullable) {
                // A conditional return model based on another input yields to the PolyNull return.
                nullableModel.top(nonNull, nullable, nonNull, () -> nonNull).hashCode();
                nonNullModel.top(nonNull, nullable, nonNull, () -> nonNull).hashCode();
                // BUG: Diagnostic contains: dereferenced expression
                nullableModel.top(nullable, nonNull, nonNull, () -> nonNull).hashCode();
                // BUG: Diagnostic contains: dereferenced expression
                nonNullModel.top(nullable, nonNull, nonNull, () -> nonNull).hashCode();

                var nullableResult = nonNullModel.top(nullable, nonNull, nonNull, () -> nonNull);
                // BUG: Diagnostic contains: dereferenced expression 'nullableResult' is @Nullable
                nullableResult.hashCode();
                var nonNullResult = nullableModel.top(nonNull, nullable, nonNull, () -> nonNull);
                nonNullResult.hashCode();

                // Nullable and non-null models on other parameters remain effective.
                // BUG: Diagnostic contains: passing @Nullable parameter
                nullableModel.top(nonNull, nullable, nullable, () -> nonNull);
                // BUG: Diagnostic contains: passing @Nullable parameter
                nonNullModel.top(nonNull, nullable, nullable, () -> nonNull);

                // Filter the nullable-return model before ordinary generic result constraints.
                field = nullableModel.generic(() -> nonNull, nonNull);
                field = nonNullModel.generic(() -> nonNull, nonNull);
                // BUG: Diagnostic contains: linked nullness for call to generic constrained to be both @NonNull and @Nullable
                field = nullableModel.generic(() -> nonNull, nullable);
                // BUG: Diagnostic contains: linked nullness for call to generic constrained to be both @NonNull and @Nullable
                field = nonNullModel.generic(() -> nonNull, nullable);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inheritedNestedPolyNullPreservesOtherFixedModels() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullOverrides.NullableOverride;
            import com.uber.lib.unannotated.PolyNullOverrides.NonNullOverride;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(NullableOverride nullableModel, NonNullOverride nonNullModel,
                  List<Object> nonNull, List<@Nullable Object> nullable) {
                // The nested input and output models yield to inherited PolyNull, while the
                // unrelated nullable list contents on parameter 1 remain allowed.
                var nonNullContents = nullableModel.nested(nonNull, nullable);
                if (nonNullContents != null) {
                  nonNullContents.get(0).hashCode();
                }
                var nullableContents = nonNullModel.nested(nullable, nullable);
                // A fixed non-null top-level return remains effective.
                // BUG: Diagnostic contains: dereferenced expression 'nullableContents.get(0)' is @Nullable
                nullableContents.get(0).hashCode();

                // A fixed nullable top-level return remains effective too.
                // BUG: Diagnostic contains: dereferenced expression
                nullableModel.nested(nonNull, nullable).hashCode();
                nonNullModel.nested(nonNull, nullable).get(0).hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inputOnlyPolyNullPreservesNullableReturnAssignmentError() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullOverrides.NullableOverride;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              private Object field = new Object();

              void test(NullableOverride library, List<Object> values) {
                // With no PolyNull return location, retain the ordinary nullable-return error.
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                field = library.inputOnly(values);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inheritedInputOnlyPolyNullPreservesFixedReturns() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullOverrides.NullableOverride;
            import com.uber.lib.unannotated.PolyNullOverrides.NonNullOverride;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(NullableOverride nullableModel, NonNullOverride nonNullModel,
                  List<@Nullable Object> nullable) {
                // BUG: Diagnostic contains: dereferenced expression
                nullableModel.inputOnly(nullable).hashCode();
                nonNullModel.inputOnly(nullable).hashCode();
                var nullableResult = nullableModel.inputOnly(nullable);
                // BUG: Diagnostic contains: dereferenced expression 'nullableResult' is @Nullable
                nullableResult.hashCode();
                var nonNullResult = nonNullModel.inputOnly(nullable);
                nonNullResult.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inheritedInputOnlyPolyNullPreservesConditionalReturns() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullOverrides.NullableOverride;
            import com.uber.lib.unannotated.PolyNullOverrides.NonNullOverride;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(NullableOverride nullableModel, NonNullOverride nonNullModel,
                  Object nonNull, @Nullable Object nullable) {
                nullableModel.conditional(() -> nullable, nonNull).hashCode();
                nonNullModel.conditional(() -> nullable, nonNull).hashCode();
                // BUG: Diagnostic contains: dereferenced expression
                nullableModel.conditional(() -> nonNull, nullable).hashCode();
                // BUG: Diagnostic contains: dereferenced expression
                nonNullModel.conditional(() -> nonNull, nullable).hashCode();
                var nullableResult = nonNullModel.conditional(() -> nonNull, nullable);
                // BUG: Diagnostic contains: dereferenced expression 'nullableResult' is @Nullable
                nullableResult.hashCode();
              }
            }
            """)
        .doTest();
  }

  /**
   * A top-level PolyNull location on a varargs parameter is the array. The array javac creates for
   * individually passed elements is non-null, and each element is checked against the element type,
   * so only the supplier decides the result. Two elements used to fail with
   * ArrayIndexOutOfBoundsException in InvocationArguments.forEach, and a single element was linked
   * to the result in place of the array.
   */
  @Test
  public void individuallyPassedVarargsElementsAreNotLinkedToTheResult() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(Object value, @Nullable Object nullable) {
                PolyNullMethods.varargsArray(() -> value).hashCode();
                // BUG: Diagnostic contains: passing @Nullable parameter 'nullable'
                Object oneElement = PolyNullMethods.varargsArray(() -> value, nullable);
                oneElement.hashCode();
                PolyNullMethods.varargsArray(() -> value, value, value).hashCode();
                Object nullableSupplier = PolyNullMethods.varargsArray(() -> nullable, value, value);
                // BUG: Diagnostic contains: dereferenced expression 'nullableSupplier' is @Nullable
                nullableSupplier.hashCode();
              }
            }
            """)
        .doTest();
  }

  /** An explicitly passed array is the location itself, so its nullness reaches the result. */
  @Test
  public void explicitlyPassedVarargsArrayLinksItsNullnessToTheResult() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(Object value, Object[] array, Object @Nullable [] nullableArray) {
                PolyNullMethods.varargsArray(() -> value, array).hashCode();
                Object fromNullableArray = PolyNullMethods.varargsArray(() -> value, nullableArray);
                // BUG: Diagnostic contains: dereferenced expression 'fromNullableArray' is @Nullable
                fromNullableArray.hashCode();
              }
            }
            """)
        .doTest();
  }

  /**
   * When the varargs array is the only modeled input, it still resolves the PolyNull nullness of
   * the declared nullable contents: to non-null for individually passed elements, and to nullable
   * for an explicitly passed nullable array.
   */
  @Test
  public void varargsArrayAsTheOnlyInputResolvesTheContents() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(Object value, Object @Nullable [] nullableArray) {
                PolyNullMethods.varargsContents(value, value).get(0).hashCode();
                // BUG: Diagnostic contains: dereferenced expression 'PolyNullMethods.varargsContents(nullableArray).get(0)' is @Nullable
                PolyNullMethods.varargsContents(nullableArray).get(0).hashCode();
              }
            }
            """)
        .doTest();
  }

  /**
   * Inference of an enclosing generic call builds the PolyNull overlay of a nested call too, and
   * handles individually passed varargs elements the same way.
   */
  @Test
  public void individuallyPassedVarargsElementsInsideGenericCallLeaveTheResultNonNull() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              static <T> T id(T value) {
                return value;
              }

              Object test(Object value) {
                return id(PolyNullMethods.varargsArray(() -> value, value, value));
              }
            }
            """)
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(List.of("-XepOpt:NullAway:OnlyNullMarked=true")));
  }
}
