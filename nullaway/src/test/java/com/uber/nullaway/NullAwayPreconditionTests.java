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
  public void simpleCheckStateTest() {
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
            "  @Nullable private Object a;",
            "  private void foo() {",
            "    Preconditions.checkState(this.a != null);",
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
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Preconditions;",
            "class Test {",
            "  private void foo(@Nullable Object a) {",
            "    Preconditions.checkArgument(this.hashCode() != 5 && a != null);",
            "    a.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void compoundCheckArgumentLongTest() {
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
            "  private void foo(@Nullable Object a, @Nullable Object b, @Nullable Object c, @Nullable Object d, @Nullable Object e) {",
            "    Preconditions.checkArgument(a != null && b != null && c != null && d != null && e != null);",
            "    a.toString();",
            "    b.toString();",
            "    c.toString();",
            "    d.toString();",
            "    e.toString();",
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

  @Test
  public void irrelevantCheckArgumentTest() {
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
            "    Preconditions.checkArgument(this.hashCode() != 5);",
            "    // BUG: Diagnostic contains: dereferenced expression a is @Nullable",
            "    a.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void inconclusiveCheckArgumentTest() {
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
            "    Preconditions.checkArgument(this.hashCode() != 5 || a != null);",
            "    // BUG: Diagnostic contains: dereferenced expression a is @Nullable",
            "    a.toString();",
            "  }",
            "}")
        .doTest();
  }
}
