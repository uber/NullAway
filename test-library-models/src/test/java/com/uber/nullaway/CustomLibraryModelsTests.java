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

import com.google.errorprone.BugCheckerRefactoringTestHelper;
import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CustomLibraryModelsTests {

  private CompilationTestHelper makeLibraryModelsTestHelperWithArgs(List<String> args) {
    return CompilationTestHelper.newInstance(NullAway.class, getClass()).setArgs(args);
  }

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void allowLibraryModelsOverrideAnnotations() {
    makeLibraryModelsTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "AnnotatedWithModels.java",
            """
            package com.uber;
            public class AnnotatedWithModels {
               Object field = new Object();
               // implicitly @Nullable due to library model
               Object returnsNullFromModel() {
                  // null is valid here only because of the library model
                  return null;
               }
               Object nullableReturn() {
                   // BUG: Diagnostic contains: returning @Nullable
                   return returnsNullFromModel();
               }
               void run() {
                   // just to make sure, flow analysis is also impacted by library models information
                  Object temp = returnsNullFromModel();
                   // BUG: Diagnostic contains: assigning @Nullable
                  this.field = temp;
               }
            }
            """)
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
            """
            package com.uber;
            import com.uber.lib.unannotated.RestrictivelyAnnotatedFIWithModelOverride;
            import javax.annotation.Nullable;
            public class Test {
              void bar(RestrictivelyAnnotatedFIWithModelOverride f) {
                 // Param is @NullableDecl in bytecode, overridden by library model
                 // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull
                 f.apply(null);
              }
              void foo() {
                RestrictivelyAnnotatedFIWithModelOverride func = (x) -> {
                 // Param is @NullableDecl in bytecode, overridden by library model, thus safe
                 return x.toString();
                };
              }
              void baz() {
                 // Safe to pass, since Function can't have a null instance parameter
                 bar(Object::toString);
              }
            }
            """)
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
            """
            package com.uber;
            import com.uber.lib.unannotated.UnannotatedWithModels;
            import javax.annotation.Nullable;
            public class CallMethods {
              Object testWithoutCheck(UnannotatedWithModels u) {
                 // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type
                 return u.returnsNullUnannotated();
              }
              Object testWithCheck(@Nullable Object o) {
                 if (UnannotatedWithModels.isNonNull(o)) {
                   return o;
                 }
                 return new Object();
              }
            }
            """)
        .addSourceLines(
            "OverrideCheck.java",
            """
            package com.uber;
            import com.uber.lib.unannotated.UnannotatedWithModels;
            import javax.annotation.Nullable;
            public class OverrideCheck extends UnannotatedWithModels {
              @Nullable
              public Object returnsNullUnannotated() {
                 return null;
              }
            }
            """)
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
            """
            package com.uber;
            import com.uber.lib.unannotated.UnannotatedWithModels;
            import javax.annotation.Nullable;
            public class CallMethods {
              Object testWithoutCheck(UnannotatedWithModels u) {
                 // Ok. Library model ignored
                 return u.returnsNullUnannotated();
              }
              Object testWithoutCheckNonIgnoredMethod(UnannotatedWithModels u) {
                 // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type
                 return u.returnsNullUnannotated2();
              }
              Object testWithCheck(@Nullable Object o) {
                 if (UnannotatedWithModels.isNonNull(o)) {
                   // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type
                   return o;
                 }
                 return new Object();
              }
            }
            """)
        .addSourceLines(
            "OverrideCheck.java",
            """
            package com.uber;
            import com.uber.lib.unannotated.UnannotatedWithModels;
            import javax.annotation.Nullable;
            public class OverrideCheck extends UnannotatedWithModels {
              @Nullable // Still safe, because the method is not @NonNull, it's unannotated
            // without model!
              public Object returnsNullUnannotated() {
                 return null;
              }
            }
            """)
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
            """
            package com.uber;
            import com.uber.lib.unannotated.RestrictivelyAnnotatedFIWithModelOverride;
            import javax.annotation.Nullable;
            public class Test {
              void bar(RestrictivelyAnnotatedFIWithModelOverride f) {
                 // Param is @NullableDecl in bytecode, and library model making it non-null is skipped
                 f.apply(null);
              }
              void foo() {
                RestrictivelyAnnotatedFIWithModelOverride func = (x) -> {
                 // Param is @NullableDecl in bytecode, and overriding library model is ignored, thus unsafe
                 // BUG: Diagnostic contains: dereferenced expression x is @Nullable
                 return x.toString();
                };
              }
              void baz() {
                 // BUG: Diagnostic contains: unbound instance method reference cannot be used
                 bar(Object::toString);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void libraryModelsAndOverridingFieldNullability() {
    makeLibraryModelsTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.uber.lib.unannotated.UnannotatedWithModels;
            public class Test {
               UnannotatedWithModels uwm = new UnannotatedWithModels();
               Object nonnullField = new Object();
               void assignNullableFromLibraryModelField() {
                  // BUG: Diagnostic contains: assigning @Nullable
                  this.nonnullField = uwm.nullableFieldUnannotated1;
                  // BUG: Diagnostic contains: assigning @Nullable
                  this.nonnullField = uwm.nullableFieldUnannotated2;
               }
               void flowTest() {
                  if(uwm.nullableFieldUnannotated1 != null) {
                     // no error here, to check that library models only initialize  flow store
                     this.nonnullField = uwm.nullableFieldUnannotated1;
                  }
               }
               String dereferenceTest() {
                  // BUG: Diagnostic contains: dereferenced expression uwm.nullableFieldUnannotated1 is @Nullable
                  return uwm.nullableFieldUnannotated1.toString();
               }
               void assignmentTest() {
                  uwm.nullableFieldUnannotated1 = null;
               }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1194() {
    makeLibraryModelsTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber")))
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.uber.lib.unannotated.ProviderNullMarkedViaModel;
            import org.jspecify.annotations.Nullable;
            public class Test {
              void use(Object o) {}
              void f(Object o) {
                use(o);
                // BUG: Diagnostic contains: passing @Nullable parameter
                use(provider.get());
              }
              ProviderNullMarkedViaModel<@Nullable Object> provider = () -> null;
            }
            """)
        .doTest();
  }

  @Test
  public void methodTypeVarNullableUpperBound() {
    makeLibraryModelsTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.ProviderNullMarkedViaModel;
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
              void test() {
                ProviderNullMarkedViaModel<@Nullable Object> p = ProviderNullMarkedViaModel.of(null);
                // BUG: Diagnostic contains: dereferenced expression p.get() is @Nullable
                p.get().toString();
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                ProviderNullMarkedViaModel<Object> q = ProviderNullMarkedViaModel.of(null);
                ProviderNullMarkedViaModel<Object> r = ProviderNullMarkedViaModel.of(new Object());
                r.get().toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nestedTypeAnnotation() {
    makeLibraryModelsTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.NestedAnnots;
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
              void test() {
                NestedAnnots<@Nullable String> g = NestedAnnots.genericMethod(String.class);
                NestedAnnots<Integer> g2 = NestedAnnots.genericMethod(Integer.class);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void deeplyNestedTypeAnnot() {
    makeLibraryModelsTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.NestedAnnots;
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
              void testPositive(NestedAnnots<NestedAnnots<String>> p) {
                // BUG: Diagnostic contains: incompatible types
                NestedAnnots.deeplyNested(p);
              }
              void testNegative(NestedAnnots<NestedAnnots<@Nullable String>> p) {
                NestedAnnots.deeplyNested(p);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nestedArray1() {
    makeLibraryModelsTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.NestedAnnots;
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
              void testPositive() {
                // BUG: Diagnostic contains: incompatible types
                NestedAnnots<NestedAnnots<String>[]> unused = NestedAnnots.nestedArray1();
              }
              void testNegative() {
                NestedAnnots<NestedAnnots<@Nullable String>[]> unused = NestedAnnots.nestedArray1();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nestedArray2() {
    makeLibraryModelsTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.NestedAnnots;
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
              void testPositive() {
                // BUG: Diagnostic contains: incompatible types
                NestedAnnots<String[]> unused = NestedAnnots.nestedArray2();
              }
              void testNegative() {
                NestedAnnots<String @Nullable []> unused = NestedAnnots.nestedArray2();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nestedWildcards() {
    makeLibraryModelsTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.NestedAnnots;
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
              void testUpper(NestedAnnots<@Nullable String> t) {
                // TODO report an error here when we support wildcards
                NestedAnnots.wildcardUpper(t);
              }
              void testLower(NestedAnnots<String> t) {
                // TODO report an error here when we support wildcards
                NestedAnnots.wildcardLower(t);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void multipleArgs() {
    makeLibraryModelsTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import com.uber.lib.unannotated.NestedAnnots;
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
              void testPositive() {
                NestedAnnots.multipleArgs(
                    // BUG: Diagnostic contains: incompatible types
                    new NestedAnnots<@Nullable String>(),
                    // BUG: Diagnostic contains: incompatible types
                    new NestedAnnots<Integer>());
              }
              void testNegative() {
                NestedAnnots.multipleArgs(
                    new NestedAnnots<String>(), new NestedAnnots<@Nullable Integer>());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void suggestRemovingUnnecessaryCastToNonNullFromLibraryModel() {
    var testHelper =
        BugCheckerRefactoringTestHelper.newInstance(NullAway.class, getClass())
            .setArgs(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SuggestSuppressions=true");
    testHelper
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  public static <T> T castToNonNull(String reason, T value, int line) {",
            "    if (value == null) {",
            "      throw new NullPointerException(reason + \" at line \" + line);",
            "    }",
            "    return value;",
            "  }",
            "  Object test1(Object o) {",
            "    return Test.castToNonNull(\"CAST_REASON\",o,42);",
            "  }",
            "}")
        .addOutputLines(
            "out/Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  public static <T> T castToNonNull(String reason, T value, int line) {",
            "    if (value == null) {",
            "      throw new NullPointerException(reason + \" at line \" + line);",
            "    }",
            "    return value;",
            "  }",
            "  Object test1(Object o) {",
            "    return o;",
            "  }",
            "}")
        .doTest();
  }
}
