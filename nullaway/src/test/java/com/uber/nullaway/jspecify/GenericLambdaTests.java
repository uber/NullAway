package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import org.junit.Test;

public class GenericLambdaTests extends NullAwayTestsBase {

  @Test
  public void lambdaArgumentUsesAnnotatedTypeVar() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.function.Function;
            @NullMarked
            class Test {
              static class Foo<T extends @Nullable CharSequence> {
                T doSomething(Function<@Nullable T, T> f) {
                  return f.apply(null);
                }
              }
              void test(Foo<String> foo) {
                foo.doSomething(
                    t -> {
                      // BUG: Diagnostic contains: dereferenced expression t is @Nullable
                      return "length: " + t.length();
                    });
              }
            }
            """)
        .doTest();
  }

  @Test
  public void lambdaAssignedToFieldOrLocalUsesAnnotatedTypeVar() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.function.Function;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable CharSequence> {
                @Nullable Function<@Nullable T, T> field;
                void assignField(Box<String> box) {
                  box.field = t -> {
                    // BUG: Diagnostic contains: dereferenced expression t is @Nullable
                    t.length();
                    return t;
                  };
                }
                void assignLocal() {
                  Function<@Nullable T, T> local =
                      t -> {
                        // BUG: Diagnostic contains: dereferenced expression t is @Nullable
                        t.length();
                        return t;
                      };
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void lambdaReturnedUsesAnnotatedTypeVar() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.function.Function;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable CharSequence> {
                Function<@Nullable T, T> make() {
                  return t -> {
                    // BUG: Diagnostic contains: dereferenced expression t is @Nullable
                    t.length();
                    return t;
                  };
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void explicitlyAnnotatedLambdaArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.function.Function;
            @NullMarked
            class Test {
              static class Foo<T extends @Nullable CharSequence> {
                T doSomething(Function<@Nullable T, T> f) {
                  return f.apply(null);
                }
              }
              void test(Foo<String> foo) {
                foo.doSomething(
                    // BUG: Diagnostic contains: parameter t is @NonNull, but parameter in functional interface method
                    (String t) -> {
                      return "length: " + t.length();
                    });
              }
            }
            """)
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList("-XepOpt:NullAway:AnnotatedPackages=com.uber")));
  }
}
