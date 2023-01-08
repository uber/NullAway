package com.uber.nullaway;

import com.google.errorprone.CompilationTestHelper;
import java.util.Arrays;
import org.junit.Test;

public class NullAwayJSpecifyGenericsTests extends NullAwayTestsBase {

  @Test
  public void basicTypeParamInstantiation() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "    static class NonNullTypeParam<E> {}",
            "    static class NullableTypeParam<E extends @Nullable Object> {}",
            "    // BUG: Diagnostic contains: Generic type parameter",
            "    static void testBadNonNull(NonNullTypeParam<@Nullable String> t1) {",
            "        // BUG: Diagnostic contains: Generic type parameter",
            "        NonNullTypeParam<@Nullable String> t2 = null;",
            "        NullableTypeParam<@Nullable String> t3 = null;",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void constructorTypeParamInstantiation() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "    static class NonNullTypeParam<E> {}",
            "    static class NullableTypeParam<E extends @Nullable Object> {}",
            "    static void testOkNonNull(NonNullTypeParam<String> t) {",
            "        NonNullTypeParam<String> t2 = new NonNullTypeParam<String>();",
            "    }",
            "    static void testBadNonNull(NonNullTypeParam<String> t) {",
            "        // BUG: Diagnostic contains: Generic type parameter",
            "       NonNullTypeParam<String> t2 = new NonNullTypeParam<@Nullable String>();",
            "        // BUG: Diagnostic contains: Generic type parameter",
            "        testBadNonNull(new NonNullTypeParam<@Nullable String>());",
            "        testBadNonNull(new NonNullTypeParam<",
            "              // BUG: Diagnostic contains: Generic type parameter",
            "              @Nullable String>());",
            "    }",
            "    static void testOkNullable(NullableTypeParam<String> t1, NullableTypeParam<@Nullable String> t2) {",
            "        NullableTypeParam<String> t3 = new NullableTypeParam<String>();",
            "        NullableTypeParam<@Nullable String> t4 = new NullableTypeParam<@Nullable String>();",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void multipleTypeParametersInstantiation() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "    static class MixedTypeParam<E1, E2 extends @Nullable Object, E3 extends @Nullable Object, E4> {}",
            "    // BUG: Diagnostic contains: Generic type parameter",
            "     static class PartiallyInvalidSubclass extends MixedTypeParam<@Nullable String, String, String, @Nullable String> {}",
            "     static class ValidSubclass1 extends MixedTypeParam<String, @Nullable String, @Nullable String, String> {}",
            "     static class PartiallyInvalidSubclass2 extends MixedTypeParam<String, String, String, ",
            "         // BUG: Diagnostic contains: Generic type parameter",
            "         @Nullable String> {}",
            "     static class ValidSubclass2 extends MixedTypeParam<String, String, String, String> {}",
            "}")
        .doTest();
  }

  @Test
  public void subClassTypeParamInstantiation() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "    static class NonNullTypeParam<E> {}",
            "    static class NullableTypeParam<E extends @Nullable Object> {}",
            "    static class SuperClassForValidSubclass {",
            "        static class ValidSubclass extends NullableTypeParam<@Nullable String> {}",
            "        // BUG: Diagnostic contains: Generic type parameter",
            "        static class InvalidSubclass extends NonNullTypeParam<@Nullable String> {}",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void interfaceImplementationTypeParamInstantiation() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "    static interface NonNullTypeParamInterface<E>{}",
            "    static interface NullableTypeParamInterface<E extends @Nullable Object>{}",
            "    // BUG: Diagnostic contains: Generic type parameter",
            "    static class InvalidInterfaceImplementation implements NonNullTypeParamInterface<@Nullable String> {}",
            "    static class ValidInterfaceImplementation implements NullableTypeParamInterface<String> {}",
            "}")
        .doTest();
  }

  @Test
  public void nestedTypeParams() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "    static class NonNullTypeParam<E> {}",
            "    static class NullableTypeParam<E extends @Nullable Object> {}",
            "    // BUG: Diagnostic contains: Generic type parameter",
            "    static void testBadNonNull(NullableTypeParam<NonNullTypeParam<@Nullable String>> t) {",
            "        // BUG: Diagnostic contains: Generic type parameter",
            "        NullableTypeParam<NonNullTypeParam<NonNullTypeParam<@Nullable String>>> t2 = null;",
            "        // BUG: Diagnostic contains: Generic type parameter",
            "        t2 = new NullableTypeParam<NonNullTypeParam<NonNullTypeParam<@Nullable String>>>();",
            "        // this is fine",
            "        NullableTypeParam<NonNullTypeParam<NullableTypeParam<@Nullable String>>> t3 = null;",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void returnTypeParamInstantiation() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "    static class NonNullTypeParam<E> {}",
            "    static class NullableTypeParam<E extends @Nullable Object> {}",
            "    // BUG: Diagnostic contains: Generic type parameter",
            "    static NonNullTypeParam<@Nullable String> testBadNonNull() {",
            "          return new NonNullTypeParam<String>();",
            "    }",
            "    static NullableTypeParam<@Nullable String> testOKNull() {",
            "          return new NullableTypeParam<@Nullable String>();",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void testOKNewClassInstantiationForOtherAnnotations() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import lombok.NonNull;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            " static class NonNullTypeParam<E> {}",
            " static class DifferentAnnotTypeParam1<E extends @NonNull Object> {}",
            " static class DifferentAnnotTypeParam2<@NonNull E> {}",
            " static void testOKOtherAnnotation(NonNullTypeParam<String> t) {",
            "        // should not show error for annotation other than @Nullable",
            "        testOKOtherAnnotation(new NonNullTypeParam<@NonNull String>());",
            "        DifferentAnnotTypeParam1<String> t1 = new DifferentAnnotTypeParam1<String>();",
            "       // BUG: Diagnostic contains: Generic type parameter",
            "        DifferentAnnotTypeParam2<String> t2 = new DifferentAnnotTypeParam2<@Nullable String>();",
            "       // BUG: Diagnostic contains: Generic type parameter",
            "        DifferentAnnotTypeParam1<String> t3 = new DifferentAnnotTypeParam1<@Nullable String>();",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void downcastInstantiation() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  static class NonNullTypeParam<E> { }",
            "  static void instOf(Object o) {",
            "    // BUG: Diagnostic contains: Generic type parameter",
            "    Object p = (NonNullTypeParam<@Nullable String>) o;",
            "  }",
            "}")
        .doTest();
  }

  // assignment tree tests
  @Test
  public void genericsChecksForAssignments() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            " static class NullableTypeParam<E extends @Nullable Object> {}",
            " static class NullableTypeParamMultipleArguments<E1 extends @Nullable Object, E2> {}",
            " static class NullableTypeParamMultipleArgumentsNested<E1 extends @Nullable Object, E2, E3 extends @Nullable Object> {}",
            " static void testOKOtherAnnotation(NullableTypeParam<String> t) {",
            "       NullableTypeParam<String> t3;",
            "       // BUG: Diagnostic contains: Cannot assign from type",
            "        t3 = new NullableTypeParam<@Nullable String>();",
            "        NullableTypeParam<@Nullable String> t4;",
            "       // BUG: Diagnostic contains: Cannot assign from type",
            "        t4 = t;",
            "       NullableTypeParamMultipleArguments<String, String> t5 = new NullableTypeParamMultipleArguments<String, String>();",
            "       NullableTypeParamMultipleArguments<@Nullable String, String> t6 = new NullableTypeParamMultipleArguments<@Nullable String, String>();",
            "       // BUG: Diagnostic contains: Cannot assign from type",
            "       t5 = t6;",
            "       NullableTypeParam<NullableTypeParam<NullableTypeParam<@Nullable String>>> t7 = new NullableTypeParam<NullableTypeParam<NullableTypeParam<@Nullable String>>>();",
            "       NullableTypeParam<NullableTypeParam<NullableTypeParam<String>>> t8 = new NullableTypeParam<NullableTypeParam<NullableTypeParam<String>>>();",
            "       // BUG: Diagnostic contains: Cannot assign from type",
            "       t7 = t8;",
            "       NullableTypeParam<NullableTypeParam<NullableTypeParam<@Nullable String>>> t9 = new  NullableTypeParam<NullableTypeParam<NullableTypeParam<@Nullable String>>> ();",
            "       //No error",
            "       t7 = t9;",
            "       NullableTypeParamMultipleArguments<NullableTypeParam<NullableTypeParam<@Nullable String>>, String> t10 = new  NullableTypeParamMultipleArguments<NullableTypeParam<NullableTypeParam<@Nullable String>>, String> ();",
            "       NullableTypeParamMultipleArguments<NullableTypeParam<NullableTypeParam<String>>, String> t11 = new  NullableTypeParamMultipleArguments<NullableTypeParam<NullableTypeParam<String>>, String> ();",
            "       // BUG: Diagnostic contains: Cannot assign from type",
            "       t10 = t11;",
            "       NullableTypeParamMultipleArgumentsNested<NullableTypeParam<NullableTypeParam<@Nullable String>>, String, @Nullable String> t12 = new  NullableTypeParamMultipleArgumentsNested<NullableTypeParam<NullableTypeParam<@Nullable String>>, String, @Nullable String> ();",
            "       NullableTypeParamMultipleArgumentsNested<NullableTypeParam<NullableTypeParam<String>>, String, @Nullable String> t13 = new  NullableTypeParamMultipleArgumentsNested<NullableTypeParam<NullableTypeParam<String>>, String, @Nullable String>  ();",
            "       // BUG: Diagnostic contains: Cannot assign from type",
            "       t12 = t13;",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void superTypeAssignmentChecksSingleInterface() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface Fn<P extends @Nullable Object, R extends @Nullable Object>{}",
            "  class FnImpl implements Fn<@Nullable String, @Nullable String>{}",
            " void sampleError() {",
            "    // BUG: Diagnostic contains: Cannot assign from type",
            "   Fn<@Nullable String, String> f = new FnImpl();",
            " }",
            "  }")
        .doTest();
  }

  @Test
  public void superTypeAssignmentChecksMultipleInterface() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  interface Fn1<P1 extends @Nullable Object, P2 extends @Nullable Object>{}",
            "  interface Fn2<P extends @Nullable Object>{}",
            "  class FnImpl implements Fn1<@Nullable String, @Nullable String>, Fn2<String> {}",
            " void sampleError() {",
            "  Fn2<@Nullable String> f;",
            "    // BUG: Diagnostic contains: Cannot assign from type",
            "  f = new FnImpl();",
            " }",
            "  }")
        .doTest();
  }

  @Test
  public void superTypeAssignmentChecksMultipleLevelInheritance() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  class SuperClassC<P1 extends @Nullable Object>{}",
            "  class SuperClassB<P extends @Nullable Object> extends SuperClassC<P>{}",
            "  class SubClassA<P extends @Nullable Object> extends SuperClassB<P>{}",
            "  class FnImpl1 extends SubClassA<String>{}",
            "  class FnImpl2 extends SubClassA<@Nullable String>{}",
            "  void sampleError() {",
            "    SuperClassC<@Nullable String> f;",
            "    // BUG: Diagnostic contains: Cannot assign from type",
            "    f = new FnImpl1();",
            "   }",
            "  void sampleValidInstantiation() {",
            "  SuperClassC<@Nullable String> f;",
            "    // No error",
            "    f = new FnImpl2();",
            "  }",
            "  }")
        .doTest();
  }

  @Test
  public void nestedAssignmentChecks() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  class D<P extends @Nullable Object> {}",
            "  class B<P extends @Nullable Object> extends D<P>{}",
            "  class C<p extends @Nullable Object>{}",
            " void sampleError1() {",
            "    C<B<String>> f = new C<B<String>>();",
            "    // BUG: Diagnostic contains: Cannot assign from type",
            "    f = new C<B<@Nullable String>>();",
            " }",
            "  }")
        .doTest();
  }

  @Test
  public void subtypeWithParameters() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  class D<P extends @Nullable Object> {}",
            "  class B<P extends @Nullable Object> extends D<P>{}",
            " void test1() {",
            "    // BUG: Diagnostic contains: Cannot assign from type",
            "    D<String> f = new B<@Nullable String>();",
            " }",
            " void test2(B<@Nullable String> b) {",
            "    // BUG: Diagnostic contains: Cannot assign from type",
            "    D<String> f = b;",
            " }",
            "  }")
        .doTest();
  }

  @Test
  public void fancierSubtypeWithParameters() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  class Super<A extends @Nullable Object, B> {}",
            "  class Sub<C, D extends @Nullable Object> extends Super<D, C> {}",
            "  void test1() {",
            "    // valid assignment",
            "    Super<@Nullable String, String> s = new Sub<String, @Nullable String>();",
            "    // BUG: Diagnostic contains: Cannot assign from type",
            "    Super<@Nullable String, String> s2 = new Sub<@Nullable String, String>();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nestedVariableDeclarationChecks() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  class D<P extends @Nullable Object> {}",
            "  class B<P extends @Nullable Object> extends D<P>{}",
            "  class C<P extends @Nullable Object>{}",
            "  class A<T extends C<P>, P extends @Nullable Object>{}",
            " void sampleError() {",
            "   // BUG: Diagnostic contains: Cannot assign from type",
            "   D<C<String>> f3 = new B<@Nullable C<String>>();",
            " }",
            "  }")
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        Arrays.asList(
            "-XepOpt:NullAway:AnnotatedPackages=com.uber", "-XepOpt:NullAway:JSpecifyMode=true"));
  }
}
