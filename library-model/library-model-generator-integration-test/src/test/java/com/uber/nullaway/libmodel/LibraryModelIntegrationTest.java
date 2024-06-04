package com.uber.nullaway.libmodel;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAway;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Integration test for library model support. The library models are contained in the jar for the
 * test-library-model-generator project, as a stubx file. These tests ensure that NullAway correctly
 * loads the stubx file and reports the right errors based on those models.
 */
public class LibraryModelIntegrationTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper compilationHelper;

  @Before
  public void setup() {
    compilationHelper = CompilationTestHelper.newInstance(NullAway.class, getClass());
  }

  @Test
  public void libraryModelNullableReturnsTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:JarInferEnabled=true",
                "-XepOpt:NullAway:JarInferUseReturnAnnotations=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.libmodel.AnnotationExample;",
            "class Test {",
            "  static AnnotationExample annotationExample = new AnnotationExample();",
            "  static void test(String value){",
            "  }",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'annotationExample.makeUpperCase(\"nullaway\")'",
            "    test(annotationExample.makeUpperCase(\"nullaway\"));",
            "  }",
            "  static void testNegative() {",
            "    test(annotationExample.nullReturn());",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void libraryModelNullableReturnsArrayTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:JarInferEnabled=true",
                "-XepOpt:NullAway:JarInferUseReturnAnnotations=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.libmodel.AnnotationExample;",
            "class Test {",
            "  static AnnotationExample annotationExample = new AnnotationExample();",
            "  static void test(Integer[] value){",
            "  }",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'annotationExample.generateIntArray(7)'",
            "    test(annotationExample.generateIntArray(7));",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void libraryModelWithoutJarInferEnabledTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.libmodel.AnnotationExample;",
            "class Test {",
            "  static AnnotationExample annotationExample = new AnnotationExample();",
            "  static void test(String value){",
            "  }",
            "  static void testNegative() {",
            "    // Since the JarInferEnabled and JarInferUseReturnAnnotations flags are not set, we don't get an error here",
            "    test(annotationExample.makeUpperCase(\"nullaway\"));",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void libraryModelInnerClassNullableReturnsTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:JarInferEnabled=true",
                "-XepOpt:NullAway:JarInferUseReturnAnnotations=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.nullaway.libmodel.AnnotationExample;",
            "class Test {",
            "  static AnnotationExample.InnerExample innerExample = new AnnotationExample.InnerExample();",
            "  static void test(String value){",
            "  }",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'innerExample.returnNull()'",
            "    test(innerExample.returnNull());",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void libraryModelInnerClassNullableUpperBoundsTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:JSpecifyMode=true",
                "-XepOpt:NullAway:JarInferEnabled=true",
                "-XepOpt:NullAway:JarInferUseReturnAnnotations=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "import com.uber.nullaway.libmodel.AnnotationExample;",
            "class Test {",
            "  static AnnotationExample.UpperBoundExample<@Nullable Object> upperBoundExample = new AnnotationExample.UpperBoundExample<@Nullable Object>();",
            "  static void test(Object value){",
            "  }",
            "  static void testPositive() {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'upperBoundExample.getNull()'",
            "    test(upperBoundExample.getNull());",
            "  }",
            "}")
        .doTest();
  }
}
