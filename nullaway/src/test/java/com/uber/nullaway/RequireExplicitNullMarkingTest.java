package com.uber.nullaway;

import com.google.errorprone.CompilationTestHelper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RequireExplicitNullMarkingTest {

  private CompilationTestHelper compilationHelper;
  private CompilationTestHelper compilationHelperSuggestionLevel;

  @Before
  public void setUp() {
    compilationHelper =
        CompilationTestHelper.newInstance(RequireExplicitNullMarking.class, getClass())
            .setArgs("-Xep:RequireExplicitNullMarking:WARN");
    compilationHelperSuggestionLevel =
        CompilationTestHelper.newInstance(RequireExplicitNullMarking.class, getClass());
  }

  @Test
  public void missingNullMarkedAnnotationOnTopLevelClass() {
    compilationHelper
        .addSourceLines(
            "test/MissingAnnotation.java",
            "package test;",
            "// BUG: Diagnostic contains: Top-level classes must either be directly annotated",
            "class MissingAnnotation {",
            "  // no report on nested class",
            "  class NestedClass {}",
            "}")
        .doTest();
  }

  @Test
  public void defaultURL() {
    compilationHelper
        .addSourceLines(
            "test/MissingAnnotation.java",
            "package test;",
            "// BUG: Diagnostic contains: https://github.com/uber/NullAway/wiki/JSpecify-Support#requireexplicitnullmarking-checker ",
            "class MissingAnnotation {",
            "}")
        .doTest();
  }

  /** Test that at SUGGESTION level, no report is made for missing annotations */
  @Test
  public void noReportAtDefaultLevel() {
    compilationHelperSuggestionLevel
        .addSourceLines(
            "test/MissingAnnotation.java",
            "package test;",
            "// no warning here since we're at the default SUGGESTION level",
            "class MissingAnnotation {",
            "}")
        .doTest();
  }

  @Test
  public void directNullMarkedAnnotationAllowed() {
    compilationHelper
        .addSourceLines(
            "test/DirectMarked.java",
            "package test;",
            "import org.jspecify.annotations.NullMarked;",
            "@NullMarked",
            "class DirectMarked {}")
        .doTest();
  }

  @Test
  public void directNullUnmarkedAnnotationAllowed() {
    compilationHelper
        .addSourceLines(
            "test/DirectUnmarked.java",
            "package test;",
            "import org.jspecify.annotations.NullUnmarked;",
            "@NullUnmarked",
            "class DirectUnmarked {}")
        .doTest();
  }

  @Test
  public void packageLevelAnnotationAllowsMissingClassAnnotation() {
    compilationHelper
        .addSourceLines(
            "test/package-info.java", "@org.jspecify.annotations.NullMarked", "package test;")
        .addSourceLines(
            "test/PackageMarkedClass.java", "package test;", "class PackageMarkedClass {}")
        .doTest();
  }
}
