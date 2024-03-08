package com.uber.nullaway.libmodel;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAway;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LibModelIntegrationTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper compilationHelper;

  @Before
  public void setup() {
    compilationHelper = CompilationTestHelper.newInstance(NullAway.class, getClass());
  }

  @Test
  public void libModelNullableReturnsTest() {
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
  public void libModelNullableReturnsArrayTest() {
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
            "  static void test1() {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'annotationExample.generateIntArray(7)'",
            "    test(annotationExample.generateIntArray(7));",
            "  }",
            "}")
        .doTest();
  }
}
