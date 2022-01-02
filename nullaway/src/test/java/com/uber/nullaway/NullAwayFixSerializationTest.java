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

package com.uber.nullaway;

import com.uber.nullaway.fixserialization.FixSerializationConfig;
import com.uber.nullaway.tools.FixDisplay;
import com.uber.nullaway.tools.FixSerializationTestHelper;
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

/** Unit tests for {@link com.uber.nullaway.NullAway}. */
@RunWith(JUnit4.class)
public class NullAwayFixSerializationTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private FixSerializationTestHelper fixSerializationTestHelper;
  private String outputPath;

  @Before
  public void setup() {
    Path home = Paths.get(temporaryFolder.getRoot().getPath());
    outputPath = home.toString();
    try {
      Files.createDirectories(home);
      fixSerializationTestHelper = new FixSerializationTestHelper(home);
      FixSerializationConfig.FixSerializationConfigBuilder builder =
          new FixSerializationConfig.FixSerializationConfigBuilder()
              .setSuggest(true, false)
              .setOutputDirectory(outputPath);
      Path configPath = home.resolve("explorer.config");
      Files.createFile(configPath);
      builder.write(configPath.toString());
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
    fixSerializationTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:WriteFixMetadata=true",
                "-XepOpt:NullAway:FixMetadataOutputDir=" + outputPath))
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
    fixSerializationTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:WriteFixMetadata=true",
                "-XepOpt:NullAway:FixMetadataOutputDir=" + outputPath))
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
    fixSerializationTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:WriteFixMetadata=true",
                "-XepOpt:NullAway:FixMetadataOutputDir=" + outputPath))
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
    fixSerializationTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:WriteFixMetadata=true",
                "-XepOpt:NullAway:FixMetadataOutputDir=" + outputPath))
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
  public void add_nullable_field_simple() {
    fixSerializationTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:WriteFixMetadata=true",
                "-XepOpt:NullAway:FixMetadataOutputDir=" + outputPath))
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
  public void add_nullable_field_initialization() {
    fixSerializationTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:WriteFixMetadata=true",
                "-XepOpt:NullAway:FixMetadataOutputDir=" + outputPath))
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
    fixSerializationTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:WriteFixMetadata=true",
                "-XepOpt:NullAway:FixMetadataOutputDir=" + outputPath))
        .addSourceLines(
            "com/uber/android/Super.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import javax.annotation.Nonnull;",
            "public class Super {",
            "   Object h, f, g, i;",
            "   // BUG: Diagnostic contains: initializer method",
            "   public Super(boolean b) {",
            "      g = new Object();",
            "      i = g;",
            "      if(b) {",
            "         h = new Object();",
            "      }",
            "      else{",
            "         f = new Object();",
            "      }",
            "   }",
            "   // BUG: Diagnostic contains: initializer method",
            "   public Super(boolean b, boolean a) {",
            "      f = new Object();",
            "      h = f;",
            "      if(a) {",
            "         g = new Object();",
            "      }",
            "      else{",
            "         i = new Object();",
            "      }",
            "   }",
            "}")
        .addFixes(
            new FixDisplay(
                "javax.annotation.Nullable",
                "null",
                "h",
                "CLASS_FIELD",
                "com.uber.Super",
                "com/uber/android/Super.java"),
            new FixDisplay(
                "javax.annotation.Nullable",
                "null",
                "f",
                "CLASS_FIELD",
                "com.uber.Super",
                "com/uber/android/Super.java"),
            new FixDisplay(
                "javax.annotation.Nullable",
                "null",
                "g",
                "CLASS_FIELD",
                "com.uber.Super",
                "com/uber/android/Super.java"),
            new FixDisplay(
                "javax.annotation.Nullable",
                "null",
                "i",
                "CLASS_FIELD",
                "com.uber.Super",
                "com/uber/android/Super.java"))
        .doTest();
  }

  @Test
  public void add_nullable_no_initialization_field() {
    fixSerializationTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:WriteFixMetadata=true",
                "-XepOpt:NullAway:FixMetadataOutputDir=" + outputPath))
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
    fixSerializationTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:WriteFixMetadata=true",
                "-XepOpt:NullAway:FixMetadataOutputDir=" + outputPath))
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

  @Test
  public void skip_pass_nullable_param_explicit_nonnull() {
    fixSerializationTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:WriteFixMetadata=true",
                "-XepOpt:NullAway:FixMetadataOutputDir=" + outputPath))
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
        .doTest();
  }

  @Test
  public void skip_return_nullable_explicit_nonnull() {
    fixSerializationTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:WriteFixMetadata=true",
                "-XepOpt:NullAway:FixMetadataOutputDir=" + outputPath))
        .addSourceLines(
            "com/uber/Base.java",
            "package com.uber;",
            "import javax.annotation.Nonnull;",
            "public class Base {",
            "   @Nonnull Object test() {",
            "     // BUG: Diagnostic contains: returning @Nullable",
            "     return null;",
            "   }",
            "}")
        .doTest();
  }

  @Test
  public void skip_field_nullable_explicit_nonnull() {
    fixSerializationTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:WriteFixMetadata=true",
                "-XepOpt:NullAway:FixMetadataOutputDir=" + outputPath))
        .addSourceLines(
            "com/uber/Base.java",
            "package com.uber;",
            "import javax.annotation.Nonnull;",
            "public class Base {",
            "   // BUG: Diagnostic contains: field f not initialized",
            "   @Nonnull Object f;",
            "}")
        .doTest();
  }
}
