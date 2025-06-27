package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
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
  @Ignore("requires inference of generic method type arguments")
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
            "        // BUG: Diagnostic contains: due to mismatched nullability of type parameters",
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
            "            // BUG: Diagnostic contains: has mismatched type parameter nullability",
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
            "    // BUG: Diagnostic contains: due to mismatched nullability of type parameters",
            "    Foo<Integer, ArrayList<@Nullable String>> fooNonNull_2 = f.nonNullTest();",
            "    // BUG: Diagnostic contains: due to mismatched nullability of type parameters",
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
            "        // BUG: Diagnostic contains: due to mismatched nullability of type parameters",
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
            "        // BUG: Diagnostic contains: due to mismatched nullability of type parameters",
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
            "        // BUG: Diagnostic contains: Cannot assign from type Bar<Bar<String>> to type Bar<Bar<@Nullable String>>",
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
            "        // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type",
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
            "        // BUG: Diagnostic contains: Cannot pass parameter of type Function<String, String>, as formal parameter has type",
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
            "        // BUG: Diagnostic contains: due to mismatched nullability of type parameters",
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
            "        // BUG: Diagnostic contains: Cannot pass parameter of type Foo<Object>",
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
            "        // BUG: Diagnostic contains: Cannot pass parameter of type Foo<Object>",
            "        handleFooNullableVarargs(Foo.makeNonNull(new Object()));",
            "        handleFooNonNullVarargs(Foo.makeNonNull(new Object()));",
            "      }",
            "    }")
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        Arrays.asList(
            "-XepOpt:NullAway:AnnotatedPackages=com.uber", "-XepOpt:NullAway:JSpecifyMode=true"));
  }
}
