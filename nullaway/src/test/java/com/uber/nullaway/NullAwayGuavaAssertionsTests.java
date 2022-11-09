package com.uber.nullaway;

import java.util.Arrays;
import org.junit.Test;

public class NullAwayGuavaAssertionsTests extends NullAwayTestsBase {

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
  public void checkNotNullComplexAccessPathsTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "TestField.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Preconditions;",
            "class TestField {",
            "  @Nullable private Object f = null;",
            "  private void foo(@Nullable TestField a) {",
            "    Preconditions.checkNotNull(a);",
            "    Preconditions.checkNotNull(a.f);",
            "    a.f.toString();",
            "  }",
            "}")
        .addSourceLines(
            "TestMap.java",
            "package com.uber;",
            "import java.util.Map;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Preconditions;",
            "class TestMap {",
            "  private void foo(@Nullable Map<String,Object> m) {",
            "    Preconditions.checkNotNull(m);",
            "    Preconditions.checkNotNull(m.get(\"foo\"));",
            "    m.get(\"foo\").toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void verifyNotNullTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Verify;",
            "class Test {",
            "  private void foo(@Nullable Object a) {",
            "    Verify.verifyNotNull(a);",
            "    a.toString();",
            "  }",
            "  private void bar(@Nullable Object a) {",
            "    Verify.verifyNotNull(a, \"message\", new Object(), new Object());",
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
  public void simpleVerifyTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Verify;",
            "class Test {",
            "  private void foo(@Nullable Object a) {",
            "    Verify.verify(a != null);",
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

  @Test
  public void checkArgumentCatchException() {
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
            "    try {",
            "      Preconditions.checkArgument(a != null);",
            "    } catch (IllegalArgumentException e) {}",
            "    // BUG: Diagnostic contains: dereferenced expression a is @Nullable",
            "    a.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void checkStateCatchException() {
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
            "    try {",
            "      Preconditions.checkState(a != null);",
            "    } catch (IllegalStateException e) {}",
            "    // BUG: Diagnostic contains: dereferenced expression a is @Nullable",
            "    a.toString();",
            "  }",
            "}")
        .doTest();
  }
}
