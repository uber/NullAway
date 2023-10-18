/*
 * Copyright (c) 2017 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.nullaway;

import static com.google.errorprone.BugCheckerRefactoringTestHelper.TestMode.TEXT_MATCH;

import com.google.errorprone.BugCheckerRefactoringTestHelper;
import com.sun.source.tree.Tree;
import com.uber.nullaway.testlibrarymodels.TestLibraryModels;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NullAwayAutoSuggestTest {

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private BugCheckerRefactoringTestHelper makeTestHelper() {
    return BugCheckerRefactoringTestHelper.newInstance(NullAway.class, getClass())
        .setArgs(
            "-d",
            temporaryFolder.getRoot().getAbsolutePath(),
            "-processorpath",
            TestLibraryModels.class.getProtectionDomain().getCodeSource().getLocation().getPath(),
            "-XepOpt:NullAway:AnnotatedPackages=com.uber,com.ubercab,io.reactivex",
            "-XepOpt:NullAway:CastToNonNullMethod=com.uber.nullaway.testdata.Util.castToNonNull",
            "-XepOpt:NullAway:SuggestSuppressions=true");
  }

  @Test
  public void correctCastToNonNull() throws IOException {
    makeTestHelper()
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import static com.uber.nullaway.testdata.Util.castToNonNull;",
            "class Test {",
            "  Object test1(@Nullable Object o) {",
            "    return castToNonNull(o);",
            "  }",
            "}")
        .expectUnchanged()
        .doTest();
  }

  @Test
  public void suggestCastToNonNull() throws IOException {
    makeTestHelper()
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  @Nullable Object o;",
            "  Object test1() {",
            "    return o;",
            "  }",
            "}")
        .addOutputLines(
            "out/Test.java",
            "package com.uber;",
            "import static com.uber.nullaway.testdata.Util.castToNonNull;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  @Nullable Object o;",
            "  Object test1() {",
            "    return castToNonNull(o);",
            "  }",
            "}")
        .doTest();
  }

  /**
   * Test for cases where we heuristically decide not to wrap an expression in castToNonNull; see
   * {@link ErrorBuilder#canBeCastToNonNull(Tree)}
   */
  @Test
  public void suppressInsteadOfCastToNonNull() throws IOException {
    makeTestHelper()
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  Object f = new Object();",
            "  Object test1(@Nullable Object o) {",
            "    return o;",
            "  }",
            "  Object test2() {",
            "    return null;",
            "  }",
            "  void test3() {",
            "    f = null;",
            "  }",
            "  @Nullable Object m() { return null; }",
            "  Object shouldAddCast() {",
            "    return m();",
            "  }",
            "}")
        .addOutputLines(
            "out/Test.java",
            "package com.uber;",
            "import static com.uber.nullaway.testdata.Util.castToNonNull;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  Object f = new Object();",
            "  @SuppressWarnings(\"NullAway\") Object test1(@Nullable Object o) {",
            "    return o;",
            "  }",
            "  @SuppressWarnings(\"NullAway\") Object test2() {",
            "    return null;",
            "  }",
            "  @SuppressWarnings(\"NullAway\") void test3() {",
            "    f = null;",
            "  }",
            "  @Nullable Object m() { return null; }",
            "  Object shouldAddCast() {",
            "    return castToNonNull(m());",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void removeUnnecessaryCastToNonNull() throws IOException {
    makeTestHelper()
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import static com.uber.nullaway.testdata.Util.castToNonNull;",
            "class Test {",
            "  Object test1(Object o) {",
            "    return castToNonNull(o);",
            "  }",
            "}")
        .addOutputLines(
            "out/Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import static com.uber.nullaway.testdata.Util.castToNonNull;",
            "class Test {",
            "  Object test1(Object o) {",
            "    return o;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void removeUnnecessaryCastToNonNullFromLibraryModel() throws IOException {
    makeTestHelper()
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import static com.uber.nullaway.testdata.Util.castToNonNull;",
            "class Test {",
            "  Object test1(Object o) {",
            "    return castToNonNull(\"CAST_REASON\",o,42);",
            "  }",
            "}")
        .addOutputLines(
            "out/Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import static com.uber.nullaway.testdata.Util.castToNonNull;",
            "class Test {",
            "  Object test1(Object o) {",
            "    return o;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void removeUnnecessaryCastToNonNullMultiLine() throws IOException {
    makeTestHelper()
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import static com.uber.nullaway.testdata.Util.castToNonNull;",
            "class Test {",
            "  static class Foo { Object getObj() { return new Object(); } }",
            "  Object test1(Foo f) {",
            "    return castToNonNull(f",
            "                         // comment that should not be deleted",
            "                         .getObj());",
            "  }",
            "}")
        .addOutputLines(
            "out/Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import static com.uber.nullaway.testdata.Util.castToNonNull;",
            "class Test {",
            "  static class Foo { Object getObj() { return new Object(); } }",
            "  Object test1(Foo f) {",
            "    return f",
            "        // comment that should not be deleted",
            "        .getObj();",
            "  }",
            "}")
        .doTest(TEXT_MATCH);
  }

  @Test
  public void suggestSuppressionOnMethodRef() throws IOException {
    makeTestHelper()
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  public @Nullable Object doReturnNullable() {",
            "    return null;",
            "  }",
            "  public static void takesNonNull(Object o) { ",
            "    System.out.println(o.toString());",
            "  }",
            "  public <R> R execute(io.reactivex.functions.Function<Test,R> f) throws Exception {",
            "    return f.apply(this);",
            "  }",
            "  void test() throws Exception {",
            "    takesNonNull(execute(Test::doReturnNullable));",
            "  }",
            "}")
        .addOutputLines(
            "out/Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  public @Nullable Object doReturnNullable() {",
            "    return null;",
            "  }",
            "  public static void takesNonNull(Object o) { ",
            "    System.out.println(o.toString());",
            "  }",
            "  public <R> R execute(io.reactivex.functions.Function<Test,R> f) throws Exception {",
            "    return f.apply(this);",
            "  }",
            "  @SuppressWarnings(\"NullAway\") void test() throws Exception {",
            "    takesNonNull(execute(Test::doReturnNullable));",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void suggestCastToNonNullPreserveComments() throws IOException {
    makeTestHelper()
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  Object x = new Object();",
            "  static class Foo { @Nullable Object getObj() { return null; } }",
            "  Object test1(Foo f) {",
            "    return f",
            "           // comment that should not be deleted",
            "           .getObj();",
            "  }",
            "  void test2(Foo f) {",
            "    x = f.getObj(); // comment that should not be deleted",
            "  }",
            "  Object test3(Foo f) {",
            "    return f./* keep this comment */getObj();",
            "  }",
            "}")
        .addOutputLines(
            "out/Test.java",
            "package com.uber;",
            "import static com.uber.nullaway.testdata.Util.castToNonNull;",
            "",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  Object x = new Object();",
            "  static class Foo { @Nullable Object getObj() { return null; } }",
            "  Object test1(Foo f) {",
            "    return castToNonNull(f",
            "           // comment that should not be deleted",
            "           .getObj());",
            "  }",
            "  void test2(Foo f) {",
            "    x = castToNonNull(f.getObj()); // comment that should not be deleted",
            "  }",
            "  Object test3(Foo f) {",
            "    return castToNonNull(f./* keep this comment */getObj());",
            "  }",
            "}")
        .doTest(TEXT_MATCH);
  }

  public void suggestInitSuppressionOnConstructor() throws IOException {
    makeTestHelper()
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  Object f;",
            "  Object g;",
            "  Test() {}",
            "}")
        .addOutputLines(
            "out/Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  Object f;",
            "  Object g;",
            "  @SuppressWarnings(\"NullAway.Init\") Test() {}",
            "}")
        .doTest();
  }

  @Test
  public void suggestInitSuppressionOnField() throws IOException {
    makeTestHelper()
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  Object f;",
            "}")
        .addOutputLines(
            "out/Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  @SuppressWarnings(\"NullAway.Init\") Object f;",
            "}")
        .doTest();
  }

  @Test
  public void updateExtantSuppressWarnings() throws IOException {
    makeTestHelper()
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  @SuppressWarnings(\"unused\") Object f;",
            "}")
        .addOutputLines(
            "out/Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  @SuppressWarnings({\"unused\",\"NullAway.Init\"}) Object f;",
            "}")
        .doTest();
  }
}
