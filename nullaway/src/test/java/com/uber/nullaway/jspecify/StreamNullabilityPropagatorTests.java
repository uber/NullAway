package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import org.junit.Test;

public class StreamNullabilityPropagatorTests extends NullAwayTestsBase {

  @Test
  public void filterObjectsNonNullRefinesStreamElementType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Arrays;
            import java.util.List;
            import java.util.Objects;
            import java.util.Optional;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              static Optional<Double> sumNumbersAsDoubles(@Nullable Number... numbers) {
                return Arrays.stream(numbers)
                    .filter(Objects::nonNull)
                    .map(Number::doubleValue)
                    .reduce(Double::sum);
              }

              static Optional<Double> sumDoubles(@Nullable Double... doubles) {
                return Arrays.stream(doubles)
                    .filter(Objects::nonNull)
                    .reduce(Double::sum);
              }

              static Optional<Double> sumDoublesWithExplicitMap(@Nullable Double... doubles) {
                return Arrays.stream(doubles)
                    .filter(Objects::nonNull)
                    .map(Objects::requireNonNull)
                    .reduce(Double::sum);
              }

              static List<String> nullableStringsToNonNull(List<@Nullable String> values) {
                return values.stream().filter(Objects::nonNull).toList();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void filterLibraryModeledNullRejectingPredicateRefinesStreamElementType() {
    makeHelper()
        .addSourceLines(
            "org/springframework/util/StringUtils.java",
            """
            package org.springframework.util;

            public final class StringUtils {
              public static boolean hasLength(String value) {
                return value != null && !value.isEmpty();
              }
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import org.springframework.util.StringUtils;

            @NullMarked
            class Test {
              static List<String> nullableStringsToNonNull(List<@Nullable String> values) {
                return values.stream().filter(StringUtils::hasLength).toList();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void filterContractNullRejectingPredicateRefinesStreamElementType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jetbrains.annotations.Contract;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              @Contract("null -> false")
              static boolean hasLength(@Nullable String value) {
                return value != null && !value.isEmpty();
              }

              static List<String> nullableStringsToNonNull(List<@Nullable String> values) {
                return values.stream().filter(Test::hasLength).toList();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void unrelatedFilterDoesNotRefineStreamElementType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import java.util.Objects;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              static List<String> filterDoesNotProveNonNull(List<@Nullable String> values) {
                // BUG: Diagnostic contains: incompatible types
                return values.stream().filter(value -> true).toList();
              }

              static @Nullable String maybeNull(String value) {
                return null;
              }

              static List<@Nullable String> genericMapAfterNullRejectingFilterUsesCallSiteInference(
                  List<@Nullable String> values) {
                return values.stream()
                    .filter(Objects::nonNull)
                    .map(Test::maybeNull)
                    .toList();
              }

              static List<String> mapAfterNullRejectingFilterCanStillReturnNullable(
                  List<@Nullable String> values) {
                return values.stream()
                    .filter(Objects::nonNull)
                    .map(Test::maybeNull)
                    // BUG: Diagnostic contains: incompatible types
                    .toList();
              }
            }
            """)
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList("-XepOpt:NullAway:OnlyNullMarked=true")));
  }
}
