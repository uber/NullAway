package com.uber.nullaway;

import com.google.errorprone.CompilationTestHelper;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NullAwayAssertionLibsTests {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper defaultCompilationHelper;

  /**
   * Creates a new {@link CompilationTestHelper} with a list of javac arguments. As of Error Prone
   * 2.5.1, {@link CompilationTestHelper#setArgs(List)} can only be invoked once per object. So,
   * this method must be used to create a test helper when a different set of javac arguments is
   * required than those used for {@link #defaultCompilationHelper}.
   *
   * @param args the javac arguments
   * @return the test helper
   */
  private CompilationTestHelper makeTestHelperWithArgs(List<String> args) {
    return CompilationTestHelper.newInstance(NullAway.class, getClass()).setArgs(args);
  }

  @Test
  public void supportTruthAssertThatIsNotNull_Object() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.lang.Object;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "import static com.google.common.truth.Truth.assertThat;",
            "class Test {",
            "  private void foo(@Nullable Object o) {",
            "    assertThat(o).isNotNull();",
            "    o.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void supportTruthAssertThatIsNotNull_String() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "import static com.google.common.truth.Truth.assertThat;",
            "class Test {",
            "  private void foo(@Nullable String s) {",
            "    assertThat(s).isNotNull();",
            "    s.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void doNotSupportTruthAssertThatWhenDisabled() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=false"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.lang.Object;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "import static com.google.common.truth.Truth.assertThat;",
            "class Test {",
            "  private void foo(@Nullable Object a) {",
            "    assertThat(a).isNotNull();",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    a.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void supportHamcrestAssertThatMatchersIsNotNull() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.lang.Object;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "import static org.hamcrest.MatcherAssert.assertThat;",
            "import static org.hamcrest.Matchers.*;",
            "class Test {",
            "  private void foo(@Nullable Object a, @Nullable Object b) {",
            "    assertThat(a, is(notNullValue()));",
            "    a.toString();",
            "    assertThat(b, is(not(nullValue())));",
            "    b.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void doNotSupportHamcrestAssertThatWhenDisabled() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=false"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.lang.Object;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "import static org.hamcrest.MatcherAssert.assertThat;",
            "import static org.hamcrest.Matchers.*;",
            "class Test {",
            "  private void foo(@Nullable Object a) {",
            "    assertThat(a, is(notNullValue()));",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    a.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void supportHamcrestAssertThatCoreMatchersIsNotNull() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.lang.Object;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "import static org.hamcrest.MatcherAssert.assertThat;",
            "import static org.hamcrest.CoreMatchers.*;",
            "class Test {",
            "  private void foo(@Nullable Object a, @Nullable Object b) {",
            "    assertThat(a, is(notNullValue()));",
            "    a.toString();",
            "    assertThat(b, is(not(nullValue())));",
            "    b.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void supportHamcrestAssertThatCoreIsNotNull() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.lang.Object;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "import static org.hamcrest.MatcherAssert.assertThat;",
            "import static org.hamcrest.CoreMatchers.*;",
            "import org.hamcrest.core.IsNull;",
            "class Test {",
            "  private void foo(@Nullable Object a, @Nullable Object b) {",
            "    assertThat(a, is(IsNull.notNullValue()));",
            "    a.toString();",
            "    assertThat(b, is(not(IsNull.nullValue())));",
            "    b.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void supportJunitAssertThatIsNotNull_Object() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.lang.Object;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "import static org.junit.Assert.assertThat;",
            "import static org.hamcrest.Matchers.*;",
            "class Test {",
            "  private void foo(@Nullable Object a, @Nullable Object b) {",
            "    assertThat(a, is(notNullValue()));",
            "    a.toString();",
            "    assertThat(b, is(not(nullValue())));",
            "    b.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void doNotSupportJunitAssertThatWhenDisabled() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=false"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.lang.Object;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "import static org.junit.Assert.assertThat;",
            "import static org.hamcrest.Matchers.*;",
            "class Test {",
            "  private void foo(@Nullable Object a) {",
            "    assertThat(a, is(notNullValue()));",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    a.toString();",
            "  }",
            "}")
        .doTest();
  }
}
