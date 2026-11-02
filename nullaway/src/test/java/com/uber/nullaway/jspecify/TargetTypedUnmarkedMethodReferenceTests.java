package com.uber.nullaway.jspecify;

import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import org.junit.Test;

public class TargetTypedUnmarkedMethodReferenceTests extends NullAwayTestsBase {
  @Test
  public void functionalInterfaceMarkednessIsConsistentAcrossContexts() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of("-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "legacy/Legacy.java",
            """
            package legacy;
            import org.jspecify.annotations.NullUnmarked;
            public class Legacy {
              @NullUnmarked
              public interface Supplier<T> { T get(); }
              public interface Consumer<T> { void accept(T value); }
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import legacy.Legacy;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Supplier<T extends @Nullable Object> { T get(); }
              interface Consumer<T extends @Nullable Object> { void accept(T value); }
              static List<@Nullable String> nullableElements() { throw new AssertionError(); }
              static void consumeNonNullElements(List<String> value) {}
              static void acceptLegacySupplier(Legacy.Supplier<List<String>> supplier) {}
              static void acceptLegacyConsumer(Legacy.Consumer<List<@Nullable String>> consumer) {}
              static void acceptMarkedSupplier(Supplier<List<String>> supplier) {}
              static void acceptMarkedConsumer(Consumer<List<@Nullable String>> consumer) {}

              static Legacy.Supplier<List<String>> returnedExplicitlyUnmarked() {
                return (Test::nullableElements);
              }
              static Legacy.Supplier<List<String>> conditionalExplicitlyUnmarked(boolean choose) {
                return choose ? (Test::nullableElements) : ((Test::nullableElements));
              }
              static Legacy.Consumer<List<@Nullable String>> returnedImplicitlyUnmarked() {
                return (Test::consumeNonNullElements);
              }
              static Legacy.Consumer<List<@Nullable String>> conditionalImplicitlyUnmarked(
                  boolean choose) {
                return choose ? (Test::consumeNonNullElements) : ((Test::consumeNonNullElements));
              }
              static void unmarkedArguments() {
                acceptLegacySupplier(Test::nullableElements);
                acceptLegacyConsumer(Test::consumeNonNullElements);
              }
              static Supplier<List<String>> markedReturnStillChecksNestedReturn() {
                // BUG: Diagnostic contains: referenced method returns
                return Test::nullableElements;
              }
              static Consumer<List<@Nullable String>> markedReturnStillChecksNestedParameter() {
                // BUG: Diagnostic contains: parameter type of referenced method
                return Test::consumeNonNullElements;
              }
              static Supplier<List<String>> markedConditionalStillChecksNestedReturn(
                  boolean choose) {
                return choose
                    // BUG: Diagnostic contains: referenced method returns
                    ? Test::nullableElements
                    // BUG: Diagnostic contains: referenced method returns
                    : Test::nullableElements;
              }
              static Consumer<List<@Nullable String>> markedConditionalStillChecksNestedParameter(
                  boolean choose) {
                return choose
                    // BUG: Diagnostic contains: parameter type of referenced method
                    ? Test::consumeNonNullElements
                    // BUG: Diagnostic contains: parameter type of referenced method
                    : Test::consumeNonNullElements;
              }
              static void markedArgumentsStillCheckNestedTypes() {
                // BUG: Diagnostic contains: referenced method returns
                acceptMarkedSupplier(Test::nullableElements);
                // BUG: Diagnostic contains: parameter type of referenced method
                acceptMarkedConsumer(Test::consumeNonNullElements);
              }
              static Supplier<List<@Nullable String>> compatibleMarkedReturn() {
                return Test::nullableElements;
              }
              static Consumer<List<String>> compatibleMarkedParameter() {
                return Test::consumeNonNullElements;
              }
            }
            """)
        .doTest();
  }
}
