package com.uber.nullaway;

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

import com.google.errorprone.BugCheckerRefactoringTestHelper;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NullAwayAutoFixTest {

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private ErrorProneFlags flags;

  @Before
  public void setup() {
    ErrorProneFlags.Builder b = ErrorProneFlags.builder();
    b.putFlag("NullAway:AnnotatedPackages", "com.uber,com.ubercab,io.reactivex");
    b.putFlag("NullAway:AutoFix", "true");
    flags = b.build();
  }

  @Test
  public void seeNullAwayResponse() {
    CompilationTestHelper compilationHelper =
        CompilationTestHelper.newInstance(NullAway.class, getClass());
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "com/uber/android/Super.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import javax.annotation.Nonnull;",
            "public class Super {",
            "   Object f = foo();",
            "   void test() {",
            "     f = null;",
            "     System.out.println(f.toString());",
            "   }",
            "   @Nullable Object foo() {",
            "     return null;",
            "   }",
            "}")
        .doTest();
  }

  @Test
  public void correctCode() {
    BugCheckerRefactoringTestHelper bcr =
        BugCheckerRefactoringTestHelper.newInstance(new NullAway(flags), getClass());

    bcr.setArgs("-d", temporaryFolder.getRoot().getAbsolutePath())
        .addInputLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  @Nullable Object test1(@Nullable Object o) {",
            "    return o;",
            "  }",
            "}")
        .expectUnchanged()
        .doTest();
  }

  // TODO: make the following tests compatible with the new explorer/patcher mode

  //  @Test
  //  public void assignmentNullableToNonnull_Local_Simple() {
  //    BugCheckerRefactoringTestHelper bcr =
  //        BugCheckerRefactoringTestHelper.newInstance(new NullAway(flags), getClass());
  //
  //    bcr.setArgs("-d", temporaryFolder.getRoot().getAbsolutePath())
  //        .addInputLines(
  //            "Test.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "class Test {",
  //            "   void test(@Nullable Object other) {",
  //            "       Object t = new Object();",
  //            "       t = other;",
  //            "   }",
  //            "}")
  //        .addOutputLines(
  //            "Test.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "class Test {",
  //            "   void test(@Nullable Object other) {",
  //            "       @Nullable Object t = new Object();",
  //            "       t = other;",
  //            "   }",
  //            "}")
  //        .doTest();
  //  }
  //
  //  @Test
  //  public void assignmentNullableToNonnull_Field_Simple() {
  //    BugCheckerRefactoringTestHelper bcr =
  //        BugCheckerRefactoringTestHelper.newInstance(new NullAway(flags), getClass());
  //
  //    bcr.setArgs("-d", temporaryFolder.getRoot().getAbsolutePath())
  //        .addInputLines(
  //            "Test.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "class Test {",
  //            "   Object t1 = new Object();",
  //            "   Object test(Object o) {",
  //            "     return o;",
  //            "   }",
  //            "}")
  //        .addOutputLines(
  //            "Test.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "class Test {",
  //            "   @Nullable Object t1 = new Object();",
  //            "   void test(@Nullable Object other) {",
  //            "       t1 = other;",
  //            "   }",
  //            "}")
  //        .doTest();
  //  }
  //
  //  @Test
  //  public void assignmentNullableToNonnull_Field_FromOtherClass() {
  //    BugCheckerRefactoringTestHelper bcr =
  //        BugCheckerRefactoringTestHelper.newInstance(new NullAway(flags), getClass());
  //
  //    bcr.setArgs("-d", temporaryFolder.getRoot().getAbsolutePath())
  //        .addInputLines(
  //            "Test.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "public class Test {",
  //            "   Other other = new Other();",
  //            "   void test(@Nullable Object o) {",
  //            "     this.other.t = o;",
  //            "   }",
  //            "}")
  //        .addOutputLines(
  //            "Test.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "public class Test {",
  //            "   Other other = new Other();",
  //            "   void test(@Nullable Object o) {",
  //            "     this.other.t = o;",
  //            "   }",
  //            "}")
  //        .addInputLines(
  //            "Other.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "public class Other {",
  //            "   Object t = new Object();",
  //            "}")
  //        .addOutputLines(
  //            "Other.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "class Other {",
  //            "   @Nullable Object t = new Object();",
  //            "}")
  //        .doTest();
  //  }
  //
  //  @Test
  //  public void assignmentNullableToNonnull_Field_FromSuperClass() {
  //    BugCheckerRefactoringTestHelper bcr =
  //        BugCheckerRefactoringTestHelper.newInstance(new NullAway(flags), getClass());
  //
  //    bcr.setArgs("-d", temporaryFolder.getRoot().getAbsolutePath())
  //        .addInputLines(
  //            "Test.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "public class Test {",
  //            "   Other other = new Other();",
  //            "   void test(@Nullable Object o) {",
  //            "     this.other.t = o;",
  //            "   }",
  //            "}")
  //        .addOutputLines(
  //            "Test.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "public class Test {",
  //            "   Other other = new Other();",
  //            "   void test(@Nullable Object o) {",
  //            "     this.other.t = o;",
  //            "   }",
  //            "}")
  //        .addInputLines(
  //            "Base.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "class Base {",
  //            "   Object t = new Object();",
  //            "}")
  //        .addOutputLines(
  //            "Base.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "class Base {",
  //            "   @Nullable Object t = new Object();",
  //            "}")
  //        .addInputLines(
  //            "Other.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "class Other extends Base {",
  //            "   Object t1 = new Object();",
  //            "}")
  //        .addOutputLines(
  //            "Other.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "class Other extends Base {",
  //            "   Object t1 = new Object();",
  //            "}")
  //        .doTest();
  //  }
  //
  //  @Test
  //  public void assignmentNullableToNonnull_Field_FromSuperClassOverride() {
  //    BugCheckerRefactoringTestHelper bcr =
  //        BugCheckerRefactoringTestHelper.newInstance(new NullAway(flags), getClass());
  //
  //    bcr.setArgs("-d", temporaryFolder.getRoot().getAbsolutePath())
  //        .addInputLines(
  //            "Test.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "public class Test {",
  //            "   Other other = new Other();",
  //            "   void test(@Nullable Object o) {",
  //            "     this.other.t = o;",
  //            "   }",
  //            "}")
  //        .addOutputLines(
  //            "Test.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "public class Test {",
  //            "   Other other = new Other();",
  //            "   void test(@Nullable Object o) {",
  //            "     this.other.t = o;",
  //            "   }",
  //            "}")
  //        .addInputLines(
  //            "Base.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "class Base {",
  //            "   Object t = new Object();",
  //            "}")
  //        .addOutputLines(
  //            "Base.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "class Base {",
  //            "   @Nullable Object t = new Object();",
  //            "}")
  //        .addInputLines(
  //            "Other.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "class Other extends Base {",
  //            "   Object t = new Object();",
  //            "}")
  //        .addOutputLines(
  //            "Other.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "class Other extends Base {",
  //            "   @Nullable Object t = new Object();",
  //            "}")
  //        .doTest();
  //  }
  //
  //  @Test
  //  public void addReturnNullable_Simple() {
  //    BugCheckerRefactoringTestHelper bcr =
  //        BugCheckerRefactoringTestHelper.newInstance(new NullAway(flags), getClass());
  //
  //    bcr.setArgs("-d", temporaryFolder.getRoot().getAbsolutePath())
  //        .addInputLines(
  //            "Test.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "public class Test {",
  //            "   Object test1(@Nullable Object o) {",
  //            "     return o;",
  //            "   }",
  //            "}")
  //        .addOutputLines(
  //            "Test.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "public class Test {",
  //            "   @Nullable Object test1(@Nullable Object o) {",
  //            "     return o;",
  //            "   }",
  //            "}")
  //        .doTest();
  //  }
  //
  //  @Test
  //  public void addReturnNullable_SuperClass_Simple() {
  //    BugCheckerRefactoringTestHelper bcr =
  //        BugCheckerRefactoringTestHelper.newInstance(new NullAway(flags), getClass());
  //
  //    bcr.setArgs("-d", temporaryFolder.getRoot().getAbsolutePath())
  //        .addInputLines(
  //            "Super.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "public class Super {",
  //            "   Object test(boolean flag) {",
  //            "     return new Object();",
  //            "   }",
  //            "}")
  //        .addOutputLines(
  //            "Super.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "public class Super {",
  //            "   @Nullable Object test(boolean flag) {",
  //            "     return new Object();",
  //            "   }",
  //            "}")
  //        .addInputLines(
  //            "SubClass.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "class SubClass extends Super {",
  //            "   @Nullable Object test(boolean flag) {",
  //            "       if(flag) {",
  //            "           return new Object();",
  //            "       } ",
  //            "       else return null;",
  //            "   }",
  //            "}")
  //        .addOutputLines(
  //            "SubClass.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "class SubClass extends Super {",
  //            "   @Nullable Object test(boolean flag) {",
  //            "       if(flag) {",
  //            "           return new Object();",
  //            "       } ",
  //            "       else return null;",
  //            "   }",
  //            "}")
  //        .doTest();
  //  }
  //
  //  @Test
  //  public void addReturnNullable_SuperClass() {
  //    BugCheckerRefactoringTestHelper bcr =
  //        BugCheckerRefactoringTestHelper.newInstance(new NullAway(flags), getClass());
  //
  //    bcr.setArgs("-d", temporaryFolder.getRoot().getAbsolutePath())
  //        .addInputLines(
  //            "Super.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "public class Super {",
  //            "   Object test(boolean flag) {",
  //            "     return new Object();",
  //            "   }",
  //            "}")
  //        .addOutputLines(
  //            "Super.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "public class Super {",
  //            "   @Nullable Object test(boolean flag) {",
  //            "     return new Object();",
  //            "   }",
  //            "}")
  //        .addInputLines(
  //            "SubClass.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "class SubClass extends Super {",
  //            "   Object test(boolean flag) {",
  //            "       if(flag) {",
  //            "           return new Object();",
  //            "       } ",
  //            "       else return null;",
  //            "   }",
  //            "}")
  //        .addOutputLines(
  //            "SubClass.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "class SubClass extends Super {",
  //            "   @Nullable Object test(boolean flag) {",
  //            "       if(flag) {",
  //            "           return new Object();",
  //            "       } ",
  //            "       else return null;",
  //            "   }",
  //            "}")
  //        .doTest();
  //  }
  //
  //  @Test
  //  public void passNullableToNonnull_Simple() {
  //    BugCheckerRefactoringTestHelper bcr =
  //        BugCheckerRefactoringTestHelper.newInstance(new NullAway(flags), getClass());
  //
  //    bcr.setArgs("-d", temporaryFolder.getRoot().getAbsolutePath())
  //        .addInputLines(
  //            "Test.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "public class Test {",
  //            "   void test(Object o) {",
  //            "     System.out.println(o);",
  //            "   }",
  //            "   void callTest(@Nullable Object o) {",
  //            "       test(o);",
  //            "   }",
  //            "}")
  //        .addOutputLines(
  //            "Test.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "public class Test {",
  //            "   void test(@Nullable Object o) {",
  //            "     System.out.println(o);",
  //            "   }",
  //            "   void callTest(@Nullable Object o) {",
  //            "       test(o);",
  //            "   }",
  //            "}")
  //        .doTest();
  //  }
  //
  //  @Test
  //  public void passNullableToNonnull_ToSuperClass() {
  //    BugCheckerRefactoringTestHelper bcr =
  //        BugCheckerRefactoringTestHelper.newInstance(new NullAway(flags), getClass());
  //
  //    bcr.setArgs("-d", temporaryFolder.getRoot().getAbsolutePath())
  //        .addInputLines(
  //            "Base.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "public class Base {",
  //            "   void test(Object o) {",
  //            "       System.out.println(o);",
  //            "   }",
  //            "}")
  //        .addOutputLines(
  //            "Base.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "public class Base {",
  //            "   void test(@Nullable Object o) {",
  //            "       System.out.println(o);",
  //            "   }",
  //            "}")
  //        .addInputLines(
  //            "Child.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "class Child extends Base{",
  //            "   void callTest(@Nullable Object o) {",
  //            "       test(o);",
  //            "   }",
  //            "}")
  //        .addOutputLines(
  //            "Child.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "class Child extends Base{",
  //            "   void callTest(@Nullable Object o) {",
  //            "       test(o);",
  //            "   }",
  //            "}")
  //        .doTest();
  //  }
  //
  //  @Test
  //  public void paramNullableDefinition_Simple() {
  //    BugCheckerRefactoringTestHelper bcr =
  //        BugCheckerRefactoringTestHelper.newInstance(new NullAway(flags), getClass());
  //
  //    bcr.setArgs("-d", temporaryFolder.getRoot().getAbsolutePath())
  //        .addInputLines(
  //            "Base.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "public class Base {",
  //            "   void test(Object o) {",
  //            "       System.out.println(o);",
  //            "   }",
  //            "}")
  //        .addOutputLines(
  //            "Base.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "public class Base {",
  //            "   void test(@Nullable Object o) {",
  //            "       System.out.println(o);",
  //            "   }",
  //            "}")
  //        .addInputLines(
  //            "Child.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "class Child extends Base{",
  //            "   void test(@Nullable Object o) {",
  //            "       System.out.println(o);",
  //            "   }",
  //            "}")
  //        .addOutputLines(
  //            "Child.java",
  //            "package com.uber;",
  //            "import javax.annotation.Nullable;",
  //            "class Child extends Base{",
  //            "   void test(@Nullable Object o) {",
  //            "       System.out.println(o);",
  //            "   }",
  //            "}")
  //        .doTest();
  //  }
}
