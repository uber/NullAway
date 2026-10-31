package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import org.junit.Test;

/** Poly calls respect discarded lambda results and annotated implicit enclosing instances. */
public class PolyCallContextRegressionTests extends NullAwayTestsBase {

  @Test
  public void voidExpressionLambdaDoesNotConstrainTheDiscardedResult() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.concurrent.CompletableFuture;
            import java.util.function.Consumer;
            import java.util.function.Supplier;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <X extends @Nullable Object> X idn(X value) { return value; }
              interface Processor<T extends @Nullable Object> { T process(); }
              static <T extends @Nullable Object> T invoke(Processor<T> processor) {
                return processor.process();
              }
              static @Nullable Void release() { return null; }
              void test(@Nullable String value) {
                Runnable expression = () -> idn(value);
                Runnable block = () -> { idn(value); };
                Runnable earlyReturn = () -> { if (value == null) { return; } idn(value); };
                Runnable literal = () -> idn(null);
                Consumer<@Nullable String> parameter = input -> idn(input);
                Supplier<@Nullable String> kept = () -> idn(value);
                Runnable parenthesized = () -> idn((value));
                CompletableFuture<@Nullable Void> async =
                    CompletableFuture.runAsync(() -> invoke(() -> release()));
                Supplier<String> invalid =
                    // BUG: Diagnostic contains: inference failure
                    () -> idn(value);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void implicitInheritedOuterInstancePreservesAnnotationsForBothConstructorForms() {
    outerHelper()
        .addSourceLines(
            "Sub.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Sub extends Outer<@Nullable String> {
              void test() {
                In<String> diamond = new In<>(null, null);
                In<String> fixed = new In<String>(null, null);
                In<String> witness = new <@Nullable String>In<String>(null, null);
                In<@Nullable String> nullable = new In<>(null, "x");
              }
            }
            @NullMarked
            class NonNullSub extends Outer<String> {
              void test() {
                In<String> diamond = new In<>("x", null);
                In<String> fixed = new In<String>("x", null);
                // BUG: Diagnostic contains: passing @Nullable parameter
                In<String> invalidDiamond = new In<>(null, "x");
                // BUG: Diagnostic contains: passing @Nullable parameter
                In<String> invalidFixed = new In<String>(null, "x");
              }
            }
            """)
        .doTest();
  }

  @Test
  public void anonymousInnerDiamondRetainsItsIdentityAndImplicitOuterView() {
    outerHelper()
        .addSourceLines(
            "Sub.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Sub extends Outer<@Nullable String> {
              static void take(Object value) {}
              void test() {
                In<String> diamond = new In<>("x", "y") {
                  private void call() { take(this); }
                };
                In<String> fixed = new In<String>(null, null) {
                  private void call() { take(this); }
                };
              }
            }
            """)
        .doTest();
  }

  /** Creates shared inner-constructor declarations in their own compilation unit. */
  private CompilationTestHelper outerHelper() {
    return makeHelper()
        .addSourceLines(
            "Outer.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Outer<E extends @Nullable Object> {
              class In<F extends @Nullable Object> {
                <U extends @Nullable Object> In(E outer, U value) {}
              }
            }
            """);
  }

  /** Creates a helper with the same JSpecify configuration as the reported regressions. */
  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(List.of("-XepOpt:NullAway:OnlyNullMarked=true")));
  }
}
