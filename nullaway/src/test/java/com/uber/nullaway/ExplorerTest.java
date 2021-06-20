package com.uber.nullaway;

import com.uber.nullaway.tools.ExplorerTestHelper;
import com.uber.nullaway.tools.Fix;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
@SuppressWarnings({"CheckTestExtendsBaseClass", "UnusedVariable"})
public class ExplorerTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private ExplorerTestHelper explorerTestHelper;

  @SuppressWarnings("CheckReturnValue")
  @Before
  public void setup() {
    explorerTestHelper = ExplorerTestHelper.newInstance(NullAway.class, getClass());
  }

  @Test
  public void add_nullable_returnType_simple() {
    String outputPath = "/tmp/NullAwayFix/fixes.json";
    explorerTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true"))
        .setOutputPath(outputPath)
        .addSourceLines(
            "com/uber/SubClass.java",
            "package com.uber;",
            "public class SubClass {",
            "   Object test(boolean flag) {",
            "       if(flag) {",
            "           return new Object();",
            "       } ",
            "       else return null;",
            "   }",
            "}")
        .addFixes(
            new Fix(
                "javax.annotation.Nullable",
                "test(boolean)",
                "",
                "METHOD_RETURN",
                "com.uber.SubClass",
                "com.uber",
                "com/uber/SubClass.java",
                "true",
                "false"))
        .doTest();
  }

  @Test
  public void add_nullable_returnType_superClass() {
    String outputPath = "/tmp/NullAwayFix/fixes.json";
    explorerTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:FixFilePath=" + outputPath))
        .setOutputPath(outputPath)
        .addSourceLines(
            "com/uber/android/Super.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import javax.annotation.Nonnull;",
            "public class Super {",
            "   Object test(boolean flag) {",
            "     return new Object();",
            "   }",
            "}")
        .addSourceLines(
            "com/uber/test/SubClass.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import javax.annotation.Nonnull;",
            "public class SubClass extends Super {",
            "   @Nullable Object test(boolean flag) {",
            "       if(flag) {",
            "           return new Object();",
            "       } ",
            "       else return null;",
            "   }",
            "}")
        .addFixes(
            new Fix(
                "javax.annotation.Nullable",
                "test(boolean)",
                "",
                "METHOD_RETURN",
                "com.uber.Super",
                "com.uber",
                "com/uber/android/Super.java",
                "true",
                "false"))
        .doTest();
  }

  @Test
  public void add_nullable_paramType_subclass() {
    String outputPath = "/tmp/NullAwayFix/fixes.json";
    explorerTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:FixFilePath=" + outputPath))
        .setOutputPath(outputPath)
        .addSourceLines(
            "com/uber/android/Super.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import javax.annotation.Nonnull;",
            "public class Super {",
            "   @Nullable String test(@Nullable Object o) {",
            "     if(o != null) {",
            "       return o.toString();",
            "     }",
            "     return null;",
            "   }",
            "}")
        .addSourceLines(
            "com/uber/test/SubClass.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import javax.annotation.Nonnull;",
            "public class SubClass extends Super {",
            "   @Nullable String test(Object o) {",
            "     return o.toString();",
            "   }",
            "}")
        .addFixes(
            new Fix(
                "javax.annotation.Nullable",
                "test(java.lang.Object)",
                "o",
                "METHOD_PARAM",
                "com.uber.SubClass",
                "com.uber",
                "com/uber/test/SubClass.java",
                "true",
                "false"))
        .doTest();
  }

  @Test
  public void add_nullable_pass_param_simple() {
    String outputPath = "/tmp/NullAwayFix/fixes.json";
    explorerTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:FixFilePath=" + outputPath))
        .setOutputPath(outputPath)
        .addSourceLines(
            "com/uber/android/Super.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import javax.annotation.Nonnull;",
            "public class Super {",
            "   Object test(int i, Object h) {",
            "     return h;",
            "   }",
            "   Object test_param(@Nullable String o) {",
            "     return test(0, o);",
            "   }",
            "}")
        .addFixes(
            new Fix(
                "javax.annotation.Nullable",
                "test(int,java.lang.Object)",
                "h",
                "METHOD_PARAM",
                "com.uber.Super",
                "com.uber",
                "com/uber/android/Super.java",
                "true",
                "false"))
        .doTest();
  }

  @Test
  public void add_nullable_pass_param_simple_no_fix() {
    String outputPath = "/tmp/NullAwayFix/fixes.json";
    explorerTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:FixFilePath=" + outputPath))
        .setOutputPath(outputPath)
        .addSourceLines(
            "com/uber/android/Super.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import javax.annotation.Nonnull;",
            "public class Super {",
            "   Object test(int i, @Nonnull Object h) {",
            "     return h;",
            "   }",
            "   Object test_param(@Nullable String o) {",
            "     return test(0, o);",
            "   }",
            "}")
        .doTest();
  }

  @Test
  public void add_nullable_field_simple() {
    String outputPath = "/tmp/NullAwayFix/fixes.json";
    explorerTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:FixFilePath=" + outputPath))
        .setOutputPath(outputPath)
        .addSourceLines(
            "com/uber/android/Super.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import javax.annotation.Nonnull;",
            "public class Super {",
            "   Object h = new Object();",
            "   public void test(@Nullable Object f) {",
            "      h = f;",
            "   }",
            "}")
        .addFixes(
            new Fix(
                "javax.annotation.Nullable",
                "",
                "h",
                "CLASS_FIELD",
                "com.uber.Super",
                "com.uber",
                "com/uber/android/Super.java",
                "true",
                "false"))
        .doTest();
  }

  @Test
  public void add_nullable_field_skip_final() {
    String outputPath = "/tmp/NullAwayFix/fixes.json";
    explorerTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:FixFilePath=" + outputPath))
        .setOutputPath(outputPath)
        .addSourceLines(
            "com/uber/android/Super.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Super {",
            "   final Object h;",
            "   public Super(@Nullable Object f) {",
            "      h = f;",
            "   }",
            "}")
        .doTest();
  }

  @Test
  public void add_nullable_field_initialization() {
    String outputPath = "/tmp/NullAwayFix/fixes.json";
    explorerTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:FixFilePath=" + outputPath))
        .setOutputPath(outputPath)
        .addSourceLines(
            "com/uber/android/Super.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Super {",
            "   Object f = foo();",
            "   void test() {",
            "     System.out.println(f.toString());",
            "   }",
            "   @Nullable Object foo() {",
            "     return null;",
            "   }",
            "}")
        .addFixes(
            new Fix(
                "javax.annotation.Nullable",
                "",
                "f",
                "CLASS_FIELD",
                "com.uber.Super",
                "com.uber",
                "com/uber/android/Super.java",
                "true",
                "false"))
        .doTest();
  }

  @Test
  public void add_nullable_field_control_flow() {
    String outputPath = "/tmp/NullAwayFix/fixes.json";
    explorerTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:FixFilePath=" + outputPath))
        .setOutputPath(outputPath)
        .addSourceLines(
            "com/uber/android/Super.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import javax.annotation.Nonnull;",
            "public class Super {",
            "   Object h;",
            "   public Super(boolean b) {",
            "      if(b) h = new Object();",
            "   }",
            "}")
        .addFixes(
            new Fix(
                "javax.annotation.Nullable",
                "",
                "h",
                "CLASS_FIELD",
                "com.uber.Super",
                "com.uber",
                "com/uber/android/Super.java",
                "true",
                "false"))
        .doTest();
  }

  @Test
  public void add_nullable_no_initialization_field() {
    String outputPath = "/tmp/NullAwayFix/fixes.json";
    explorerTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:FixFilePath=" + outputPath))
        .setOutputPath(outputPath)
        .addSourceLines(
            "com/uber/android/Super.java",
            "package com.uber;",
            "public class Super {",
            "   Object f;",
            "}")
        .addFixes(
            new Fix(
                "javax.annotation.Nullable",
                "",
                "f",
                "CLASS_FIELD",
                "com.uber.Super",
                "com.uber",
                "com/uber/android/Super.java",
                "true",
                "false"))
        .doTest();
  }

  @Test
  public void add_nullable_pass_param_generics() {
    String outputPath = "/tmp/NullAwayFix/fixes.json";
    explorerTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:FixFilePath=" + outputPath))
        .setOutputPath(outputPath)
        .addSourceLines(
            "com/uber/Base.java",
            "package com.uber;",
            "import java.util.ArrayList;",
            "public class Base extends Super<String>{",
            "   public void newSideEffect(ArrayList<String> op) {",
            "     newStatement(null, op, true, true);",
            "   }",
            "}")
        .addSourceLines(
            "com/uber/Super.java",
            "package com.uber;",
            "import java.util.ArrayList;",
            "class Super<T extends Object> {",
            "   public boolean newStatement(",
            "     T lhs, ArrayList<T> operator, boolean toWorkList, boolean eager) {",
            "       return false;",
            "   }",
            "}")
        .addFixes(
            new Fix(
                "javax.annotation.Nullable",
                "newStatement(T,java.util.ArrayList<T>,boolean,boolean)",
                "lhs",
                "METHOD_PARAM",
                "com.uber.Super",
                "com.uber",
                "com/uber/Super.java",
                "true",
                "false"))
        .doTest();
  }

  //  @Test
  //  public void param_test_mode_simple() {
  //    String outputPath = "/tmp/NullAwayFix/fixes.json";
  //    explorerTestHelper
  //            .setArgs(
  //                    Arrays.asList(
  //                            "-d",
  //                            temporaryFolder.getRoot().getAbsolutePath(),
  //                            "-XepOpt:NullAway:AnnotatedPackages=com.uber",
  //                            "-XepOpt:NullAway:AutoFix=true",
  //                            "-XepOpt:NullAway:FixFilePath=" + outputPath))
  //            .setOutputPath(outputPath)
  //            .addSourceLines(
  //                    "com/uber/android/Super.java",
  //                    "package com.uber;",
  //                    "import javax.annotation.Nullable;",
  //                    "import javax.annotation.Nonnull;",
  //                    "public class Super {",
  //                    "   Object test(Object h) {",
  //                    "     if(h == null) return new Object();",
  //                    "     return h;",
  //                    "   }",
  //                    "}")
  //            .addFixes(
  //                    new Fix(
  //                            "javax.annotation.Nullable",
  //                            "test(java.lang.Object)",
  //                            "",
  //                            "METHOD_RETURN",
  //                            "com.uber.Super",
  //                            "com.uber",
  //                            "com/uber/android/Super.java",
  //                            "true",
  //                            "false"))
  //            .doTest();
  //  }

  //  @Test
  //  public void detect_must_be_nullable_field() {
  //    String outputPath = "/tmp/NullAwayFix/fixes.json";
  //    explorerTestHelper
  //        .setArgs(
  //            Arrays.asList(
  //                "-d",
  //                temporaryFolder.getRoot().getAbsolutePath(),
  //                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
  //                "-XepOpt:NullAway:AutoFix=true",
  //                "-XepOpt:NullAway:FixFilePath=" + outputPath))
  //        .setOutputPath(outputPath)
  //        .addSourceLines(
  //            "com/uber/Base.java",
  //            "package com.uber;",
  //            "import java.util.ArrayList;",
  //            "public class Base {",
  //            "   Object mustBeNullable = new Object();",
  //            "   public boolean perform_if_check() {",
  //            "     mustBeNullable = new Object();",
  //            "     if(mustBeNullable == null) return true; else return false;",
  //            "   }",
  //            "}")
  //        .addFixes(
  //            new Fix(
  //                "javax.annotation.Nullable",
  //                "",
  //                "mustBeNullable",
  //                "CLASS_FIELD",
  //                "com.uber.Base",
  //                "com.uber",
  //                "com/uber/Base.java",
  //                "true",
  //                "false"))
  //        .doTest();
  //  }
}
