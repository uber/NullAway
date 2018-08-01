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

  private void testTemplate(
      String testName,
      String pkg, // in dot syntax
      String cls,
      HashMap<String, Set<String>> expected,
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
          defDerefParamDriver.verify(
              defDerefParamDriver.run(temporaryFolder.getRoot().getAbsolutePath()), expected));
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @Test
  public void toy1() {
    testTemplate(
        "toyStatic",
        "toys",
        "Test",
        new HashMap<String, Set<String>>() {
          {
            put(
                "< Application, Ltoys/Test, test(Ljava/lang/String;Ltoys/Foo;Ltoys/Bar;)V >",
                Sets.newHashSet("v1", "v3"));
            put("< Application, Ltoys/Foo, run(Ljava/lang/String;)Z >", Sets.newHashSet("v2"));
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

  @Test
  public void toy2() {
    testTemplate(
        "toyNonStatic",
        "toys",
        "Foo",
        new HashMap<String, Set<String>>() {
          {
            put(
                "< Application, Ltoys/Foo, test(Ljava/lang/String;Ljava/lang/String;)V >",
                Sets.newHashSet("v2"));
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
}
