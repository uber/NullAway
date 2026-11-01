package com.uber.nullaway.jspecify;

import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import org.junit.Test;

/** Recovered Java shapes must not impose PolyNull qualifier constraints. */
public class PolyNullShapeProjectionTests extends NullAwayTestsBase {
  @Test
  public void nestedMapperUsesActualQualifierEvidence() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of("-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T id(T value) { return value; }
              void directNullable(@Nullable Object seed) {
                PolyNullMethods.withMapper(seed, value -> "ok");
              }
              void nestedNonNull(Object seed) {
                PolyNullMethods.withMapper(seed, id(value -> value.toString()));
              }
              void explicitNullable(@Nullable Object seed) {
                PolyNullMethods.withMapper(
                    seed, Test.<Function<@Nullable Object, String>>id(value -> "ok"));
              }
              void nestedNullable(@Nullable Object seed) {
                PolyNullMethods.withMapper(seed, id(value -> "ok"));
              }
              void nullableParameterIsStillChecked(@Nullable Object seed) {
                // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                PolyNullMethods.withMapper(seed, id(value -> value.toString()));
              }
              void nonNullMapperIsStillRejected(
                  @Nullable Object seed, Function<Object, String> mapper) {
                // BUG: Diagnostic contains: inference failure
                PolyNullMethods.withMapper(seed, id(mapper));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nestedEmptyListPreservesNullableModelResultAndNonNullObligation() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of("-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.PolyNullMethods;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              Object field = new Object();
              static <T extends @Nullable Object> T id(T value) { return value; }
              static <T extends @Nullable Object> List<T> empty() {
                return new java.util.ArrayList<>();
              }
              void directNullable(List<@Nullable Object> values) {
                Object result = PolyNullMethods.first(values, values);
              }
              void explicitNullable(List<@Nullable Object> values) {
                Object result = PolyNullMethods.first(id(Test.<@Nullable Object>empty()), values);
              }
              void nestedNonNull(List<Object> values) {
                Object result = PolyNullMethods.first(id(empty()), values);
                result.hashCode();
              }
              void nestedNullable(List<@Nullable Object> values) {
                Object result = PolyNullMethods.first(id(empty()), values);
                // BUG: Diagnostic contains: dereferenced expression 'result' is @Nullable
                result.hashCode();
              }
              void reversedNullable(List<@Nullable Object> values) {
                Object result = PolyNullMethods.first(values, id(empty()));
                // BUG: Diagnostic contains: dereferenced expression 'result' is @Nullable
                result.hashCode();
              }
              void nonNullResultIsStillRejected(List<@Nullable Object> values) {
                // BUG: Diagnostic contains: inference failure
                field = PolyNullMethods.first(id(empty()), values);
              }
            }
            """)
        .doTest();
  }
}
