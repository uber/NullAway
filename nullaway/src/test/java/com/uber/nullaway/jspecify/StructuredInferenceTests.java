package com.uber.nullaway.jspecify;

import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import org.junit.Test;

/** End-to-end structural incorporation without source-attribution diagnostics. */
public class StructuredInferenceTests extends NullAwayTestsBase {
  @Test
  public void structuredBoundsConstrainNestedCallsAndDependentVariables() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of("-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {
                E get() { throw new RuntimeException(); }
              }
              static <T extends @Nullable Object> T id(T value) { return value; }
              static <T extends @Nullable Object> T pick(T first, T second) { return first; }
              static void consume(Box<@Nullable String> value) {}
              static void consumeNonNull(Box<String> value) {}
              void nestedConsumerKeepsItsArgument(Box<@Nullable String> value) {
                consume(id(value));
                // BUG: Diagnostic contains: incompatible types
                consumeNonNull(id(value));
              }
              void siblingDiamondForward(Box<@Nullable String> value) {
                consume(pick(value, new Box<>()));
                // BUG: Diagnostic contains: incompatible types
                consumeNonNull(pick(value, new Box<>()));
              }
              void siblingDiamondReverse(Box<@Nullable String> value) {
                consume(pick(new Box<>(), value));
                // BUG: Diagnostic contains: incompatible types
                consumeNonNull(pick(new Box<>(), value));
              }
              void siblingNonNullControls(Box<String> value) {
                consumeNonNull(pick(value, new Box<>()));
                consumeNonNull(pick(new Box<>(), value));
              }
              static <T extends @Nullable Object, U extends List<T>, V extends U> T first(V value) {
                return value.get(0);
              }
              void dependentVToUToT(List<@Nullable String> nullable, List<String> clean) {
                first(clean).length();
                // BUG: Diagnostic contains: dereferenced expression
                first(nullable).length();
              }
              static <T extends @Nullable Object> T widen(T value) { return value; }
              void scalarWidenDoesNotInventNestedStructure() {
                // BUG: Diagnostic contains: dereferenced expression
                widen(null).toString();
                // BUG: Diagnostic contains: dereferenced expression
                pick("value", null).toString();
              }
              static <T extends @Nullable Object> Box<T> arrays(T first, T second) {
                throw new RuntimeException();
              }
              static void nullableArray(Box<@Nullable String[]> value) {}
              static void nonNullArray(Box<String[]> value) {}
              void arrayMerging(String[] clean, @Nullable String[] nullable) {
                nonNullArray(arrays(clean, clean));
                nullableArray(arrays(clean, nullable));
                nullableArray(arrays(nullable, clean));
                // BUG: Diagnostic contains: incompatible types
                nonNullArray(arrays(clean, nullable));
                // BUG: Diagnostic contains: incompatible types
                nonNullArray(arrays(nullable, clean));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inheritedAndCyclicDeclarationBoundsAreCheckedIndependently() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of("-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import java.util.ArrayList;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class NullableStrings extends ArrayList<@Nullable String> {}
              static class CleanStrings extends ArrayList<String> {}
              static <T extends List<String>> void requireClean(T value) {}
              void inheritedNonGenericBounds() {
                requireClean(new CleanStrings());
                // BUG: Diagnostic contains: incompatible types
                requireClean(new NullableStrings());
              }
              interface Cycle<T extends @Nullable Object> {}
              static class CleanCycle implements Cycle<CleanCycle> {}
              static class NullableCycle implements Cycle<@Nullable NullableCycle> {}
              static <T extends Cycle<T>> T checked(T value) { return value; }
              void cyclicBoundsDoNotHideKnownViolations(CleanCycle clean, NullableCycle nullable) {
                checked(clean);
                // BUG: Diagnostic contains: inference failure
                checked(nullable);
              }
            }
            """)
        .doTest();
  }
}
