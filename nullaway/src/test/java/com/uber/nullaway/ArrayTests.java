/*
 * Copyright (c) 2024 Uber Technologies, Inc.
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

import com.google.errorprone.CompilationTestHelper;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ArrayTests extends NullAwayTestsBase {
  @Test
  public void arrayDeclarationAnnotation() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  static @Nullable String [] fizz = {\"1\"};",
            "  static Object o1 = new Object();",
            "  static void foo() {",
            "      // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field",
            "      o1 = fizz;",
            "      // BUG: Diagnostic contains: dereferenced expression fizz is @Nullable",
            "      o1 = fizz.length;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void arrayLegacyDeclarationAnnotation() {
    makeLegacyModeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  static @Nullable String [] fizz = {\"1\"};",
            "  static Object o1 = new Object();",
            "  static void foo() {",
            "      // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field",
            "      o1 = fizz;",
            "      // BUG: Diagnostic contains: dereferenced expression fizz is @Nullable",
            "      o1 = fizz.length;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void typeUseLegacyAnnotationOnArray() {
    makeLegacyModeHelper()
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  // ok only for backwards compat",
            "  @Nullable Object[] foo1 = null;",
            "  // ok according to spec",
            "  Object @Nullable[] foo2 = null;",
            "  // ok, but elements are not treated as @Nullable outside of JSpecify mode",
            "  @Nullable Object @Nullable[] foo3 = null;",
            "  // ok only for backwards compat",
            "  @Nullable Object [][] foo4 = null;",
            "  // ok according to spec",
            "  Object @Nullable [][] foo5 = null;",
            "  // ok, but @Nullable applies to first array dimension not the elements or the array ref",
            "  Object [] @Nullable [] foo6 = null;",
            "}")
        .doTest();
  }

  @Test
  public void typeUseAnnotationOnArray() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "  // @Nullable is not applied on top-level of array",
            "  // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field",
            "  @Nullable Object[] foo1 = null;",
            "  // ok according to spec",
            "  Object @Nullable[] foo2 = null;",
            "  // ok according to spec",
            "  @Nullable Object @Nullable [] foo3 = null;",
            "  // @Nullable is not applied on top-level of array",
            "  // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field",
            "  @Nullable Object [][] foo4 = null;",
            "  // ok according to spec",
            "  Object @Nullable [][] foo5 = null;",
            "  // @Nullable is not applied on top-level of array",
            "  // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field",
            "  Object [] @Nullable [] foo6 = null;",
            "}")
        .doTest();
  }

  @Test
  public void typeUseAndDeclarationAnnotationOnArray() {
    defaultCompilationHelper
        .addSourceLines(
            "Nullable.java",
            "package com.uber;",
            "import java.lang.annotation.ElementType;",
            "import java.lang.annotation.Target;",
            "@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE})",
            "public @interface Nullable {}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  @Nullable Object[] foo1 = null;",
            "  Object @Nullable[] foo2 = null;",
            "  @Nullable Object @Nullable [] foo3 = null;",
            "  @Nullable Object [][] foo4 = null;",
            "  Object @Nullable [][] foo5 = null;",
            "  // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field",
            "  Object [] @Nullable [] foo6 = null;",
            "}")
        .doTest();
  }

  @Test
  public void typeUseAndDeclarationLegacyAnnotationOnArray() {
    makeLegacyModeHelper()
        .addSourceLines(
            "Nullable.java",
            "package com.uber;",
            "import java.lang.annotation.ElementType;",
            "import java.lang.annotation.Target;",
            "@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE})",
            "public @interface Nullable {}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  @Nullable Object[] foo1 = null;",
            "  Object @Nullable[] foo2 = null;",
            "  @Nullable Object @Nullable [] foo3 = null;",
            "  @Nullable Object [][] foo4 = null;",
            "  Object @Nullable [][] foo5 = null;",
            "  Object [] @Nullable [] foo6 = null;",
            "}")
        .doTest();
  }

  private CompilationTestHelper makeLegacyModeHelper() {
    return makeTestHelperWithArgs(
        Arrays.asList(
            "-XepOpt:NullAway:AnnotatedPackages=com.uber",
            "-XepOpt:NullAway:LegacyAnnotationLocations=true"));
  }
}
