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
package com.uber.nullaway.jdk17;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAway;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** NullAway unit tests involving language features available on JDK 17 but not JDK 11. */
public class NullAwayJDK17Test {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper defaultCompilationHelper;

  @Before
  public void setup() {
    defaultCompilationHelper =
        CompilationTestHelper.newInstance(NullAway.class, getClass())
            .setArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber"));
  }

  @Test
  public void testSwitchExpression() {
    defaultCompilationHelper
        .addSourceLines(
            "SwitchExpr.java",
            "package com.uber;",
            "class SwitchExpr {",
            "  public void testSwitchExpr1(int i) {",
            "    Object o = switch (i) { case 3, 4, 5 -> new Object(); default -> null; };",
            "    // BUG: Diagnostic contains: dereferenced expression o is @Nullable",
            "    o.toString();",
            "    Object o2 = switch (i) { case 3, 4, 5 -> new Object(); default -> \"hello\"; };",
            "    // NOTE: we are imprecise for this case, as for now the dataflow analysis always treats",
            "    // switch expressions as being nullable",
            "    // BUG: Diagnostic contains: dereferenced expression o2 is @Nullable",
            "    o2.toString();",
            "  }",
            "  public void testSwitchExpr2(int i) {",
            "    // NOTE: should get an error here, we are unsound for this case",
            "    (switch (i) { case 3, 4, 5 -> new Object(); default -> null; }).toString();",
            "  }",
            "  private void takesNonNull(Object o) {}",
            "  public void testSwitchExpr3(int i) {",
            "    // NOTE: should get an error here, we are unsound for this case",
            "    takesNonNull(switch (i) { case 3, 4, 5 -> new Object(); default -> null; });",
            "  }",
            "  public void testSwitchStmtArrowCase(int i) {",
            "    Object o = null;",
            "    switch (i) {",
            "      case 3, 4, 5 -> { o = new Object(); }",
            "      default -> { o = null; }",
            "    }",
            "    // BUG: Diagnostic contains: dereferenced expression o is @Nullable",
            "    o.toString();",
            "    Object o2 = null;",
            "    switch (i) {",
            "      case 3, 4, 5 -> { o2 = null; }",
            "      default -> { o2 = new Object(); }",
            "    }",
            "    // NOTE: should get an error here, we are unsound for this case",
            "    o2.toString();",
            "  }",
            "}")
        .doTest();
  }
}
