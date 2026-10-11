package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import org.junit.Test;

/** Constructor call descriptions preserve fixed annotations and declaration bounds across uses. */
public class ConstructorCallInferenceTests extends NullAwayTestsBase {

  @Test
  public void fixedClassArgumentsSurviveNestedMethodAndLambdaCalls() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Foo<E extends @Nullable Object> {
                <U> Foo(U value) {}
              }
              static <X extends @Nullable Object> X id(X value) { return value; }
              static <P, R> R map(P value, Function<P, R> function) {
                return function.apply(value);
              }
              void test(String value) {
                Foo<@Nullable String> direct = id(new Foo<@Nullable String>(value));
                Foo<@Nullable String> nested = id(id(new Foo<@Nullable String>(value)));
                Foo<@Nullable String> explicit = id(new <String>Foo<@Nullable String>(value));
                Foo<@Nullable String> lambda = map(value, parameter -> {
                  var local = id(new Foo<@Nullable String>(parameter));
                  return local;
                });
                // BUG: Diagnostic contains: incompatible types
                Foo<String> invalid = id(new Foo<@Nullable String>(value));
                // BUG: Diagnostic contains: incompatible types
                Foo<@Nullable String> invalidReverse = id(new Foo<String>(value));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void qualifiedInnerConstructorsKeepAnnotatedEnclosingType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Outer<E extends @Nullable Object> {
                class Inner<F extends @Nullable Object> {
                  <U> Inner(U value) {}
                }
              }
              static <X extends @Nullable Object> X id(X value) { return value; }
              void test(Outer<@Nullable String> outer) {
                Outer<@Nullable String>.Inner<String> direct = id(outer.new Inner<String>("x"));
                Outer<@Nullable String>.Inner<@Nullable String> nested =
                    id(id(outer.new Inner<@Nullable String>("x")));
                // BUG: Diagnostic contains: incompatible types
                Outer<String>.Inner<String> invalid = id(outer.new Inner<String>("x"));
              }
            }
            """)
        .doTest();
  }

  /** Creates a helper with full-type inference enabled in nullness-marked source. */
  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(List.of("-XepOpt:NullAway:OnlyNullMarked=true")));
  }
}
