package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import org.junit.Ignore;
import org.junit.Test;

public class GenericMethodTests extends NullAwayTestsBase {

  @Test
  public void genericNonNullIdentityFunction() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static <T> T nonNullIdentity(T t) {",
            "    return t;",
            "  }",
            "  static void test() {",
            "    // legal",
            "    nonNullIdentity(new Object()).toString();",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null'",
            "    nonNullIdentity(null);",
            "    // BUG: Diagnostic contains: Type argument cannot be @Nullable",
            "    Test.<@Nullable Object>nonNullIdentity(new Object());",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void genericNullAllowingIdentityFunction() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static <T extends @Nullable Object> T identity(T t) {",
            "    return t;",
            "  }",
            "  static void test() {",
            "    // legal",
            "    identity(new Object()).toString();",
            "    // also legal",
            "    Test.<@Nullable Object>identity(null);",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    Test.<@Nullable Object>identity(null).toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void multipleTypeVariablesMethodCall() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import org.jspecify.annotations.NonNull;",
            "class Test {",
            "    public static <T extends @Nullable Object, U> void twoTypeVars(T first, U second) {}",
            "    static void test() {",
            "       // legal",
            "       Test.<@NonNull Object, Object>twoTypeVars(new Object(), new Object());",
            "       // legal",
            "       Test.<@Nullable Object, Object>twoTypeVars(null, new Object());",
            "       // BUG: Diagnostic contains: Type argument cannot be @Nullable",
            "       Test.<@Nullable Object, @Nullable Object>twoTypeVars(null, null);",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void genericInstanceMethods() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "     abstract class Foo<T extends @Nullable String, S> {",
            "       public abstract <U> T test1(U u);",
            "       public abstract <U> S test2(U u);",
            "       public abstract <U extends @Nullable Object> void test3(U u);",
            "     }",
            "     public void run(Foo<@Nullable String, Character> f) {",
            "       String s = f.<Integer>test1(3);",
            "       // BUG: Diagnostic contains: dereferenced expression",
            "       s.toString();",
            "       Character c = f.<Integer>test2(3);",
            "       // legal, Type S is @NonNull",
            "       c.toString();",
            "       // BUG: Diagnostic contains: passing @Nullable parameter 'null'",
            "       f.<Integer>test3(null);",
            "       f.<@Nullable Integer>test3(null);",
            "     }",
            "}")
        .doTest();
  }

  @Test
  public void genericMethodAndVoidType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class Foo {",
            "    <C extends @Nullable Object> void foo(C c, Visitor<C> visitor) {",
            "      visitor.visit(this, c);",
            "    }",
            "  }",
            "  static abstract class Visitor<C extends @Nullable Object> {",
            "    abstract void visit(Foo foo, C c);",
            "  }",
            "  static class MyVisitor extends Visitor<@Nullable Void> {",
            "    @Override",
            "    void visit(Foo foo, @Nullable Void c) {}",
            "  }",
            "  static void test(Foo f) {",
            "    // this is safe",
            "    f.<@Nullable Void>foo(null, new MyVisitor());",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void genericMethodAndVoidTypeWithInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class Foo {",
            "    <C extends @Nullable Object> void foo(C c, Visitor<C> visitor) {",
            "      visitor.visit(this, c);",
            "    }",
            "  }",
            "  static abstract class Visitor<C extends @Nullable Object> {",
            "    abstract void visit(Foo foo, C c);",
            "  }",
            "  static class MyVisitor extends Visitor<@Nullable Void> {",
            "    @Override",
            "    void visit(Foo foo, @Nullable Void c) {}",
            "  }",
            "  static void test(Foo f) {",
            "    // this is safe",
            "    f.foo(null, new MyVisitor());",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void genericInferenceOnAssignments() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "    class Test {",
            "      static class Foo<T extends @Nullable Object> {",
            "        Foo(T t) {}",
            "        static <U extends @Nullable Object> Foo<U> makeNull(U u) {",
            "          return new Foo<>(u);",
            "        }",
            "        static <U> Foo<U> makeNonNull(U u) {",
            "          return new Foo<>(u);",
            "        }",
            "      }",
            "      static void testLocalAssign() {",
            "        // legal",
            "        Foo<@Nullable Object> f1 = Foo.makeNull(null);",
            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "        Foo<Object> f2 = Foo.makeNull(null);",
            "        Foo<@Nullable Object> f3 = Foo.makeNull(new Object());",
            "        Foo<Object> f4 = Foo.makeNull(new Object());",
            "        // ILLEGAL: U does not have a @Nullable upper bound",
            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "        Foo<@Nullable Object> f5 = Foo.makeNonNull(null);",
            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "        Foo<Object> f6 = Foo.makeNonNull(null);",
            "        // BUG: Diagnostic contains: incompatible types:",
            "        Foo<@Nullable Object> f7 = Foo.makeNonNull(new Object());",
            "        Foo<Object> f8 = Foo.makeNonNull(new Object());",
            "      }",
            "    }")
        .doTest();
  }

