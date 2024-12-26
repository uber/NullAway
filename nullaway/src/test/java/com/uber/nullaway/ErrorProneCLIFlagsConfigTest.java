package com.uber.nullaway;

import static com.uber.nullaway.ErrorProneCLIFlagsConfig.ANNOTATED_PACKAGES_ONLY_NULLMARKED_ERROR_MSG;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.errorprone.CompilationTestHelper;
import java.util.List;
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
            "package foo.baz;",
            "import org.jspecify.annotations.NullMarked;",
            "@NullMarked",
            "class Marked {",
            "  // BUG: Diagnostic contains: @NonNull field uninit not initialized",
            "  Object uninit;",
            "}")
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
}
