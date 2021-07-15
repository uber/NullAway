package com.uber.nullaway;

import com.google.errorprone.BugCheckerRefactoringTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.uber.nullaway.autofix.AutoFixConfig;
import com.uber.nullaway.autofix.out.display.FixDisplay;
import com.uber.nullaway.tools.AutoFixTestHelper;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NullAwayAutoFixTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private AutoFixTestHelper explorerTestHelper;
  private final String outputPath = "/tmp/NullAwayFix/fixes.csv";

  @Before
  public void setup() {
    explorerTestHelper = AutoFixTestHelper.newInstance(NullAway.class, getClass());
    makeDefaultConfig();
  }

  private void makeDefaultConfig() {
    AutoFixConfig.AutoFixConfigWriter writer =
        new AutoFixConfig.AutoFixConfigWriter().setSuggest(true);
    writer.write("/tmp/NullAwayFix/explorer.config");
  }

  @Test
  public void autofixFlagEnclosingTest() {
    ErrorProneFlags.Builder b = ErrorProneFlags.builder();
    b.putFlag("NullAway:AnnotatedPackages", "com.uber");
    b.putFlag("NullAway:AutoFix", "true");
    ErrorProneFlags flags = b.build();
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

  @Test
  public void add_nullable_returnType_simple() {
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
            new FixDisplay(
                "javax.annotation.Nullable",
                "test(boolean)",
                "null",
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
            new FixDisplay(
                "javax.annotation.Nullable",
                "test(boolean)",
                "null",
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
            new FixDisplay(
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
            new FixDisplay(
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
        .setNoFix()
        .doTest();
  }

  @Test
  public void add_nullable_field_simple() {
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
            new FixDisplay(
                "javax.annotation.Nullable",
                "null",
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
        .setNoFix()
        .doTest();
  }

  @Test
  public void add_nullable_field_initialization() {
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
            new FixDisplay(
                "javax.annotation.Nullable",
                "null",
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
            new FixDisplay(
                "javax.annotation.Nullable",
                "null",
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
            new FixDisplay(
                "javax.annotation.Nullable",
                "null",
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
            new FixDisplay(
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
}
