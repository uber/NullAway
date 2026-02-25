package com.uber.nullaway;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class VarargsLibraryModelsTests {

  private CompilationTestHelper makeLibraryModelsTestHelperWithArgs(List<String> args) {
    return CompilationTestHelper.newInstance(NullAway.class, getClass()).setArgs(args);
  }

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void jspecifyModeIndividual() {
    makeLibraryModelsTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.NestedAnnots;
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
              void testNegativeArray() {
                String[] x = null;
                NestedAnnots.varargs(x);
              }
              void testNegativeIndividual() {
                NestedAnnots.varargs(null, null);
              }
            }
            """)
        .doTest();
  }
}
