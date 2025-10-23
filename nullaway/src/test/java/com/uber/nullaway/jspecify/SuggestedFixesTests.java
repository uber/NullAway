package com.uber.nullaway.jspecify;

import com.google.errorprone.BugCheckerRefactoringTestHelper;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.testhelper.NullAwayJSpecifyConfig;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Tests for combining fix suggestions with JSpecify mode. */
public class SuggestedFixesTests {

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private BugCheckerRefactoringTestHelper makeTestHelper() {
    List<String> args =
        new ArrayList<>(
            List.of(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-processorpath",
                SuggestedFixesTests.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .getPath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"));
    args.addAll(NullAwayJSpecifyConfig.jspecifyModeArgs());
    args.add("-XepOpt:NullAway:SuggestSuppressions=true");
    return BugCheckerRefactoringTestHelper.newInstance(NullAway.class, getClass())
        .setArgs(args.toArray(new String[0]));
  }

  @Test
  public void suggestSuppressionForAssigningNullableIntoNonNullArray() throws IOException {
    makeTestHelper()
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  void test() {",
            "    Object[] arr = new Object[1];",
            "    arr[0] = null;",
            "  }",
            "}")
        .addOutputLines(
            "out/Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  @SuppressWarnings(\"NullAway\")",
            "  void test() {",
            "    Object[] arr = new Object[1];",
            "    arr[0] = null;",
            "  }",
            "}")
        .doTest();
  }
}
