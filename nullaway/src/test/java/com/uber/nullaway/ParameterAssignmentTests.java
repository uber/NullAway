package com.uber.nullaway;

import com.google.errorprone.BugCheckerRefactoringTestHelper;
import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import org.junit.Test;

/** Tests for checking a parameter's declared nullness when it is reassigned. */
public class ParameterAssignmentTests extends NullAwayTestsBase {

  @Test
  public void parameterAssignments() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            class Test {
              void rejectsNull(Object parameter) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull parameter
                parameter = null;
              }
              void rejectsNullable(Object parameter, @Nullable Object value) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull parameter
                parameter = value;
              }
              void acceptsNonNull(Object parameter) {
                parameter = new Object();
                parameter.toString();
              }
              void acceptsRefinedValue(Object parameter, @Nullable Object value) {
                if (value != null) {
                  parameter = value;
                }
              }
              void nullableParameterKeepsDeclaredNullness(@Nullable Object parameter) {
                parameter = new Object();
                parameter.toString();
                parameter = null;
                // BUG: Diagnostic contains: dereferenced expression
                parameter.toString();
              }
              void localNullnessIsInferred(@Nullable Object value) {
                Object local = new Object();
                local = value;
                local = null;
              }
              void primitiveAssignmentOnlyChecksUnboxing(int parameter, @Nullable Integer value) {
                // BUG: Diagnostic contains: unboxing
                parameter = value;
              }
              void primitiveAssignmentAcceptsPrimitive(int parameter) {
                parameter = 1;
              }
              Test(Object parameter) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull parameter
                parameter = null;
              }
              Test(@Nullable Object parameter, int unused) {
                parameter = null;
              }
              @SuppressWarnings("NullAway")
              void suppressedAssignment(Object parameter) {
                parameter = null;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void lambdaParameterAssignments() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            class Test {
              interface NonNullConsumer { void accept(Object value); }
              interface NullableConsumer { void accept(@Nullable Object value); }

              NonNullConsumer implicitNonNull() {
                return parameter -> {
                  // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull parameter
                  parameter = null;
                };
              }
              NonNullConsumer explicitNonNull() {
                return (Object parameter) -> {
                  // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull parameter
                  parameter = null;
                };
              }
              NullableConsumer implicitNullable() {
                return parameter -> {
                  parameter = new Object();
                  parameter.toString();
                  parameter = null;
                  // BUG: Diagnostic contains: dereferenced expression
                  parameter.toString();
                };
              }
              NullableConsumer explicitNullable() {
                return (@Nullable Object parameter) -> {
                  parameter = null;
                };
              }
              NonNullConsumer nullableParameterOnNonNullTarget() {
                return (@Nullable Object parameter) -> {
                  parameter = null;
                };
              }
            }
            """)
        .doTest();
  }

  @Test
  public void jspecifyParameterAssignments() {
    jspecifyHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            class Test {
              static <T extends @Nullable Object> T identity(T value) { return value; }

              void explicitNonNull(@NonNull Object parameter) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull parameter
                parameter = null;
              }
              void nullableGenericResult(Object parameter, @Nullable Object value) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull parameter
                parameter = Test.<@Nullable Object>identity(value);
              }
              void inferredGenericResult(Object parameter, @Nullable Object value) {
                // BUG: Diagnostic contains: inference failure
                parameter = identity(value);
              }
              void nullableParameterAcceptsGenericResult(
                  @Nullable Object parameter, @Nullable Object value) {
                parameter = identity(value);
              }
              void localAcceptsGenericResult(@Nullable Object value) {
                Object local = identity(value);
                local = identity(value);
              }
              <T extends @Nullable Object> void typeVariableRejectsNull(T parameter) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull parameter
                parameter = null;
              }
              <T extends @Nullable Object> void nullableTypeVariableAcceptsNull(
                  @Nullable T parameter) {
                parameter = null;
              }
              interface Box<T extends @Nullable Object> {}
              void nullableParameterStillChecksNestedNullness(
                  @Nullable Box<Object> parameter, Box<@Nullable Object> value) {
                // BUG: Diagnostic contains: incompatible types
                parameter = value;
              }
              interface Consumer<T extends @Nullable Object> { void accept(T value); }
              Consumer<@Nullable Object> nullableLambdaParameter() {
                return parameter -> { parameter = null; };
              }
              Consumer<Object> nonNullLambdaParameter() {
                return parameter -> {
                  // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull parameter
                  parameter = null;
                };
              }
              Consumer<Object> explicitlyNonNullLambdaParameter() {
                return (@NonNull Object parameter) -> {
                  // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull parameter
                  parameter = null;
                };
              }
              Consumer<@Nullable Object> explicitlyNullableLambdaParameter() {
                return (@Nullable Object parameter) -> { parameter = null; };
              }
              interface ArrayConsumer { void accept(String @Nullable [] value); }
              ArrayConsumer explicitlyNullableArrayLambdaParameter() {
                return (String @Nullable [] parameter) -> { parameter = null; };
              }
              interface NullableElementConsumer { void accept(@Nullable String[] value); }
              NullableElementConsumer nullableElementsDoNotMakeArrayNullable() {
                return (@Nullable String[] parameter) -> {
                  // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull parameter
                  parameter = null;
                };
              }
            }
            """)
        .doTest();
  }

  @Test
  public void varargsParameterAssignments() {
    jspecifyHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            class Test {
              void nonNullArray(String... parameter) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull parameter
                parameter = null;
              }
              void nullableElements(@Nullable String... parameter) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull parameter
                parameter = null;
              }
              void explicitlyNonNullArray(String @NonNull ... parameter) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull parameter
                parameter = null;
              }
              void nullableArray(String @Nullable ... parameter) {
                parameter = new String[0];
                parameter = null;
              }
              void primitiveElements(int... parameter) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull parameter
                parameter = null;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void methodNullMarkedScope() {
    jspecifyHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NullUnmarked;
            @NullUnmarked
            class Test {
              @NullMarked
              void markedMethod(Object parameter) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull parameter
                parameter = null;
              }
              void unmarkedMethod(Object parameter) {
                parameter = null;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericParameterAssignmentsInMarkedMethods() {
    jspecifyHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NullUnmarked;
            import org.jspecify.annotations.Nullable;
            @NullUnmarked
            class Test {
              @NullMarked
              static class Box<T extends @Nullable Object> {}

              @NullMarked
              void rejectsNestedMismatch(Box<String> target, Box<@Nullable String> value) {
                // BUG: Diagnostic contains: incompatible types
                target = value;
              }
              @NullMarked
              void nullableParameterStillRejectsNestedMismatch(
                  @Nullable Box<String> target, Box<@Nullable String> value) {
                // BUG: Diagnostic contains: incompatible types
                target = value;
              }
              @NullMarked
              void acceptsCompatibleAssignment(
                  Box<@Nullable String> target, Box<@Nullable String> value) {
                target = value;
              }
              void unmarkedMethodIsNotChecked(Box<String> target, Box<@Nullable String> value) {
                target = value;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void suggestsCastToNonNullForParameterAssignment() {
    BugCheckerRefactoringTestHelper.newInstance(NullAway.class, getClass())
        .setArgs(
            "-XepOpt:NullAway:AnnotatedPackages=com.uber",
            "-XepOpt:NullAway:SuggestSuppressions=true",
            "-XepOpt:NullAway:CastToNonNullMethod=com.uber.Test.castToNonNull")
        .addInputLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            class Test {
              @Nullable Object value;
              static <T> T castToNonNull(@Nullable T value) { throw new UnsupportedOperationException(); }
              void reassign(Object parameter) {
                parameter = value;
              }
            }
            """)
        .addOutputLines(
            "Test.java",
            """
            package com.uber;
            import static com.uber.Test.castToNonNull;
            import javax.annotation.Nullable;
            class Test {
              @Nullable Object value;
              static <T> T castToNonNull(@Nullable T value) { throw new UnsupportedOperationException(); }
              void reassign(Object parameter) {
                parameter = castToNonNull(value);
              }
            }
            """)
        .doTest();
  }

  /** Returns a helper that checks parameter assignments with JSpecify type-use annotations. */
  private CompilationTestHelper jspecifyHelper() {
    return makeTestHelperWithArgs(JSpecifyJavacConfig.withJSpecifyModeArgs(defaultArgs()));
  }
}
