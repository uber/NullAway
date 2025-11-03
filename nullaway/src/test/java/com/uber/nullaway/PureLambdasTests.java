package com.uber.nullaway;

import org.junit.Test;

/**
 * Tests for preserving environment nullness facts for lambdas passed to pure methods
 * (methods annotated with @Contract(pure = true), Checker Framework @Pure or @SideEffectFree).
 * Variables captured in such lambdas should not be treated as automatically nullable; instead,
 * the facts at the call site should be visible in the lambda body.
 */
public class PureLambdasTests extends NullAwayTestsBase {

  @Test
  public void pureMethodLambdaPreservesFacts() {
    defaultCompilationHelper
        .addSourceLines(
            "com/example/library/PureLibrary.java",
            "package com.example.library;",
            "import org.jetbrains.annotations.Contract;",
            "import java.util.function.Consumer;",
            "public class PureLibrary {",
            "  @Contract(pure = true)",
            "  public static <T> void withConsumer(T t, Consumer<T> consumer) {",
            "    consumer.accept(t);",
            "  }",
            "}")
        .addSourceLines(
            "com/uber/Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import com.example.library.PureLibrary;",
            "public class Test {",
            "  private @Nullable Object f;",
            "  public void test1() {",
            "    if (this.f == null) {",
            "      throw new IllegalArgumentException();",
            "    }",
            "    // f is known non-null after the check; that fact should be visible in the lambda",
            "    PureLibrary.withConsumer(\"x\", v -> System.out.println(this.f.toString()));",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nonPureMethodLambdaDoesNotPreserveFacts() {
    defaultCompilationHelper
        .addSourceLines(
            "com/example/library/OrdinaryLibrary.java",
            "package com.example.library;",
            "import java.util.function.Consumer;",
            "public class OrdinaryLibrary {",
            "  public static <T> void withConsumer(T t, Consumer<T> consumer) {",
            "    consumer.accept(t);",
            "  }",
            "}")
        .addSourceLines(
            "com/uber/Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import com.example.library.OrdinaryLibrary;",
            "public class Test {",
            "  private @Nullable Object f;",
            "  public void test1() {",
            "    if (this.f == null) {",
            "      throw new IllegalArgumentException();",
            "    }",
            "    // Without purity annotation, we should not propagate the non-null fact into the lambda",
            "    // BUG: Diagnostic contains: dereferenced expression this.f is @Nullable",
            "    OrdinaryLibrary.withConsumer(\"x\", v -> System.out.println(this.f.toString()));",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nonJetbrainsPureContractAnnotationPreservesFacts() {
    defaultCompilationHelper
        .addSourceLines(
            "com/example/library/Contract.java",
            "package com.example.library;",
            "import static java.lang.annotation.RetentionPolicy.CLASS;",
            "import java.lang.annotation.Retention;",
            "import java.lang.annotation.Target;",
            "import java.lang.annotation.ElementType;",
            "@Retention(CLASS)",
            "@Target(ElementType.METHOD)",
            "public @interface Contract {",
            "  boolean pure() default false;",
            "  String value() default \"\";",
            "}")
        .addSourceLines(
            "com/example/library/PureLibrary2.java",
            "package com.example.library;",
            "import java.util.function.Consumer;",
            "public class PureLibrary2 {",
            "  @Contract(pure = true)",
            "  public static <T> void withConsumer(T t, Consumer<T> consumer) {",
            "    consumer.accept(t);",
            "  }",
            "}")
        .addSourceLines(
            "com/uber/Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import com.example.library.PureLibrary2;",
            "public class Test {",
            "  private @Nullable Object f;",
            "  public void test1() {",
            "    if (this.f == null) {",
            "      throw new IllegalArgumentException();",
            "    }",
            "    // f is known non-null after the check; that fact should be visible in the lambda",
            "    PureLibrary2.withConsumer(\"x\", v -> System.out.println(this.f.toString()));",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void checkerFrameworkPureAnnotationPreservesFacts() {
    defaultCompilationHelper
        .addSourceLines(
            "org/checkerframework/dataflow/qual/Pure.java",
            "package org.checkerframework.dataflow.qual;",
            "import static java.lang.annotation.RetentionPolicy.CLASS;",
            "import java.lang.annotation.Retention;",
            "import java.lang.annotation.Target;",
            "import java.lang.annotation.ElementType;",
            "@Retention(CLASS)",
            "@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})",
            "public @interface Pure {}")
        .addSourceLines(
            "com/example/library/PureLibraryCF.java",
            "package com.example.library;",
            "import java.util.function.Consumer;",
            "import org.checkerframework.dataflow.qual.Pure;",
            "public class PureLibraryCF {",
            "  @Pure",
            "  public static <T> void withConsumer(T t, Consumer<T> consumer) {",
            "    consumer.accept(t);",
            "  }",
            "}")
        .addSourceLines(
            "com/uber/TestCFPure.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import com.example.library.PureLibraryCF;",
            "public class TestCFPure {",
            "  private @Nullable Object f;",
            "  public void test1() {",
            "    if (this.f == null) {",
            "      throw new IllegalArgumentException();",
            "    }",
            "    PureLibraryCF.withConsumer(\"x\", v -> System.out.println(this.f.toString()));",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void checkerFrameworkSideEffectFreeAnnotationPreservesFacts() {
    defaultCompilationHelper
        .addSourceLines(
            "org/checkerframework/dataflow/qual/SideEffectFree.java",
            "package org.checkerframework.dataflow.qual;",
            "import static java.lang.annotation.RetentionPolicy.CLASS;",
            "import java.lang.annotation.Retention;",
            "import java.lang.annotation.Target;",
            "import java.lang.annotation.ElementType;",
            "@Retention(CLASS)",
            "@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})",
            "public @interface SideEffectFree {}")
        .addSourceLines(
            "com/example/library/PureLibraryCF2.java",
            "package com.example.library;",
            "import java.util.function.Consumer;",
            "import org.checkerframework.dataflow.qual.SideEffectFree;",
            "public class PureLibraryCF2 {",
            "  @SideEffectFree",
            "  public static <T> void withConsumer(T t, Consumer<T> consumer) {",
            "    consumer.accept(t);",
            "  }",
            "}")
        .addSourceLines(
            "com/uber/TestCFSEF.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import com.example.library.PureLibraryCF2;",
            "public class TestCFSEF {",
            "  private @Nullable Object f;",
            "  public void test1() {",
            "    if (this.f == null) {",
            "      throw new IllegalArgumentException();",
            "    }",
            "    PureLibraryCF2.withConsumer(\"x\", v -> System.out.println(this.f.toString()));",
            "  }",
            "}")
        .doTest();
  }
}
