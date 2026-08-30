package com.uber.nullaway;

import static com.uber.nullaway.ErrorProneCLIFlagsConfig.ANNOTATED_PACKAGES_ONLY_NULLMARKED_ERROR_MSG;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.errorprone.ErrorProneFlags;
import com.uber.nullaway.tools.DualModeCompilationTestHelper;
import com.uber.nullaway.tools.SkipBytecodeTestMode;
import java.util.List;
import java.util.Map;
import org.junit.Assume;
import org.junit.Test;

@SkipBytecodeTestMode("the tests assert on the error a misconfigured compilation throws")
public class ErrorProneCLIFlagsConfigTest extends NullAwayTestsBase {

  @Test
  public void noFlagsFails() {
    DualModeCompilationTestHelper compilationTestHelper =
        makeTestHelperWithArgs(List.of())
            .addSourceLines("Stub.java", "package com.uber; class Stub {}");
    AssertionError e = assertThrows(AssertionError.class, () -> compilationTestHelper.doTest());
    assertTrue(e.getMessage().contains(ANNOTATED_PACKAGES_ONLY_NULLMARKED_ERROR_MSG));
  }

  @Test
  public void onlyNullMarkedOk() {
    makeTestHelperWithArgs(List.of("-XepOpt:NullAway:OnlyNullMarked"))
        .addSourceLines(
            "Test.java",
            """
            package foo.baz;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Marked {
              // BUG: Diagnostic contains: @NonNull field 'uninit' not initialized
              Object uninit;
            }
            """)
        .doTest();
  }

  @Test
  public void onlyNullMarkedFalseFails() {
    DualModeCompilationTestHelper compilationTestHelper =
        makeTestHelperWithArgs(List.of("-XepOpt:NullAway:OnlyNullMarked=false"))
            .addSourceLines("Stub.java", "package com.uber; class Stub {}");
    AssertionError e = assertThrows(AssertionError.class, () -> compilationTestHelper.doTest());
    assertTrue(e.getMessage().contains(ANNOTATED_PACKAGES_ONLY_NULLMARKED_ERROR_MSG));
  }

  @Test
  public void bothAnnotatedPackagesAndOnlyNullMarkedFails() {
    DualModeCompilationTestHelper compilationTestHelper =
        makeTestHelperWithArgs(
                List.of(
                    "-XepOpt:NullAway:OnlyNullMarked",
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
            .addSourceLines("Stub.java", "package com.uber; class Stub {}");
    AssertionError e = assertThrows(AssertionError.class, () -> compilationTestHelper.doTest());
    assertTrue(e.getMessage().contains(ANNOTATED_PACKAGES_ONLY_NULLMARKED_ERROR_MSG));
  }

  @Test
  public void missingTypeAnnotationSymbolFlagForJSpecifyModeOnOlderJDK() {
    Assume.assumeTrue(Runtime.version().feature() < 22);
    DualModeCompilationTestHelper compilationTestHelper =
        makeTestHelperWithArgs(
                List.of("-XepOpt:NullAway:OnlyNullMarked", "-XepOpt:NullAway:JSpecifyMode=true"))
            .addSourceLines("Stub.java", "package com.uber; class Stub {}");
    AssertionError e = assertThrows(AssertionError.class, () -> compilationTestHelper.doTest());
    assertTrue(
        e.getMessage().contains("Running NullAway in JSpecify mode requires either JDK 22+"));
  }

  @Test
  public void jspecifyJDKOutsideJSpecifyMode() {
    DualModeCompilationTestHelper compilationTestHelper =
        makeTestHelperWithArgs(
                List.of(
                    "-XepOpt:NullAway:OnlyNullMarked", "-XepOpt:NullAway:JSpecifyJDKModels=true"))
            .addSourceLines("Stub.java", "package com.uber; class Stub {}");
    AssertionError e = assertThrows(AssertionError.class, () -> compilationTestHelper.doTest());
    assertTrue(e.getMessage().contains("should only be set in JSpecify mode"));
  }

  @Test
  public void jspecifyExperimentalEnablesExperimentalFeatures() {
    ErrorProneCLIFlagsConfig config =
        new ErrorProneCLIFlagsConfig(
            ErrorProneFlags.fromMap(
                Map.of(
                    "NullAway:AnnotatedPackages", "foo",
                    "NullAway:JSpecifyMode", "true",
                    "NullAway:JSpecifyExperimental", "true")));

    assertTrue(config.isJSpecifyJDKModels());
    assertTrue(config.handleWildcardGenerics());
  }
}
