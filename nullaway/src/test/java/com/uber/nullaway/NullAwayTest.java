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

import com.google.errorprone.CompilationTestHelper;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link com.uber.nullaway.NullAway}. */
@RunWith(JUnit4.class)
@SuppressWarnings("CheckTestExtendsBaseClass")
public class NullAwayTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper compilationHelper;

  @Before
  public void setup() {
    compilationHelper = CompilationTestHelper.newInstance(NullAway.class, getClass());
    compilationHelper.setArgs(
        Arrays.asList(
            "-d",
            temporaryFolder.getRoot().getAbsolutePath(),
            "-XepOpt:NullAway:KnownInitializers="
                + "com.uber.nullaway.testdata.CheckFieldInitNegativeCases.Super.doInit,"
                + "com.uber.nullaway.testdata.CheckFieldInitNegativeCases"
                + ".SuperInterface.doInit2",
            "-XepOpt:NullAway:AnnotatedPackages=com.uber,com.ubercab,io.reactivex",
            "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.nullaway.testdata.unannotated",
            "-XepOpt:NullAway:ExcludedClasses="
                + "com.uber.nullaway.testdata.Shape_Stuff,"
                + "com.uber.nullaway.testdata.excluded",
            "-XepOpt:NullAway:ExcludedClassAnnotations=com.uber.nullaway.testdata.TestAnnot",
            "-XepOpt:NullAway:CastToNonNullMethod=com.uber.nullaway.testdata.Util.castToNonNull",
            "-XepOpt:NullAway:ExternalInitAnnotations=com.uber.ExternalInit"));
  }

  @Test
  public void coreNullabilityPositiveCases() {
    compilationHelper.addSourceFile("NullAwayPositiveCases.java").doTest();
  }

  @Test
  public void nullabilityAnonymousClass() {
    compilationHelper.addSourceFile("NullAwayAnonymousClass.java").doTest();
  }

  @Test
  public void coreNullabilityNegativeCases() {
    compilationHelper
        .addSourceFile("NullAwayNegativeCases.java")
        .addSourceFile("OtherStuff.java")
        .addSourceFile("TestAnnot.java")
        .addSourceFile("unannotated/UnannotatedClass.java")
        .doTest();
  }

  @Test
  public void coreNullabilitySkipClass() {
    compilationHelper.addSourceFile("Shape_Stuff.java").doTest();
    compilationHelper.addSourceFile("excluded/Shape_Stuff2.java").doTest();
    compilationHelper.addSourceFile("AnnotatedClass.java").addSourceFile("TestAnnot.java").doTest();
  }

  @Test
  public void coreNullabilitySkipPackage() {
    compilationHelper.addSourceFile("unannotated/UnannotatedClass.java").doTest();
  }

  @Test
  public void coreNullabilityNativeModels() {
    compilationHelper
        .addSourceFile("NullAwayNativeModels.java")
        .addSourceFile("androidstubs/WebView.java")
        .addSourceFile("androidstubs/TextUtils.java")
        .doTest();
  }

  @Test
  public void initFieldPositiveCases() {
    compilationHelper.addSourceFile("CheckFieldInitPositiveCases.java").doTest();
  }

  @Test
  public void initFieldNegativeCases() {
    compilationHelper.addSourceFile("CheckFieldInitNegativeCases.java").doTest();
  }

  @Test
  public void java8PositiveCases() {
    compilationHelper.addSourceFile("NullAwayJava8PositiveCases.java").doTest();
  }

  @Test
  public void java8NegativeCases() {
    compilationHelper.addSourceFile("NullAwayJava8NegativeCases.java").doTest();
  }

  @Test
  public void rxSupportPositiveCases() {
    compilationHelper.addSourceFile("NullAwayRxSupportPositiveCases.java").doTest();
  }

  @Test
  public void rxSupportNegativeCases() {
    compilationHelper.addSourceFile("NullAwayRxSupportNegativeCases.java").doTest();
  }

  @Test
  public void functionalMethodSuperInterface() {
    compilationHelper.addSourceFile("NullAwaySuperFunctionalInterface.java").doTest();
  }

  @Test
  public void functionalMethodOverrideSuperInterface() {
    compilationHelper.addSourceFile("NullAwayOverrideFunctionalInterfaces.java").doTest();
  }

  @Test
  public void readBeforeInitPositiveCases() {
    compilationHelper
        .addSourceFile("ReadBeforeInitPositiveCases.java")
        .addSourceFile("Util.java")
        .doTest();
  }

  @Test
  public void readBeforeInitNegativeCases() {
    compilationHelper
        .addSourceFile("ReadBeforeInitNegativeCases.java")
        .addSourceFile("Util.java")
        .doTest();
  }

  @Test
  public void tryFinallySupport() {
    compilationHelper.addSourceFile("NullAwayTryFinallyCases.java").doTest();
  }

  @Test
  public void externalInitSupport() {
    compilationHelper
        .addSourceLines(
            "ExternalInit.java",
            "package com.uber;",
            "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)",
            "public @interface ExternalInit {}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "@ExternalInit",
            "class Test {",
            "  Object f;",
            // no error here due to external init
            "  public Test() {}",
            "  // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field",
            "  public Test(int x) {}",
            "}")
        .doTest();
  }

  @Test
  public void generatedAsUnannotated() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:TreatGeneratedAsUnannotated=true"))
        .addSourceLines(
            "Generated.java",
            "package com.uber;",
            "@javax.annotation.Generated(\"foo\")",
            "public class Generated { public void takeObj(Object o) {} }")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  void foo() { (new Generated()).takeObj(null); }",
            "}")
        .doTest();
  }

  @Test
  public void basicContractAnnotation() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "NullnessChecker.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import org.jetbrains.annotations.Contract;",
            "public class NullnessChecker {",
            "  @Contract(\"_, null -> true\")",
            "  static boolean isNull(boolean flag, @Nullable Object o) { return o == null; }",
            "  @Contract(\"null -> false\")",
            "  static boolean isNonNull(@Nullable Object o) { return o != null; }",
            "  @Contract(\"null -> fail\")",
            "  static void assertNonNull(@Nullable Object o) { if (o != null) throw new Error(); }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  String test1(@Nullable Object o) {",
            "    return NullnessChecker.isNonNull(o) ? o.toString() : \"null\";",
            "  }",
            "  String test2(@Nullable Object o) {",
            "    return NullnessChecker.isNull(false, o) ? \"null\" : o.toString();",
            "  }",
            "  String test3(@Nullable Object o) {",
            "    NullnessChecker.assertNonNull(o);",
            "    return o.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void impliesNonNullContractAnnotation() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "NullnessChecker.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import org.jetbrains.annotations.Contract;",
            "public class NullnessChecker {",
            "  @Contract(\"!null -> !null\")",
            "  static @Nullable Object noOp(@Nullable Object o) { return o; }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  String test1(@Nullable Object o1) {",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    return NullnessChecker.noOp(o1).toString();",
            "  }",
            "  String test2(Object o2) {",
            "    return NullnessChecker.noOp(o2).toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void malformedContractAnnotations() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import org.jetbrains.annotations.Contract;",
            "class Test {",
            "  @Contract(\"!null -> -> !null\")",
            "  static @Nullable Object foo(@Nullable Object o) { return o; }",
            "  @Contract(\"!null -> !null\")",
            "  static @Nullable Object bar(@Nullable Object o, String s) { return o; }",
            "  @Contract(\"jabberwocky -> !null\")",
            "  static @Nullable Object baz(@Nullable Object o) { return o; }",
            // We don't care as long as nobody calls the method:
            "  @Contract(\"!null -> -> !null\")",
            "  static @Nullable Object dontcare(@Nullable Object o) { return o; }",
            "  static Object test1() {",
            "    // BUG: Diagnostic contains: Invalid @Contract annotation",
            "    return foo(null);",
            "  }",
            "  static Object test2() {",
            "    // BUG: Diagnostic contains: Invalid @Contract annotation",
            "    return bar(null, \"\");",
            "  }",
            "  static Object test3() {",
            "    // BUG: Diagnostic contains: Invalid @Contract annotation",
            "    return baz(null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void contractNonVarArg() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "NullnessChecker.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import org.jetbrains.annotations.Contract;",
            "public class NullnessChecker {",
            "  @Contract(\"null -> fail\")",
            "  static void assertNonNull(@Nullable Object o) { if (o != null) throw new Error(); }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  void test(java.util.function.Function<Object, Object> fun) {",
            "    NullnessChecker.assertNonNull(fun.apply(new Object()));",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testEnumInit() {
    compilationHelper
        .addSourceLines(
            "SomeEnum.java",
            "package com.uber;",
            "import java.util.Random;",
            "enum SomeEnum {",
            "  FOO, BAR;",
            "  final Object o;",
            "  final Object p;",
            "  private SomeEnum() {",
            "    this.o = new Object();",
            "    this.p = new Object();",
            "    this.o.equals(this.p);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testGenericAnonymousInner() {
    compilationHelper
        .addSourceLines(
            "GenericSuper.java",
            "package com.uber;",
            "class GenericSuper<T> {",
            "  T x;",
            "  GenericSuper(T y) {",
            "    this.x = y;",
            "  }",
            "}")
        .addSourceLines(
            "AnonSub.java",
            "package com.uber;",
            "import java.util.List;",
            "import javax.annotation.Nullable;",
            "class AnonSub {",
            "  static GenericSuper<List<String>> makeSuper(List<String> list) {",
            "    return new GenericSuper<List<String>>(list) {};",
            "  }",
            "  static GenericSuper<List<String>> makeSuperBad(@Nullable List<String> list) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'list' where @NonNull",
            "    return new GenericSuper<List<String>>(list) {};",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testThriftIsSet() {
    compilationHelper
        .addSourceLines("TBase.java", "package org.apache.thrift;", "public interface TBase {}")
        .addSourceLines(
            "Generated.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Generated implements org.apache.thrift.TBase {",
            "  public @Nullable Object id;",
            "  @Nullable public Object getId() { return this.id; }",
            "  public boolean isSetId() { return this.id != null; }",
            "}")
        .addSourceLines(
            "Client.java",
            "package com.uber;",
            "public class Client {",
            "  public void testNeg(Generated g) {",
            "    if (g.isSetId()) {",
            "      g.getId().toString();",
            "      g.id.hashCode();",
            "    }",
            "  }",
            "  public void testPos(Generated g) {",
            "    if (!g.isSetId()) {",
            "      // BUG: Diagnostic contains: dereferenced expression g.getId() is @Nullable",
            "      g.getId().hashCode();",
            "    } else {",
            "      g.id.toString();",
            "    }",
            "  }",
            "}")
        .doTest();
  }
}
