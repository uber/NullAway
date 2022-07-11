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
import com.uber.nullaway.fixserialization.out.ErrorInfo;
import com.uber.nullaway.fixserialization.out.FieldInitializationInfo;
import com.uber.nullaway.fixserialization.out.SuggestedFixInfo;
import com.uber.nullaway.tools.DisplayFactory;
import com.uber.nullaway.tools.ErrorDisplay;
import com.uber.nullaway.tools.FieldInitDisplay;
import com.uber.nullaway.tools.FixDisplay;
import com.uber.nullaway.tools.SerializationTestHelper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link com.uber.nullaway.NullAway}. */
@RunWith(JUnit4.class)
public class NullAwaySerializationTest extends NullAwayTestsBase {
  private String configPath;
  private Path root;
  private final DisplayFactory<FixDisplay> fixDisplayFactory;
  private final DisplayFactory<ErrorDisplay> errorDisplayFactory;
  private final DisplayFactory<FieldInitDisplay> fieldInitDisplayFactory;

  private static final String SUGGEST_FIX_FILE_NAME = "fixes.tsv";
  private static final String SUGGEST_FIX_FILE_HEADER = SuggestedFixInfo.header();
  private static final String ERROR_FILE_NAME = "errors.tsv";
  private static final String ERROR_FILE_HEADER = ErrorInfo.header();
  private static final String FIELD_INIT_FILE_NAME = "field_init.tsv";
  private static final String FIELD_INIT_HEADER = FieldInitializationInfo.header();

