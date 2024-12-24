package com.uber.nullaway.jarinfer;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAway;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Ignore;
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
  public void arrayTest() {
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
            "import org.jspecify.annotations.Nullable;",
            "import com.uber.nullaway.jarinfer.toys.unannotated.Toys;",
            "class Test {",
            "  void test1(Object @Nullable [] o) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'o'",
            "    Toys.testArray(o);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void genericsTest() {
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
            "import org.jspecify.annotations.Nullable;",
            "import com.uber.nullaway.jarinfer.toys.unannotated.Toys;",
            "class Test {",
            "  void test1(Toys.Generic<String> g) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null'",
            "    g.getString(null);",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null'",
            "    Toys.genericParam(null);",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null'",
            "    Toys.nestedGenericParam(null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void wildcards() {
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
            "import org.jspecify.annotations.Nullable;",
            "import com.uber.nullaway.jarinfer.toys.unannotated.Toys;",
            "class Test {",
            "  void test1() {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null'",
            "    Toys.genericWildcard(null);",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null'",
            "    Toys.nestedGenericWildcard(null);",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null'",
            "    Toys.genericWildcardUpper(null);",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null'",
            "    Toys.genericWildcardLower(null);",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null'",
            "    Toys.doubleGenericWildcard(\"\", null);",
            "    Toys.doubleGenericWildcardNullOk(\"\", null);",
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
                "-XepOpt:NullAway:JarInferEnabled=true"))
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

  /**
   * Tests our pre-generated models for Android SDK classes. See also the build.gradle file for this
   * project which determines which SDK version's models are being tested.
   */
  @Test
  @Ignore(
      "temporarily ignore while making some astubx format changes; see https://github.com/uber/NullAway/issues/1072")
  public void jarInferAndroidSDKModels() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:JarInferEnabled=true"))
        // stub implementation of SpannableStringBuilder.append(CharSequence) which we know is
        // modelled as having a @Nullable parameter
        .addSourceLines(
            "SpannableStringBuilder.java",
            "package android.text;",
            "public class SpannableStringBuilder {",
            "  public SpannableStringBuilder append(CharSequence text) { return this; }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  void test1(android.text.SpannableStringBuilder builder) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'null'",
            "    builder.append(null);",
            "  }",
            "}")
        .doTest();
  }
}
