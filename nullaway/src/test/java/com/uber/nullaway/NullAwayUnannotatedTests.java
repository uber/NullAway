package com.uber.nullaway;

import java.util.Arrays;
import org.junit.Test;

public class NullAwayUnannotatedTests extends NullAwayTestsBase {

  @Test
  public void coreNullabilitySkipClass() {
    defaultCompilationHelper
        .addSourceFile("Shape_Stuff.java")
        .addSourceFile("excluded/Shape_Stuff2.java")
        .addSourceFile("AnnotatedClass.java")
        .addSourceFile("TestAnnot.java")
        .doTest();
  }

  @Test
  public void skipClass() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:ExcludedClassAnnotations=com.uber.lib.MyExcluded"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "@com.uber.lib.MyExcluded",
            "public class Test {",
            "  static void bar() {",
            "    // No error",
            "    Object x = null; x.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void skipNestedClass() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:ExcludedClassAnnotations=com.uber.lib.MyExcluded"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  @com.uber.lib.MyExcluded",
            "  static class Inner {",
            "    @Nullable",
            "    static Object foo() {",
            "      Object x = null; x.toString();",
            "      return x;",
            "    }",
            "  }",
            "  static void bar() {",
            "    // BUG: Diagnostic contains: dereferenced expression Inner.foo()",
            "    Inner.foo().toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void coreNullabilitySkipPackage() {
    defaultCompilationHelper.addSourceFile("unannotated/UnannotatedClass.java").doTest();
  }

  @Test
  public void generatedAsUnannotated() {
    String generatedAnnot =
        (Double.parseDouble(System.getProperty("java.specification.version")) >= 11)
            ? "@javax.annotation.processing.Generated"
            : "@javax.annotation.Generated";
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:TreatGeneratedAsUnannotated=true"))
        .addSourceLines(
            "Generated.java",
            "package com.uber;",
            generatedAnnot + "(\"foo\")",
            "public class Generated { public void takeObj(Object o) {} }")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  void foo() { (new Generated()).takeObj(null); }",
            "}")
        .doTest();
  }

  @Test
  public void generatedAsUnannotatedCustomAnnotation() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:CustomGeneratedCodeAnnotations=com.uber.MyGeneratedMarkerAnnotation",
                "-XepOpt:NullAway:TreatGeneratedAsUnannotated=true"))
        .addSourceLines(
            "MyGeneratedMarkerAnnotation.java",
            "package com.uber;",
            "import java.lang.annotation.Retention;",
            "import java.lang.annotation.Target;",
            "import static java.lang.annotation.ElementType.CONSTRUCTOR;",
            "import static java.lang.annotation.ElementType.FIELD;",
            "import static java.lang.annotation.ElementType.TYPE;",
            "import static java.lang.annotation.ElementType.METHOD;",
            "import static java.lang.annotation.ElementType.PACKAGE;",
            "import static java.lang.annotation.RetentionPolicy.SOURCE;",
            "@Retention(SOURCE)",
            "@Target({PACKAGE, TYPE, METHOD, CONSTRUCTOR, FIELD})",
            "public @interface MyGeneratedMarkerAnnotation {}")
        .addSourceLines(
            "Generated.java",
            "package com.uber;",
            "@MyGeneratedMarkerAnnotation",
            "public class Generated { public void takeObj(Object o) {} }")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  void foo() { (new Generated()).takeObj(null); }",
            "}")
        .doTest();
  }

  @Test
  public void unannotatedClass() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedClasses=com.uber.UnAnnot"))
        .addSourceLines(
            "UnAnnot.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class UnAnnot {",
            "  @Nullable static Object retNull() { return null; }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  @Nullable static Object nullRetSameClass() { return null; }",
            "  void test() {",
            "    UnAnnot.retNull().toString();",
            // make sure other classes in the package still get analyzed
            "    Object x = nullRetSameClass();",
            "    // BUG: Diagnostic contains: dereferenced expression x is @Nullable",
            "    x.hashCode();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void overrideFailsOnExplicitlyNullableLibraryModelParam() {
    defaultCompilationHelper
        .addSourceLines( // Dummy android.view.GestureDetector.OnGestureListener interface
            "GestureDetector.java",
            "package android.view;",
            "public class GestureDetector {",
            "  public static interface OnGestureListener {",
            // Ignore other methods for this test, to make code shorter on both files:
            "    boolean onScroll(MotionEvent me1, MotionEvent me2, float f1, float f2);",
            "  }",
            "}")
        .addSourceLines( // Dummy android.view.MotionEvent class
            "MotionEvent.java", "package android.view;", "public class MotionEvent { }")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import android.view.GestureDetector;",
            "import android.view.MotionEvent;",
            "class Test implements GestureDetector.OnGestureListener {",
            "  Test() {  }",
            "  @Override",
            "  // BUG: Diagnostic contains: parameter me1 is @NonNull",
            "  public boolean onScroll(MotionEvent me1, MotionEvent me2, float f1, float f2) {",
            "    return false; // NoOp",
            "  }",
            "}")
        .addSourceLines(
            "Test2.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import android.view.GestureDetector;",
            "import android.view.MotionEvent;",
            "class Test2 implements GestureDetector.OnGestureListener {",
            "  Test2() {  }",
            "  @Override",
            "  public boolean onScroll(@Nullable MotionEvent me1, MotionEvent me2, float f1, float f2) {",
            "    return false; // NoOp",
            "  }",
            "}")
        .doTest();
  }

  /**
   * Ensure we don't crash for a type cast in unannotated code. See
   * https://github.com/uber/NullAway/issues/711
   */
  @Test
  public void typeCastInUnannotatedCode() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.other"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.function.Consumer;",
            "import org.junit.Assert;",
            "class Test {",
            "  private void verifyCountZero() {",
            "    verifyData((count) -> Assert.assertEquals(0, (long) count));",
            "  }",
            "  private void verifyData(Consumer<Long> assertFunction) {",
            "  }",
            "}")
        .doTest();
  }
}
