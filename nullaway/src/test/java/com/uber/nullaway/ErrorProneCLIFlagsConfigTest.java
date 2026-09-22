package com.uber.nullaway;

import static com.uber.nullaway.ErrorProneCLIFlagsConfig.ANNOTATED_PACKAGES_ONLY_NULLMARKED_ERROR_MSG;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.uber.nullaway.generics.JSpecifyJavacConfig.JavacConfigValidityResult;
import java.util.List;
import java.util.Map;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ErrorProneCLIFlagsConfigTest extends NullAwayTestsBase {

  @Test
  public void noFlagsFails() {
    CompilationTestHelper compilationTestHelper =
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
    CompilationTestHelper compilationTestHelper =
        makeTestHelperWithArgs(List.of("-XepOpt:NullAway:OnlyNullMarked=false"))
            .addSourceLines("Stub.java", "package com.uber; class Stub {}");
    AssertionError e = assertThrows(AssertionError.class, () -> compilationTestHelper.doTest());
    assertTrue(e.getMessage().contains(ANNOTATED_PACKAGES_ONLY_NULLMARKED_ERROR_MSG));
  }

  @Test
  public void bothAnnotatedPackagesAndOnlyNullMarkedFails() {
    CompilationTestHelper compilationTestHelper =
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
    CompilationTestHelper compilationTestHelper =
        makeTestHelperWithArgs(
                List.of("-XepOpt:NullAway:OnlyNullMarked", "-XepOpt:NullAway:JSpecifyMode=true"))
            .addSourceLines("Stub.java", "package com.uber; class Stub {}");
    AssertionError e = assertThrows(AssertionError.class, () -> compilationTestHelper.doTest());
    assertTrue(
        e.getMessage().contains("The flag -XDaddTypeAnnotationsToSymbol=true was not passed"));
  }

  @Test
  public void missingTypeAnnotationSymbolFlagErrorMessage() {
    String message =
        NullAway.invalidJSpecifyJavacConfigErrorMessage(
            JavacConfigValidityResult.FLAG_NOT_SET_TO_TRUE);

    assertTrue(message.contains("The flag -XDaddTypeAnnotationsToSymbol=true was not passed"));
  }

  @Test
  public void unsupportedTypeAnnotationSymbolFlagErrorMessage() {
    String message =
        NullAway.invalidJSpecifyJavacConfigErrorMessage(
            JavacConfigValidityResult.FLAG_NOT_SUPPORTED_BY_JAVAC);

    assertTrue(
        message.contains(
            "The flag -XDaddTypeAnnotationsToSymbol=true was passed, but it is not supported"));
    assertTrue(message.contains("running JDK (version " + Runtime.version() + ")"));
    assertTrue(message.contains("JDK 17.0.19+ or 21.0.8+ is required for flag support"));
  }

  @Test
  public void jspecifyJDKOutsideJSpecifyMode() {
    CompilationTestHelper compilationTestHelper =
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
