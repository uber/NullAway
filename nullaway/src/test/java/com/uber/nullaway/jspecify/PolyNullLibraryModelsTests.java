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
  public void optionalOrElseGetWithExplicitSupplierTypeArgument() {
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
              }
            }
            """)
        .doTest();
  }

  @Test
  public void optionalOrElseGetWithExplicitSupplierTypeArgumentAndVarResult() {
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
  public void mapComputeIfAbsentWithExplicitFunctionTypeArgument() {
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
              }
            }
            """)
        .doTest();
  }

  @Test
  public void mapComputeIfAbsentWithExplicitFunctionTypeArgumentAndVarResult() {
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
  public void customLibraryModelWithMultipleExplicitTypeArguments() {
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
  public void customLibraryModelRejectsMismatchedExplicitTypeArguments() {
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
                // BUG: Diagnostic contains: polymorphic nullness constrained to both @NonNull and @Nullable
                PolyNullMethods.first(nonNull, nullable);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericMethodRequiresMatchingExplicitTypeArgumentNullability() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(String nonNull) {
                PolyNullMethods.<String, String>twoTypeVariables(nonNull, nonNull);
                PolyNullMethods.<@Nullable String, @Nullable String>twoTypeVariables(
                    nonNull, nonNull);

                // BUG: Diagnostic contains: polymorphic nullness constrained to both @NonNull and @Nullable
                PolyNullMethods.<String, @Nullable String>twoTypeVariables(nonNull, nonNull);

                // BUG: Diagnostic contains: polymorphic nullness constrained to both @NonNull and @Nullable
                PolyNullMethods.<@Nullable String, String>twoTypeVariables(nonNull, nonNull);
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
                PolyNullMethods.genericFirst(nonNull, nonNull).hashCode();

                // BUG: Diagnostic contains: dereferenced expression 'PolyNullMethods.genericFirst(nullable, nullable)' is @Nullable
                PolyNullMethods.genericFirst(nullable, nullable).hashCode();

                var nonNullResult = PolyNullMethods.genericFirst(nonNull, nonNull);
                nonNullResult.hashCode();

                var nullableResult = PolyNullMethods.genericFirst(nullable, nullable);
                // BUG: Diagnostic contains: dereferenced expression 'nullableResult' is @Nullable
                nullableResult.hashCode();

                // BUG: Diagnostic contains: polymorphic nullness constrained to both @NonNull and @Nullable
                PolyNullMethods.genericFirst(nonNull, nullable);

                // BUG: Diagnostic contains: polymorphic nullness constrained to both @NonNull and @Nullable
                PolyNullMethods.genericFirst(nullable, nonNull);
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

                // BUG: Diagnostic contains: polymorphic nullness constrained to both @NonNull and @Nullable
                PolyNullMethods.genericFromSuppliers(() -> "first", () -> null);
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
