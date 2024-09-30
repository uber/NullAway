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
            "    // BUG: Diagnostic contains: Type argument cannot be @Nullable", // something about invalid type argument", // line 13
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
//            "    // legal",
//            "    identity(new Object()).toString();",
//            "    // also legal",
//            "    Test.<@Nullable Object>identity(null);",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    Test.<@Nullable Object>identity(null).toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  @Ignore("requires generic method support")
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
            "    f.foo(null, new MyVisitor());",
            "  }",
            "}")
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        Arrays.asList(
            "-XepOpt:NullAway:AnnotatedPackages=com.uber", "-XepOpt:NullAway:JSpecifyMode=true"));
  }
}
