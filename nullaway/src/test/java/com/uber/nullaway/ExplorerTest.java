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
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:FixFilePath=" + outputPath))
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
                "",
                "com.uber.SubClass",
                "com.uber",
                "com/uber/SubClass.java",
                "true"))
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
                "",
                "com.uber.Super",
                "com.uber",
                "com/uber/android/Super.java",
                "true"))
        .doTest();
  }
}
