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

import com.google.common.base.Preconditions;
import com.uber.nullaway.fixserialization.FixSerializationConfig;
import com.uber.nullaway.tools.ErrorDisplay;
import com.uber.nullaway.tools.Factory;
import com.uber.nullaway.tools.FieldInitializationDisplay;
import com.uber.nullaway.tools.FixDisplay;
import com.uber.nullaway.tools.SerializationTestHelper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.springframework.util.FileSystemUtils;

/** Unit tests for {@link com.uber.nullaway.NullAway}. */
@RunWith(JUnit4.class)
public class NullAwaySerializationTest extends NullAwayTestsBase {
  private String configPath;
  private Path root;
  private final Factory<FixDisplay> fixDisplayFactory;
  private Factory<ErrorDisplay> errorDisplayFactory;
  private Factory<FieldInitializationDisplay> fieldInitializationDisplayFactory;

  public NullAwaySerializationTest() {
    fixDisplayFactory =
        line -> {
          String[] infos = line.split("\\t");
          Preconditions.checkArgument(
              infos.length == 10,
              "Needs exactly 10 values to create FixDisplay object but found: " + infos.length);
          // Fixes are written in Temp Directory and is not known at compile time, therefore,
          // relative
          // paths are getting compared.
          FixDisplay fixDisplay =
              new FixDisplay(infos[7], infos[2], infos[3], infos[0], infos[1], infos[5]);
          fixDisplay.uri = fixDisplay.uri.substring(fixDisplay.uri.indexOf("com/uber/"));
          return fixDisplay;
        };
  }

