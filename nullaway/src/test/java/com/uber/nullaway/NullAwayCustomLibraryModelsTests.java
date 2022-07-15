/*
 * Copyright (c) 2022 Uber Technologies, Inc.
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
import com.uber.nullaway.testlibrarymodels.TestLibraryModels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class NullAwayCustomLibraryModelsTests extends NullAwayTestsBase {

  private CompilationTestHelper makeLibraryModelsTestHelperWithArgs(List<String> args) {
    // Adding directly to args will throw an UnsupportedOperationException, since that list is
    // created by calling Arrays.asList (for consistency with the rest of NullAway's test cases),
    // which produces a list which doesn't support add/addAll. Because of this, before we add our
    // additional arguments, we must first copy the list into a mutable ArrayList.
    List<String> extendedArguments = new ArrayList<>(args);
    extendedArguments.addAll(
        0,
        Arrays.asList(
            "-processorpath",
            TestLibraryModels.class.getProtectionDomain().getCodeSource().getLocation().getPath()));
    return makeTestHelperWithArgs(extendedArguments);
  }

  @Test
  public void allowLibraryModelsOverrideAnnotations() {
    makeLibraryModelsTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AcknowledgeLibraryModelsOfAnnotatedCode=true"))
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "public class Foo {",
            "   Object field = new Object();",
            "   Object bar() {",
            "      return new Object();",
            "   }",
            "   Object nullableReturn() {",
            "       // BUG: Diagnostic contains: returning @Nullable",
            "       return bar();",
            "   }",
            "   void run() {",
            "       // just to make sure, flow analysis is also impacted by library models information",
            "      Object temp = bar();",
            "       // BUG: Diagnostic contains: assigning @Nullable",
            "      this.field = temp;",
            "   }",
            "}")
        .doTest();
  }

  @Test
  public void allowLibraryModelsOverrideAnnotationsFlagTest() {
    makeLibraryModelsTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AcknowledgeLibraryModelsOfAnnotatedCode=false"))
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "public class Foo {",
            "   Object field = new Object();",
            "   Object bar() {",
            "      return new Object();",
            "   }",
            "   Object nullableReturn() {",
            "       return bar();",
            "   }",
            "   void run() {",
            "       // just to make sure, flow analysis is not impacted by library models information",
            "      Object temp = bar();",
            "      this.field = temp;",
            "   }",
            "}")
        .doTest();
  }

  @Test
  public void libraryModelsOverrideRestrictiveAnnotations() {
    makeLibraryModelsTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.lib.unannotated.RestrictivelyAnnotatedFIWithModelOverride;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  void bar(RestrictivelyAnnotatedFIWithModelOverride f) {",
            "     // Param is @NullableDecl in bytecode, overridden by library model",
            "     // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull",
            "     f.apply(null);",
            "  }",
            "  void foo() {",
            "    RestrictivelyAnnotatedFIWithModelOverride func = (x) -> {",
            "     // Param is @NullableDecl in bytecode, overridden by library model, thus safe",
            "     return x.toString();",
            "    };",
            "  }",
            "  void baz() {",
            "     // Safe to pass, since Function can't have a null instance parameter",
            "     bar(Object::toString);",
            "  }",
            "}")
        .doTest();
  }
}
