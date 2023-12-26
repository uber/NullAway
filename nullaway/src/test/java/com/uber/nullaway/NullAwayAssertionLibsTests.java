package com.uber.nullaway;

import java.util.Arrays;
import org.junit.Test;

public class NullAwayAssertionLibsTests extends NullAwayTestsBase {

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
  public void supportTruthAssertThatIsNotNull_MapKey() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Map;",
            "import javax.annotation.Nullable;",
            "import static com.google.common.truth.Truth.assertThat;",
            "class Test {",
            "  private void foo(Map<String,Object> m) {",
            "    assertThat(m.get(\"foo\")).isNotNull();",
            "    m.get(\"foo\").toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void supportTruthAssertThatIsInstanceOf() {
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
            "    // inInstanceOf => isNotNull!",
            "    assertThat(o).isInstanceOf(Object.class);",
            "    o.toString();",
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
  public void supportHamcrestAssertThatIsInstanceOf() {
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
            "    assertThat(a, is(instanceOf(Object.class)));",
            "    a.toString();",
            "    assertThat(b, isA(Object.class));",
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
  public void supportJunitAssertThatIsInstanceOf() {
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
            "    assertThat(a, is(instanceOf(Object.class)));",
            "    a.toString();",
            "    assertThat(b, isA(Object.class));",
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

  @Test
  public void supportAssertJAssertThatIsNotNull_Object() {
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
            "import static org.assertj.core.api.Assertions.assertThat;",
            "class Test {",
            "  private void foo(@Nullable Object o) {",
            "    assertThat(o).isNotNull();",
            "    o.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void supportAssertJAssertThatIsNotNullWithDescription_Object() {
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
            "import static org.assertj.core.api.Assertions.assertThat;",
            "class Test {",
            "  private void foo(@Nullable Object o) {",
            "    assertThat(o).as(\"test\").isNotNull();",
            "    o.toString();",
            "  }",
            "  private void foo2(@Nullable Object o) {",
            "    assertThat(o).describedAs(\"test\").isNotNull();",
            "    o.toString();",
            "  }",
            "  private void foo3(@Nullable Object o) {",
            "    assertThat(o).describedAs(\"test1\").as(\"test2\").isNotNull();",
            "    o.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void assertJAssertThatIsNotNullUnhandled() {
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
            "import static org.assertj.core.api.Assertions.assertThat;",
            "class Test {",
            "  private void foo(@Nullable Object o) {",
            "    org.assertj.core.api.ObjectAssert t = assertThat(o);",
            "    t.isNotNull();",
            "    // False positive",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    o.toString();",
            "  }",
            "  private void foo2(@Nullable Object o) {",
            "    assertThat(o).isEqualToIgnoringNullFields(o).describedAs(\"test\").isNotNull();",
            "    // False positive",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    o.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void supportAssertJAssertThatIsNotNull_String() {
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
            "import static org.assertj.core.api.Assertions.assertThat;",
            "class Test {",
            "  private void foo(@Nullable String s) {",
            "    assertThat(s).isNotNull();",
            "    s.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void supportAssertJAssertThatIsNotNull_MapKey() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Map;",
            "import javax.annotation.Nullable;",
            "import static org.assertj.core.api.Assertions.assertThat;",
            "class Test {",
            "  private void foo(Map<String,Object> m) {",
            "    assertThat(m.get(\"foo\")).isNotNull();",
            "    m.get(\"foo\").toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void supportAssertJAssertThatIsInstanceOf() {
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
            "import static org.assertj.core.api.Assertions.assertThat;",
            "class Test {",
            "  private void foo(@Nullable Object a, @Nullable Object b) {",
            "    assertThat(a).isInstanceOf(Object.class);",
            "    a.toString();",
            "    assertThat(b).isInstanceOfAny(String.class, Exception.class);",
            "    b.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void doNotSupportAssertJAssertThatWhenDisabled() {
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
            "import static org.assertj.core.api.Assertions.assertThat;",
            "class Test {",
            "  private void foo(@Nullable Object a) {",
            "    assertThat(a).isNotNull();",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    a.toString();",
            "  }",
            "}")
        .doTest();
  }
}
