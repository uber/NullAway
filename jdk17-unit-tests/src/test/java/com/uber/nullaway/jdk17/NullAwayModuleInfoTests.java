package com.uber.nullaway.jdk17;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAway;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NullAwayModuleInfoTests {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper defaultCompilationHelper;

  @Before
  public void setup() {
    // TODO set up module path
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
            "module com.uber.mymodule {",
            "  requires static org.checkerframework.checker.qual;",
            "}")
        .doTest();
  }
}
