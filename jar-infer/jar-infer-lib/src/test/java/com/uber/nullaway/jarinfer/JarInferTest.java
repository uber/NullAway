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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ObjectArrays;
import com.google.common.collect.Sets;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAOptions;
import com.ibm.wala.ssa.analysis.ExplodedControlFlowGraph;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.sun.tools.javac.main.Main;
import com.sun.tools.javac.main.Main.Result;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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

  /**
   * Create, compile, and run a unit test.
   *
   * @param testName An useful name for the unit test.
   * @param pkg Qualified package name.
   * @param cls Target class to be analyzed.
   * @param expected Map of 'method signatures' to their 'expected list of NonNull parameters'.
   * @param lines Source lines for the test code.
   */
  private void testTemplate(
      String testName,
      String pkg, // in dot syntax
      String cls,
      Map<String, Set<Integer>> expected,
      String... lines)
      throws Exception {
    Result compileResult =
        compilerUtil
            .addSourceLines(cls + ".java", ObjectArrays.concat("package " + pkg + ";\n", lines))
            .run();
    Assert.assertEquals(
        testName + ": test compilation failed!\n" + compilerUtil.getOutput(),
        Main.Result.OK,
        compileResult);
    DefinitelyDerefedParamsDriver.reset();
    Assert.assertTrue(
        testName + ": test failed!",
        verify(
            DefinitelyDerefedParamsDriver.run(
                temporaryFolder.getRoot().getAbsolutePath(), "L" + pkg.replaceAll("\\.", "/")),
            new HashMap<>(expected)));
  }

  private void testDataFlowTemplate(
      String testName,
      String pkg, // in dot syntax
      String cls,
      String... lines)
      throws Exception {
    Result compileResult =
        compilerUtil
            .addSourceLines(cls + ".java", ObjectArrays.concat("package " + pkg + ";\n", lines))
            .run();
    Assert.assertEquals(
        testName + ": test compilation failed!\n" + compilerUtil.getOutput(),
        Main.Result.OK,
        compileResult);
    AnalysisScope scope = AnalysisScopeReader.makePrimordialScope(null);
    AnalysisScopeReader.addClassPathToScope(
        temporaryFolder.getRoot().getAbsolutePath(), scope, ClassLoaderReference.Application);
    IClassHierarchy cha = ClassHierarchyFactory.make(scope);
    MethodReference ref =
        MethodReference.findOrCreate(
            ClassLoaderReference.Application,
            "L" + pkg.replaceAll("\\.", "/") + "/" + cls,
            "test",
            "()V");
    IMethod mtd = cha.resolveMethod(ref);
    IAnalysisCacheView cache = new AnalysisCacheImpl();
    IR ir = cache.getIRFactory().makeIR(mtd, Everywhere.EVERYWHERE, SSAOptions.defaultOptions());
    ExplodedControlFlowGraph ecfg = ExplodedControlFlowGraph.make(ir);
    IntraProcNullFlow nullFlows = new IntraProcNullFlow(ecfg, cha);
    //        BitVectorSolver<IExplodedBasicBlock> solver = nullFlows.analyze();
    nullFlows.analyze();
    Assert.assertTrue(testName + ": test failed!", true);
  }

  /**
   * Run a unit test with a specified jar file.
   *
   * @param testName An useful name for the unit test.
   * @param pkg Qualified package name.
   * @param jarPath Path to the target jar file.
   */
  private void testJARTemplate(
      String testName,
      String pkg, // in dot syntax
      String jarPath // in dot syntax
      ) throws Exception {
    DefinitelyDerefedParamsDriver.reset();
    DefinitelyDerefedParamsDriver.run(jarPath, "L" + pkg.replaceAll("\\.", "/"));
    String outJARPath = DefinitelyDerefedParamsDriver.lastOutPath;
    Assert.assertTrue("jar file not found! - " + outJARPath, new File(outJARPath).exists());
  }

  /**
   * Check set equality of results with expected results.
   *
   * @param result Map of 'method signatures' to their 'inferred list of NonNull parameters'.
   * @param expected Map of 'method signatures' to their 'expected list of NonNull parameters'.
   */
  private boolean verify(Map<String, Set<Integer>> result, HashMap<String, Set<Integer>> expected) {
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

  @Test
  public void emptyTest() {
    Assert.assertTrue("this test never fails!", true);
  }

  @Test
  public void toyStatic() throws Exception {
    testTemplate(
        "toyStatic",
        "toys",
        "Test",
        ImmutableMap.of(
            "toys.Test:void test(String, Foo, Bar)", Sets.newHashSet(0, 1, 2),
            "toys.Foo:boolean run(String)", Sets.newHashSet(1)),
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
  public void toyNonStatic() throws Exception {
    testTemplate(
        "toyNonStatic",
        "toys",
        "Foo",
        ImmutableMap.of("toys.Foo:void test(String, String)", Sets.newHashSet(1)),
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
  public void toyJAR() throws Exception {
    testJARTemplate(
        "toyJAR",
        "com.uber.nullaway.jarinfer.toys.unannotated",
        "../test-java-lib-jarinfer/build/libs/test-java-lib-jarinfer.jar");
  }

  @Test
  public void toyNullTestAPI() throws Exception {
    testTemplate(
        "toyNullTestAPI",
        "toys",
        "Foo",
        ImmutableMap.of("toys.Foo:void test(String, String, String)", Sets.newHashSet(1, 3)),
        "import com.google.common.base.Preconditions;",
        "import java.util.Objects;",
        "import org.junit.Assert;",
        "class Foo {",
        "  private String foo;",
        "  public Foo(String str) {",
        "    if (str == null) str = \"foo\";",
        "    this.foo = str;",
        "  }",
        "  public void test(String s, String t, String u) {",
        "    Preconditions.checkNotNull(s);",
        "    Assert.assertNotNull(\"Param u is null!\", u);",
        "    if (s.length() >= 5) {",
        "      t = s;",
        "    } else {",
        "      t = u;",
        "    }",
        "    Objects.requireNonNull(t);",
        "  }",
        "}");
  }

  @Test
  public void toyNullFlow() throws Exception {
    testDataFlowTemplate(
        "toyNullFlow",
        "toys",
        "Foo",
        "class Foo {",
        "  private static String foo;",
        "  public Foo(String str) {",
        "    if (str == null) str = \"foo\";",
        "    this.foo = str;",
        "  }",
        "  public void test() {",
        "    String str = \"try\";",
        "    if (foo != null) {",
        "      foo = null;",
        "    }",
        "  }",
        "}");
  }

  @Test
  public void toyNullUntestedDeref() throws Exception {
    testTemplate(
        "toyNullUntestedDeref",
        "toys",
        "Foo",
        ImmutableMap.of("toys.Foo:String test(String, String)", Sets.newHashSet(2)),
        "class Foo {",
        "  private String foo;",
        "  public String test(String s, String t) {",
        "    if (s != null) {",
        "      if (s.length() > 5)",
        "        return s.substring(t.length());",
        "      else ",
        "        return t.substring(s.length());",
        "    } else",
        "      return null;",
        "  }",
        "}");
  }

  @Test
  public void toyNullTestedThrow() throws Exception {
    testTemplate(
        "toyNullTestedThrow",
        "toys",
        "Foo",
        ImmutableMap.of("toys.Foo:String test(String, String)", Sets.newHashSet(1, 2)),
        "class Foo {",
        "  private String foo;",
        "  public String test(String s, String t) throws Exception{",
        "    if (s != null) {",
        "      if (s.length() > 5)",
        "        return s.substring(t.length());",
        "      else ",
        "        return t.substring(s.length());",
        "    } else",
        "      throw new Exception(\"cannot handle null parameter 's'!\");",
        "  }",
        "}");
  }
}
