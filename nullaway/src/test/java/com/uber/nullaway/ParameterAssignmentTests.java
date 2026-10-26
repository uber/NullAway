package com.uber.nullaway;

import com.uber.nullaway.generics.JSpecifyJavacConfig;
import org.junit.Test;

/** Tests that parameter nullness is flow sensitive after an assignment. */
public class ParameterAssignmentTests extends NullAwayTestsBase {

  @Test
  public void regularMethodParameters() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              @NonNull String field = "initial";
              void nullableOverwriteIsAllowed(@NonNull String value, @Nullable String replacement) {
                value.length();
                value = replacement;
              }
              void dereferenceAfterNullableOverwrite(@NonNull String value, @Nullable String replacement) {
                value.length();
                value = replacement;
                // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                value.length();
              }
              void assignToNonNullFieldAfterNullableOverwrite(@NonNull String value, @Nullable String replacement) {
                field = value;
                value = replacement;
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                field = value;
              }
              void dereferenceAfterNullOverwrite(@NonNull String value, @Nullable String replacement) {
                value.length();
                value = null;
                // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                value.length();
              }
              void useAfterNullCheck(@NonNull String value, @Nullable String replacement) {
                value = replacement;
                if (value != null) {
                  value.length();
                  field = value;
                }
              }
              void useAfterNonNullOverwrite(@NonNull String value, @Nullable String replacement) {
                value = replacement;
                value = "restored";
                value.length();
                field = value;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void explicitlyTypedLambdaParameters() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              @NonNull String field = "initial";
              interface Action {
                void accept(@NonNull String value);
              }
              void nullableOverwriteIsAllowed(@Nullable String replacement) {
                Action action = (@NonNull String value) -> {
                  value.length();
                  value = replacement;
                };
              }
              void dereferenceAfterNullableOverwrite(@Nullable String replacement) {
                Action action = (@NonNull String value) -> {
                  value.length();
                  value = replacement;
                  // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                  value.length();
                };
              }
              void assignToNonNullFieldAfterNullableOverwrite(@Nullable String replacement) {
                Action action = (@NonNull String value) -> {
                  field = value;
                  value = replacement;
                  // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                  field = value;
                };
              }
              void dereferenceAfterNullOverwrite(@Nullable String replacement) {
                Action action = (@NonNull String value) -> {
                  value.length();
                  value = null;
                  // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                  value.length();
                };
              }
              void useAfterNullCheck(@Nullable String replacement) {
                Action action = (@NonNull String value) -> {
                  value = replacement;
                  if (value != null) {
                    value.length();
                    field = value;
                  }
                };
              }
              void useAfterNonNullOverwrite(@Nullable String replacement) {
                Action action = (@NonNull String value) -> {
                  value = replacement;
                  value = "restored";
                  value.length();
                  field = value;
                };
              }
            }
            """)
        .doTest();
  }

  @Test
  public void implicitlyTypedLambdaParameters() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              @NonNull String field = "initial";
              interface Action {
                void accept(@NonNull String value);
              }
              void nullableOverwriteIsAllowed(@Nullable String replacement) {
                Action action = value -> {
                  value.length();
                  value = replacement;
                };
              }
              void dereferenceAfterNullableOverwrite(@Nullable String replacement) {
                Action action = value -> {
                  value.length();
                  value = replacement;
                  // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                  value.length();
                };
              }
              void assignToNonNullFieldAfterNullableOverwrite(@Nullable String replacement) {
                Action action = value -> {
                  field = value;
                  value = replacement;
                  // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                  field = value;
                };
              }
              void dereferenceAfterNullOverwrite(@Nullable String replacement) {
                Action action = value -> {
                  value.length();
                  value = null;
                  // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                  value.length();
                };
              }
              void useAfterNullCheck(@Nullable String replacement) {
                Action action = value -> {
                  value = replacement;
                  if (value != null) {
                    value.length();
                    field = value;
                  }
                };
              }
              void useAfterNonNullOverwrite(@Nullable String replacement) {
                Action action = value -> {
                  value = replacement;
                  value = "restored";
                  value.length();
                  field = value;
                };
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferredLambdaParameters() {
    makeTestHelperWithArgs(JSpecifyJavacConfig.withJSpecifyModeArgs(defaultArgs()))
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              @NonNull String field = "initial";
              interface Action<T extends @Nullable Object> {
                void accept(T value);
              }
              static class Box<T extends @Nullable Object> {}
              // The invariant Box argument fixes T's nullness; the upper bound permits null.
              static <T extends @Nullable Object> void apply(Box<T> box, Action<T> action) {}
              void nullableOverwriteIsAllowed(Box<String> box, @Nullable String replacement) {
                apply(box, value -> {
                  value.length();
                  value = replacement;
                });
              }
              void dereferenceAfterNullableOverwrite(Box<String> box, @Nullable String replacement) {
                apply(box, value -> {
                  value.length();
                  value = replacement;
                  // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                  value.length();
                });
              }
              void assignToNonNullFieldAfterNullableOverwrite(Box<String> box, @Nullable String replacement) {
                apply(box, value -> {
                  field = value;
                  value = replacement;
                  // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                  field = value;
                });
              }
              void dereferenceAfterNullOverwrite(Box<String> box, @Nullable String replacement) {
                apply(box, value -> {
                  value.length();
                  value = null;
                  // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                  value.length();
                });
              }
              void useAfterNullCheck(Box<String> box, @Nullable String replacement) {
                apply(box, value -> {
                  value = replacement;
                  if (value != null) {
                    value.length();
                    field = value;
                  }
                });
              }
              void useAfterNonNullOverwrite(Box<String> box, @Nullable String replacement) {
                apply(box, value -> {
                  value = replacement;
                  value = "restored";
                  value.length();
                  field = value;
                });
              }
              void nullableInferenceControl(Box<@Nullable String> box) {
                apply(box, value -> {
                  // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                  value.length();
                });
              }
            }
            """)
        .doTest();
  }
}
