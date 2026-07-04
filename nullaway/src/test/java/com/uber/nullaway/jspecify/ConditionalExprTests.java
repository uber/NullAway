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
  public void genericMethodCallInOneArm() {
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
  public void genericMethodCallInBothArms() {
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
  public void nestedConditionalExpressions() {
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
  public void conditionalAsMethodArgument() {
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
  public void varConditionalDoesNotProvideTargetTypeForGenericMethodInference() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            package com.example;
            import org.jspecify.annotations.*;
            @NullMarked
            final class Test {
              interface Box<T extends @Nullable Object> {
                T get();
              }
              static <T extends @Nullable Object> Box<T> box(T t) {
                throw new RuntimeException();
              }
              void test(boolean flag) {
                var inferredFromInitializer = flag ? box(null) : box("fallback");
                Box<@Nullable String> explicitNullableTarget =
                    flag ? box(null) : box("fallback");
                Box<String> explicitNonNullTarget =
                    // BUG: Diagnostic contains: passing @Nullable parameter
                    flag ? box(null) : box("fallback");
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
  public void varConditionalDoesNotProvideTargetTypeForDiamondInference() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            """
            package com.example;
            import org.jspecify.annotations.*;
            @NullMarked
            final class Test {
              static final class Box<T extends @Nullable Object> {
                private final T value;
                Box(T value) {
                  this.value = value;
                }
                T get() {
                  return value;
                }
              }
              void test(boolean flag) {
                // BUG: Diagnostic contains: passing @Nullable parameter
                var inferredFromInitializer = flag ? new Box<>(null) : new Box<>("fallback");
                Box<@Nullable String> explicitNullableTarget =
                    flag ? new Box<>(null) : new Box<>("fallback");
                Box<String> explicitNonNullTarget =
                    // BUG: Diagnostic contains: passing @Nullable parameter
                    flag ? new Box<>(null) : new Box<>("fallback");
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

  @Test
  public void conditionalGenericMethodInferenceWithDataflow() {
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
              void test(boolean flag, @Nullable String s) {
                if (s != null) {
                  nonNullField = flag ? id(s) : id("fallback");
                }
                nullableField = flag ? id(s) : id("fallback");
                // BUG: Diagnostic contains: passing @Nullable parameter
                nonNullField = flag ? id(s) : id("fallback");
              }
            }
            """)
        .doTest();
  }

  @Test
  public void conditionalGenericTypeArgumentInferenceWithDataflow() {
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
              void test(boolean flag, @Nullable String s) {
                if (s != null) {
                  Box<String> nonNullBox = flag ? box(s) : box("fallback");
                }
                Box<@Nullable String> nullableBox = flag ? box(s) : box("fallback");
                // BUG: Diagnostic contains: passing @Nullable parameter
                Box<String> bad = flag ? box(s) : box("fallback");
              }
            }
            """)
        .doTest();
  }

  @Test
  public void conditionalGenericMethodInferenceDataflowAndLoops() {
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
              void testLoop1(boolean flag) {
                String s = "hello";
                while (true) {
                  String t = flag ? id(s) : id("fallback");
                  // BUG: Diagnostic contains: dereferenced expression 't' is @Nullable
                  t.hashCode();
                  s = null;
                }
              }
              void testLoop2(boolean flag) {
                String t = "hello";
                while (true) {
                  // BUG: Diagnostic contains: dereferenced expression 't' is @Nullable
                  t.hashCode();
                  String s = null;
                  t = flag ? id(s) : id("fallback");
                }
              }
              void testLoop3(boolean flag) {
                String t = "hello";
                String s = "hello";
                t.hashCode();
                int i = 2;
                while (i > 0) {
                  t = flag ? id(s) : id("fallback");
                  s = null;
                  i--;
                }
                // BUG: Diagnostic contains: dereferenced expression 't' is @Nullable
                t.hashCode();
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
