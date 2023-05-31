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
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "AnnotatedWithModels.java",
            "package com.uber;",
            "public class AnnotatedWithModels {",
            "   Object field = new Object();",
            "   // implicitly @Nullable due to library model",
            "   Object returnsNullFromModel() {",
            "      // null is valid here only because of the library model",
            "      return null;",
            "   }",
            "   Object nullableReturn() {",
            "       // BUG: Diagnostic contains: returning @Nullable",
            "       return returnsNullFromModel();",
            "   }",
            "   void run() {",
            "       // just to make sure, flow analysis is also impacted by library models information",
            "      Object temp = returnsNullFromModel();",
            "       // BUG: Diagnostic contains: assigning @Nullable",
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

  @Test
  public void libraryModelsAndSelectiveSkippingViaCommandLineOptions() {
    // First test with the library models in effect
    makeLibraryModelsTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated"))
        .addSourceLines(
            "CallMethods.java",
            "package com.uber;",
            "import com.uber.lib.unannotated.UnannotatedWithModels;",
            "import javax.annotation.Nullable;",
            "public class CallMethods {",
            "  Object testWithoutCheck(UnannotatedWithModels u) {",
            "     // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type",
            "     return u.returnsNullUnannotated();",
            "  }",
            "  Object testWithCheck(@Nullable Object o) {",
            "     if (UnannotatedWithModels.isNonNull(o)) {",
            "       return o;",
            "     }",
            "     return new Object();",
            "  }",
            "}")
        .addSourceLines(
            "OverrideCheck.java",
            "package com.uber;",
            "import com.uber.lib.unannotated.UnannotatedWithModels;",
            "import javax.annotation.Nullable;",
            "public class OverrideCheck extends UnannotatedWithModels {",
            "  @Nullable",
            "  public Object returnsNullUnannotated() {",
            "     return null;",
            "  }",
            "}")
        .doTest();
    // Now test disabling the library model
    makeLibraryModelsTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:IgnoreLibraryModelsFor=com.uber.lib.unannotated.UnannotatedWithModels.returnsNullUnannotated,com.uber.lib.unannotated.UnannotatedWithModels.isNonNull"))
        .addSourceLines(
            "CallMethods.java",
            "package com.uber;",
            "import com.uber.lib.unannotated.UnannotatedWithModels;",
            "import javax.annotation.Nullable;",
            "public class CallMethods {",
            "  Object testWithoutCheck(UnannotatedWithModels u) {",
            "     // Ok. Library model ignored",
            "     return u.returnsNullUnannotated();",
            "  }",
            "  Object testWithoutCheckNonIgnoredMethod(UnannotatedWithModels u) {",
            "     // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type",
            "     return u.returnsNullUnannotated2();",
            "  }",
            "  Object testWithCheck(@Nullable Object o) {",
            "     if (UnannotatedWithModels.isNonNull(o)) {",
            "       // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type",
            "       return o;",
            "     }",
            "     return new Object();",
            "  }",
            "}")
        .addSourceLines(
            "OverrideCheck.java",
            "package com.uber;",
            "import com.uber.lib.unannotated.UnannotatedWithModels;",
            "import javax.annotation.Nullable;",
            "public class OverrideCheck extends UnannotatedWithModels {",
            "  @Nullable", // Still safe, because the method is not @NonNull, it's unannotated
            // without model!
            "  public Object returnsNullUnannotated() {",
            "     return null;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void libraryModelsAndSelectiveSkippingViaCommandLineOptions2() {
    makeLibraryModelsTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true",
                "-XepOpt:NullAway:IgnoreLibraryModelsFor=com.uber.lib.unannotated.RestrictivelyAnnotatedFIWithModelOverride.apply"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.lib.unannotated.RestrictivelyAnnotatedFIWithModelOverride;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  void bar(RestrictivelyAnnotatedFIWithModelOverride f) {",
            "     // Param is @NullableDecl in bytecode, and library model making it non-null is skipped",
            "     f.apply(null);",
            "  }",
            "  void foo() {",
            "    RestrictivelyAnnotatedFIWithModelOverride func = (x) -> {",
            "     // Param is @NullableDecl in bytecode, and overriding library model is ignored, thus unsafe",
            "     // BUG: Diagnostic contains: dereferenced expression x is @Nullable",
            "     return x.toString();",
            "    };",
            "  }",
            "  void baz() {",
            "     // BUG: Diagnostic contains: unbound instance method reference cannot be used",
            "     bar(Object::toString);",
            "  }",
            "}")
        .doTest();
  }
}
