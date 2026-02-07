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
                // BUG: Diagnostic contains: incompatible types:
                return new Baz<>(new Bar<>(Foo.makeNullableStr()));
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
