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
  public void jspecifyModeNullMarked() {
    makeLibraryModelsTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.NullMarkedVarargsWithModel;
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
              void testNegative() {
                String x = null;
                String[] y = null;
                NullMarkedVarargsWithModel.nullableContents(x, x);
                NullMarkedVarargsWithModel.nullableArray(y);
                NullMarkedVarargsWithModel.bothNullable(x, x);
                NullMarkedVarargsWithModel.bothNullable(y);
              }
              void testPositive() {
                String x = null;
                String[] y = null;
                // BUG: Diagnostic contains: passing @Nullable parameter 'y' where @NonNull is required
                NullMarkedVarargsWithModel.nullableContents(y);
                // BUG: Diagnostic contains: passing @Nullable parameter 'x' where @NonNull is required
                NullMarkedVarargsWithModel.nullableArray(x, x);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void jspecifyModeRestrictive() {
    makeLibraryModelsTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.NullUnmarkedVarargsWithModel;
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
              void testNegative() {
                String x = null;
                String[] y = null;
                NullUnmarkedVarargsWithModel.nonNullContents(y);
                NullUnmarkedVarargsWithModel.nonNullArray(x, x);
              }
              void testPositive() {
                String x = null;
                String[] y = null;
                // BUG: Diagnostic contains: passing @Nullable parameter 'y' where @NonNull is required
                NullUnmarkedVarargsWithModel.nonNullArray(y);
                // BUG: Diagnostic contains: passing @Nullable parameter 'x' where @NonNull is required
                NullUnmarkedVarargsWithModel.nonNullContents(x, x);
                // BUG: Diagnostic contains: passing @Nullable parameter 'y' where @NonNull is required
                NullUnmarkedVarargsWithModel.bothNonNull(y);
                // BUG: Diagnostic contains: passing @Nullable parameter 'x' where @NonNull is required
                NullUnmarkedVarargsWithModel.bothNonNull(x, x);
              }
            }
            """)
        .doTest();
  }
}
