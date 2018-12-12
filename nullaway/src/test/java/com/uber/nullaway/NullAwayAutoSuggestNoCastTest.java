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

  private ErrorProneFlags flags;

  private ErrorProneFlags flagsNoAutoFixSuppressionComment;

  @Before
  public void setup() {
    // With AutoFixSuppressionComment
    ErrorProneFlags.Builder b = ErrorProneFlags.builder();
    b.putFlag("NullAway:AnnotatedPackages", "com.uber,com.ubercab,io.reactivex");
    b.putFlag("NullAway:SuggestSuppressions", "true");
    b.putFlag("NullAway:AutoFixSuppressionComment", "PR #000000");
    flags = b.build();
    // Without AutoFixSuppressionComment
    b = ErrorProneFlags.builder();
    b.putFlag("NullAway:AnnotatedPackages", "com.uber,com.ubercab,io.reactivex");
    b.putFlag("NullAway:SuggestSuppressions", "true");
    flagsNoAutoFixSuppressionComment = b.build();
  }

  @Test
  public void suggestSuppressionWithComment() {
    BugCheckerRefactoringTestHelper bcr =
        BugCheckerRefactoringTestHelper.newInstance(new NullAway(flags), getClass());

    bcr.setArgs("-d", temporaryFolder.getRoot().getAbsolutePath());
    bcr.addInputLines(
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
            "}");
    bcr.doTest(BugCheckerRefactoringTestHelper.TestMode.TEXT_MATCH); // Yes we can!
  }

  @Test
  public void suggestSuppressionWithoutComment() {
    BugCheckerRefactoringTestHelper bcr =
        BugCheckerRefactoringTestHelper.newInstance(
            new NullAway(flagsNoAutoFixSuppressionComment), getClass());

    bcr.setArgs("-d", temporaryFolder.getRoot().getAbsolutePath());
    bcr.addInputLines(
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
            "}");
    bcr.doTest(BugCheckerRefactoringTestHelper.TestMode.TEXT_MATCH);
  }

  @Test
  public void suggestSuppressionFieldLambdaDeref() {
    BugCheckerRefactoringTestHelper bcr =
        BugCheckerRefactoringTestHelper.newInstance(
            new NullAway(flagsNoAutoFixSuppressionComment), getClass());

    bcr.setArgs("-d", temporaryFolder.getRoot().getAbsolutePath());
    bcr.addInputLines(
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
            "}");
    bcr.doTest(BugCheckerRefactoringTestHelper.TestMode.TEXT_MATCH);
  }

  @Test
  public void suggestSuppressionFieldLambdaUnbox() {
    BugCheckerRefactoringTestHelper bcr =
        BugCheckerRefactoringTestHelper.newInstance(
            new NullAway(flagsNoAutoFixSuppressionComment), getClass());

    bcr.setArgs("-d", temporaryFolder.getRoot().getAbsolutePath());
    bcr.addInputLines(
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
            "}");
    bcr.doTest(BugCheckerRefactoringTestHelper.TestMode.TEXT_MATCH);
  }
}
