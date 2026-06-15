package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import org.junit.Test;

public class ConditionalExprTests extends NullAwayTestsBase {

  @Test
  public void wildcard() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            package com.example;
            import org.jspecify.annotations.*;
            @NullMarked
            final class Test {
              interface ClassLike<T extends @Nullable Object> {
                String name();
              }
              static String guardedNullableWildcard(@Nullable ClassLike<?> maybeClass) {
                return (maybeClass != null ? maybeClass : fallback()).name();
              }
              static ClassLike<?> fallback() {
                throw new RuntimeException();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericMethodConditionalArmWithFieldAssignmentContext() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            package com.example;
            import org.jspecify.annotations.*;
            @NullMarked
            final class Test {
              String nonNullField = "hello";
              @Nullable String nullableField;
              static <T extends @Nullable Object> T id(T t) {
                return t;
              }
              void test(boolean flag) {
                nullableField = flag ? id(null) : "fallback";
                // BUG: Diagnostic contains: passing @Nullable parameter
                nonNullField = flag ? id(null) : "fallback";
                nonNullField = flag ? id("value") : "fallback";
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericMethodConditionalBothArmsWithFieldAssignmentContext() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            package com.example;
            import org.jspecify.annotations.*;
            @NullMarked
            final class Test {
              String nonNullField = "hello";
              @Nullable String nullableField;
              static <T extends @Nullable Object> T id(T t) {
                return t;
              }
              void test(boolean flag) {
                nullableField = flag ? id(null) : id("fallback");
                // BUG: Diagnostic contains: passing @Nullable parameter
                nonNullField = flag ? id(null) : id("fallback");
                nonNullField = flag ? id("value") : id("fallback");
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nestedGenericMethodConditionalWithFieldAssignmentContext() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            package com.example;
            import org.jspecify.annotations.*;
            @NullMarked
            final class Test {
              String nonNullField = "hello";
              @Nullable String nullableField;
              static <T extends @Nullable Object> T id(T t) {
                return t;
              }
              void test(boolean first, boolean second) {
                nullableField = first ? id(null) : second ? id("value") : "fallback";
                // BUG: Diagnostic contains: passing @Nullable parameter
                nonNullField = first ? id(null) : second ? id("value") : "fallback";
                nonNullField = first ? id("first") : second ? id("second") : "fallback";
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericMethodConditionalAsMethodArgument() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            package com.example;
            import org.jspecify.annotations.*;
            @NullMarked
            final class Test {
              static <T extends @Nullable Object> T id(T t) {
                return t;
              }
              static void takesNonNull(String s) {}
              static void takesNullable(@Nullable String s) {}
              void test(boolean flag) {
                takesNullable(flag ? id(null) : "fallback");
                // BUG: Diagnostic contains: passing @Nullable parameter
                takesNonNull(flag ? id(null) : "fallback");
                takesNonNull(flag ? id("value") : "fallback");
              }
            }
            """)
        .doTest();
  }

  @Test
  public void conditionalInfersGenericTypeArguments() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            package com.example;
            import org.jspecify.annotations.*;
            @NullMarked
            final class Test {
              static final class Box<T extends @Nullable Object> {}
              static <T extends @Nullable Object> Box<T> box(T t) {
                return new Box<T>();
              }
              void test(boolean flag) {
                Box<@Nullable String> nullableBox = flag ? box(null) : box("fallback");
                // BUG: Diagnostic contains: passing @Nullable parameter
                Box<String> nonNullBox = flag ? box(null) : box("fallback");
                Box<String> nonNullBoxOk = flag ? box("value") : box("fallback");
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nestedConditionalInfersGenericTypeArguments() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            package com.example;
            import org.jspecify.annotations.*;
            @NullMarked
            final class Test {
              static final class Box<T extends @Nullable Object> {}
              static <T extends @Nullable Object> Box<T> box(T t) {
                return new Box<T>();
              }
              void test(boolean first, boolean second) {
                Box<@Nullable String> nullableBox =
                    first ? box(null) : second ? box("value") : box("fallback");
                Box<String> nonNullBox =
                    // BUG: Diagnostic contains: passing @Nullable parameter
                    first ? box(null) : second ? box("value") : box("fallback");
                Box<String> nonNullBoxOk =
                    first ? box("first") : second ? box("second") : box("fallback");
              }
            }
            """)
        .doTest();
  }

  @Test
  public void conditionalInfersDiamondTypeArguments() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            package com.example;
            import org.jspecify.annotations.*;
            @NullMarked
            final class Test {
              static final class Box<T extends @Nullable Object> {
                Box(T t) {}
              }
              void test(boolean flag) {
                Box<@Nullable String> nullableBox =
                    flag ? new Box<>(null) : new Box<>("fallback");
                Box<String> nonNullBox =
                    // BUG: Diagnostic contains: passing @Nullable parameter
                    flag ? new Box<>(null) : new Box<>("fallback");
                Box<String> nonNullBoxOk =
                    flag ? new Box<>("value") : new Box<>("fallback");
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nestedConditionalInfersDiamondTypeArguments() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            package com.example;
            import org.jspecify.annotations.*;
            @NullMarked
            final class Test {
              static final class Box<T extends @Nullable Object> {
                Box(T t) {}
              }
              void test(boolean first, boolean second) {
                Box<@Nullable String> nullableBox =
                    first
                        ? new Box<>(null)
                        : second ? new Box<>("value") : new Box<>("fallback");
                Box<String> nonNullBox =
                    first
                        // BUG: Diagnostic contains: passing @Nullable parameter
                        ? new Box<>(null)
                        : second ? new Box<>("value") : new Box<>("fallback");
                Box<String> nonNullBoxOk =
                    first
                        ? new Box<>("first")
                        : second ? new Box<>("second") : new Box<>("fallback");
              }
            }
            """)
        .doTest();
  }

  private CompilationTestHelper makeHelperWithInferenceFailureWarning() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            List.of(
                "-XepOpt:NullAway:OnlyNullMarked=true",
                "-XepOpt:NullAway:WarnOnGenericInferenceFailure=true")));
  }
}