  @Test
  public void genericInferenceOnAssignmentAfterDeclaration() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "    class Test {",
            "      static class Foo<T extends @Nullable Object> {",
            "        Foo(T t) {}",
            "        static <U extends @Nullable Object> Foo<U> makeNull(U u) {",
            "          return new Foo<>(u);",
            "        }",
            "        static <U> Foo<U> makeNonNull(U u) {",
            "          return new Foo<>(u);",
            "        }",
            "      }",
            "      static void testAssignAfterDeclaration() {",
            "        // legal",
            "        Foo<@Nullable Object> f1; f1 = Foo.makeNull(null);",
            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "        Foo<Object> f2; f2 = Foo.makeNull(null);",
            "      }",
            "    }")
        .doTest();
  }

  @Test
  public void multipleParametersOneNested() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "    static class Foo<T extends @Nullable Object> {",
            "        Foo(T t) {}",
            "        static <U extends @Nullable Object> Foo<U> create(U u, Foo<U> other) {",
            "            return new Foo<>(u);",
            "        }",
            "        static void test(Foo<@Nullable Object> f1, Foo<Object> f2) {",
            "            // no error expected",
            "            Foo<@Nullable Object> result = Foo.create(null, f1);",
            "            // BUG: Diagnostic contains: incompatible types: Foo<Object> cannot be converted to Foo<@Nullable Object>",
            "            Foo<@Nullable Object> result2 = Foo.create(null, f2);",
            "        }",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void genericInferenceOnAssignmentsMultipleParams() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  class Foo<T extends @Nullable Object> {",
            "    Foo(T t) {}",
            "    public <U extends @Nullable Object> Foo<U> make(U u, @Nullable String s) {",
            "      return new Foo<>(u);",
            "    }",
            "  }",
            "  static class Bar<S extends @Nullable Object, Z extends @Nullable Object> {",
            "    Bar(S s, Z z) {}",
            "    static <U extends @Nullable Object, B extends @Nullable Object> Bar<U, B> make(U u, B b) {",
            "      return new Bar<>(u, b);",
            "    }",
            "  }",
            "  static class Baz<S, Z> {",
            "    Baz(S s, Z z) {}",
            "    static <U, B> Baz<U, B> make(U u, B b) {",
            "      return new Baz<>(u, b);",
            "    }",
            "  }",
            "  public void run(Foo<@Nullable String> foo) {",
            "    // legal",
            "    Foo<@Nullable Object> f1 = foo.make(null, new String());",
            "    Foo<@Nullable Object> f2 = foo.make(null, null);",
            "    Foo<Object> f3 = foo.make(new Object(), null);",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    Foo<Object> f4 = foo.make(null, null);",
            "    // legal",
            "    Bar<@Nullable Object, Object> b1 = Bar.make(null, new Object());",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    Bar<@Nullable Object, Object> b2 = Bar.make(null, null);",
            "    Bar<@Nullable Object, @Nullable Object> b3 = Bar.make(null, null);",
            "    Bar<Object, @Nullable Object> b4 = Bar.make(new Object(), null);",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    Bar<Object, @Nullable Object> b5 = Bar.make(null, null);",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    Bar<Object, Object> b6 = Bar.make(null, null);",
            "    // legal",
            "    Baz<String, Object> baz1 = Baz.make(new String(), new Object());",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    Baz<String, Object> baz2 = Baz.make(null, new Object());",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    Baz<String, Object> baz3 = Baz.make(new String(), null);",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    Baz<String, Object> baz4 = Baz.make(null, null);",
            "    // BUG: Diagnostic contains: Generic type parameter cannot be @Nullable",
            "    Baz<@Nullable String, Object> baz5 = Baz.make(new String(), new Object());",
            "    // BUG: Diagnostic contains: Generic type parameter cannot be @Nullable",
            "    Baz<String, @Nullable Object> baz6 = Baz.make(new String(), new Object());",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void genericsInferenceOnAssignmentsWithGenericClasses() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import java.util.ArrayList;",
            "class Test {",
            "  abstract class Foo<K extends @Nullable Object, V> {",
            "    abstract <U, R> Foo<U,ArrayList<R>> nonNullTest();",
            "    abstract <U extends @Nullable Object, R extends @Nullable Object> Foo<U,ArrayList<R>> nullTest();",
            "  }",
            "  static void test(Foo<Void, Void> f) {",
            "    Foo<Integer, ArrayList<String>> fooNonNull_1 = f.nonNullTest();",
            "    // BUG: Diagnostic contains: incompatible types:",
            "    Foo<Integer, ArrayList<@Nullable String>> fooNonNull_2 = f.nonNullTest();",
            "    // BUG: Diagnostic contains: incompatible types:",
            "    Foo<@Nullable Integer, ArrayList<String>> fooNonNull_3 = f.nonNullTest();",
            "    Foo<Integer, ArrayList<String>> fooNull_1 = f.nullTest();",
            "    Foo<Integer, ArrayList<@Nullable String>> fooNull_2 = f.nullTest();",
            "    Foo<@Nullable Integer, ArrayList<String>> fooNull_3 = f.nullTest();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void genericInferenceOnAssignmentsWithArrays() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "    class Test {",
            "      static class Foo<T extends @Nullable Object> {",
            "        Foo(T t) {}",
            "        static <U extends @Nullable Object> Foo<Foo<U>[]> test1Null(U u) {",
            "          return new Foo<>((Foo<U>[]) new Foo<?>[5]);",
            "        }",
            "        static <U> Foo<Foo<U>[]> test1Nonnull(U u) {",
            "          return new Foo<>((Foo<U>[]) new Foo<?>[5]);",
            "        }",
            "        static <U extends @Nullable Object> Foo<U>[] test2Null(U u) {",
            "          return (Foo<U>[]) new Foo<?>[5];",
            "        }",
            "        static <U> Foo<U>[] test2Nonnull(U u) {",
            "          return (Foo<U>[]) new Foo<?>[5];",
            "        }",
            "      }",
            "      static void testLocalAssign() {",
            "        Foo<Foo<Object>[]> f1 = Foo.test1Null(new Object());",
            "        Foo<Foo<@Nullable Object>[]> f2 = Foo.test1Null(new Object());",
            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "        Foo<Foo<Object>[]> f3 = Foo.test1Null(null);",
            "        Foo<Foo<@Nullable Object>[]> f4 = Foo.test1Null(null);",
            "        Foo<Foo<Object>[]> f5 = Foo.test1Nonnull(new Object());",
            "        // BUG: Diagnostic contains: incompatible types:",
            "        Foo<Foo<@Nullable Object>[]> f6 = Foo.test1Nonnull(new Object());",
            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "        Foo<Foo<Object>[]> f7 = Foo.test1Nonnull(null);",
            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "        Foo<Foo<@Nullable Object>[]> f8 = Foo.test1Nonnull(null);",
            "        Foo<Object>[] f9 = Foo.test2Null(new Object());",
            "        Foo<@Nullable Object>[] f10 = Foo.test2Null(new Object());",
            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "        Foo<Object>[] f11 = Foo.test2Null(null);",
            "        Foo<@Nullable Object>[] f12 = Foo.test2Null(null);",
            "        Foo<Object>[] f13 = Foo.test2Nonnull(new Object());",
            "        // BUG: Diagnostic contains: incompatible types:",
            "        Foo<@Nullable Object>[] f14 = Foo.test2Nonnull(new Object());",
            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "        Foo<Object>[] f15 = Foo.test2Nonnull(null);",
            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "        Foo<@Nullable Object>[] f16 = Foo.test2Nonnull(null);",
            "      }",
            "    }")
        .doTest();
  }

  @Test
  public void inferNestedNonNullUpperBound() {
    makeHelper()
        .addSourceLines(
            "TestCase.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "abstract class TestCase {",
            "    static class Bar<T extends @Nullable Object> {}",
            "    abstract <U> Bar<U> make(Bar<U> other);",
            "    void test(Bar<Bar<String>> other) {",
            "        // BUG: Diagnostic contains: incompatible types: Bar<Bar<String>> cannot be converted to Bar<Bar<@Nullable String>>",
            "        Bar<Bar<@Nullable String>> unused = make(other);",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void issue1035() {
    makeHelper()
        .addSourceLines(
            "Todo.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "public class Todo {",
            "    public static <T extends @Nullable Object> T foo(NullableSupplier<T> code) {",
            "        return code.get();",
            "    }",
            "    public static void main(String[] args) {",
            "        // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type",
            "        Todo.<Object>foo(() -> null);",
            "        Todo.<@Nullable Object>foo(() -> null);",
            "    }",
            "    // this method should have no errors once we support inference for generic methods",
            "    public static void requiresInferenceSupport() {",
            "        Todo.foo(() -> null);",
            "    }",
            "    @FunctionalInterface",
            "    public interface NullableSupplier<T extends @Nullable Object> {",
            "        T get();",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void issue1138() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "class Foo {",
            "    <T> Foo(T source) {",
            "    }",
            "    static <T> Foo createNoTypeArgs(T in) {",
            "        return new Foo(in);",
            "    }",
            "    static Foo createWithTypeArgNegative(String s) {",
            "        return new <String>Foo(s);",
            "    }",
            "    static Foo createWithTypeArgPositive() {",
            "        // BUG: Diagnostic contains: Type argument cannot be @Nullable, as method <T>Foo(T)'s type variable T is not @Nullable",
            "        return new <@Nullable String>Foo(null);",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void nullableAnnotOnMethodTypeVarUse() {
    makeHelper()
        .addSourceLines(
            "GenericMethod.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import java.util.function.Function;",
            "public abstract class GenericMethod {",
            "    abstract <V> @Nullable V foo(",
            "            Function<@Nullable V, @Nullable V> f);",
            "    void testNegative(Function<@Nullable String, @Nullable String> f) {",
            "        this.<String>foo(f);",
            "    }",
            "    void testPositive(Function<String, String> f) {",
            "        // BUG: Diagnostic contains: incompatible types: Function<String, String> cannot be converted to",
            "        this.<String>foo(f);",
            "    }",
            "    void testPositive2(Function<@Nullable String, @Nullable String> f) {",
            "        // BUG: Diagnostic contains: dereferenced expression this.<String>foo(f) is @Nullable",
            "        this.<String>foo(f).hashCode();",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void issue1176() {
    makeHelper()
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import java.util.Set;",
            "import java.util.concurrent.CompletableFuture;",
            "import java.util.concurrent.ConcurrentHashMap;",
            "class Foo {",
            "    final Set<CompletableFuture<?>> f;",
            "    public Foo() {",
            "        f = ConcurrentHashMap.newKeySet();",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void issue1178() {
    makeHelper()
        .addSourceLines(
            "SampleNullUnmarkedCall.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "class SampleNullUnmarkedCall {",
            "  @NullUnmarked",
            "  static class Foo<T> {",
            "    Foo(T t) {}",
            "    static <U> Foo<U> id(U u) { return new Foo<>(u); }",
            "  }",
            "  static void test() {",
            "    Foo<@Nullable Object> x = Foo.id(null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void varargsOfGenericType() {
    makeHelper()
        .addSourceLines(
            "Varargs.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "public class Varargs {",
            "    static <T extends @Nullable Object> void foo(T... args) {",
            "    }",
            "    static void testNegative(@Nullable String s) {",
            "        Varargs.<@Nullable String>foo(s);",
            "    }",
            "    static void testPositive(@Nullable String s) {",
            "        // BUG: Diagnostic contains: passing @Nullable parameter",
            "        Varargs.<String>foo(s);",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void nullableVarargsArray() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.Nullable;",
            "import org.jspecify.annotations.NullMarked;",
            "@NullMarked",
            "class Test {",
            "    <T extends Object> void varargsTest(T @Nullable... args) {}",
            "    void f() {",
            "        String[] x = null;",
            "        this.<String>varargsTest(x);",
            "        this.<String>varargsTest((String[])null);",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void varargsConstructor() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.Nullable;",
            "import org.jspecify.annotations.NullMarked;",
            "@NullMarked",
            "class Test {",
            "    static class Foo {",
            "      <T> Foo(T @Nullable... args) {}",
            "    }",
            "    void testNegative() {",
            "        String[] x = null;",
            "        Foo f = new <String>Foo(x);",
            "        f = new <String>Foo((String[])null);",
            "    }",
            "    static class Bar {",
            "      <T> Bar(T... args) {}",
            "    }",
            "    void testPositive() {",
            "        String[] x = null;",
            "        // BUG: Diagnostic contains: passing @Nullable parameter",
            "        Bar b = new <String>Bar(x);",
            "        // BUG: Diagnostic contains: passing @Nullable parameter",
            "        b = new <String>Bar((String[])null);",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void genericInferenceOnReturn() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "    class Test {",
            "      static class Foo<T extends @Nullable Object> {",
            "        Foo(T t) {}",
            "        static <U extends @Nullable Object> Foo<U> makeNull(U u) {",
            "          return new Foo<>(u);",
            "        }",
            "        static <U> Foo<U> makeNonNull(U u) {",
            "          return new Foo<>(u);",
            "        }",
            "      }",
            "      static Foo<@Nullable Object> makeNull() {",
            "        // legal",
            "        return Foo.makeNull(null);",
            "      }",
            "      static Foo<Object> makeNullInvalid() {",
            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "        return Foo.makeNull(null);",
            "      }",
            "      static Foo<@Nullable Object> makeNonNullInvalid() {",
            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "        return Foo.makeNonNull(null);",
            "      }",
            "      static Foo<@Nullable Object> makeNonNullInvalid2() {",
            "        // BUG: Diagnostic contains: incompatible types:",
            "        return Foo.makeNonNull(new Object());",
            "      }",
            "      static Foo<Object> makeNonNullValid() {",
            "        return Foo.makeNonNull(new Object());",
            "      }",
            "    }")
        .doTest();
  }

  @Test
  public void genericInferenceOnParameterPassing() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "    class Test {",
            "      static class Foo<T extends @Nullable Object> {",
            "        Foo(T t) {}",
            "        static <U extends @Nullable Object> Foo<U> makeNull(U u) {",
            "          return new Foo<>(u);",
            "        }",
            "        static <U> Foo<U> makeNonNull(U u) {",
            "          return new Foo<>(u);",
            "        }",
            "      }",
            "      static void handleFooNullable(Foo<@Nullable Object> f) {}",
            "      static void handleFooNonNull(Foo<Object> f) {}",
            "      static void testCalls() {",
            "        // legal",
            "        handleFooNullable(Foo.makeNull(null));",
            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "        handleFooNonNull(Foo.makeNull(null));",
            "        handleFooNullable(Foo.makeNull(new Object()));",
            "        handleFooNonNull(Foo.makeNull(new Object()));",
            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "        handleFooNullable(Foo.makeNonNull(null));",
            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "        handleFooNonNull(Foo.makeNonNull(null));",
            "        // BUG: Diagnostic contains: incompatible types: Foo<Object>",
            "        handleFooNullable(Foo.makeNonNull(new Object()));",
            "        handleFooNonNull(Foo.makeNonNull(new Object()));",
            "      }",
            "      static void handleFooNullableVarargs(Foo<@Nullable Object>... args) {}",
            "      static void handleFooNonNullVarargs(Foo<Object>... f) {}",
            "      static void testVarargsCalls() {",
            "        // legal",
            "        handleFooNullableVarargs(Foo.makeNull(null));",
            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "        handleFooNonNullVarargs(Foo.makeNull(null));",
            "        handleFooNullableVarargs(Foo.makeNull(new Object()));",
            "        handleFooNonNullVarargs(Foo.makeNull(new Object()));",
            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "        handleFooNullableVarargs(Foo.makeNonNull(null));",
            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "        handleFooNonNullVarargs(Foo.makeNonNull(null));",
            "        // BUG: Diagnostic contains: incompatible types: Foo<Object>",
            "        handleFooNullableVarargs(Foo.makeNonNull(new Object()));",
            "        handleFooNonNullVarargs(Foo.makeNonNull(new Object()));",
            "      }",
            "    }")
        .doTest();
  }

  @Test
  public void genericMethodReturningTypeVariable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class Foo<T extends @Nullable Object> {}",
            "  static <T extends @Nullable Object> T returnTypeVariable(Foo<T> t) {",
            "    throw new RuntimeException();",
            "  }",
            "  static void takesNullable(@Nullable String s) {}",
            "  static void test() {",
            "    // legal, with explicit types",
            "    takesNullable(Test.<@Nullable String>returnTypeVariable(new Foo<@Nullable String>()));",
            "    // legal, with inference",
            "    takesNullable(returnTypeVariable(new Foo<@Nullable String>()));",
            "    // also legal",
            "    takesNullable(returnTypeVariable(new Foo<String>()));",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void typeVarReturnNonNullUpperBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static <T> T id(T t) {",
            "    return t;",
            "  }",
            "  static void takesNullable(@Nullable String s) {}",
            "  static void test() {",
            "    // legal",
            "    takesNullable(id(\"hi\"));",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    takesNullable(id(null));",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void firstOrDefaultSelfContained() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "class Test {",
            "  public interface List<E extends @Nullable Object> { boolean isEmpty(); E get(int index); }",
            "  public static class Collections {",
            "    public static <T extends @Nullable Object> List<T> singletonList(T element) {",
            "      throw new UnsupportedOperationException();",
            "    }",
            "  }",
            "  public static <U extends @Nullable Object> U firstOrDefault(List<U> list, U defaultValue) {",
            "    return list.isEmpty() ? defaultValue : list.get(0);",
            "  }",
            "  static void use() {",
            "    // should infer T -> @Nullable String",
            "    String result = firstOrDefault(Collections.singletonList(null), \"hello\");",
            "    // BUG: Diagnostic contains: dereferenced expression result is @Nullable",
            "    result.hashCode();",
            "    // should infer T -> @NonNull String",
            "    String result2 = firstOrDefault(Collections.singletonList(\"bye\"), \"hello\");",
            "    result2.hashCode();",
            "    // should infer T -> @Nullable String (testing that inference is called from dataflow)",
            "    String result3 = firstOrDefault(Collections.singletonList(null), \"hello\");",
            "    // BUG: Diagnostic contains: dereferenced expression result3 is @Nullable",
            "    result3.hashCode();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void firstOrDefaultLocalVarParam() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "class Test {",
            "  public interface List<E extends @Nullable Object> { boolean isEmpty(); E get(int index); }",
            "  public static class Collections {",
            "    public static <T extends @Nullable Object> List<T> singletonList(T element) {",
            "      throw new UnsupportedOperationException();",
            "    }",
            "  }",
            "  public static <U extends @Nullable Object> U firstOrDefault(List<U> list, U defaultValue) {",
            "    return list.isEmpty() ? defaultValue : list.get(0);",
            "  }",
            "  static void use() {",
            "    String x = null;",
            "    // should infer T -> @Nullable String",
            "    String result = firstOrDefault(Collections.singletonList(x), \"hello\");",
            "    // BUG: Diagnostic contains: dereferenced expression result is @Nullable",
            "    result.hashCode();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void localsWithTypesFromDataflow() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "class Test {",
            "    static <T extends @Nullable Object> T id(T t) {",
            "        return t;",
            "    }",
            "    void testPositive() {",
            "        String s = null;",
            "        String t = id(s);",
            "        // BUG: Diagnostic contains: dereferenced expression t is @Nullable",
            "        t.hashCode();",
            "    }",
            "    void testNegative() {",
            "        String s = \"hello\";",
            "        String t = id(s);",
            "        t.hashCode();",
            "    }",
            "    String field = \"hello\";",
            "    void testField() {",
            "        String s = null;",
            "        // BUG: Diagnostic contains: Failed to infer type argument nullability",
            "        field = id(s);",
            "    }",
            "    @Nullable String field2 = null;",
            "    void testField2() {",
            "        String s = null;",
            "        field2 = id(s);",
            "        // BUG: Diagnostic contains: dereferenced expression field2 is @Nullable",
            "        field2.hashCode();",
            "        s = \"hello\";",
            "        field2 = id(s);",
            "        field2.hashCode();",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void dataflowAndLoops() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "class Test {",
            "    static <T extends @Nullable Object> T id(T t) {",
            "        return t;",
            "    }",
            "    void testLoop1() {",
            "        String s = \"hello\";",
            "        while (true) {",
            "            String t = id(s);",
            "            // BUG: Diagnostic contains: dereferenced expression t is @Nullable",
            "            t.hashCode();",
            "            s = null;",
            "        }",
            "    }",
            "    void testLoop2() {",
            "        String t = \"hello\";",
            "        while (true) {",
            "            // BUG: Diagnostic contains: dereferenced expression t is @Nullable",
            "            t.hashCode();",
            "            String s = null;",
            "            t = id(s);",
            "        }",
            "    }",
            "    void testLoop3() {",
            "        String t = \"hello\";",
            "        String s = \"hello\";",
            "        t.hashCode();",
            "        int i = 2;",
            "        while (i > 0) {",
            "            t = id(s);",
            "            s = null;",
            "            i--;",
            "        }",
            "        // BUG: Diagnostic contains: dereferenced expression t is @Nullable",
            "        t.hashCode();",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void otherExprsWithTypesFromDataflow() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "class Test {",
            "    static <T extends @Nullable Object> T id(T t) {",
            "        return t;",
            "    }",
            "    @Nullable String field;",
            "    void testPositive() {",
            "        String t = id(field);",
            "        // BUG: Diagnostic contains: dereferenced expression t is @Nullable",
            "        t.hashCode();",
            "    }",
            "    void testNegative() {",
            "        if (field != null) {",
            "            String t = id(field);",
            "            t.hashCode();",
            "        }",
            "    }",
            "    void testUnsupported() {",
            "        String s = \"hello\";",
            "        // dataflow can't prove the argument is non-null",
            "        // BUG: Diagnostic contains: passing @Nullable parameter 's == null ? null : s'",
            "        String t = id(s == null ? null : s);",
            "        t.hashCode();",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void varargsInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "class Test {",
            "    static class Foo<T extends @Nullable Object> {}",
            "    public static <U extends @Nullable Object> Foo<U> make(U... args) {",
            "      throw new RuntimeException();",
            "    }",
            "    static <V extends @Nullable String> V makeStr(V v) {",
            "      return v;",
            "    }",
            "    Foo<String> foo1 = make(\"hello\", \"world\");",
            "    Foo<@Nullable String> foo2 = make(\"hello\", null, \"world\");",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    Foo<String> foo3 = make(\"hello\", null, \"world\");",
            "    Foo<@Nullable String> foo4 = make(\"hello\", \"world\");",
            "    Foo<@Nullable String> foo5 = make(\"hello\", \"world\", makeStr(null));",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    Foo<String> foo6 = make(\"hello\", \"world\", makeStr(null));",
            "    // Inference from assignment context only (no args)",
            "    Foo<String> foo7 = make();",
            "    // And the nullable variant",
            "    Foo<@Nullable String> foo8 = make();",
            "}")
        .doTest();
  }

  @Test
  public void varargsInferencePassingArray() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "class Test {",
            "    static class Foo<T extends @Nullable Object> {}",
            "    public static <U extends @Nullable Object> Foo<U> make(U... args) {",
            "      throw new RuntimeException();",
            "    }",
            "    Foo<String> foo1 = make(new String[] { \"hello\", \"world\" });",
            "    Foo<@Nullable String> foo2 = make(new @Nullable String[] { \"hello\", null, \"world\" });",
            "    // BUG: Diagnostic contains: incompatible types:",
            "    Foo<String> foo3 = make(new @Nullable String[] { \"hello\", null, \"world\" });",
            "    Foo<@Nullable String> foo4 = make(new String[] { \"hello\", \"world\" });",
            "}")
        .doTest();
  }

  @Test
  public void supplierLambdaInference() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "class Test {",
            "    static interface Supplier<T extends @Nullable Object> {",
            "        T get();",
            "    }",
            "    static <R> void invoke(Supplier<@Nullable R> supplier) {}",
            "    static <R extends @Nullable Object> R invokeWithReturn(Supplier<R> supplier) {",
            "        return supplier.get();",
            "    }",
            "    static void test() {",
            "        // legal, should infer R -> @Nullable Object, but inference can't handle yet",
            "        invoke(() -> null);",
            "        // legal, should infer R -> @Nullable Object, but inference can't handle yet",
            "        Object x = invokeWithReturn(() -> null);",
            "        // BUG: Diagnostic contains: dereferenced expression x is @Nullable",
            "        x.hashCode();",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void lambdaReturnsGenericMethodCall() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "class Test {",
            "    static interface Supplier<T extends @Nullable Object> {",
            "        T get();",
            "    }",
            "    static <R extends @Nullable Object> R invokeWithReturn(Supplier<R> supplier) {",
            "        return supplier.get();",
            "    }",
            "    static <U extends @Nullable Object> U genericMethod(U var){",
            "         return var;",
            "    }",
            "    static void test() {",
            "        Object x = invokeWithReturn(() -> { return genericMethod(\"value\");});",
            "        Object y = invokeWithReturn(() -> { return genericMethod(null);});",
            "        // legal, should infer x is a @NonNull String",
            "        x.hashCode();",
            "        // BUG: Diagnostic contains: dereferenced expression y is @Nullable",
            "        y.hashCode();",
            "        // Block-bodied with parenthesized return",
            "        Object x_block_paren = invokeWithReturn(() -> { return (genericMethod(\"value\"));});",
            "        Object y_block_paren = invokeWithReturn(() -> { return (genericMethod(null));});",
            "        // legal, should infer x_block_paren is a @NonNull String",
            "        x_block_paren.hashCode();",
            "        // BUG: Diagnostic contains: dereferenced expression y_block_paren is @Nullable",
            "        y_block_paren.hashCode();",
            "        // Expression-bodied",
            "        Object x_expr = invokeWithReturn(() -> genericMethod(\"value\"));",
            "        Object y_expr = invokeWithReturn(() -> genericMethod(null));",
            "        // legal, should infer x_expr is a @NonNull String",
            "        x_expr.hashCode();",
            "        // BUG: Diagnostic contains: dereferenced expression y_expr is @Nullable",
            "        y_expr.hashCode();",
            "        // Expression-bodied with parenthesized return",
            "        Object x_expr_paren = invokeWithReturn(() -> (genericMethod(\"value\")));",
            "        Object y_expr_paren = invokeWithReturn(() -> (genericMethod(null)));",
            "        // legal, should infer x_expr_paren is a @NonNull String",
            "        x_expr_paren.hashCode();",
            "        // BUG: Diagnostic contains: dereferenced expression y_expr_paren is @Nullable",
            "        y_expr_paren.hashCode();",
            "        // TODO",
            "        // Object x2 = invokeWithReturn(() ->{ Object y2 = null; return y2;});",
            "    }",
            "}")
        .doTest();
  }

  @Ignore("https://github.com/uber/NullAway/issues/1350")
  @Test
  public void genericMethodLambdaArgWildCard() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.*;",
            "import java.util.function.Function;",
            "@NullMarked",
            "class Test {",
            "    static <T, R> R invokeWithReturn(Function <? super T, ? extends @Nullable R> mapper) {",
            "        throw new RuntimeException();",
            "    }",
            "    static void test() {",
            "        // legal, should infer R -> Object but then the type of the lambda as ",
            "        //  Function<Object, @Nullable Object> via wildcard upper bound",
            "        Object x = invokeWithReturn(t -> null);",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void inferenceWithFieldAssignment() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "class Test {",
            "    static <T extends @Nullable Object> T id(T t) {",
            "        return t;",
            "    }",
            "    String field = id(\"hello\");",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    String field2 = id(null);",
            "    @Nullable String field3 = id(null);",
            "    @Nullable String field4 = id(\"hello\");",
            "}")
        .doTest();
  }

  @Test
  public void functionBoundRespectedDuringInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.*;",
            "import java.util.function.Function;",
            "@NullMarked",
            "class Test {",
            "  static <T extends @Nullable Object> T applyId(Function<T, T> f, T x) {",
            "    return f.apply(x);",
            "  }",
            "  static void ok(Function<@Nullable String, @Nullable String> f) {",
            "    String s = applyId(f, null);",
            "  }",
            "  static void bad(Function<String, String> f) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    String s = applyId(f, null);",
            "  }",
            "}")
        .doTest();
  }

  @Ignore("we need to respect upper bounds of class type variables during inference")
  @Test
  public void inferenceNonNullUpperBoundOnClassTypeVar() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "class Test<T> {",
            "  static <U extends @Nullable Object> Test<U> make(U u) {",
            "    throw new RuntimeException();",
            "  }",
            "  static <V extends @Nullable Object> V unmake(Test<V> v) {",
            "    throw new RuntimeException();",
            "  }",
            "  static void test() {",
            "    // BUG: Diagnostic contains:",
            "    String s = unmake(make(null));",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void arrayCovariantSubtyping() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "class Test {",
            "  static <T extends @Nullable Object> T f(T[] vals, T other) {",
            "    return other;",
            "  }",
            "  String stringField = \"hi\";",
            "  void test(String[] arr, @Nullable String[] arr2) {",
            "    // legal, should infer T -> @Nullable String",
            "    // we can pass String[] due to covariant subtyping",
            "    String s1 = f(arr, null);",
            "    // BUG: Diagnostic contains: dereferenced expression s1 is @Nullable",
            "    s1.hashCode();",
            "    String s2 = f(arr, \"hi\");",
            "    // legal",
            "    s2.hashCode();",
            "    // BUG: Diagnostic contains: incompatible types: @Nullable String []",
            "    stringField = f(arr2, \"hi\");",
            "  }",
            "}")
        .doTest();
  }

  /**
   * A more complex example inspired by code from Spring. Testing that we properly distinguish
   * {@code T} from {@code @Nullable T}.
   */
  @Test
  public void rowMapperTest() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "TestRowMapper.java",
            "import java.util.List;",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "class TestRowMapper {",
            "",
            "  interface RowMapper<T extends @Nullable Object> {}",
            "",
            "  static class SingleColumnRowMapper<T> implements RowMapper<@Nullable T> {",
            "    public SingleColumnRowMapper(Class<T> requiredType) {}",
            "  }",
            "",
            "  protected <U> RowMapper<@Nullable U> getSingleColumnRowMapper(Class<U> requiredType) {",
            "    return new SingleColumnRowMapper<>(requiredType);",
            "  }  ",
            "  ",
            "  public <V extends @Nullable Object> List<V> query(RowMapper<V> rowMapper) {",
            "    throw new RuntimeException();",
            "  }",
            "  ",
            "  public <W> List<@Nullable W> queryForList(Class<W> elementType) {",
            "    return query(getSingleColumnRowMapper(elementType));",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void selfContainedOptional() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "public class Test {",
            "    static class Optional<T> {",
            "        public static <T> Optional<T> ofNullable(@Nullable T value) {",
            "            return new Optional<>();",
            "        }",
            "        public static <T> Optional<T> of(T value) {",
            "            throw new RuntimeException();",
            "        }",
            "    }",
            "    public static <U extends @Nullable Object> Optional<U> optionalResultNegative1(@Nullable U value) {",
            "        return Optional.ofNullable(value);",
            "    }",
            "    public static <U> Optional<U> optionalResultNegative2(@Nullable U value) {",
            "        return Optional.ofNullable(value);",
            "    }",
            "    public static <U extends @Nullable Object> Optional<U> optionalResultPositive1(@Nullable U value) {",
            "        // BUG: Diagnostic contains: Failed to infer type argument nullability",
            "        return Optional.of(value);",
            "    }",
            "    // identical to above, testing the other error message",
            "    public static <U extends @Nullable Object> Optional<U> optionalResultPositive2(@Nullable U value) {",
            "        // BUG: Diagnostic contains: passing @Nullable parameter 'value'",
            "        return Optional.of(value);",
            "    }",
            "}")
        .doTest();
  }

  /**
   * Tests that we properly handle an {@code AssignmentTree} that re-assigns a local variable (not
   * just {@code VariableTree} initializers).
   */
  @Test
  public void reassignLocal() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "public class Test {",
            "    private <R> @Nullable R make() {",
            "        return null;",
            "    }",
            "    void test() {",
            "        Object result = null;",
            "        result = make();",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void issue1238() {
    makeHelper()
        .addSourceLines(
            "CreatorMediator.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "public class CreatorMediator {",
            "    private final PropertyModel mCreatorModel = new PropertyModel();",
            "    private void followClickHandler() {",
            "        // this is fine: byte[] is assignable to byte @Nullable []",
            "        WebFeedBridge.followFromId(",
            "                mCreatorModel.get(CreatorProperties.WEB_FEED_ID_KEY));",
            "    }",
            "    private static class PropertyModel {",
            "        <T extends @Nullable Object> T get(Key<T> key) {",
            "            throw new RuntimeException();",
            "        }",
            "    }",
            "    private static class CreatorProperties {",
            "        static final Key<byte[]> WEB_FEED_ID_KEY = makeKey();",
            "        private static Key<byte[]> makeKey() {",
            "            throw new RuntimeException();",
            "        }",
            "    }",
            "    private static class WebFeedBridge {",
            "        static void followFromId(",
            "                byte @Nullable [] webFeedId) {",
            "        }",
            "    }",
            "    interface Key<T> {",
            "    }",
            "}")
        .doTest();
  }

  /**
   * Extracted from Caffeine; exposed some subtle bugs in substitutions involving identity of {@code
   * Type} objects
   */
  @Test
  public void nullableWildcardFromCaffeine() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "public class Test {",
            "    public interface CacheLoader<K, V extends @Nullable Object> {}",
            "    static class JCacheLoaderAdapter<K, V> implements CacheLoader<K, @Nullable Expirable<V>> {}",
            "    static class Expirable<V> {}",
            "    static class Caffeine<K, V> {",
            "        public <K1 extends K, V1 extends @Nullable V> Object build(",
            "                CacheLoader<? super K1, V1> loader) {",
            "            throw new RuntimeException();",
            "        }",
            "    }",
            "    class Builder<K, V> {",
            "        Caffeine<Object, Object> caffeine = new Caffeine<>();",
            "        void test() {",
            "            JCacheLoaderAdapter<K, V> adapter = new JCacheLoaderAdapter<>();",
            "            caffeine.<K, @Nullable Expirable<V>>build(adapter);",
            "            // also works with inference",
            "            Object o = caffeine.build(adapter);",
            "        }",
            "    }",
            "}")
        .doTest();
  }

  /** various cases where dataflow analysis forces inference to run for a generic method call */
  @Test
  public void inferenceFromDataflow() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "public class Test {",
            "  static <U extends @Nullable Object> U id(U u) {",
            "    return u;",
            "  }",
            "  static void testReceiver() {",
            "    // to ensure that dataflow runs",
            "    Object x = new Object(); x.toString();",
            "    Object y = null;",
            "    // BUG: Diagnostic contains: dereferenced expression id(y) is @Nullable",
            "    id(y).toString();",
            "  }",
            "  static Object testReturn() {",
            "    // to ensure that dataflow runs",
            "    Object x = new Object(); x.toString();",
            "    Object y = null;",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'y' where @NonNull is required",
            "    return id(y);",
            "  }",
            "  static Object testReturnWithParens() {",
            "    // to ensure that dataflow runs",
            "    Object x = new Object(); x.toString();",
            "    Object y = null;",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'y' where @NonNull is required",
            "    return (((id(y))));",
            "  }",
            "  static Object testReturnNested() {",
            "    // to ensure that dataflow runs",
            "    Object x = new Object(); x.toString();",
            "    Object y = null;",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'y' where @NonNull is required",
            "    return id(id(y));",
            "  }",
            "  static void takesNonNull(Object o) {}",
            "  static void testParam() {",
            "    // to ensure that dataflow runs",
            "    Object x = new Object(); x.toString();",
            "    Object y = null;",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'y' where @NonNull is required",
            "    takesNonNull(id(y));",
            "  }",
            "}")
        .doTest();
  }

  /**
   * Self-contained test for https://github.com/uber/NullAway/issues/1157. We need to import
   * JSpecify JDK models to get the original test working properly.
   */
  @Test
  public void atomicReferenceFieldUpdaterSelfContained() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "public class Test {",
            "    static class AtomicReferenceFieldUpdater<T, V extends @Nullable Object> {",
            "      public static <U,W extends @Nullable Object> AtomicReferenceFieldUpdater<U,W> ",
            "        newUpdater(Class<U> tclass, Class<@NonNull W> vclass, String fieldName) { throw new RuntimeException(); }",
            "    }",
            "    static final AtomicReferenceFieldUpdater<Test, @Nullable Object> RESULT_UPDATER =",
            "            AtomicReferenceFieldUpdater.newUpdater(Test.class, Object.class, \"result\");",
            "}")
        .doTest();
  }

  @Test
  public void inferenceFromReceiverPassing() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "public class Test {",
            "  static class Foo<T extends @Nullable Object> {",
            "    T get() {",
            "      throw new UnsupportedOperationException();",
            "    }",
            "  }",
            "  static <U extends @Nullable Object> Foo<U> make(U u) {",
            "    throw new RuntimeException();",
            "  }",
            "  static void test() {",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    make(null).get().toString();",
            "    // Also with a parenthesized receiver",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    ((make(null))).get().toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void issue1294_lambdaArguments() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "class Foo {",
            " public interface Callback<T extends @Nullable Object> {",
            "   void onResult(T thing);",
            " }",
            " public static <T extends @Nullable Object> Callback<T> wrap(Callback<T> thing) {",
            "   return thing;",
            " }",
            " public static void test() {",
            "   Callback<@Nullable String> ret1 = wrap(s -> {});",
            // we should get an error at the s.hashCode() call.
            "   // BUG: Diagnostic contains: dereferenced expression",
            "   Callback<@Nullable String> ret2 = wrap(s -> { s.hashCode(); });",
            "   Callback<@Nullable String> ret3 = wrap(s -> { if (s != null) s.hashCode(); });",
            "   Callback<String> ret4 = wrap(s -> { s.hashCode(); });",
            "   }",
            "}")
        .doTest();
  }

  @Test
  public void typeOfParameterWithInferredLambda() {
    makeHelperWithInferenceFailureWarning()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "import java.util.Map;",
            "@NullMarked",
            "class Test {",
            "  interface MyFunc<T extends @Nullable Object, U extends @Nullable Object> {",
            "    U apply(T t);",
            "  }",
            "  static Map<String, @Nullable String> bar(Map<String, @Nullable String> m) {",
            "    return m;",
            "  }",
            "  static <T extends @Nullable String, U extends @Nullable String> void foo(",
            "      MyFunc<Map<T, U>, Map<T, U>> f) {}",
            "  void test() {",
            "    foo(map -> bar(map));",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void instanceGenericMethodWithMethodRefArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.NullMarked;",
            "import java.util.List;",
            "import java.util.function.Consumer;",
            "@NullMarked",
            "class Test {",
            "        public <E extends Enum<E>> void visitEnum(String descriptor, String value, Consumer<E> consumer) {}",
            "        void test(String s1, String s2, List<Object> l) {",
            "            visitEnum(s1, s2, l::add);",
            "        }",
            "}")
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList("-XepOpt:NullAway:AnnotatedPackages=com.uber")));
  }

  private CompilationTestHelper makeHelperWithInferenceFailureWarning() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList(
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:WarnOnGenericInferenceFailure=true")));
  }
}
