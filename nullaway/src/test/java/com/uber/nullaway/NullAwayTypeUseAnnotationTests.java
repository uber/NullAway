package com.uber.nullaway;

import org.junit.Test;

public class NullAwayTypeUseAnnotationTests extends NullAwayTestsBase {
  @Test
  public void typeParameterAnnotationIsDistinctFromMethodReturnAnnotation() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import java.util.function.Supplier;",
            "public class Test {",
            "  public static <R> @Nullable Supplier<@Nullable R> getNullableSupplierOfNullable() {",
            "    return new Supplier<R>() {",
            "      @Nullable",
            "      public R get() { return null; }",
            "    };",
            "  }",
            "  public static <R> Supplier<@Nullable R> getNonNullSupplierOfNullable() {",
            "    return new Supplier<R>() {",
            "      @Nullable",
            "      public R get() { return null; }",
            "    };",
            "  }",
            "  public static String test1() {",
            "    // BUG: Diagnostic contains: dereferenced expression getNullableSupplierOfNullable() is @Nullable",
            "    return getNullableSupplierOfNullable().toString();",
            "  }",
            "  public static String test2() {",
            "    // The supplier contains null, but isn't itself nullable. Check against a past FP",
            "    return getNonNullSupplierOfNullable().toString();",
            "  }",
            "}")
        .doTest();
  }
}