  public NullAwaySerializationTest() {
    this.fixDisplayFactory =
        values -> {
          Preconditions.checkArgument(
              values.length == 10,
              "Needs exactly 10 values to create FixDisplay object but found: " + values.length);
          // Fixes are written in Temp Directory and is not known at compile time, therefore,
          // relative paths are getting compared.
          FixDisplay display =
              new FixDisplay(values[7], values[2], values[3], values[0], values[1], values[5]);
          display.uri = display.uri.substring(display.uri.indexOf("com/uber/"));
          return display;
        };
    this.errorDisplayFactory =
        values -> {
          Preconditions.checkArgument(
              values.length == 4,
              "Needs exactly 4 values to create ErrorDisplay object but found: " + values.length);
          return new ErrorDisplay(values[0], values[1], values[2], values[3]);
        };
    this.fieldInitDisplayFactory =
        values -> {
          Preconditions.checkArgument(
              values.length == 7,
              "Needs exactly 7 values to create FieldInitDisplay object but found: "
                  + values.length);
          FieldInitDisplay display =
              new FieldInitDisplay(
                  values[6], values[2], values[3], values[0], values[1], values[5]);
          display.uri = display.uri.substring(display.uri.indexOf("com/uber/"));
          return display;
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

  @Test
  public void suggestNullableReturnSimpleTest() {
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
        .setFactory(fixDisplayFactory)
        .setOutputFileNameAndHeader(SUGGEST_FIX_FILE_NAME, SUGGEST_FIX_FILE_HEADER)
        .doTest();
  }

  @Test
  public void suggestNullableReturnSuperClassTest() {
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
        .setFactory(fixDisplayFactory)
        .setOutputFileNameAndHeader(SUGGEST_FIX_FILE_NAME, SUGGEST_FIX_FILE_HEADER)
        .doTest();
  }

  @Test
  public void suggestNullableParamSimpleTest() {
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
        .setFactory(fixDisplayFactory)
        .setOutputFileNameAndHeader(SUGGEST_FIX_FILE_NAME, SUGGEST_FIX_FILE_HEADER)
        .doTest();
  }

  @Test
  public void suggestNullableParamSubclassTest() {
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
        .setFactory(fixDisplayFactory)
        .setOutputFileNameAndHeader(SUGGEST_FIX_FILE_NAME, SUGGEST_FIX_FILE_HEADER)
        .doTest();
  }

  @Test
  public void suggestNullableParamThisConstructorTest() {
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
        .setFactory(fixDisplayFactory)
        .setOutputFileNameAndHeader(SUGGEST_FIX_FILE_NAME, SUGGEST_FIX_FILE_HEADER)
        .doTest();
  }

  @Test
  public void suggestNullableParamGenericsTest() {
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
        .setFactory(fixDisplayFactory)
        .setOutputFileNameAndHeader(SUGGEST_FIX_FILE_NAME, SUGGEST_FIX_FILE_HEADER)
        .doTest();
  }

  @Test
  public void suggestNullableFieldSimpleTest() {
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
        .setFactory(fixDisplayFactory)
        .setOutputFileNameAndHeader(SUGGEST_FIX_FILE_NAME, SUGGEST_FIX_FILE_HEADER)
        .doTest();
  }

  @Test
  public void suggestNullableFieldInitializationTest() {
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
        .setFactory(fixDisplayFactory)
        .setOutputFileNameAndHeader(SUGGEST_FIX_FILE_NAME, SUGGEST_FIX_FILE_HEADER)
        .doTest();
  }

  @Test
  public void suggestNullableFieldControlFlowTest() {
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
        .setFactory(fixDisplayFactory)
        .setOutputFileNameAndHeader(SUGGEST_FIX_FILE_NAME, SUGGEST_FIX_FILE_HEADER)
        .doTest();
  }

  @Test
  public void suggestNullableNoInitializationFieldTest() {
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
        .setFactory(fixDisplayFactory)
        .setOutputFileNameAndHeader(SUGGEST_FIX_FILE_NAME, SUGGEST_FIX_FILE_HEADER)
        .doTest();
  }

  @Test
  public void skipSuggestPassNullableParamExplicitNonnullTest() {
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
        .setFactory(fixDisplayFactory)
        .setOutputFileNameAndHeader(SUGGEST_FIX_FILE_NAME, SUGGEST_FIX_FILE_HEADER)
        .doTest();
  }

  @Test
  public void skipSuggestReturnNullableExplicitNonnullTest() {
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
        .setFactory(fixDisplayFactory)
        .setOutputFileNameAndHeader(SUGGEST_FIX_FILE_NAME, SUGGEST_FIX_FILE_HEADER)
        .doTest();
  }

  @Test
  public void skipSuggestFieldNullableExplicitNonnullTest() {
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
        .setOutputFileNameAndHeader(SUGGEST_FIX_FILE_NAME, SUGGEST_FIX_FILE_HEADER)
        .doTest();
  }

  @Test
  public void suggestCustomAnnotTest() {
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
        .setExpectedOutputs(
            new FixDisplay(
                "Custom.Nullable",
                "test(boolean)",
                "null",
                "METHOD",
                "com.uber.Test",
                "com/uber/Test.java"))
        .setFactory(fixDisplayFactory)
        .setOutputFileNameAndHeader(SUGGEST_FIX_FILE_NAME, SUGGEST_FIX_FILE_HEADER)
        .doTest();
  }

  @Test
  public void examineMethodParamProtectionTest() {
    Path tempRoot = Paths.get(temporaryFolder.getRoot().getAbsolutePath(), "custom_annot");
    String output = tempRoot.toString();
    SerializationTestHelper<FixDisplay> tester = new SerializationTestHelper<>(tempRoot);
    try {
      Files.createDirectories(tempRoot);
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
    tester
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
        .setExpectedOutputs(
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
        .setFactory(fixDisplayFactory)
        .setOutputFileNameAndHeader(SUGGEST_FIX_FILE_NAME, SUGGEST_FIX_FILE_HEADER)
        .doTest();
  }

  @Test
  public void errorSerializationTest() {
    SerializationTestHelper<ErrorDisplay> tester = new SerializationTestHelper<>(root);
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
            "import javax.annotation.Nullable;",
            "public class Super {",
            "   Object foo;",
            "   // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field foo",
            "   Super(boolean b) {",
            "   }",
            "   String test(@Nullable Object o) {",
            "     // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull",
            "     foo = null;",
            "     if(o == null) {",
            "       // BUG: Diagnostic contains: dereferenced expression",
            "       return o.toString();",
            "     }",
            "     // BUG: Diagnostic contains: returning @Nullable expression",
            "     return null;",
            "   }",
            "}")
        .addSourceLines(
            "com/uber/SubClass.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class SubClass extends Super{",
            "   SubClass(boolean b) {",
            "      super(b);",
            "      // BUG: Diagnostic contains: passing @Nullable parameter",
            "      test(null);",
            "   }",
            "   // BUG: Diagnostic contains: method returns @Nullable, but superclass",
            "   @Nullable String test(Object o) {",
            "     return null;",
            "   }",
            "}")
        .setExpectedOutputs(
            new ErrorDisplay(
                "METHOD_NO_INIT",
                "initializer method does not guarantee @NonNull field foo",
                "com.uber.Super",
                "null"),
            new ErrorDisplay(
                "ASSIGN_FIELD_NULLABLE",
                "assigning @Nullable expression to @NonNull field",
                "com.uber.Super",
                "test(java.lang.Object)"),
            new ErrorDisplay(
                "DEREFERENCE_NULLABLE",
                "dereferenced expression o is @Nullable",
                "com.uber.Super",
                "test(java.lang.Object)"),
            new ErrorDisplay(
                "RETURN_NULLABLE",
                "returning @Nullable expression from method",
                "com.uber.Super",
                "test(java.lang.Object)"),
            new ErrorDisplay(
                "PASS_NULLABLE",
                "passing @Nullable parameter",
                "com.uber.SubClass",
                "SubClass(boolean)"),
            new ErrorDisplay(
                "WRONG_OVERRIDE_RETURN",
                "method returns @Nullable, but superclass",
                "com.uber.SubClass",
                "test(java.lang.Object)"))
        .setFactory(errorDisplayFactory)
        .setOutputFileNameAndHeader(ERROR_FILE_NAME, ERROR_FILE_HEADER)
        .doTest();
  }

  @Test
  public void errorSerializationEscapeSpecialCharactersTest() {
    // Input source lines for this test are not correctly formatted intentionally to make sure error
    // serialization will not be affected by any existing white spaces in the source code.
    SerializationTestHelper<ErrorDisplay> tester = new SerializationTestHelper<>(root);
    tester
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
            "   Object m = new Object();",
            "   public void run() {",
            "     // BUG: Diagnostic contains: passing @Nullable parameter 'm.hashCode()",
            "     foo(m.hashCode() == 2 || m.toString().equals('\\t') ? \t",
            "\t",
            " new Object() : null);",
            "   }",
            "   public void foo(Object o) { }",
            "}")
        .setExpectedOutputs(
            new ErrorDisplay(
                "PASS_NULLABLE",
                "passing @Nullable parameter 'm.hashCode() == 2 || m.toString().equals('\\\\t') ? \\t\\n\\t\\n new Object() : null'",
                "com.uber.Test",
                "run()"))
        .setFactory(errorDisplayFactory)
        .setOutputFileNameAndHeader(ERROR_FILE_NAME, ERROR_FILE_HEADER)
        .doTest();
  }

  @Test
  public void fieldInitializationSerializationTest() {
    Path tempRoot = Paths.get(temporaryFolder.getRoot().getAbsolutePath(), "test_field_init");
    String output = tempRoot.toString();
    try {
      Files.createDirectories(tempRoot);
      FixSerializationConfig.Builder builder =
          new FixSerializationConfig.Builder()
              .setSuggest(true, false)
              .setFieldInitInfo(true)
              .setOutputDirectory(output);
      Path config = tempRoot.resolve("serializer.xml");
      Files.createFile(config);
      configPath = config.toString();
      builder.writeAsXML(configPath);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
    SerializationTestHelper<FieldInitDisplay> tester = new SerializationTestHelper<>(tempRoot);
    tester
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
            "import javax.annotation.Nullable;",
            "public class Test {",
            "   Object foo;",
            "   Object bar;",
            "   @Nullable Object nullableFoo;",
            "   // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field foo",
            "   Test() {",
            "       // We are not tracing initializations in constructors.",
            "       bar = new Object();",
            "   }",
            "   void notInit() {",
            "     if(foo == null){",
            "         throw new RuntimeException();",
            "     }",
            "   }",
            "   void actualInit() {",
            "     foo = new Object();",
            "     // We are not tracing initialization of @Nullable fields.",
            "     nullableFoo = new Object();",
            "   }",
            "   void notInit2(@Nullable Object bar) {",
            "     foo = new Object();",
            "     // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field",
            "     foo = bar;",
            "   }",
            "}")
        .setExpectedOutputs(
            new FieldInitDisplay(
                "foo", "actualInit()", "null", "METHOD", "com.uber.Test", "com/uber/Test.java"))
        .setOutputFileNameAndHeader(FIELD_INIT_FILE_NAME, FIELD_INIT_HEADER)
        .setFactory(fieldInitDisplayFactory)
        .doTest();
  }

  @Test
  public void errorSerializationTestAnonymousInnerClass() {
    SerializationTestHelper<ErrorDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
        .addSourceLines(
            "com/uber/TestWithAnonymousRunnable.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class TestWithAnonymousRunnable {",
            "   void takesNonNull(String s) { }",
            "   void test(Object o) {",
            "     Runnable r = new Runnable() {",
            "         public String returnsNullable() {",
            "             // BUG: Diagnostic contains: returning @Nullable expression",
            "             return null;",
            "         }",
            "         @Override",
            "         public void run() {",
            "             takesNonNull(this.returnsNullable());",
            "             // BUG: Diagnostic contains: passing @Nullable parameter 'null'",
            "             takesNonNull(null);",
            "         }",
            "     };",
            "     r.run();",
            "   }",
            "}")
        .setExpectedOutputs(
            new ErrorDisplay(
                "RETURN_NULLABLE",
                "returning @Nullable expression from method with @NonNull return type",
                "com.uber.TestWithAnonymousRunnable$1",
                "returnsNullable()"),
            new ErrorDisplay(
                "PASS_NULLABLE",
                "passing @Nullable parameter 'null' where @NonNull is required",
                "com.uber.TestWithAnonymousRunnable$1",
                "run()"))
        .setFactory(errorDisplayFactory)
        .setOutputFileNameAndHeader(ERROR_FILE_NAME, ERROR_FILE_HEADER)
        .doTest();
  }

  @Test
  public void errorSerializationTestLocalTypes() {
    SerializationTestHelper<ErrorDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
        .addSourceLines(
            "com/uber/TestWithLocalType.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class TestWithLocalType {",
            "   @Nullable String test(Object o) {",
            "     class LocalType {",
            "         public String returnsNullable() {",
            "             // BUG: Diagnostic contains: returning @Nullable expression",
            "             return null;",
            "         }",
            "     }",
            "     LocalType local = new LocalType();",
            "     return local.returnsNullable();",
            "   }",
            "}")
        .setExpectedOutputs(
            new ErrorDisplay(
                "RETURN_NULLABLE",
                "returning @Nullable expression from method with @NonNull return type",
                "com.uber.TestWithLocalType$1LocalType",
                "returnsNullable()"))
        .setFactory(errorDisplayFactory)
        .setOutputFileNameAndHeader(ERROR_FILE_NAME, ERROR_FILE_HEADER)
        .doTest();
  }

  @Test
  public void errorSerializationTestIdenticalLocalTypes() {
    String[] sourceLines = {
      "package com.uber;",
      "import javax.annotation.Nullable;",
      "public class TestWithLocalTypes {",
      "   @Nullable String test(Object o) {",
      "     class LocalType {",
      "         public String returnsNullable() {",
      "             // BUG: Diagnostic contains: returning @Nullable expression",
      "             return null;",
      "         }",
      "     }",
      "     LocalType local = new LocalType();",
      "     return local.returnsNullable();",
      "   }",
      "   @Nullable String test2(Object o) {",
      "     class LocalType {",
      "         public String returnsNullable2() {",
      "             // BUG: Diagnostic contains: returning @Nullable expression",
      "             return null;",
      "         }",
      "     }",
      "     LocalType local = new LocalType();",
      "     return local.returnsNullable2();",
      "   }",
      "   @Nullable String test2() {",
      "     class LocalType {",
      "         public String returnsNullable2() {",
      "             // BUG: Diagnostic contains: returning @Nullable expression",
      "             return null;",
      "         }",
      "     }",
      "     LocalType local = new LocalType();",
      "     return local.returnsNullable2();",
      "   }",
      "}"
    };
    SerializationTestHelper<ErrorDisplay> errorTester = new SerializationTestHelper<>(root);
    errorTester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
        .addSourceLines("com/uber/TestWithLocalTypes.java", sourceLines)
        .setExpectedOutputs(
            new ErrorDisplay(
                "RETURN_NULLABLE",
                "returning @Nullable expression from method with @NonNull return type",
                "com.uber.TestWithLocalTypes$1LocalType",
                "returnsNullable()"),
            new ErrorDisplay(
                "RETURN_NULLABLE",
                "returning @Nullable expression from method with @NonNull return type",
                "com.uber.TestWithLocalTypes$2LocalType",
                "returnsNullable2()"),
            new ErrorDisplay(
                "RETURN_NULLABLE",
                "returning @Nullable expression from method with @NonNull return type",
                "com.uber.TestWithLocalTypes$3LocalType",
                "returnsNullable2()"))
        .setFactory(errorDisplayFactory)
        .setOutputFileNameAndHeader(ERROR_FILE_NAME, ERROR_FILE_HEADER)
        .doTest();
    SerializationTestHelper<FixDisplay> fixTester = new SerializationTestHelper<>(root);
    fixTester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
        .addSourceLines("com/uber/TestWithLocalTypes.java", sourceLines)
        .setExpectedOutputs(
            new FixDisplay(
                "javax.annotation.Nullable",
                "returnsNullable()",
                "null",
                "METHOD",
                "com.uber.TestWithLocalTypes$1LocalType",
                "com/uber/TestWithLocalTypes.java"),
            new FixDisplay(
                "javax.annotation.Nullable",
                "returnsNullable2()",
                "null",
                "METHOD",
                "com.uber.TestWithLocalTypes$2LocalType",
                "com/uber/TestWithLocalTypes.java"),
            new FixDisplay(
                "javax.annotation.Nullable",
                "returnsNullable2()",
                "null",
                "METHOD",
                "com.uber.TestWithLocalTypes$3LocalType",
                "com/uber/TestWithLocalTypes.java"))
        .setFactory(fixDisplayFactory)
        .setOutputFileNameAndHeader(SUGGEST_FIX_FILE_NAME, SUGGEST_FIX_FILE_HEADER)
        .doTest();
  }

  @Test
  public void errorSerializationTestLocalTypesNested() {
    SerializationTestHelper<ErrorDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
        .addSourceLines(
            "com/uber/TestWithLocalType.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class TestWithLocalType {",
            "   @Nullable String test(Object o) {",
            "     class LocalTypeA {",
            "         @Nullable",
            "         public String returnsNullable() {",
            "             class LocalTypeB {",
            "                 public String returnsNullable() {",
            "                     // BUG: Diagnostic contains: returning @Nullable expression",
            "                     return null;",
            "                 }",
            "             }",
            "             LocalTypeB local = new LocalTypeB();",
            "             return local.returnsNullable();",
            "         }",
            "     }",
            "     LocalTypeA local = new LocalTypeA();",
            "     return local.returnsNullable();",
            "   }",
            "}")
        .setExpectedOutputs(
            new ErrorDisplay(
                "RETURN_NULLABLE",
                "returning @Nullable expression from method with @NonNull return type",
                "com.uber.TestWithLocalType$1LocalTypeA$1LocalTypeB",
                "returnsNullable()"))
        .setFactory(errorDisplayFactory)
        .setOutputFileNameAndHeader(ERROR_FILE_NAME, ERROR_FILE_HEADER)
        .doTest();
  }

  @Test
  public void errorSerializationTestLocalTypesInitializers() {
    SerializationTestHelper<ErrorDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
        .addSourceLines(
            "com/uber/TestWithLocalTypes.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class TestWithLocalTypes {",
            "   private Object o1;",
            "   private Object o2;",
            "   private Object o3;",
            "   {",
            "     class LocalType {",
            "         public String returnsNullable() {",
            "             // BUG: Diagnostic contains: returning @Nullable expression",
            "             return null;",
            "         }",
            "     }",
            "     o1 = new LocalType();",
            "   }",
            "   {",
            "     class LocalType {",
            "         public String returnsNullable() {",
            "             return \"\";",
            "         }",
            "     }",
            "     o2 = new LocalType();",
            "   }",
            "   {",
            "     class LocalType {",
            "         public String returnsNullable() {",
            "             // BUG: Diagnostic contains: returning @Nullable expression",
            "             return null;",
            "         }",
            "     }",
            "     o3 = new LocalType();",
            "   }",
            "   void test(Object o) {",
            "   }",
            "}")
        .setExpectedOutputs(
            new ErrorDisplay(
                "RETURN_NULLABLE",
                "returning @Nullable expression from method with @NonNull return type",
                "com.uber.TestWithLocalTypes$1LocalType",
                "returnsNullable()"),
            new ErrorDisplay(
                "RETURN_NULLABLE",
                "returning @Nullable expression from method with @NonNull return type",
                "com.uber.TestWithLocalTypes$3LocalType",
                "returnsNullable()"))
        .setFactory(errorDisplayFactory)
        .setOutputFileNameAndHeader(ERROR_FILE_NAME, ERROR_FILE_HEADER)
        .doTest();
  }

  @Test
  public void errorSerializationTestInheritanceViolationForParameter() {
    SerializationTestHelper<ErrorDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
        .addSourceLines(
            "com/uber/Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public interface Foo {",
            "   void bar(@Nullable Object o);",
            "}")
        .addSourceLines(
            "com/uber/Main.java",
            "package com.uber;",
            "public class Main {",
            "   public void run(){",
            "     Foo foo = new Foo() {",
            "       @Override",
            "       // BUG: Diagnostic contains: parameter o is @NonNull",
            "       public void bar(Object o) {",
            "       }",
            "     };",
            "   }",
            "}")
        .setExpectedOutputs(
            new ErrorDisplay(
                "WRONG_OVERRIDE_PARAM",
                "parameter o is @NonNull, but parameter in superclass method com.uber.Foo.bar(java.lang.Object) is @Nullable",
                "com.uber.Main$1",
                "bar(java.lang.Object)"))
        .setFactory(errorDisplayFactory)
        .setOutputFileNameAndHeader(ERROR_FILE_NAME, ERROR_FILE_HEADER)
        .doTest();
  }

  @Test
  public void errorSerializationTestInheritanceViolationForMethod() {
    SerializationTestHelper<ErrorDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
        .addSourceLines(
            "com/uber/Foo.java",
            "package com.uber;",
            "public interface Foo {",
            "   Object bar();",
            "}")
        .addSourceLines(
            "com/uber/Main.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Main {",
            "   public void run(){",
            "     Foo foo = new Foo() {",
            "       @Override",
            "       @Nullable",
            "       // BUG: Diagnostic contains: method returns @Nullable, but superclass",
            "       public Object bar() {",
            "           return null;",
            "       }",
            "     };",
            "   }",
            "}")
        .setExpectedOutputs(
            new ErrorDisplay(
                "WRONG_OVERRIDE_RETURN",
                "method returns @Nullable, but superclass method com.uber.Foo.bar() returns @NonNull",
                "com.uber.Main$1",
                "bar()"))
        .setFactory(errorDisplayFactory)
        .setOutputFileNameAndHeader(ERROR_FILE_NAME, ERROR_FILE_HEADER)
        .doTest();
  }

  @Test
  public void errorSerializationTestAnonymousClassField() {
    SerializationTestHelper<ErrorDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
        .addSourceLines("com/uber/Foo.java", "package com.uber;", "public interface Foo { }")
        .addSourceLines(
            "com/uber/Main.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Main {",
            "   public void run(){",
            "     Foo foo = new Foo() {",
            "       // BUG: Diagnostic contains: @NonNull field Main$1.bar not initialized",
            "       Object bar;",
            "     };",
            "   }",
            "}")
        .setExpectedOutputs(
            new ErrorDisplay(
                "FIELD_NO_INIT",
                "@NonNull field Main$1.bar not initialized",
                "com.uber.Main$1",
                "null"))
        .setFactory(errorDisplayFactory)
        .setOutputFileNameAndHeader(ERROR_FILE_NAME, ERROR_FILE_HEADER)
        .doTest();
  }

  @Test
  public void errorSerializationTestLocalClassField() {
    SerializationTestHelper<ErrorDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
        .addSourceLines(
            "com/uber/Main.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Main {",
            "   public void run(){",
            "     class Foo {",
            "       // BUG: Diagnostic contains: @NonNull field Main$1Foo.bar not initialized",
            "       Object bar;",
            "     };",
            "   }",
            "}")
        .setExpectedOutputs(
            new ErrorDisplay(
                "FIELD_NO_INIT",
                "@NonNull field Main$1Foo.bar not initialized",
                "com.uber.Main$1Foo",
                "null"))
        .setFactory(errorDisplayFactory)
        .setOutputFileNameAndHeader(ERROR_FILE_NAME, ERROR_FILE_HEADER)
        .doTest();
  }

  @Test
  public void errorSerializationTestForUpstreamMethodCall() {
    try {
      String output = root.toString();
      Path upstreamAPIFilePath = root.resolve("upstream.tsv");
      Path config = root.resolve("serializer.xml");
      // Need to delete the config if exists since we are overriding config created in setup process
      // for all serialization tests.
      Files.deleteIfExists(config);
      BufferedWriter writer =
          Files.newBufferedWriter(upstreamAPIFilePath, Charset.defaultCharset());
      writer.write("CLASS\tMETHOD\n");
      writer.write("com.uber.UnAnnot" + "\t" + "retNull()" + "\n");
      writer.write("com.uber.UnAnnot" + "\t" + "staticRetNull()" + "\n");
      writer.write("com.uber.UnAnnot" + "\t" + "importedRetNull()" + "\n");
      writer.flush();
      writer.close();
      Files.createDirectories(root);
      FixSerializationConfig.Builder builder =
          new FixSerializationConfig.Builder()
              .setSuggest(true, false)
              .setAPIAnalysisFromUpstreamDependency(true, upstreamAPIFilePath.toFile().getPath())
              .setOutputDirectory(output);
      Files.createFile(config);
      configPath = config.toString();
      builder.writeAsXML(configPath);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
    SerializationTestHelper<ErrorDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedClasses=com.uber.UnAnnot",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
        .addSourceLines(
            "UnAnnot.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class UnAnnot {",
            "  public Object retNull() { return null; }",
            "  public static Object staticRetNull() { return null; }",
            "  public static Object importedRetNull() { return null; }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import static com.uber.UnAnnot.importedRetNull;",
            "class Test {",
            "  Object f = new Object();",
            "  void test() {",
            "    UnAnnot unAnnot = new UnAnnot();",
            "    // BUG: Diagnostic contains: dereferenced expression unAnnot.retNull() is @Nullable",
            "    String t1 = unAnnot.retNull().toString();",
            "    // BUG: Diagnostic contains: dereferenced expression UnAnnot.staticRetNull() is @Nullable",
            "    String t2 = UnAnnot.staticRetNull().toString();",
            "    // BUG: Diagnostic contains: dereferenced expression importedRetNull() is @Nullable",
            "    String t3 = importedRetNull().toString();",
            "  }",
            "}")
        .setExpectedOutputs(
            new ErrorDisplay(
                "DEREFERENCE_NULLABLE",
                "dereferenced expression unAnnot.retNull() is @Nullable",
                "com.uber.Test",
                "test()"),
            new ErrorDisplay(
                "DEREFERENCE_NULLABLE",
                "dereferenced expression UnAnnot.staticRetNull() is @Nullable",
                "com.uber.Test",
                "test()"),
            new ErrorDisplay(
                "DEREFERENCE_NULLABLE",
                "dereferenced expression importedRetNull() is @Nullable",
                "com.uber.Test",
                "test()"))
        .setFactory(errorDisplayFactory)
        .setOutputFileNameAndHeader(ERROR_FILE_NAME, ERROR_FILE_HEADER)
        .doTest();
  }

  @Test
  public void errorSerializationTestForUpstreamMethodCallDataflow() {
    try {
      String output = root.toString();
      Path upstreamAPIFilePath = root.resolve("upstream.tsv");
      Path config = root.resolve("serializer.xml");
      // Need to delete the config if exists since we are overriding config created in setup process
      // for all serialization tests.
      Files.deleteIfExists(config);
      BufferedWriter writer =
          Files.newBufferedWriter(upstreamAPIFilePath, Charset.defaultCharset());
      writer.write("CLASS\tMETHOD\n");
      writer.write("com.uber.UnAnnot" + "\t" + "retNull()" + "\n");
      writer.flush();
      writer.close();
      Files.createDirectories(root);
      FixSerializationConfig.Builder builder =
          new FixSerializationConfig.Builder()
              .setSuggest(true, false)
              .setAPIAnalysisFromUpstreamDependency(true, upstreamAPIFilePath.toFile().getPath())
              .setOutputDirectory(output);
      Files.createFile(config);
      configPath = config.toString();
      builder.writeAsXML(configPath);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
    SerializationTestHelper<ErrorDisplay> tester = new SerializationTestHelper<>(root);
    tester
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedClasses=com.uber.UnAnnot",
                "-XepOpt:NullAway:SerializeFixMetadata=true",
                "-XepOpt:NullAway:FixSerializationConfigPath=" + configPath))
        .addSourceLines(
            "UnAnnot.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class UnAnnot {",
            "  public Object retNull() { return null; }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  Object f = new Object();",
            "  void test() {",
            "    UnAnnot unAnnot = new UnAnnot();",
            "    Object tmp = unAnnot.retNull();",
            "    // BUG: Diagnostic contains: assigning @Nullable",
            "    this.f = tmp;",
            "  }",
            "}")
        .setExpectedOutputs(
            new ErrorDisplay(
                "ASSIGN_FIELD_NULLABLE",
                "assigning @Nullable expression to @NonNull field",
                "com.uber.Test",
                "test()"))
        .setFactory(errorDisplayFactory)
        .setOutputFileNameAndHeader(ERROR_FILE_NAME, ERROR_FILE_HEADER)
        .doTest();
  }
}
