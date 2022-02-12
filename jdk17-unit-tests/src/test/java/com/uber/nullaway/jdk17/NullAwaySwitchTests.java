/*
 * Copyright (c) 2021 Uber Technologies, Inc.
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
public class NullAwaySwitchTests {

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
  public void testSwitchExpressionAssign() {
    defaultCompilationHelper
        .addSourceLines(
            "SwitchExpr.java",
            "package com.uber;",
            "class SwitchExpr {",
            "  public void testSwitchExpressionAssign(int i) {",
            "    Object o = switch (i) { case 3, 4, 5 -> new Object(); default -> null; };",
            "    // BUG: Diagnostic contains: dereferenced expression o is @Nullable",
            "    o.toString();",
            "    Object o2 = switch (i) { case 3, 4, 5 -> new Object(); default -> \"hello\"; };",
            "    // This dereference is safe",
            "    o2.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testDirectlyDerefedSwitchExpr() {
    defaultCompilationHelper
        .addSourceLines(
            "SwitchExpr.java",
            "package com.uber;",
            "class SwitchExpr {",
            "  public void testDirectlyDerefedSwitchExpr(int i) {",
            "    // BUG: Diagnostic contains: dereferenced expression (switch",
            "    (switch (i) { case 3, 4, 5 -> new Object(); default -> null; }).toString();",
            "    // This deference is safe",
            "    (switch (i) { case 3, 4, 5 -> new Object(); default -> \"hello\"; }).toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testPassingSwitchExprAsParam() {
    defaultCompilationHelper
        .addSourceLines(
            "SwitchExpr.java",
            "package com.uber;",
            "class SwitchExpr {",
            "  private void takesNonNull(Object o) {}",
            "  public void testSwitchExpr3(int i) {",
            "    // BUG: Diagnostic contains: passing",
            "    takesNonNull(switch (i) { case 3, 4, 5 -> new Object(); default -> null; });",
            "    // This call is safe",
            "    takesNonNull(switch (i) { case 3, 4, 5 -> new Object(); default -> new Object(); });",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testSwitchStmtArrowCase() {
    defaultCompilationHelper
        .addSourceLines(
            "SwitchExpr.java",
            "package com.uber;",
            "class SwitchExpr {",
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
            "    // BUG: Diagnostic contains: dereferenced expression o2 is @Nullable",
            "    o2.toString();",
            "    Object o3 = null;",
            "    switch (i) {",
            "      case 3, 4, 5 -> { o3 = new Object(); }",
            "      default -> { o3 = new Object(); }",
            "    }",
            "    // This dereference is safe",
            "    o3.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testSwitchExprUnbox() {
    defaultCompilationHelper
        .addSourceLines(
            "SwitchExpr.java",
            "package com.uber;",
            "class SwitchExpr {",
            "  public void testSwitchExprUnbox() {",
            "    Integer i = null;",
            "    // NOTE: we should report a bug here due to the unboxing of i, but cannot do so until",
            "    // Error Prone supports matching switch expressions",
            "    Object o = switch (i) { case 3, 4, 5 -> new Object(); default -> null; };",
            "    int j1 = 0;",
            "    // BUG: Diagnostic contains: unboxing of a @Nullable value",
            "    int j2 = 3 + (switch (j1) { case 3, 4, 5 -> Integer.valueOf(4); default -> null; });",
            "    // This is safe",
            "    int j3 = 3 + (switch (j1) { case 3, 4, 5 -> Integer.valueOf(4); default -> Integer.valueOf(6); });",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testSwitchExprLambda() {
    defaultCompilationHelper
        .addSourceLines(
            "SwitchExpr.java",
            "package com.uber;",
            "import java.util.function.Function;",
            "class SwitchExpr {",
            "  int i;",
            "  public void testSwitchExprLambda() {",
            "    // Here we just test to make sure there is no crash.  We need better",
            "    // generics support to do a more substantive test.",
            "    Function<SwitchExpr,Object> f = (s) -> switch (s.i) { case 3, 4, 5 -> new Object(); default -> \"hello\"; };",
            "  }",
            "}")
        .doTest();
  }
}
