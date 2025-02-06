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
//            "        // legal",
//            "        Foo<@Nullable Object> f1 = Foo.makeNull(null);",
//            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
//            "        Foo<Object> f2 = Foo.makeNull(null);",
//            "        // ILLEGAL: U does not have a @Nullable upper bound",
//            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
//            "        Foo<@Nullable Object> f3 = Foo.makeNonNull(null);",
//            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
//            "        Foo<Object> f4 = Foo.makeNonNull(null);",
            "        Foo<@Nullable Object> f5 = Foo.makeNonNull(new Object());",
            "      }",
            "    }")
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
            "    // legal",
            "    Baz<String, Object> baz1 = Baz.make(new String(), new Object());",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    Baz<String, Object> baz2 = Baz.make(null, new Object());",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    Baz<String, Object> baz3 = Baz.make(new String(), null);",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "    Baz<String, Object> baz4 = Baz.make(null, null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void genericsUsedForGenericClasses() {
    makeHelper()
      .addSourceLines(
        "Test.java",
        "package com.uber;",
        "import org.jspecify.annotations.Nullable;",
        "import java.util.ArrayList;",
        "class Test {",
        "  abstract class Foo<K, V> {",
        "    abstract <U, R> Foo<U,ArrayList<R>> nonNullTest();",
        "    abstract <U extends @Nullable Object, R extends @Nullable Object> Foo<U,ArrayList<R>> nullTest();",
        "  }",
        "  static void test(Foo<Void, Void> f) {",
        "    Foo<Integer, ArrayList<String>> fooNonNull_1 = f.nonNullTest();",
        "    Foo<Integer, ArrayList<@Nullable String>> fooNonNull_2 = f.nonNullTest();", // error message
        "    Foo<Integer, ArrayList<String>> fooNull_1 = f.nullTest();",
        "    Foo<Integer, ArrayList<@Nullable String>> fooNull_2 = f.nullTest();", // error message
        "  }",
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

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        Arrays.asList(
            "-XepOpt:NullAway:AnnotatedPackages=com.uber", "-XepOpt:NullAway:JSpecifyMode=true"));
  }
}
