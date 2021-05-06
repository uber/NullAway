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

import com.google.errorprone.BugCheckerRefactoringTestHelper;
import com.google.errorprone.ErrorProneFlags;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NullAwayAutoSuggestNoCastTest {

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private ErrorProneFlags flagsWithAutoFixSuppressionComment;

  private ErrorProneFlags flagsNoAutoFixSuppressionComment;

  @Before
  public void setup() {
    // With AutoFixSuppressionComment
    ErrorProneFlags.Builder b = ErrorProneFlags.builder();
    b.putFlag("NullAway:AnnotatedPackages", "com.uber,com.ubercab,io.reactivex");
    b.putFlag("NullAway:SuggestSuppressions", "true");
    b.putFlag("NullAway:AutoFixSuppressionComment", "PR #000000");
    flagsWithAutoFixSuppressionComment = b.build();
    // Without AutoFixSuppressionComment
    b = ErrorProneFlags.builder();
    b.putFlag("NullAway:AnnotatedPackages", "com.uber,com.ubercab,io.reactivex");
    b.putFlag("NullAway:SuggestSuppressions", "true");
    flagsNoAutoFixSuppressionComment = b.build();
  }

  // In EP 2.6.0 the newInstance() method we use below is deprecated.  We cannot currently address
  // the warning since the replacement method was only added in EP 2.5.1, and we still want to
  // support EP 2.4.0.  So, we suppress the warning for now
  @SuppressWarnings("deprecation")
  private BugCheckerRefactoringTestHelper makeTestHelperWithSuppressionComment() {
    return BugCheckerRefactoringTestHelper.newInstance(
            new NullAway(flagsWithAutoFixSuppressionComment), getClass())
        .setArgs(
            "-d",
            temporaryFolder.getRoot().getAbsolutePath(),
            // the remaining args are not needed right now, but they will be necessary when we
            // switch to the more modern newInstance() API
            "-XepOpt:NullAway:AnnotatedPackages=com.uber,com.ubercab,io.reactivex",
            "-XepOpt:NullAway:SuggestSuppressions=true",
            "-XepOpt:NullAway:AutoFixSuppressionComment=PR #000000");
  }

  // In EP 2.6.0 the newInstance() method we use below is deprecated.  We cannot currently address
  // the warning since the replacement method was only added in EP 2.5.1, and we still want to
  // support EP 2.4.0.  So, we suppress the warning for now
  @SuppressWarnings("deprecation")
  private BugCheckerRefactoringTestHelper makeTestHelper() {
    return BugCheckerRefactoringTestHelper.newInstance(
            new NullAway(flagsNoAutoFixSuppressionComment), getClass())
        .setArgs(
            "-d",
            temporaryFolder.getRoot().getAbsolutePath(),
            // the remaining args are not needed right now, but they will be necessary when we
            // switch to the more modern newInstance() API
            "-XepOpt:NullAway:AnnotatedPackages=com.uber,com.ubercab,io.reactivex",
            "-XepOpt:NullAway:SuggestSuppressions=true");
  }

  @Test
  public void suggestSuppressionWithComment() {
    makeTestHelperWithSuppressionComment()
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  Object test1() {",
            "    return null;",
            "  }",
            "}")
        .addOutputLines(
            "out/Test.java",
            "package com.uber;",
            "class Test {", // Can we actually check comments?
            "  @SuppressWarnings(\"NullAway\") /* PR #000000 */ Object test1() {",
            "    return null;",
            "  }",
            "}")
        .doTest(BugCheckerRefactoringTestHelper.TestMode.TEXT_MATCH); // Yes we can!
  }

  @Test
  public void suggestSuppressionWithoutComment() {
    makeTestHelper()
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  Object test1() {",
            "    return null;",
            "  }",
            "}")
        .addOutputLines(
            "out/Test.java",
            "package com.uber;",
            "class Test {",
            "  @SuppressWarnings(\"NullAway\") Object test1() {",
            "    return null;",
            "  }",
            "}")
        .doTest(BugCheckerRefactoringTestHelper.TestMode.TEXT_MATCH);
  }

  @Test
  public void suggestSuppressionFieldLambdaDeref() {
    makeTestHelper()
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  @Nullable private Object foo;",
            "  private final Runnable runnable =",
            "    () -> {",
            "      foo.toString();",
            "    };",
            "}")
        .addOutputLines(
            "out/Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  @Nullable private Object foo;",
            "  @SuppressWarnings(\"NullAway\")",
            "  private final Runnable runnable =",
            "    () -> {",
            "      foo.toString();",
            "    };",
            "}")
        .doTest(BugCheckerRefactoringTestHelper.TestMode.TEXT_MATCH);
  }

  @Test
  public void suggestSuppressionFieldLambdaUnbox() {
    makeTestHelper()
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  @Nullable private Integer foo;",
            "  static int id(int x) { return x; }",
            "  private final Runnable runnable =",
            "    () -> {",
            "      id(foo);",
            "    };",
            "}")
        .addOutputLines(
            "out/Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  @Nullable private Integer foo;",
            "  static int id(int x) { return x; }",
            "  @SuppressWarnings(\"NullAway\")",
            "  private final Runnable runnable =",
            "    () -> {",
            "      id(foo);",
            "    };",
            "}")
        .doTest(BugCheckerRefactoringTestHelper.TestMode.TEXT_MATCH);
  }

  @Test
  public void suggestSuppressionFieldLambdaAssignment() {
    makeTestHelper()
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  @Nullable private Integer foo;",
            "  static int id(int x) { return x; }",
            "  private final Runnable runnable =",
            "    () -> {",
            "      int x = foo + 1;",
            "    };",
            "}")
        .addOutputLines(
            "out/Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  @Nullable private Integer foo;",
            "  static int id(int x) { return x; }",
            "  private final Runnable runnable =",
            "    () -> {",
            "      @SuppressWarnings(\"NullAway\")",
            "      int x = foo + 1;",
            "    };",
            "}")
        .doTest(BugCheckerRefactoringTestHelper.TestMode.TEXT_MATCH);
  }

  @Test
  public void suggestLambdaAssignInMethod() {
    makeTestHelper()
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  @Nullable private Integer foo;",
            "  @Nullable private java.util.function.Function<Object, Integer> f;",
            "  void m1() {",
            "    f = (x) -> { return foo + 1; };",
            "  }",
            "  void m2() {",
            "    java.util.function.Function<Object,Integer> g = (x) -> { return foo + 1; };",
            "  }",
            "}")
        .addOutputLines(
            "out/Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  @Nullable private Integer foo;",
            "  @Nullable private java.util.function.Function<Object, Integer> f;",
            "  @SuppressWarnings(\"NullAway\")",
            "  void m1() {",
            "    f = (x) -> { return foo + 1; };",
            "  }",
            "  void m2() {",
            "    @SuppressWarnings(\"NullAway\")",
            "    java.util.function.Function<Object,Integer> g = (x) -> { return foo + 1; };",
            "  }",
            "}")
        .doTest(BugCheckerRefactoringTestHelper.TestMode.TEXT_MATCH);
  }

  @Test
  public void suppressMethodRefOverrideParam() {
    makeTestHelper()
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  static interface I {",
            "    public void foo(@Nullable Object o);",
            "  }",
            "  static void biz(Object p) {}",
            "  static void callFoo(I i) { i.foo(null); }",
            "  static void bar() {",
            "    callFoo(Test::biz);",
            "  }",
            "}")
        .addOutputLines(
            "out/Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  static interface I {",
            "    public void foo(@Nullable Object o);",
            "  }",
            "  static void biz(Object p) {}",
            "  static void callFoo(I i) { i.foo(null); }",
            "  @SuppressWarnings(\"NullAway\")",
            "  static void bar() {",
            "    callFoo(Test::biz);",
            "  }",
            "}")
        .doTest(BugCheckerRefactoringTestHelper.TestMode.TEXT_MATCH);
  }

  @Test
  public void suppressMethodRefOverrideReturn() {
    makeTestHelper()
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  static interface I {",
            "    public Object foo();",
            "  }",
            "  @Nullable",
            "  static Object biz() { return null; }",
            "  static void callFoo(I i) { i.foo(); }",
            "  static void bar() {",
            "    callFoo(Test::biz);",
            "  }",
            "}")
        .addOutputLines(
            "out/Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  static interface I {",
            "    public Object foo();",
            "  }",
            "  @Nullable",
            "  static Object biz() { return null; }",
            "  static void callFoo(I i) { i.foo(); }",
            "  @SuppressWarnings(\"NullAway\")",
            "  static void bar() {",
            "    callFoo(Test::biz);",
            "  }",
            "}")
        .doTest(BugCheckerRefactoringTestHelper.TestMode.TEXT_MATCH);
  }
}
