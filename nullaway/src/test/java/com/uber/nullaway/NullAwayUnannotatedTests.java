package com.uber.nullaway;

import com.google.errorprone.CompilationTestHelper;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NullAwayUnannotatedTests {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper defaultCompilationHelper;

  /**
   * Creates a new {@link CompilationTestHelper} with a list of javac arguments. As of Error Prone
   * 2.5.1, {@link CompilationTestHelper#setArgs(List)} can only be invoked once per object. So,
   * this method must be used to create a test helper when a different set of javac arguments is
   * required than those used for {@link #defaultCompilationHelper}.
   *
   * @param args the javac arguments
   * @return the test helper
   */
  private CompilationTestHelper makeTestHelperWithArgs(List<String> args) {
    return CompilationTestHelper.newInstance(NullAway.class, getClass()).setArgs(args);
  }

  @SuppressWarnings("CheckReturnValue")
  @Before
  public void setup() {
    defaultCompilationHelper =
        CompilationTestHelper.newInstance(NullAway.class, getClass())
            .setArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:KnownInitializers="
                        + "com.uber.nullaway.testdata.CheckFieldInitNegativeCases.Super.doInit,"
                        + "com.uber.nullaway.testdata.CheckFieldInitNegativeCases"
                        + ".SuperInterface.doInit2",
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber,com.ubercab,io.reactivex",
                    // We give the following in Regexp format to test that support
                    "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.nullaway.[a-zA-Z0-9.]+.unannotated",
                    "-XepOpt:NullAway:ExcludedClasses="
                        + "com.uber.nullaway.testdata.Shape_Stuff,"
                        + "com.uber.nullaway.testdata.excluded",
                    "-XepOpt:NullAway:ExcludedClassAnnotations=com.uber.nullaway.testdata.TestAnnot",
                    "-XepOpt:NullAway:CastToNonNullMethod=com.uber.nullaway.testdata.Util.castToNonNull",
                    "-XepOpt:NullAway:ExternalInitAnnotations=com.uber.ExternalInit",
                    "-XepOpt:NullAway:ExcludedFieldAnnotations=com.uber.ExternalFieldInit"));
  }

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
}