  @Before
  @Override
  public void setup() {
    root = Paths.get(temporaryFolder.getRoot().getAbsolutePath());
    String output = root.toString();
    try {
      Files.createDirectories(root);
      FixSerializationConfig.Builder builder =
          new FixSerializationConfig.Builder().setSuggest(true, false).setOutputDirectory(output);
      Path config = root.resolve("serializer.xml");
      Files.createFile(config);
      configPath = config.toString();
      builder.writeAsXML(configPath);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  @After
  public void cleanup() {
    FileSystemUtils.deleteRecursively(temporaryFolder.getRoot());
  }

  @Test
  public void add_nullable_return_simple() {
    SerializationTestHelper<FixDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
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
        .setExpectedOutputs(
            new FixDisplay(
                "javax.annotation.Nullable",
                "test(boolean)",
                "null",
                "METHOD",
                "com.uber.SubClass",
                "com/uber/SubClass.java"))
        .doTest();
  }

  @Test
  public void add_nullable_return_superClass() {
    SerializationTestHelper<FixDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
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
        .setExpectedOutputs(
            new FixDisplay(
                "javax.annotation.Nullable",
                "test(boolean)",
                "null",
                "METHOD",
                "com.uber.Super",
                "com/uber/android/Super.java"))
        .doTest();
  }

  @Test
  public void add_nullable_param_simple() {
    SerializationTestHelper<FixDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
        .addSourceLines(
            "com/uber/android/Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import javax.annotation.Nonnull;",
            "public class Test {",
            "   Object run(int i, Object h) {",
            "     return h;",
            "   }",
            "   Object test_param(@Nullable String o) {",
            "     // BUG: Diagnostic contains: passing @Nullable",
            "     return run(0, o);",
            "   }",
            "}")
        .setExpectedOutputs(
            new FixDisplay(
                "javax.annotation.Nullable",
                "run(int,java.lang.Object)",
                "h",
                "PARAMETER",
                "com.uber.Test",
                "com/uber/android/Test.java"))
        .doTest();
  }

  @Test
  public void add_nullable_param_subclass() {
    SerializationTestHelper<FixDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
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
        .setExpectedOutputs(
            new FixDisplay(
                "javax.annotation.Nullable",
                "test(java.lang.Object)",
                "o",
                "PARAMETER",
                "com.uber.SubClass",
                "com/uber/test/SubClass.java"))
        .doTest();
  }

  @Test
  public void add_nullable_param_this_constructor() {
    SerializationTestHelper<FixDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
        .addSourceLines(
            "com/uber/test/Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import javax.annotation.Nonnull;",
            "public class Test {",
            "   Test () {",
            "       // BUG: Diagnostic contains: passing @Nullable parameter 'null'",
            "       this(null);",
            "   }",
            "   Test (Object o) {",
            "      System.out.println(o.toString());",
            "   }",
            "",
            "}")
        .setExpectedOutputs(
            new FixDisplay(
                "javax.annotation.Nullable",
                "Test(java.lang.Object)",
                "o",
                "PARAMETER",
                "com.uber.Test",
                "com/uber/test/Test.java"))
        .doTest();
  }

  @Test
  public void add_nullable_param_generics() {
    SerializationTestHelper<FixDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
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
        .addSourceLines(
            "com/uber/Child.java",
            "package com.uber;",
            "import java.util.ArrayList;",
            "public class Child extends Super<String>{",
            "   public void newSideEffect(ArrayList<String> op) {",
            "     // BUG: Diagnostic contains: passing @Nullable",
            "     newStatement(null, op, true, true);",
            "   }",
            "}")
        .setExpectedOutputs(
            new FixDisplay(
                "javax.annotation.Nullable",
                "newStatement(T,java.util.ArrayList<T>,boolean,boolean)",
                "lhs",
                "PARAMETER",
                "com.uber.Super",
                "com/uber/Super.java"))
        .doTest();
  }

  @Test
  public void add_nullable_field_simple() {
    SerializationTestHelper<FixDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
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
        .setExpectedOutputs(
            new FixDisplay(
                "javax.annotation.Nullable",
                "null",
                "h",
                "FIELD",
                "com.uber.Super",
                "com/uber/android/Super.java"))
        .doTest();
  }

  @Test
  public void add_nullable_field_initialization() {
    SerializationTestHelper<FixDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
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
        .setExpectedOutputs(
            new FixDisplay(
                "javax.annotation.Nullable",
                "null",
                "f",
                "FIELD",
                "com.uber.Super",
                "com/uber/android/Super.java"))
        .doTest();
  }

  @Test
  public void add_nullable_field_control_flow() {
    SerializationTestHelper<FixDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
        .addSourceLines(
            "com/uber/android/Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import javax.annotation.Nonnull;",
            "public class Test {",
            "   Object h, f, g, i, k;",
            "   // BUG: Diagnostic contains: initializer method",
            "   public Test(boolean b) {",
            "      g = new Object();",
            "      k = new Object();",
            "      i = g;",
            "      if(b) {",
            "         h = new Object();",
            "      }",
            "      else{",
            "         f = new Object();",
            "      }",
            "   }",
            "   // BUG: Diagnostic contains: initializer method",
            "   public Test(boolean b, boolean a) {",
            "      f = new Object();",
            "      k = new Object();",
            "      h = f;",
            "      if(a) {",
            "         g = new Object();",
            "      }",
            "      else{",
            "         i = new Object();",
            "      }",
            "   }",
            "}")
        .setExpectedOutputs(
            new FixDisplay(
                "javax.annotation.Nullable",
                "null",
                "h",
                "FIELD",
                "com.uber.Test",
                "com/uber/android/Test.java"),
            new FixDisplay(
                "javax.annotation.Nullable",
                "null",
                "f",
                "FIELD",
                "com.uber.Test",
                "com/uber/android/Test.java"),
            new FixDisplay(
                "javax.annotation.Nullable",
                "null",
                "g",
                "FIELD",
                "com.uber.Test",
                "com/uber/android/Test.java"),
            new FixDisplay(
                "javax.annotation.Nullable",
                "null",
                "i",
                "FIELD",
                "com.uber.Test",
                "com/uber/android/Test.java"))
        .doTest();
  }

  @Test
  public void add_nullable_no_initialization_field() {
    SerializationTestHelper<FixDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
        .addSourceLines(
            "com/uber/android/Test.java",
            "package com.uber;",
            "public class Test {",
            "   // BUG: Diagnostic contains: field f not initialized",
            "   Object f;",
            "}")
        .setExpectedOutputs(
            new FixDisplay(
                "javax.annotation.Nullable",
                "null",
                "f",
                "FIELD",
                "com.uber.Test",
                "com/uber/android/Test.java"))
        .doTest();
  }

  @Test
  public void skip_pass_nullable_param_explicit_nonnull() {
    SerializationTestHelper<FixDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
        .addSourceLines(
            "com/uber/android/Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import javax.annotation.Nonnull;",
            "public class Test {",
            "   Object test(int i, @Nonnull Object h) {",
            "     return h;",
            "   }",
            "   Object test_param(@Nullable String o) {",
            "   // BUG: Diagnostic contains: passing @Nullable",
            "     return test(0, o);",
            "   }",
            "}")
        .expectNoOutput()
        .doTest();
  }

  @Test
  public void skip_return_nullable_explicit_nonnull() {
    SerializationTestHelper<FixDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
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
        .expectNoOutput()
        .doTest();
  }

  @Test
  public void skip_field_nullable_explicit_nonnull() {
    SerializationTestHelper<FixDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
        .addSourceLines(
            "com/uber/Base.java",
            "package com.uber;",
            "import javax.annotation.Nonnull;",
            "public class Base {",
            "   // BUG: Diagnostic contains: field f not initialized",
            "   @Nonnull Object f;",
            "}")
        .expectNoOutput()
        .setFactory(fixDisplayFactory)
        .doTest();
  }

  @Test
  public void test_custom_annot() {
    Path tempRoot = Paths.get(temporaryFolder.getRoot().getAbsolutePath(), "custom_annot");
    String output = tempRoot.toString();
    try {
      Files.createDirectories(tempRoot);
      FixSerializationConfig.Builder builder =
          new FixSerializationConfig.Builder()
              .setSuggest(true, false)
              .setAnnotations("Custom.Nullable", "Custom.Nonnull")
              .setOutputDirectory(output);
      Path config = tempRoot.resolve("serializer.xml");
      Files.createFile(config);
      configPath = config.toString();
      builder.writeAsXML(configPath);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
    SerializationTestHelper<FixDisplay> tester = new SerializationTestHelper<>(tempRoot);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
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
        .setExpectedOutputs(
            new FixDisplay(
                "Custom.Nullable",
                "test(boolean)",
                "null",
                "METHOD",
                "com.uber.SubClass",
                "com/uber/SubClass.java"))
        .doTest();
  }
}
