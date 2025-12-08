package com.uber.nullaway.jspecify;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.BugCheckerRefactoringTestHelper;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.io.IOException;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Tests for combining fix suggestions with JSpecify mode. */
public class SuggestedFixesTests {

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private BugCheckerRefactoringTestHelper makeTestHelper() {
    List<String> args =
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            List.of(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SuggestSuppressions=true"));
    return BugCheckerRefactoringTestHelper.newInstance(NullAway.class, getClass())
        .setArgs(ImmutableList.copyOf(args));
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
