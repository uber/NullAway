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

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        Arrays.asList(
            "-XepOpt:NullAway:AnnotatedPackages=com.uber", "-XepOpt:NullAway:JSpecifyMode=true"));
  }
}
