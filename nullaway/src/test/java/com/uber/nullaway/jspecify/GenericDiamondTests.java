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

  @Test
  public void inferFromReturn() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
              interface Supplier<T extends @Nullable Object> { public T get(); }
              public interface LazyValue<T extends @Nullable Object> extends Supplier<T> {}
              record NullableLazyValue<T extends @Nullable Object>(Supplier<T> supplier) implements LazyValue<T> {
                public T get() {
                  return supplier.get();
                }
              }
              static <K> LazyValue<@Nullable K> nullable(Supplier<@Nullable K> supplier) {
                return new NullableLazyValue<>(supplier);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferFromParams() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
              static class Foo<T extends @Nullable Object> {}
              static class Bar<T extends @Nullable Object> {
                Bar(Foo<T> foo1, Foo<T> foo2) {
                }
              }
              static void testNegative1(Foo<String> f1, Foo<String> f2) {
                new Bar<>(f1, f2);
              }
              static void testNegative2(Foo<@Nullable String> f1, Foo<@Nullable String> f2) {
                new Bar<>(f1, f2);
              }
              static void testPositive(Foo<String> f1, Foo<@Nullable String> f2) {
                // BUG: Diagnostic contains: incompatible types
                new Bar<>(f1, f2);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericMethodCallWithDiamondConstructorParameter() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
              interface Foo<T extends @Nullable Object> {}
              static class FooImpl<T extends @Nullable Object> implements Foo<T> {
                FooImpl(Foo<T> value) {}
              }
              static Foo<@Nullable String> makeNullableFoo() {
                throw new RuntimeException();
              }
              static <U extends @Nullable Object> Foo<U> id(Foo<U> foo) {
                throw new RuntimeException();
              }
              static void takeFooString(Foo<String> foo) {}
              static void takeFooNullableString(Foo<@Nullable String> foo) {}
              static void testNegative() {
                takeFooNullableString(id(new FooImpl<>(makeNullableFoo())));
              }
              static void testPositive() {
                // BUG: Diagnostic contains: incompatible types
                takeFooString(id(new FooImpl<>(makeNullableFoo())));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void diamondConstructorWithGenericMethodCallParameter() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
              interface Foo<T extends @Nullable Object> {}
              static class Box<T extends @Nullable Object> {
                Box(Foo<T> foo) {}
              }
              static <U extends @Nullable Object> Foo<U> id(Foo<U> foo) {
                throw new RuntimeException();
              }
              static Foo<@Nullable String> makeNullableFoo() {
                throw new RuntimeException();
              }
              static Box<@Nullable String> testNegative() {
                return new Box<>(id(makeNullableFoo()));
              }
              static Box<String> testPositive() {
                // BUG: Diagnostic contains: incompatible types
                return new Box<>(id(makeNullableFoo()));
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
