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
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NullAwayAutoSuggestTest {

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private ErrorProneFlags flags;

  @Before
  public void setup() {
    ErrorProneFlags.Builder b = ErrorProneFlags.builder();
    b.putFlag("NullAway:AnnotatedPackages", "com.uber,com.ubercab,io.reactivex");
    b.putFlag("NullAway:CastToNonNullMethod", "com.uber.nullaway.testdata.Util.castToNonNull");
    b.putFlag("NullAway:SuggestSuppressions", "true");
    flags = b.build();
  }

  // In EP 2.6.0 the newInstance() method we use below is deprecated.  We cannot currently address
  // the warning since the replacement method was only added in EP 2.5.1, and we still want to
  // support EP 2.4.0.  So, we suppress the warning for now
  @SuppressWarnings("deprecation")
  private BugCheckerRefactoringTestHelper makeTestHelper() {
    return BugCheckerRefactoringTestHelper.newInstance(new NullAway(flags), getClass())
        .setArgs(
            "-d",
            temporaryFolder.getRoot().getAbsolutePath(),
            // the remaining args are not needed right now, but they will be necessary when we
            // switch to the more modern newInstance() API
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
            "  Object test1(@Nullable Object o) {",
            "    return o;",
            "  }",
            "}")
        .addOutputLines(
            "out/Test.java",
            "package com.uber;",
            "import static com.uber.nullaway.testdata.Util.castToNonNull;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  Object test1(@Nullable Object o) {",
            "    return castToNonNull(o);",
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
}
