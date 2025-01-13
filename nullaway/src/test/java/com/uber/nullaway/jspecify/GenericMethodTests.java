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
  @Ignore("requires generic method support")
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
            "        static <U extends @Nullable Object> Foo<U> make(U u) {", // use return type for inference
            "          return new Foo<>(u);",
            "        }",
            "      }",
            "      static class Bar<S extends @Nullable Object, Z extends @Nullable Object> {",
            "        Bar(S s, Z z) {}",
            "        static <U extends @Nullable Object, B extends @Nullable Object> Bar<U, B> make(U a, B b) {",
            "          return new Bar<>(a, b);",
            "        }",
            "      }",
            "      static void testLocalAssign() {",
            "        // legal", // [Foo.make(null) -> [U -> @Nullable Object, T -> …] ] ==> [Foo<@Nullable Object> f = Foo.make(null) -> [U -> @Nullable Object, T -> …] ]
            "        Foo<@Nullable Object> f = Foo.make(null);", // the context we need to use is that it is an assignment
            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "        Foo<Object> f2 = Foo.make(null);",
            "        // legal",
            "        Bar<@Nullable Object, Object> b = Bar.make(null, new Object());",
            "        // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required",
            "        Bar<@Nullable Object, Object> b2 = Bar.make(null, null);",
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
