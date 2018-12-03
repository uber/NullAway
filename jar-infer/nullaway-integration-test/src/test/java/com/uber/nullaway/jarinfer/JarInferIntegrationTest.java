package com.uber.nullaway.jarinfer;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAway;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JarInferIntegrationTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper compilationHelper;

  @Before
  public void setup() {
    compilationHelper = CompilationTestHelper.newInstance(NullAway.class, getClass());
  }

  @Test
  public void jarinferLoadStubsTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:JarInferEnabled=true",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.nullaway.[a-zA-Z0-9.]+.unannotated"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.jarinfer.toys.unannotated.Toys;",
            "class Test {",
            "  void test1(@Nullable String s) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 's'",
            "    Toys.test1(s, \"let's\", \"try\");",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void jarinferNullableReturnsTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.nullaway.[a-zA-Z0-9.]+.unannotated",
                "-XepOpt:NullAway:JarInferEnabled=true",
                "-XepOpt:NullAway:JarInferUseReturnAnnotations=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.jarinfer.toys.unannotated.Toys;",
            "class Test {",
            "  void test1(@Nullable String s) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'Toys.getString(false, s)'",
            "    Toys.test1(Toys.getString(false, s), \"let's\", \"try\");",
            "  }",
            "}")
        .doTest();
  }
}
