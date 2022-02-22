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

import com.uber.nullaway.fixserialization.FixSerializationConfig;
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

  private SerializationTestHelper serializationTestHelper;
  private String configPath;

  @Before
  @Override
  public void setup() {
    Path tempRoot = Paths.get(temporaryFolder.getRoot().getAbsolutePath());
    String output = tempRoot.toString();
    try {
      Files.createDirectories(tempRoot);
      serializationTestHelper = new SerializationTestHelper(tempRoot);
      FixSerializationConfig.Builder builder =
          new FixSerializationConfig.Builder().setSuggest(true, false).setOutputDirectory(output);
      Path config = tempRoot.resolve("serializer.xml");
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
    serializationTestHelper
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
        .setExpectedFixes(
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
    serializationTestHelper
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
        .setExpectedFixes(
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
    serializationTestHelper
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
        .setExpectedFixes(
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
    serializationTestHelper
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
        .setExpectedFixes(
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
    serializationTestHelper
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
        .setExpectedFixes(
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
    serializationTestHelper
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
        .setExpectedFixes(
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
    serializationTestHelper
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
        .setExpectedFixes(
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
    serializationTestHelper
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
        .setExpectedFixes(
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
    serializationTestHelper
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
        .setExpectedFixes(
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
    serializationTestHelper
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
        .setExpectedFixes(
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
    serializationTestHelper
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
        .expectNoFixSerialization()
        .doTest();
  }

  @Test
  public void skip_return_nullable_explicit_nonnull() {
    serializationTestHelper
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
        .expectNoFixSerialization()
        .doTest();
  }

  @Test
  public void skip_field_nullable_explicit_nonnull() {
    serializationTestHelper
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
        .expectNoFixSerialization()
        .doTest();
  }

  @Test
  public void test_custom_annot() {
    Path tempRoot = Paths.get(temporaryFolder.getRoot().getAbsolutePath(), "custom_annot");
    String output = tempRoot.toString();
    try {
      Files.createDirectories(tempRoot);
      serializationTestHelper = new SerializationTestHelper(tempRoot);
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
    serializationTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
        .addSourceLines(
            "com/uber/Test.java",
            "package com.uber;",
            "public class Test {",
            "   Object test(boolean flag) {",
            "       if(flag) {",
            "           return new Object();",
            "       } ",
            "       // BUG: Diagnostic contains: returning @Nullable",
            "       else return null;",
            "   }",
            "}")
        .setExpectedFixes(
            new FixDisplay(
                "Custom.Nullable",
                "test(boolean)",
                "null",
                "METHOD",
                "com.uber.Test",
                "com/uber/Test.java"))
        .doTest();
  }

  @Test
  public void test_method_param_protection_test() {
    Path tempRoot = Paths.get(temporaryFolder.getRoot().getAbsolutePath(), "custom_annot");
    String output = tempRoot.toString();
    try {
      Files.createDirectories(tempRoot);
      serializationTestHelper = new SerializationTestHelper(tempRoot);
      FixSerializationConfig.Builder builder =
          new FixSerializationConfig.Builder()
              .setSuggest(true, false)
              .setParamProtectionTest(true, 0)
              .setOutputDirectory(output);
      Path config = tempRoot.resolve("serializer.xml");
      Files.createFile(config);
      configPath = config.toString();
      builder.writeAsXML(configPath);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
    serializationTestHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
        .addSourceLines(
            "com/uber/Test.java",
            "package com.uber;",
            "public class Test {",
            "   Object test(Object foo) {",
            "       // BUG: Diagnostic contains: returning @Nullable",
            "       return foo;",
            "   }",
            "   Object test1(Object foo, Object bar) {",
            "       // BUG: Diagnostic contains: dereferenced expression foo is @Nullable",
            "       Integer hash = foo.hashCode();",
            "       return bar;",
            "   }",
            "   void test2(Object f) {",
            "       // BUG: Diagnostic contains: passing @Nullable",
            "       test1(f, new Object());",
            "   }",
            "}")
        .setExpectedFixes(
            new FixDisplay(
                "javax.annotation.Nullable",
                "test(java.lang.Object)",
                "null",
                "METHOD",
                "com.uber.Test",
                "com/uber/Test.java"),
            new FixDisplay(
                "javax.annotation.Nullable",
                "test1(java.lang.Object,java.lang.Object)",
                "foo",
                "PARAMETER",
                "com.uber.Test",
                "com/uber/Test.java"))
        .doTest();
  }
}
