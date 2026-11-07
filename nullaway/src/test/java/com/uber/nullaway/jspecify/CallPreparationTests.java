package com.uber.nullaway.jspecify;

import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import org.junit.Test;

/** Preserves receiver, witness, constructor, and model behavior across shared call preparation. */
public class CallPreparationTests extends NullAwayTestsBase {
  @Test
  public void preparationKeepsExistingCallTyping() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of("-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import java.util.Objects;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T id(T value) { return value; }
              static class Box<T extends @Nullable Object> {
                final T value;
                Box(T value) { this.value = value; }
                T get() { return value; }
                <U extends @Nullable Object> U identity(U value) { return value; }
              }
              void nullableReceiverRetainsAnnotations(Box<@Nullable String> box) {
                // BUG: Diagnostic contains: dereferenced expression
                id(box.get()).length();
                // BUG: Diagnostic contains: dereferenced expression
                box.identity(box.get()).length();
              }
              void nonNullReceiverRetainsAnnotations(Box<String> box) {
                id(box.get()).length();
                box.identity(box.get()).length();
              }
              void explicitWitnessesRetainAnnotations() {
                Test.<String>id("value").length();
                // BUG: Diagnostic contains: dereferenced expression
                Test.<@Nullable String>id(null).length();
              }
              void diamondRetainsScalarInference() {
                Box<@Nullable String> box = new Box<>(null);
                // BUG: Diagnostic contains: dereferenced expression
                box.get().length();
              }
              void fixedModelsStillApply(@Nullable String value) {
                Objects.requireNonNull(value).length();
              }
            }
            """)
        .doTest();
  }
}
