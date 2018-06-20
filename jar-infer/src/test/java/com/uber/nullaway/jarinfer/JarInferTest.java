/*
 * Copyright (C) 2018. Uber Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.uber.nullaway.jarinfer;

import com.google.common.collect.ObjectArrays;
import com.google.common.collect.Sets;
import com.sun.tools.javac.main.Main;
import com.sun.tools.javac.main.Main.Result;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link com.uber.nullaway.jarinfer}. */
@RunWith(JUnit4.class)
@SuppressWarnings("CheckTestExtendsBaseClass")
public class JarInferTest {

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilerUtil compilerUtil;

  @Before
  public void setup() {
    compilerUtil = new CompilerUtil(getClass());
    compilerUtil.setArgs(Arrays.asList("-d", temporaryFolder.getRoot().getAbsolutePath()));
  }

  /*
   * Create, compile, and run a unit test
   *
   */
  private void testTemplate(
      String testName,
      String pkg, // in dot syntax
      String cls,
      HashMap<String, Set<Integer>> expected,
      String... lines) {
    Result compileResult =
        compilerUtil
            .addSourceLines(cls + ".java", ObjectArrays.concat("package " + pkg + ";\n", lines))
            .run();
    Assert.assertEquals(
        testName + ": test compilation failed!\n" + compilerUtil.getOutput(),
        Main.Result.OK,
        compileResult);
    DefinitelyDerefedParamsDriver defDerefParamDriver = new DefinitelyDerefedParamsDriver();
    try {
      Assert.assertTrue(
          testName + ": test failed!",
          verify(
              defDerefParamDriver.run(
                  temporaryFolder.getRoot().getAbsolutePath(), "L" + pkg.replaceAll("\\.", "/")),
              expected));
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private void testJARTemplate(
      String testName,
      String pkg, // in dot syntax
      String jarPath // in dot syntax
      ) {
    DefinitelyDerefedParamsDriver defDerefParamDriver = new DefinitelyDerefedParamsDriver();
    try {
      defDerefParamDriver.run(jarPath, "L" + pkg.replaceAll("\\.", "/"));
      String outJARPath = jarPath.substring(0, jarPath.lastIndexOf('.')) + ".ji.jar";
      Assert.assertTrue("jar file not found! - " + outJARPath, new File(outJARPath).exists());
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
  /*
   * Check set equality of results with expected results
   *
   */
  private boolean verify(
      HashMap<String, Set<Integer>> result, HashMap<String, Set<Integer>> expected) {
    for (Map.Entry<String, Set<Integer>> entry : result.entrySet()) {
      String mtd_sign = entry.getKey();
      Set<Integer> ddParams = entry.getValue();
      if (ddParams.isEmpty()) continue;
      Set<Integer> xddParams = expected.get(mtd_sign);
      if (xddParams == null) return false;
      for (Integer var : ddParams) {
        if (!xddParams.remove(var)) return false;
      }
      if (!xddParams.isEmpty()) return false;
      expected.remove(mtd_sign);
    }
    return expected.isEmpty();
  }

  //  @Test
  public void toy1() {
    testTemplate(
        "toyStatic",
        "toys",
        "Test",
        new HashMap<String, Set<Integer>>() {
          {
            put("toys.Test.test(Ljava/lang/String;Ltoys/Foo;Ltoys/Bar;)V", Sets.newHashSet(1, 3));
            put("toys.Foo.run(Ljava/lang/String;)Z", Sets.newHashSet(2));
          }
        },
        "class Foo {",
        "  private String foo;",
        "  public Foo(String str) {",
        "    if (str == null) str = \"foo\";",
        "    this.foo = str;",
        "  }",
        "  public boolean run(String str) {",
        "    if (str.length() > 0) {",
        "      return str == foo;",
        "    }",
        "    return false;",
        "  }",
        "}",
        "",
        "class Bar {",
        "  private String bar;",
        "  public int b;",
        "  public Bar(String str) {",
        "    if (str == null) str = \"bar\";",
        "    this.bar = str;",
        "    this.b = bar.length();",
        "  }",
        "  public int run(String str) {",
        "    if (str != null) {",
        "      return str.length();",
        "    }",
        "    return bar.length();",
        "  }",
        "}",
        "",
        "public class Test {",
        "  public static void test(String s, Foo f, Bar b) {",
        "    if (s.length() >= 5) {",
        "      Foo f1 = new Foo(s);",
        "      f1.run(s);",
        "    } else {",
        "      f.run(s);",
        "    }",
        "    b.run(s);",
        "  }",
        "  public static void main(String arg[]) throws java.io.IOException {",
        "    String s = new String(\"test string...\");",
        "    Foo f = new Foo(\"try\");",
        "    Bar b = new Bar(null);",
        "    try {",
        "      test(s, f, b);",
        "    } catch (Error e) {",
        "      System.out.println(e.getMessage());",
        "    }",
        "  }",
        "}");
  }

  //  @Test
  public void toy2() {
    testTemplate(
        "toyNonStatic",
        "toys",
        "Foo",
        new HashMap<String, Set<Integer>>() {
          {
            put("toys.Foo.test(Ljava/lang/String;Ljava/lang/String;)V", Sets.newHashSet(2));
          }
        },
        "class Foo {",
        "  private String foo;",
        "  public Foo(String str) {",
        "    if (str == null) str = \"foo\";",
        "    this.foo = str;",
        "  }",
        "  public boolean run(String str) {",
        "    if (str != null) {",
        "      return str == foo;",
        "    }",
        "    return false;",
        "  }",
        "  public void test(String s, String t) {",
        "    if (s.length() >= 5) {",
        "      this.run(s);",
        "    } else {",
        "      this.run(t);",
        "    }",
        "  }",
        "}");
  }

  @Test
  public void toy3() {
    testJARTemplate(
        "toyJAR", "toys", "./src/test/resources/com/uber/nullaway/jarinfer/testdata/toys.jar");
  }
}
