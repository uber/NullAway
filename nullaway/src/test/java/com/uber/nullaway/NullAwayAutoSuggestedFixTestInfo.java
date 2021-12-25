package com.uber.nullaway;

import com.uber.nullaway.fixserialization.FixSerializationConfig;
import com.uber.nullaway.tools.AutoFixTestHelper;
import com.uber.nullaway.tools.FixDisplay;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.springframework.util.FileSystemUtils;

@RunWith(JUnit4.class)
public class NullAwayAutoSuggestedFixTestInfo {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private AutoFixTestHelper autoFixTestHelper;
  private String outputPath;

  @Before
  public void setup() {
    Path home = Paths.get(temporaryFolder.getRoot().getPath());
    outputPath = home.toString();
    try {
      Files.createDirectories(home);
      autoFixTestHelper = new AutoFixTestHelper(home);
      FixSerializationConfig.FixSerializationConfigBuilder writer =
          new FixSerializationConfig.FixSerializationConfigBuilder().setSuggest(true, false);
      Path configPath = home.resolve("explorer.config");
      Files.createFile(configPath);
      writer.write(configPath.toString());
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  @After
  public void cleanup() {
    FileSystemUtils.deleteRecursively(temporaryFolder.getRoot());
  }

  @Test
  public void add_nullable_returnType_simple() {
    autoFixTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:AutoFixOutPutDirectory=" + outputPath))
        .addSourceLines(
            "com/uber/SubClass.java",
            "package com.uber;",
            "public class SubClass {",
            "   Object test(boolean flag) {",
            "       if(flag) {",
            "           return new Object();",
            "       } ",
            "       // BUG: Diagnostic contains: returning @Nullable",
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
                "com/uber/SubClass.java"))
        .doTest();
  }

  @Test
  public void add_nullable_returnType_superClass() {
    autoFixTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:AutoFixOutPutDirectory=" + outputPath))
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
            "   // BUG: Diagnostic contains: returns @Nullable",
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
                "com/uber/android/Super.java"))
        .doTest();
  }

  @Test
  public void add_nullable_paramType_subclass() {
    autoFixTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:AutoFixOutPutDirectory=" + outputPath))
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
            "   // BUG: Diagnostic contains: parameter o is @NonNull",
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
                "com/uber/test/SubClass.java"))
        .doTest();
  }

  @Test
  public void add_nullable_pass_param_simple() {
    autoFixTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:AutoFixOutPutDirectory=" + outputPath))
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
            "   // BUG: Diagnostic contains: passing @Nullable",
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
                "com/uber/android/Super.java"))
        .doTest();
  }

  @Test
  public void add_nullable_pass_param_simple_no_fix() {
    autoFixTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:AutoFixOutPutDirectory=" + outputPath))
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
            "   // BUG: Diagnostic contains: passing @Nullable",
            "     return test(0, o);",
            "   }",
            "}")
        .setNoFix()
        .doTest();
  }

  @Test
  public void add_nullable_field_simple() {
    autoFixTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:AutoFixOutPutDirectory=" + outputPath))
        .addSourceLines(
            "com/uber/android/Super.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import javax.annotation.Nonnull;",
            "public class Super {",
            "   Object h = new Object();",
            "   public void test(@Nullable Object f) {",
            "   // BUG: Diagnostic contains: assigning @Nullable",
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
                "com/uber/android/Super.java"))
        .doTest();
  }

  @Test
  public void add_nullable_field_skip_final() {
    autoFixTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:AutoFixOutPutDirectory=" + outputPath))
        .addSourceLines(
            "com/uber/android/Super.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Super {",
            "   final Object h;",
            "   public Super(@Nullable Object f) {",
            "   // BUG: Diagnostic contains: assigning @Nullable",
            "      h = f;",
            "   }",
            "}")
        .setNoFix()
        .doTest();
  }

  @Test
  public void add_nullable_field_initialization() {
    autoFixTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:AutoFixOutPutDirectory=" + outputPath))
        .addSourceLines(
            "com/uber/android/Super.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Super {",
            "   // BUG: Diagnostic contains: assigning @Nullable",
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
                "com/uber/android/Super.java"))
        .doTest();
  }

  @Test
  public void add_nullable_field_control_flow() {
    autoFixTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:AutoFixOutPutDirectory=" + outputPath))
        .addSourceLines(
            "com/uber/android/Super.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import javax.annotation.Nonnull;",
            "public class Super {",
            "   Object h;",
            "   // BUG: Diagnostic contains: initializer method",
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
                "com/uber/android/Super.java"))
        .doTest();
  }

  @Test
  public void add_nullable_no_initialization_field() {
    autoFixTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:AutoFixOutPutDirectory=" + outputPath))
        .addSourceLines(
            "com/uber/android/Super.java",
            "package com.uber;",
            "public class Super {",
            "   // BUG: Diagnostic contains: field f not initialized",
            "   Object f;",
            "}")
        .addFixes(
            new FixDisplay(
                "javax.annotation.Nullable",
                "null",
                "f",
                "CLASS_FIELD",
                "com.uber.Super",
                "com/uber/android/Super.java"))
        .doTest();
  }

  @Test
  public void add_nullable_pass_param_generics() {
    autoFixTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AutoFix=true",
                "-XepOpt:NullAway:AutoFixOutPutDirectory=" + outputPath))
        .addSourceLines(
            "com/uber/Base.java",
            "package com.uber;",
            "import java.util.ArrayList;",
            "public class Base extends Super<String>{",
            "   public void newSideEffect(ArrayList<String> op) {",
            "   // BUG: Diagnostic contains: passing @Nullable",
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
                "com/uber/Super.java"))
        .doTest();
  }
}
