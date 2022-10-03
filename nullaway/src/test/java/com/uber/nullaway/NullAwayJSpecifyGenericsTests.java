package com.uber.nullaway;

import org.junit.Test;

public class NullAwayJSpecifyGenericsTests extends NullAwayTestsBase {

  @Test
  public void basicTypeParamInstantiation() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.nullness.Nullable;",
            "class Test {",
            "    static class NonNullTypeParam<E> {}",
            "    static class NullableTypeParam<E extends @Nullable Object> {}",
            "    static void testOkNonNull(NonNullTypeParam<String> t) {",
            "        NonNullTypeParam<String> t2 = new NonNullTypeParam<String>();",
            "    }",
            "    // BUG: Diagnostic contains: Generic type parameter",
            "    static void testBadNonNull(NonNullTypeParam<@Nullable String> t) {",
            "        // BUG: Diagnostic contains: Generic type parameter",
            "        NonNullTypeParam<@Nullable String> t2 = null;",
            "        // BUG: Diagnostic contains: Generic type parameter",
            "        t2 = new NonNullTypeParam<@Nullable String>();",
            "        // BUG: Diagnostic contains: Generic type parameter",
            "        testBadNonNull(new NonNullTypeParam<@Nullable String>());",
            "    }",
            "    static void testOkNullable(NullableTypeParam<String> t1, NullableTypeParam<@Nullable String> t2) {",
            "        NullableTypeParam<String> t3 = new NullableTypeParam<String>();",
            "        NullableTypeParam<@Nullable String> t4 = new NullableTypeParam<@Nullable String>();",
            "    }",
            "    // BUG: Diagnostic contains: Generic type parameter",
            "    static class InvalidSubclass extends NonNullTypeParam<@Nullable String> {}",
            "}")
        .doTest();
  }
}
