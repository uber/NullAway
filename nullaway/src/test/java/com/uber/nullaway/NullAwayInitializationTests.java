package com.uber.nullaway;

import java.util.Arrays;
import org.junit.Test;

public class NullAwayInitializationTests extends NullAwayTestsBase {
  @Test
  public void initFieldPositiveCases() {
    defaultCompilationHelper.addSourceFile("CheckFieldInitPositiveCases.java").doTest();
  }

  @Test
  public void initFieldNegativeCases() {
    defaultCompilationHelper.addSourceFile("CheckFieldInitNegativeCases.java").doTest();
  }

  @Test
  public void readBeforeInitPositiveCases() {
    defaultCompilationHelper
        .addSourceFile("ReadBeforeInitPositiveCases.java")
        .addSourceFile("Util.java")
        .doTest();
  }

  @Test
  public void readBeforeInitNegativeCases() {
    defaultCompilationHelper
        .addSourceFile("ReadBeforeInitNegativeCases.java")
        .addSourceFile("Util.java")
        .doTest();
  }

  @Test
  public void externalInitSupport() {
    defaultCompilationHelper
        .addSourceLines(
            "ExternalInit.java",
            "package com.uber;",
            "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)",
            "public @interface ExternalInit {}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "@ExternalInit",
            "class Test {",
            "  Object f;",
            // no error here due to external init
            "  public Test() {}",
            "  // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field",
            "  public Test(int x) {}",
            "}")
        .addSourceLines(
            "Test2.java",
            "package com.uber;",
            "@ExternalInit",
            "class Test2 {",
            // no error here due to external init
            "  Object f;",
            "}")
        .addSourceLines(
            "Test3.java",
            "package com.uber;",
            "@ExternalInit",
            "class Test3 {",
            "  Object f;",
            "  // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field",
            "  public Test3(int x) {}",
            "}")
        .doTest();
  }

  @Test
  public void externalInitSupportConstructors() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:ExternalInitAnnotations=com.uber.ExternalInitConstructor"))
        .addSourceLines(
            "ExternalInitConstructor.java",
            "package com.uber;",
            "import java.lang.annotation.ElementType;",
            "import java.lang.annotation.Retention;",
            "import java.lang.annotation.RetentionPolicy;",
            "import java.lang.annotation.Target;",
            "@Retention(RetentionPolicy.CLASS)",
            "@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})",
            "public @interface ExternalInitConstructor {}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  Object f;",
            // no error here due to external init
            "  @ExternalInitConstructor",
            "  public Test() {}",
            "  // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field",
            "  public Test(int x) {}",
            "  public Test(Object o) { this.f = o; }",
            "}")
        .addSourceLines(
            "Test2.java",
            "package com.uber;",
            "class Test2 {",
            "  // BUG: Diagnostic contains: @NonNull field f not initialized",
            "  Object f;",
            // must be on a constructor!
            "  @ExternalInitConstructor",
            "  public void init() {}",
            "}")
        .addSourceLines(
            "Test3.java",
            "package com.uber;",
            "class Test3 {",
            "  Object f;",
            // Must be zero-args constructor!
            "  @ExternalInitConstructor",
            "  // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field",
            "  public Test3(int x) {}",
            "}")
        .doTest();
  }

  @Test
  public void externalInitSupportFields() {
    defaultCompilationHelper
        .addSourceLines(
            "ExternalFieldInit.java",
            "package com.uber;",
            "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)",
            "public @interface ExternalFieldInit {}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  @ExternalFieldInit Object f;",
            // no error here due to external init
            "  public Test() {}",
            // no error here due to external init
            "  public Test(int x) {}",
            "}")
        .addSourceLines(
            "Test2.java",
            "package com.uber;",
            "class Test2 {",
            // no error here due to external init
            "  @ExternalFieldInit Object f;",
            "}")
        .addSourceLines(
            "Test3.java",
            "package com.uber;",
            "class Test3 {",
            "  @ExternalFieldInit Object f;",
            // no error here due to external init
            "  @ExternalFieldInit", // See GitHub#184
            "  public Test3() {}",
            // no error here due to external init
            "  @ExternalFieldInit", // See GitHub#184
            "  public Test3(int x) {}",
            "}")
        .doTest();
  }

  @Test
  public void testEnumInit() {
    defaultCompilationHelper
        .addSourceLines(
            "SomeEnum.java",
            "package com.uber;",
            "import java.util.Random;",
            "enum SomeEnum {",
            "  FOO, BAR;",
            "  final Object o;",
            "  final Object p;",
            "  private SomeEnum() {",
            "    this.o = new Object();",
            "    this.p = new Object();",
            "    this.o.equals(this.p);",
            "  }",
            "}")
        .doTest();
  }
}
