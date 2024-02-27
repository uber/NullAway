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
  public void libModelArrayTypeNullableReturnsTest() {
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
            "import javax.annotation.Nullable;",
            "import java.util.Vector;",
            "class Test {",
            "  static Vector<Integer> vector = new Vector<>();",
            "  static void test(Object[] value){",
            "  }",
            "  static void test1() {",
            "    vector.add(1);",
            "    test(vector.toArray());",
            "  }",
            "}")
        .doTest();
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
            "import javax.annotation.Nullable;",
            "import java.util.Scanner;",
            "class Test {",
            "  static Scanner scanner = new Scanner(\"nullaway test\");",
            "  static void test(String value){",
            "  }",
            "  static void test1() {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'scanner.findInLine(\"world\")'",
            "    test(scanner.findInLine(\"world\"));",
            "  }",
            "}")
        .doTest();
  }
}
