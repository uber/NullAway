package com.uber.nullaway.jdk17;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAway;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ModuleInfoTests {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper defaultCompilationHelper;

  @Before
  public void setup() {
    defaultCompilationHelper =
        CompilationTestHelper.newInstance(NullAway.class, getClass())
            .setArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    // The module path system property is set in the build.gradle file to just
                    // include the jar for the Checker Framework qualifier annotations
                    "--module-path",
                    System.getProperty("test.module.path"),
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber"));
  }

  @Test
  public void testModuleInfo() {
    // just check that the tool doesn't crash
    defaultCompilationHelper
        .addSourceLines(
            "module-info.java",
            "@SuppressWarnings(\"some-warning-name\")",
            "module com.uber.mymodule {",
            "  // Important: two-level deep module tests matching of identifier `java` as base expression;",
            "  // see further discussion at https://github.com/uber/NullAway/pull/544#discussion_r780829467",
            "  requires java.base;",
            "  requires static org.checkerframework.checker.qual;",
            "}")
        .doTest();
  }

  @Test
  public void nullmarkedModule() {
    defaultCompilationHelper
        .addSourceLines(
            "module-info.java",
            "import org.jspecify.annotations.NullMarked;",
            "@NullMarked",
            "module com.example.myapp {",
            "    exports com.example.myapp;",
            "    requires java.base;",
            "    requires org.jspecify;",
            "}")
        .addSourceLines(
            "com/example/myapp/Test.java",
            "package com.example.myapp;",
            "public class Test {",
            "  public static void main(String[] args) {",
            "    String s = null;",
            "    // BUG: Diagnostic contains: dereferenced expression s is @Nullable",
            "    s.hashCode();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullUnmarkedPackageInNullMarkedModule() {
    defaultCompilationHelper
        .addSourceLines(
            "module-info.java",
            "import org.jspecify.annotations.NullMarked;",
            "@NullMarked",
            "module com.example.myapp {",
            "    exports com.example.myapp;",
            "    requires java.base;",
            "    requires org.jspecify;",
            "}")
        .addSourceLines(
            "com/example/myapp/package-info.java",
            "@NullUnmarked package com.example.myapp;",
            "import org.jspecify.annotations.NullUnmarked;")
        .addSourceLines(
            "com/example/myapp/Test.java",
            "package com.example.myapp;",
            "public class Test {",
            "  public static void main(String[] args) {",
            "    String s = null;",
            "    // no error since @NullUnmarked is in effect",
            "    s.hashCode();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void fromBytecode() {
    defaultCompilationHelper
        .addSourceLines(
            "module-info.java",
            "import org.jspecify.annotations.NullMarked;",
            "@NullMarked",
            "module com.example.myapp {",
            "    requires java.base;",
            "    requires org.jspecify;",
            "    requires com.uber.test.java.module;",
            "}")
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.NullMarked;",
            "import com.example.nullmarked.NullMarkedFromModule;",
            "import com.example.nullunmarked.NullUnmarkedFromPackage;",
            "@NullMarked",
            "class Test {",
            "  void testPositive() {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter",
            "    NullMarkedFromModule.takesNonNull(null);",
            "  }",
            "  void testNegative() {",
            "    NullUnmarkedFromPackage.takesAny(null);",
            "  }",
            "}")
        .doTest();
  }
}
