package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import org.junit.Test;

public class GenericDiamondTests extends NullAwayTestsBase {

  @Test
  public void assignToLocal() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
              static class Foo<T extends @Nullable Object> {
                static Foo<@Nullable Void> make() {
                  throw new RuntimeException();
                }
                static Foo<@Nullable String> makeNullableStr() {
                  throw new RuntimeException();
                }
              }
              static class Bar<T extends @Nullable Object> {
                Bar(Foo<T> foo) {
                }
              }
              void testNegative() {
                // should be legal
                Bar<@Nullable Void> b = new Bar<>(Foo.make());
              }
              void testPositive() {
                // BUG: Diagnostic contains: incompatible types: Foo<@Nullable String> cannot be converted to Foo<String>
                Bar<String> b = new Bar<>(Foo.makeNullableStr());
                // BUG: Diagnostic contains: incompatible types: Foo<@Nullable Void> cannot be converted to Foo<Void>
                Bar<Void> b2 = new Bar<>(Foo.make());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void varLocalDoesNotProvideTargetTypeForDiamondInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
              static class Box<T extends @Nullable Object> {
                private final T value;
                Box(T value) {
                  this.value = value;
                }
                T get() {
                  return value;
                }
              }
              void test() {
                // NOTE: reporting a warning on the next line is a current limitation
                // of NullAway; there should be no warning. See https://github.com/uber/NullAway/issues/1633.
                // BUG: Diagnostic contains: passing @Nullable parameter
                var inferredFromInitializer = new Box<>(null);
                // BUG: Diagnostic contains: passing @Nullable parameter
                Box<String> explicitNonNullTarget = new Box<>(null);
                Box<@Nullable String> explicitNullableTarget = new Box<>(null);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void returnDiamond() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
              static class Foo<T extends @Nullable Object> {
                static Foo<@Nullable Void> make() {
                  throw new RuntimeException();
                }
                static Foo<@Nullable String> makeNullableStr() {
                  throw new RuntimeException();
                }
              }
              static class Bar<T extends @Nullable Object> {
                Bar(Foo<T> foo) {
                }
              }
              Bar<@Nullable Void> testNegative() {
                // should be legal
                return new Bar<>(Foo.make());
              }
              Bar<String> testPositive() {
                // BUG: Diagnostic contains: incompatible types: Foo<@Nullable String> cannot be converted to Foo<String>
                return new Bar<>(Foo.makeNullableStr());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void paramPassing() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
              static class Foo<T extends @Nullable Object> {
                static Foo<@Nullable Void> make() {
                  throw new RuntimeException();
                }
                static Foo<@Nullable String> makeNullableStr() {
                  throw new RuntimeException();
                }
              }
              static class Bar<T extends @Nullable Object> {
                Bar(Foo<T> foo) {
                }
              }
              static void takeNullableVoid(Bar<@Nullable Void> b) {}
              static void takeStr(Bar<String> b) {}
              void testNegative() {
                // should be legal
                takeNullableVoid(new Bar<>(Foo.make()));
              }
              void testPositive() {
                // BUG: Diagnostic contains: incompatible types: Foo<@Nullable String> cannot be converted to Foo<String>
                takeStr(new Bar<>(Foo.makeNullableStr()));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void parenthesizedDiamond() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
              static class Foo<T extends @Nullable Object> {
                static Foo<@Nullable Void> make() {
                  throw new RuntimeException();
                }
                static Foo<@Nullable String> makeNullableStr() {
                  throw new RuntimeException();
                }
              }
              static class Bar<T extends @Nullable Object> {
                Bar(Foo<T> foo) {
                }
              }
              Bar<@Nullable Void> testNegative() {
                return (new Bar<>(Foo.make()));
              }
              Bar<String> testPositive() {
                // BUG: Diagnostic contains: incompatible types: Foo<@Nullable String> cannot be converted to Foo<String>
                return (new Bar<>(Foo.makeNullableStr()));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nestedDiamondConstructors() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
              static class Foo<T extends @Nullable Object> {
                static Foo<@Nullable Void> make() {
                  throw new RuntimeException();
                }
                static Foo<@Nullable String> makeNullableStr() {
                  throw new RuntimeException();
                }
              }
              static class Bar<T extends @Nullable Object> {
                Bar(Foo<T> foo) {
                }
              }
              static class Baz<T extends @Nullable Object> {
                Baz(Bar<T> bar) {
                }
              }
              Baz<@Nullable Void> testNegative() {
                return new Baz<>(new Bar<>(Foo.make()));
              }
              Baz<String> testPositive() {
                // BUG: Diagnostic contains: incompatible types: Foo<@Nullable String> cannot be converted to Foo<String>
                return new Baz<>(new Bar<>(Foo.makeNullableStr()));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void diamondSubclassPassedToGenericMethod() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            import java.util.List;
            @NullMarked
            public class Test {
              interface Foo<T extends @Nullable Object> {
              }
              static class FooImpl<T> implements Foo<@Nullable T> {
                FooImpl(Class<T> cls) {
                }
              }
              static <U extends @Nullable Object> List<U> make(Foo<U> foo) {
                throw new RuntimeException();
              }
              static <V> List<@Nullable V> test(Class<V> cls) {
                return make(new FooImpl<>(cls));
              }
            }
            """)
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList(
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:WarnOnGenericInferenceFailure=true")));
  }
}
