package com.uber.nullaway;

import java.util.Arrays;
import org.junit.Test;

public class NullAwayPreconditionTests extends NullAwayTestsBase {

  @Test
  public void checkNotNullTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Preconditions;",
            "class Test {",
            "  private void foo(@Nullable Object a) {",
            "    Preconditions.checkNotNull(a);",
            "    a.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void simpleCheckArgumentTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Preconditions;",
            "class Test {",
            "  private void foo(@Nullable Object a) {",
            "    Preconditions.checkArgument(a != null);",
            "    a.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void simpleCheckArgumentWithMessageTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Preconditions;",
            "class Test {",
            "  private void foo(@Nullable Object a) {",
            "    Preconditions.checkArgument(a != null, \"a ought to be non-null\");",
            "    a.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void compoundCheckArgumentTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Preconditions;",
            "class Test {",
            "  private void foo(@Nullable Object a) {",
            "    Preconditions.checkArgument(a != null && !a.equals(this));",
            "    a.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void compoundCheckArgumentLastTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "NullAwayPreconditionTest.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Preconditions;",
            "class NullAwayPreconditionTest {",
            "  private void foo(@Nullable Object a) {",
            "    Preconditions.checkArgument(this.hashCode() != 5 && a != null);",
            "    a.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nestedCallCheckArgumentTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Preconditions;",
            "import com.google.common.base.Strings;",
            "class Test {",
            "  private void foo(@Nullable String a) {",
            "    Preconditions.checkArgument(!Strings.isNullOrEmpty(a));",
            "    a.toString();",
            "  }",
            "}")
        .doTest();
  }
}
