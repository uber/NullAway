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
import com.uber.nullaway.testlibrarymodels.TestLibraryModels;
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

  @SuppressWarnings("CheckReturnValue")
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
            // We give the following in Regexp format to test that support
            "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.nullaway.[a-zA-Z0-9.]+.unannotated",
            "-XepOpt:NullAway:ExcludedClasses="
                + "com.uber.nullaway.testdata.Shape_Stuff,"
                + "com.uber.nullaway.testdata.excluded",
            "-XepOpt:NullAway:ExcludedClassAnnotations=com.uber.nullaway.testdata.TestAnnot",
            "-XepOpt:NullAway:CastToNonNullMethod=com.uber.nullaway.testdata.Util.castToNonNull",
            "-XepOpt:NullAway:ExternalInitAnnotations=com.uber.ExternalInit",
            "-XepOpt:NullAway:ExcludedFieldAnnotations=com.uber.ExternalFieldInit"));
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
    compilationHelper
        .addSourceFile("Shape_Stuff.java")
        .addSourceFile("excluded/Shape_Stuff2.java")
        .addSourceFile("AnnotatedClass.java")
        .addSourceFile("TestAnnot.java")
        .doTest();
  }

  @Test
  public void lombokSupportTesting() {
    compilationHelper.addSourceFile("lombok/LombokBuilderInit.java").doTest();
  }

  @Test
  public void skipClass() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:ExcludedClassAnnotations=com.uber.lib.MyExcluded"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "@com.uber.lib.MyExcluded",
            "public class Test {",
            "  static void bar() {",
            "    // No error",
            "    Object x = null; x.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void skipNestedClass() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:ExcludedClassAnnotations=com.uber.lib.MyExcluded"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  @com.uber.lib.MyExcluded",
            "  static class Inner {",
            "    @Nullable",
            "    static Object foo() {",
            "      Object x = null; x.toString();",
            "      return x;",
            "    }",
            "  }",
            "  static void bar() {",
            "    // BUG: Diagnostic contains: dereferenced expression Inner.foo()",
            "    Inner.foo().toString();",
            "  }",
            "}")
        .doTest();
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
  public void assertSupportPositiveCases() {
    compilationHelper.addSourceFile("CheckAssertSupportPositiveCases.java").doTest();
  }

  @Test
  public void assertSupportNegativeCases() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AssertsEnabled=true"))
        .addSourceFile("CheckAssertSupportNegativeCases.java")
        .doTest();
  }

  @Test
  public void checkContractPositiveCases() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:CheckContracts=true"))
        .addSourceFile("CheckContractPositiveCases.java")
        .doTest();
  }

  @Test
  public void checkContractNegativeCases() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:CheckContracts=true"))
        .addSourceFile("CheckContractNegativeCases.java")
        .doTest();
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
  public void streamSupportNegativeCases() {
    compilationHelper.addSourceFile("NullAwayStreamSupportNegativeCases.java").doTest();
  }

  @Test
  public void streamSupportPositiveCases() {
    compilationHelper.addSourceFile("NullAwayStreamSupportPositiveCases.java").doTest();
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
        .addSourceLines(
            "Test2.java",
            "package com.uber;",
            "@ExternalInit",
            "class Test2 {",
            // no error here due to external init
            "  Object f;",
            "}")
        .addSourceLines(
            "Test3.java",
            "package com.uber;",
            "@ExternalInit",
            "class Test3 {",
            "  Object f;",
            "  // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field",
            "  public Test3(int x) {}",
            "}")
        .doTest();
  }

  @Test
  public void externalInitexternalInitSupportFields() {
    compilationHelper
        .addSourceLines(
            "ExternalFieldInit.java",
            "package com.uber;",
            "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)",
            "public @interface ExternalFieldInit {}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  @ExternalFieldInit Object f;",
            // no error here due to external init
            "  public Test() {}",
            // no error here due to external init
            "  public Test(int x) {}",
            "}")
        .addSourceLines(
            "Test2.java",
            "package com.uber;",
            "class Test2 {",
            // no error here due to external init
            "  @ExternalFieldInit Object f;",
            "}")
        .addSourceLines(
            "Test3.java",
            "package com.uber;",
            "class Test3 {",
            "  @ExternalFieldInit Object f;",
            // no error here due to external init
            "  @ExternalFieldInit", // See GitHub#184
            "  public Test3() {}",
            // no error here due to external init
            "  @ExternalFieldInit", // See GitHub#184
            "  public Test3(int x) {}",
            "}")
        .doTest();
  }

  @Test
  public void generatedAsUnannotated() {
    String generatedAnnot =
        (Double.parseDouble(System.getProperty("java.specification.version")) >= 11)
            ? "@javax.annotation.processing.Generated"
            : "@javax.annotation.Generated";
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
            generatedAnnot + "(\"foo\")",
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
  public void generatedAsUnannotatedPlusRestrictive() {
    String generatedAnnot =
        (Double.parseDouble(System.getProperty("java.specification.version")) >= 11)
            ? "@javax.annotation.processing.Generated"
            : "@javax.annotation.Generated";
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:TreatGeneratedAsUnannotated=true",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines(
            "Generated.java",
            "package com.uber;",
            generatedAnnot + "(\"foo\")",
            "public class Generated {",
            "  @javax.annotation.Nullable",
            "  public Object retNull() {",
            "    return null;",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  void foo() { (new Generated()).retNull().toString(); }",
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
            "  Object test3(@Nullable Object o1) {",
            "    // BUG: Diagnostic contains: returning @Nullable expression",
            "    return NullnessChecker.noOp(o1);",
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
  public void contractPureOnlyIgnored() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "PureLibrary.java",
            "package com.example.library;",
            "import org.jetbrains.annotations.Contract;",
            "public class PureLibrary {",
            "  @Contract(",
            "    pure = true",
            "  )",
            "  public static String pi() {",
            "    // Meh, close enough...",
            "    return Double.toString(3.14);",
            "  }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.example.library.PureLibrary;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  String piValue() {",
            "    String pi = PureLibrary.pi();",
            "    // Note: we must trigger dataflow to ensure that",
            "    // ContractHandler.onDataflowVisitMethodInvocation is called",
            "    if (pi != null) {",
            "       return pi;",
            "    }",
            "    return Integer.toString(3);",
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
            "  public boolean isFixed;",
            "  @Nullable public Object getId() { return this.id; }",
            "  // this is to ensure we don't crash on unions",
            "  public boolean isSet() { return false; }",
            "  public boolean isSetId() { return this.id != null; }",
            "  public boolean isFixed() { return this.isFixed; }",
            "  public boolean isSetIsFixed() { return false; }",
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
            "    if (g.isSetIsFixed()) {",
            "      g.isFixed();",
            "    }",
            "    if (g.isSet()) {}",
            "  }",
            "  public void testPos(Generated g) {",
            "    if (!g.isSetId()) {",
            "      // BUG: Diagnostic contains: dereferenced expression g.getId() is @Nullable",
            "      g.getId().hashCode();",
            "    } else {",
            "      g.id.toString();",
            "    }",
            "    java.util.List<Generated> l = new java.util.ArrayList<>();",
            "    if (l.get(0).isSetId()) {",
            "      l.get(0).getId().hashCode();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testThriftIsSetWithGenerics() {
    compilationHelper
        .addSourceLines(
            "TBase.java", "package org.apache.thrift;", "public interface TBase<T, F> {}")
        .addSourceLines(
            "Generated.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Generated implements org.apache.thrift.TBase<String, Integer> {",
            "  public @Nullable Object id;",
            "  @Nullable public Object getId() { return this.id; }",
            "  public boolean isSetId() { return this.id != null; }",
            "}")
        .addSourceLines(
            "Client.java",
            "package com.uber;",
            "public class Client {",
            "  public void testNeg(Generated g) {",
            "    if (!g.isSetId()) {",
            "      return;",
            "    }",
            "    Object x = g.getId();",
            "    if (x.toString() == null) return;",
            "    g.getId().toString();",
            "    g.id.hashCode();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testThriftIsSetWithArg() {
    compilationHelper
        .addSourceLines(
            "TBase.java",
            "package org.apache.thrift;",
            "public interface TBase {",
            "  boolean isSet(String fieldName);",
            "}")
        .addSourceLines(
            "Client.java",
            "package com.uber;",
            "public class Client {",
            "  public void testNeg(org.apache.thrift.TBase tBase) {",
            "    if (tBase.isSet(\"Hello\")) {",
            "      System.out.println(\"set\");",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  /** we do not have proper support for Thrift unions yet; just checks that we don't crash */
  @Test
  public void testThriftUnion() {
    compilationHelper
        .addSourceLines(
            "TBase.java", "package org.apache.thrift;", "public interface TBase<T, F> {}")
        .addSourceLines(
            "TUnion.java",
            "package org.apache.thrift;",
            "public abstract class TUnion<T, F> implements TBase<T, F> {",
            "  protected Object value_;",
            "  public Object getFieldValue() {",
            "    return this.value_;",
            "  }",
            "}")
        .addSourceLines(
            "Generated.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Generated extends org.apache.thrift.TUnion<String, Integer> {",
            "  public Object getId() { return getFieldValue(); }",
            "  public boolean isSetId() { return true; }",
            "}")
        .addSourceLines(
            "Client.java",
            "package com.uber;",
            "public class Client {",
            "  public void testNeg(Generated g) {",
            "    if (!g.isSetId()) {",
            "      return;",
            "    }",
            "    Object x = g.getId();",
            "    if (x.toString() == null) return;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void erasedIterator() {
    // just checking for crash
    compilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.*;",
            "class Test {",
            "  static class Foo implements Iterable {",
            "    public Iterator iterator() {",
            "      return new Iterator() {",
            "        @Override",
            "        public boolean hasNext() {",
            "          return false;",
            "        }",
            "        @Override",
            "        public Iterator next() {",
            "          throw new NoSuchElementException();",
            "        }",
            "      };",
            "    }",
            "  }",
            "  static void testErasedIterator(Foo foo) {",
            "    for (Object x : foo) {",
            "      x.hashCode();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void compoundAssignment() {
    compilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  static void assignments() {",
            "    String x = null; x += \"hello\";",
            "    // BUG: Diagnostic contains: unboxing of a @Nullable value",
            "    Integer y = null; y += 3;",
            "    // BUG: Diagnostic contains: unboxing of a @Nullable value",
            "    boolean b = false; Boolean c = null; b |= c;",
            "  }",
            "  static Integer returnCompound() {",
            "    Integer z = 7;",
            "    return (z += 10);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void arrayIndexUnbox() {
    compilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  static void indexUnbox() {",
            "    Integer x = null; int[] fizz = { 0, 1 };",
            "    // BUG: Diagnostic contains: unboxing of a @Nullable value",
            "    int y = fizz[x];",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void unannotatedClass() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedClasses=com.uber.UnAnnot"))
        .addSourceLines(
            "UnAnnot.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class UnAnnot {",
            "  @Nullable static Object retNull() { return null; }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  @Nullable static Object nullRetSameClass() { return null; }",
            "  void test() {",
            "    UnAnnot.retNull().toString();",
            // make sure other classes in the package still get analyzed
            "    Object x = nullRetSameClass();",
            "    // BUG: Diagnostic contains: dereferenced expression x is @Nullable",
            "    x.hashCode();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void cfNullableArrayField() {
    compilationHelper
        .addSourceLines(
            "CFNullable.java",
            "package com.uber;",
            "import org.checkerframework.checker.nullness.qual.Nullable;",
            "import java.util.List;",
            "abstract class CFNullable<E> {",
            "  List<E> @Nullable [] table;",
            "}")
        .doTest();
  }

  @Test
  public void typeUseJarReturn() {
    compilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.lib.*;",
            "class Test {",
            "  void foo(CFNullableStuff.NullableReturn r) {",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    r.get().toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void typeUseJarParam() {
    compilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.lib.*;",
            "class Test {",
            "  void foo(CFNullableStuff.NullableParam p) {",
            "    p.doSomething(null);",
            "    // BUG: Diagnostic contains: passing @Nullable parameter",
            "    p.doSomething2(null, new Object());",
            "    p.doSomething2(new Object(), null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void typeUseJarField() {
    compilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.lib.*;",
            "class Test {",
            "  void foo(CFNullableStuff c) {",
            "    // BUG: Diagnostic contains: dereferenced expression c.f",
            "    c.f.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void typeUseJarOverride() {
    compilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.lib.*;",
            "import org.checkerframework.checker.nullness.qual.Nullable;",
            "class Test {",
            "  class Test1 implements CFNullableStuff.NullableReturn {",
            "    public @Nullable Object get() { return null; }",
            "  }",
            "  class Test2 implements CFNullableStuff.NullableParam {",
            "    // BUG: Diagnostic contains: parameter o is @NonNull",
            "    public void doSomething(Object o) {}",
            "    // BUG: Diagnostic contains: parameter p is @NonNull",
            "    public void doSomething2(Object o, Object p) {}",
            "  }",
            "  class Test3 implements CFNullableStuff.NullableParam {",
            "    public void doSomething(@Nullable Object o) {}",
            "    public void doSomething2(Object o, @Nullable Object p) {}",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void tryWithResourcesSupport() {
    compilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import java.io.BufferedReader;",
            "import java.io.FileReader;",
            "import java.io.IOException;",
            "class Test {",
            "  String foo(String path, @Nullable String s, @Nullable Object o) throws IOException {",
            "    try (BufferedReader br = new BufferedReader(new FileReader(path))) {",
            "      // Code inside try-resource gets analyzed",
            "      // BUG: Diagnostic contains: dereferenced expression",
            "      o.toString();",
            "      s = br.readLine();",
            "      return s;",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void tryWithResourcesSupportInit() {
    compilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import java.io.BufferedReader;",
            "import java.io.FileReader;",
            "import java.io.IOException;",
            "class Test {",
            "  private String path;",
            "  private String f;",
            "  Test(String p) throws IOException {",
            "    path = p;",
            "    try (BufferedReader br = new BufferedReader(new FileReader(path))) {",
            "      f = br.readLine();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void tryFinallySupportInit() {
    compilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import java.io.BufferedReader;",
            "import java.io.FileReader;",
            "import java.io.IOException;",
            "class Test {",
            "  private String path;",
            "  private String f;",
            "  Test(String p) throws IOException {",
            "    path = p;",
            "    try {",
            "      BufferedReader br = new BufferedReader(new FileReader(path));",
            "      f = br.readLine();",
            "    } finally {",
            "      f = \"DEFAULT\";",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void supportObjectsIsNull() {
    compilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  private void foo(@Nullable String s) {",
            "    if (!Objects.isNull(s)) {",
            "      s.toString();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void supportTruthAssertThatIsNotNull_Object() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.lang.Object;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "import static com.google.common.truth.Truth.assertThat;",
            "class Test {",
            "  private void foo(@Nullable Object o) {",
            "    assertThat(o).isNotNull();",
            "    o.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void supportTruthAssertThatIsNotNull_String() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "import static com.google.common.truth.Truth.assertThat;",
            "class Test {",
            "  private void foo(@Nullable String s) {",
            "    assertThat(s).isNotNull();",
            "    s.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void doNotSupportTruthAssertThatWhenDisabled() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=false"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.lang.Object;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "import static com.google.common.truth.Truth.assertThat;",
            "class Test {",
            "  private void foo(@Nullable Object a) {",
            "    assertThat(a).isNotNull();",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    a.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void supportHamcrestAssertThatMatchersIsNotNull() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.lang.Object;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "import static org.hamcrest.MatcherAssert.assertThat;",
            "import static org.hamcrest.Matchers.*;",
            "class Test {",
            "  private void foo(@Nullable Object a, @Nullable Object b) {",
            "    assertThat(a, is(notNullValue()));",
            "    a.toString();",
            "    assertThat(b, is(not(nullValue())));",
            "    b.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void doNotSupportHamcrestAssertThatWhenDisabled() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=false"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.lang.Object;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "import static org.hamcrest.MatcherAssert.assertThat;",
            "import static org.hamcrest.Matchers.*;",
            "class Test {",
            "  private void foo(@Nullable Object a) {",
            "    assertThat(a, is(notNullValue()));",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    a.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void supportHamcrestAssertThatCoreMatchersIsNotNull() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.lang.Object;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "import static org.hamcrest.MatcherAssert.assertThat;",
            "import static org.hamcrest.CoreMatchers.*;",
            "class Test {",
            "  private void foo(@Nullable Object a, @Nullable Object b) {",
            "    assertThat(a, is(notNullValue()));",
            "    a.toString();",
            "    assertThat(b, is(not(nullValue())));",
            "    b.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void supportHamcrestAssertThatCoreIsNotNull() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.lang.Object;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "import static org.hamcrest.MatcherAssert.assertThat;",
            "import static org.hamcrest.CoreMatchers.*;",
            "import org.hamcrest.core.IsNull;",
            "class Test {",
            "  private void foo(@Nullable Object a, @Nullable Object b) {",
            "    assertThat(a, is(IsNull.notNullValue()));",
            "    a.toString();",
            "    assertThat(b, is(not(IsNull.nullValue())));",
            "    b.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void supportJunitAssertThatIsNotNull_Object() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.lang.Object;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "import static org.junit.Assert.assertThat;",
            "import static org.hamcrest.Matchers.*;",
            "class Test {",
            "  private void foo(@Nullable Object a, @Nullable Object b) {",
            "    assertThat(a, is(notNullValue()));",
            "    a.toString();",
            "    assertThat(b, is(not(nullValue())));",
            "    b.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void doNotSupportJunitAssertThatWhenDisabled() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=false"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.lang.Object;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "import static org.junit.Assert.assertThat;",
            "import static org.hamcrest.Matchers.*;",
            "class Test {",
            "  private void foo(@Nullable Object a) {",
            "    assertThat(a, is(notNullValue()));",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    a.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void supportSwitchExpression() {
    compilationHelper
        .addSourceLines(
            "TestPositive.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "enum Level {",
            " HIGH, MEDIUM, LOW }",
            "class TestPositive {",
            "   void foo(@Nullable Integer s) {",
            "    // BUG: Diagnostic contains: switch expression s is @Nullable",
            "    switch(s) {",
            "      case 5: break;",
            "    }",
            "    String x = null;",
            "    // BUG: Diagnostic contains: switch expression x is @Nullable",
            "    switch(x) {",
            "      default: break;",
            "    }",
            "    Level level = null;",
            "    // BUG: Diagnostic contains: switch expression level is @Nullable",
            "    switch (level) {",
            "      default: break; }",
            "    }",
            "}")
        .addSourceLines(
            "TestNegative.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class TestNegative {",
            "   void foo(Integer s, short y) {",
            "    switch(s) {",
            "      case 5: break;",
            "    }",
            "    String x = \"irrelevant\";",
            "    switch(x) {",
            "      default: break;",
            "    }",
            "    switch(y) {",
            "      default: break;",
            "    }",
            "    Level level = Level.HIGH;",
            "    switch (level) {",
            "      default: break;",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void defaultPermissiveOnUnannotated() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=false"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.lib.unannotated.RestrictivelyAnnotatedClass;",
            "class Test {",
            "  Object test() {",
            "    // Assume methods take @Nullable, even if annotated otherwise",
            "    RestrictivelyAnnotatedClass.consumesObjectUnannotated(null);",
            "    RestrictivelyAnnotatedClass.consumesObjectNonNull(null);",
            "    // Ignore explict @Nullable return",
            "    return RestrictivelyAnnotatedClass.returnsNull();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void acknowledgeRestrictiveAnnotationsWhenFlagSet() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.lib.unannotated.RestrictivelyAnnotatedClass;",
            "class Test {",
            "  Object test() {",
            "    RestrictivelyAnnotatedClass.consumesObjectUnannotated(null);",
            "    // BUG: Diagnostic contains: @NonNull is required",
            "    RestrictivelyAnnotatedClass.consumesObjectNonNull(null);",
            "    // BUG: Diagnostic contains: @NonNull is required",
            "    RestrictivelyAnnotatedClass.consumesObjectNotNull(null);",
            "    // BUG: Diagnostic contains: returning @Nullable",
            "    return RestrictivelyAnnotatedClass.returnsNull();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void defaultPermissiveOnRecently() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                // should be permissive even when AcknowledgeRestrictiveAnnotations is set
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.lib.unannotated.AndroidRecentlyAnnotatedClass;",
            "class Test {",
            "  Object test() {",
            "    // Assume methods take @Nullable, even if annotated otherwise",
            "    AndroidRecentlyAnnotatedClass.consumesObjectUnannotated(null);",
            "    AndroidRecentlyAnnotatedClass.consumesObjectNonNull(null);",
            "    // Ignore explict @Nullable return",
            "    return AndroidRecentlyAnnotatedClass.returnsNull();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void acknowledgeRecentlyAnnotationsWhenFlagSet() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true",
                "-XepOpt:NullAway:AcknowledgeAndroidRecent=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.lib.unannotated.AndroidRecentlyAnnotatedClass;",
            "class Test {",
            "  Object test() {",
            "    AndroidRecentlyAnnotatedClass.consumesObjectUnannotated(null);",
            "    // BUG: Diagnostic contains: @NonNull is required",
            "    AndroidRecentlyAnnotatedClass.consumesObjectNonNull(null);",
            "    // BUG: Diagnostic contains: returning @Nullable",
            "    return AndroidRecentlyAnnotatedClass.returnsNull();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void restrictivelyAnnotatedMethodsWorkWithNullnessFromDataflow() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.lib.unannotated.RestrictivelyAnnotatedClass;",
            "class Test {",
            "  Object test1(RestrictivelyAnnotatedClass instance) {",
            "    if (instance.getField() != null) {",
            "      return instance.getField();",
            "    }",
            "    throw new Error();",
            "  }",
            "  Object test2(RestrictivelyAnnotatedClass instance) {",
            "    if (instance.field != null) {",
            "      return instance.field;",
            "    }",
            "    throw new Error();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void restrictivelyAnnotatedMethodsWorkWithNullnessFromDataflow2() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.lib.unannotated.RestrictivelyAnnotatedGenericContainer;",
            "class Test {",
            "  String test(RestrictivelyAnnotatedGenericContainer<Integer> instance) {",
            "    if (instance.getField() == null) {",
            "      return \"\";",
            "    }",
            "    return instance.getField().toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void OverridingRestrictivelyAnnotatedMethod() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines(
            "TestNegativeCases.java",
            "package com.uber;",
            "import com.uber.lib.unannotated.RestrictivelyAnnotatedClass;",
            "import org.checkerframework.checker.nullness.qual.NonNull;",
            "import javax.annotation.Nullable;",
            "public class TestNegativeCases extends RestrictivelyAnnotatedClass {",
            "   TestNegativeCases(){ super(new Object()); }",
            "   @Override public void acceptsNonNull(@Nullable Object o) { }",
            "   @Override public void acceptsNonNull2(Object o) { }",
            "   @Override public void acceptsNullable2(@Nullable Object o) { }",
            "   @Override public Object returnsNonNull() { return new Object(); }",
            "   @Override public Object returnsNullable() { return new Object(); }",
            "   @Override public @Nullable Object returnsNullable2() { return new Object();}",
            "}")
        .addSourceLines(
            "TestPositiveCases.java",
            "package com.uber;",
            "import com.uber.lib.unannotated.RestrictivelyAnnotatedClass;",
            "import org.checkerframework.checker.nullness.qual.NonNull;",
            "import javax.annotation.Nullable;",
            "public class TestPositiveCases extends RestrictivelyAnnotatedClass {",
            "   TestPositiveCases(){ super(new Object()); }",
            "   // BUG: Diagnostic contains: parameter o is @NonNull",
            "   public void acceptsNullable(Object o) { }",
            "   // BUG: Diagnostic contains: method returns @Nullable",
            "   public @Nullable Object returnsNonNull2() { return new Object(); }",
            "}")
        .doTest();
  }

  @Test
  public void lambdaPlusRestrictivePositive() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.lib.unannotated.RestrictivelyAnnotatedFI;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  void foo() {",
            "    RestrictivelyAnnotatedFI func = (x) -> {",
            "      // BUG: Diagnostic contains: dereferenced expression x is @Nullable",
            "      x.toString();",
            "      return new Object();",
            "    };",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void lambdaPlusRestrictiveNegative() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.lib.unannotated.RestrictivelyAnnotatedFI;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  void foo() {",
            "    RestrictivelyAnnotatedFI func = (x) -> {",
            "      // no error since AcknowledgeRestrictiveAnnotations disabled",
            "      x.toString();",
            "      return new Object();",
            "    };",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessHandlerTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true"))
        .addSourceLines(
            "TestNegative.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestNegative {",
            "  void foo() {",
            "    Optional<Object> a = Optional.empty();",
            "      // no error since a.isPresent() is called",
            "      if(a.isPresent()){",
            "         a.get().toString();",
            "       }",
            "    }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.empty();",
            "      if(b.isPresent()){",
            "          lambdaConsumer(v -> b.get().toString());",
            "       }",
            "    }",
            "  @SuppressWarnings(\"NullAway.Optional\")",
            "  void SupWarn() {",
            "    Optional<Object> a = Optional.empty();",
            "      // no error since suppressed",
            "      a.get().toString();",
            "    }",
            "  void SupWarn2() {",
            "    Optional<Object> a = Optional.empty();",
            "      // no error since suppressed",
            "     @SuppressWarnings(\"NullAway.Optional\") String b = a.get().toString();",
            "    }",
            "}")
        .addSourceLines(
            "TestPositive.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestPositive {",
            "  void foo() {",
            "    Optional<Object> a = Optional.empty();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional a",
            "    a.get().toString();",
            "  }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.empty();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional b",
            "           lambdaConsumer(v -> b.get().toString());",
            "    }",
            "   // This tests if the suppression is not suppressing unrelated errors ",
            "  @SuppressWarnings(\"NullAway.Optional\")",
            "  void SupWarn() {",
            "    Object a = null;",
            "      // BUG: Diagnostic contains: dereferenced expression a is @Nullable",
            "      a.toString();",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessHandlerWithSingleCustomPathTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true",
                "-XepOpt:NullAway:CheckOptionalEmptinessCustomClasses=com.google.common.base.Optional"))
        .addSourceLines(
            "TestNegative.java",
            "package com.uber;",
            "import com.google.common.base.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestNegative {",
            "  class ABC { Optional<Object> ob = Optional.absent();} ",
            "  void foo() {",
            "      ABC abc = new ABC();",
            "      // no error since a.isPresent() is called",
            "      if(abc.ob.isPresent()){",
            "         abc.ob.get().toString();",
            "       }",
            "    }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.absent();",
            "      if(b.isPresent()){",
            "          lambdaConsumer(v -> b.get().toString());",
            "       }",
            "    }",
            "}")
        .addSourceLines(
            "TestPositive.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestPositive {",
            "  class ABC { Optional<Object> ob = Optional.empty();} ",
            "  void foo() {",
            "    ABC abc = new ABC();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional abc.ob",
            "    abc.ob.get().toString();",
            "  }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.empty();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional b",
            "           lambdaConsumer(v -> b.get().toString());",
            "    }",
            "}")
        .addSourceLines(
            "TestPositive2.java",
            "package com.uber;",
            "import com.google.common.base.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestPositive2 {",
            "  void foo() {",
            "    Optional<Object> a = Optional.absent();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional a",
            "    a.get().toString();",
            "  }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.absent();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional b",
            "           lambdaConsumer(v -> b.get().toString());",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessHandlerWithTwoCustomPathsTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true",
                "-XepOpt:NullAway:CheckOptionalEmptinessCustomClasses=does.not.matter.Optional,com.google.common.base.Optional"))
        .addSourceLines(
            "TestNegative.java",
            "package com.uber;",
            "import com.google.common.base.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestNegative {",
            "  void foo() {",
            "    Optional<Object> a = Optional.absent();",
            "      // no error since a.isPresent() is called",
            "      if(a.isPresent()){",
            "         a.get().toString();",
            "       }",
            "    }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.absent();",
            "      if(b.isPresent()){",
            "          lambdaConsumer(v -> b.get().toString());",
            "       }",
            "    }",
            "}")
        .addSourceLines(
            "TestPositive.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestPositive {",
            "  void foo() {",
            "    Optional<Object> a = Optional.empty();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional a",
            "    a.get().toString();",
            "  }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.empty();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional b",
            "           lambdaConsumer(v -> b.get().toString());",
            "    }",
            "}")
        .addSourceLines(
            "TestPositive2.java",
            "package com.uber;",
            "import com.google.common.base.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestPositive2 {",
            "  void foo() {",
            "    Optional<Object> a = Optional.absent();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional a",
            "    a.get().toString();",
            "  }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.absent();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional b",
            "           lambdaConsumer(v -> b.get().toString());",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessUncheckedTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated"))
        .addSourceLines(
            "TestNegative.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestNegative {",
            "  void foo() {",
            "    Optional<Object> a = Optional.empty();",
            "    // no error since the handler is not attached",
            "    a.get().toString();",
            "  }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.empty();",
            "      if(b.isPresent()){",
            "    // no error since the handler is not attached",
            "          lambdaConsumer(v -> b.get().toString());",
            "       }",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessRxPositiveTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber,io.reactivex",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true"))
        .addSourceLines(
            "TestPositive.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import io.reactivex.Observable;",
            "public class TestPositive {",
            "  private static boolean perhaps() { return Math.random() > 0.5; }",
            "  void foo(Observable<Optional<String>> observable) {",
            "     observable",
            "           .filter(optional -> optional.isPresent() || perhaps())",
            "           // BUG: Diagnostic contains: Invoking get() on possibly empty Optional optional",
            "           .map(optional -> optional.get().toString());",
            "     observable",
            "           .filter(optional -> optional.isPresent() || perhaps())",
            "           // BUG: Diagnostic contains: Invoking get() on possibly empty Optional optional",
            "           .map(optional -> optional.get())",
            "           .map(irr -> irr.toString());",
            "     }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessRxNegativeTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true"))
        .addSourceLines(
            "TestNegative.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import io.reactivex.Observable;",
            "public class TestNegative {",
            "  private static boolean perhaps() { return Math.random() > 0.5; }",
            "  void foo(Observable<Optional<String>> observable) {",
            "     observable",
            "           .filter(optional -> optional.isPresent())",
            "           .map(optional -> optional.get().toString());",
            "     observable",
            "           .filter(optional -> optional.isPresent() && perhaps())",
            "           .map(optional -> optional.get().toString());",
            "     observable",
            "           .filter(optional -> optional.isPresent() && perhaps())",
            "           .map(optional -> optional.get());",
            "     }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessHandleAssertionLibraryTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber,io.reactivex",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true",
                "-XepOpt:NullAway:HandleTestAssertionLibraries=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "import static com.google.common.truth.Truth.assertThat;",
            "public class Test {",
            "  void foo() {",
            "    Optional<Object> a = Optional.empty();",
            "    assertThat(a.isPresent()).isTrue(); ",
            "    a.get().toString();",
            "  }",
            "  public void lambdaConsumer(Function a){",
            "    return;",
            "  }",
            "  void bar() {",
            "    Optional<Object> b = Optional.empty();",
            "    assertThat(b.isPresent()).isTrue(); ",
            "    lambdaConsumer(v -> b.get().toString());",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessAssignmentCheckNegativeTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true"))
        .addSourceLines(
            "TestNegative.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestNegative {",
            "  void foo() {",
            "    Optional<Object> a = Optional.empty();",
            "    Object x = a.isPresent() ? a.get() : \"something\";",
            "    x.toString();",
            "  }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.empty();",
            "      if(b.isPresent()){",
            "          lambdaConsumer(v -> b.get().toString());",
            "       }",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessAssignmentCheckPositiveTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true"))
        .addSourceLines(
            "TestPositive.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "public class TestPositive {",
            "  void foo() {",
            "    Optional<Object> a = Optional.empty();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional a",
            "    Object x = a.get();",
            "    x.toString();",
            "  }",
            "   public void lambdaConsumer(Function a){",
            "        return;",
            "   }",
            "  void bar() {",
            "     Optional<Object> b = Optional.empty();",
            "    // BUG: Diagnostic contains: Invoking get() on possibly empty Optional b",
            "     lambdaConsumer(v -> {Object x = b.get();  return \"irrelevant\";});",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void optionalEmptinessContextualSuppressionTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:CheckOptionalEmptiness=true"))
        .addSourceLines(
            "TestClassSuppression.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Function;",
            "@SuppressWarnings(\"NullAway.Optional\")",
            "public class TestClassSuppression {",
            "  // no error since suppressed",
            "  Function<Optional, String> lambdaField = opt -> opt.get().toString();",
            "  void foo() {",
            "    Optional<Object> a = Optional.empty();",
            "    // no error since suppressed",
            "    a.get().toString();",
            "  }",
            "  public void lambdaConsumer(Function a){",
            "    return;",
            "  }",
            "  void bar() {",
            "    Optional<Object> b = Optional.empty();",
            "    // no error since suppressed",
            "    lambdaConsumer(v -> b.get().toString());",
            "  }",
            "  void baz(@Nullable Object o) {",
            "    // unrelated errors not suppressed",
            "    // BUG: Diagnostic contains: dereferenced expression o is @Nullable",
            "    o.toString();",
            "  }",
            "  public static class Inner {",
            "    void foo() {",
            "      Optional<Object> a = Optional.empty();",
            "      // no error since suppressed in outer class",
            "      a.get().toString();",
            "    }",
            "  }",
            "}")
        .addSourceLines(
            "TestLambdaFieldNoSuppression.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import com.google.common.base.Function;",
            "public class TestLambdaFieldNoSuppression {",
            "  // BUG: Diagnostic contains: Invoking get() on possibly empty Optional opt",
            "  Function<Optional, String> lambdaField = opt -> opt.get().toString();",
            "}")
        .addSourceLines(
            "TestLambdaFieldWithSuppression.java",
            "package com.uber;",
            "import java.util.Optional;",
            "import com.google.common.base.Function;",
            "public class TestLambdaFieldWithSuppression {",
            "  // no error since suppressed",
            "  @SuppressWarnings(\"NullAway.Optional\")",
            "  Function<Optional, String> lambdaField = opt -> opt.get().toString();",
            "}")
        .doTest();
  }

  @Test
  public void testCastToNonNull() {
    compilationHelper
        .addSourceFile("Util.java")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import static com.uber.nullaway.testdata.Util.castToNonNull;",
            "class Test {",
            "  Object test1(@Nullable Object o) {",
            "    return castToNonNull(o);",
            "  }",
            "  Object test2(Object o) {",
            "    // BUG: Diagnostic contains: passing known @NonNull parameter 'o' to CastToNonNullMethod",
            "    return castToNonNull(o);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testReadStaticInConstructor() {
    compilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  // BUG: Diagnostic contains: @NonNull static field o not initialized",
            "  static Object o;",
            "  Object f, g;",
            "  public Test() {",
            "    f = new String(\"hi\");",
            "    o = new Object();",
            "    g = o;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void customErrorURL() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:ErrorURL=http://mydomain.com/nullaway"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  static void foo() {",
            "    // BUG: Diagnostic contains: mydomain.com",
            "    Object x = null; x.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void defaultURL() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  static void foo() {",
            "    // BUG: Diagnostic contains: t.uber.com/nullaway",
            "    Object x = null; x.toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void invokeNativeFromInitializer() {
    compilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "  Object f;",
            "  private native void foo();",
            "  // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field f",
            "  Test() {",
            "    foo();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void overrideFailsOnExplicitlyNullableLibraryModelParam() {
    compilationHelper
        .addSourceLines( // Dummy android.view.GestureDetector.OnGestureListener interface
            "GestureDetector.java",
            "package android.view;",
            "public class GestureDetector {",
            "  public static interface OnGestureListener {",
            // Ignore other methods for this test, to make code shorter on both files:
            "    boolean onScroll(MotionEvent me1, MotionEvent me2, float f1, float f2);",
            "  }",
            "}")
        .addSourceLines( // Dummy android.view.MotionEvent class
            "MotionEvent.java", "package android.view;", "public class MotionEvent { }")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import android.view.GestureDetector;",
            "import android.view.MotionEvent;",
            "class Test implements GestureDetector.OnGestureListener {",
            "  Test() {  }",
            "  @Override",
            "  // BUG: Diagnostic contains: parameter me1 is @NonNull",
            "  public boolean onScroll(MotionEvent me1, MotionEvent me2, float f1, float f2) {",
            "    return false; // NoOp",
            "  }",
            "}")
        .addSourceLines(
            "Test2.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import android.view.GestureDetector;",
            "import android.view.MotionEvent;",
            "class Test2 implements GestureDetector.OnGestureListener {",
            "  Test2() {  }",
            "  @Override",
            "  public boolean onScroll(@Nullable MotionEvent me1, MotionEvent me2, float f1, float f2) {",
            "    return false; // NoOp",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testCapturingScopes() {
    compilationHelper.addSourceFile("CapturingScopes.java").doTest();
  }

  @Test
  public void testEnhancedFor() {
    compilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import java.util.List;",
            "public class Test {",
            "  public void testEnhancedFor(@Nullable List<String> l) {",
            "    // BUG: Diagnostic contains: enhanced-for expression l is @Nullable",
            "    for (String x: l) {}",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testConstantsInAccessPathsNegative() {
    compilationHelper
        .addSourceLines(
            "NullableContainer.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public interface NullableContainer<K, V> {",
            " @Nullable public V get(K k);",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  public void testSingleStringCheck(NullableContainer<String, Object> c) {",
            "    if (c.get(\"KEY_STR\") != null) {",
            "      c.get(\"KEY_STR\").toString(); // is safe",
            "    }",
            "  }",
            "  public void testSingleIntCheck(NullableContainer<Integer, Object> c) {",
            "    if (c.get(42) != null) {",
            "      c.get(42).toString(); // is safe",
            "    }",
            "  }",
            "  public void testMultipleChecks(NullableContainer<String, NullableContainer<Integer, Object>> c) {",
            "    if (c.get(\"KEY_STR\") != null && c.get(\"KEY_STR\").get(42) != null) {",
            "      c.get(\"KEY_STR\").get(42).toString(); // is safe",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testConstantsInAccessPathsPositive() {
    compilationHelper
        .addSourceLines(
            "NullableContainer.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public interface NullableContainer<K, V> {",
            " @Nullable public V get(K k);",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  public void testEnhancedFor(NullableContainer<String, NullableContainer<Integer, Object>> c) {",
            "    if (c.get(\"KEY_STR\") != null && c.get(\"KEY_STR\").get(0) != null) {",
            "      // BUG: Diagnostic contains: dereferenced expression c.get(\"KEY_STR\").get(42)",
            "      c.get(\"KEY_STR\").get(42).toString();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testVariablesInAccessPathsPositive() {
    compilationHelper
        .addSourceLines(
            "NullableContainer.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public interface NullableContainer<K, V> {",
            " @Nullable public V get(K k);",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  private Integer intKey = 42;", // No guarantee it's a constant
            "  public void testEnhancedFor(NullableContainer<String, NullableContainer<Integer, Object>> c) {",
            "    if (c.get(\"KEY_STR\") != null && c.get(\"KEY_STR\").get(this.intKey) != null) {",
            "      // BUG: Diagnostic contains: dereferenced expression c.get(\"KEY_STR\").get",
            "      c.get(\"KEY_STR\").get(this.intKey).toString();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testNonNullVarargs() {
    compilationHelper
        .addSourceLines(
            "Utilities.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Utilities {",
            " public static String takesNonNullVarargs(Object o, Object... others) {",
            "  String s = o.toString() + \" \";",
            "  for (Object other : others) {",
            "    s += other.toString() + \" \";",
            "  }",
            "  return s;",
            " }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  public void testNonNullVarargs(Object o1, Object o2, Object o3, @Nullable Object o4) {",
            "    Utilities.takesNonNullVarargs(o1, o2, o3);",
            "    Utilities.takesNonNullVarargs(o1);", // Empty var args passed
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'o4' where @NonNull",
            "    Utilities.takesNonNullVarargs(o1, o4);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testNullableVarargs() {
    compilationHelper
        .addSourceLines(
            "Utilities.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Utilities {",
            " public static String takesNullableVarargs(Object o, @Nullable Object... others) {",
            "  String s = o.toString() + \" \";",
            "  // BUG: Diagnostic contains: enhanced-for expression others is @Nullable",
            "  for (Object other : others) {",
            "    s += (other == null) ? \"(null) \" : other.toString() + \" \";",
            "  }",
            "  return s;",
            " }",
            "}")
        .doTest();
  }

  @Test
  public void testNonNullVarargsFromHandler() {
    String generatedAnnot =
        (Double.parseDouble(System.getProperty("java.specification.version")) >= 11)
            ? "@javax.annotation.processing.Generated"
            : "@javax.annotation.Generated";
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:TreatGeneratedAsUnannotated=true",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines(
            "Generated.java",
            "package com.uber;",
            "import javax.annotation.Nonnull;",
            generatedAnnot + "(\"foo\")",
            "public class Generated {",
            " public static String takesNonNullVarargs(@Nonnull Object o, @Nonnull Object... others) {",
            "  String s = o.toString() + \" \";",
            "  for (Object other : others) {",
            "    s += other.toString() + \" \";",
            "  }",
            "  return s;",
            " }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  public void testNonNullVarargs(Object o1, Object o2, Object o3, @Nullable Object o4) {",
            "    Generated.takesNonNullVarargs(o1, o2, o3);",
            "    Generated.takesNonNullVarargs(o1);", // Empty var args passed
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'o4' where @NonNull",
            "    Generated.takesNonNullVarargs(o1, o4);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void libraryModelsOverrideRestrictiveAnnotations() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-processorpath",
                TestLibraryModels.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .getPath(),
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.lib.unannotated",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.uber.lib.unannotated.RestrictivelyAnnotatedFIWithModelOverride;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  void bar(RestrictivelyAnnotatedFIWithModelOverride f) {",
            "     // Param is @NullableDecl in bytecode, overridden by library model",
            "     // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull",
            "     f.apply(null);",
            "  }",
            "  void foo() {",
            "    RestrictivelyAnnotatedFIWithModelOverride func = (x) -> {",
            "     // Param is @NullableDecl in bytecode, overridden by library model, thus safe",
            "     return x.toString();",
            "    };",
            "  }",
            "  void baz() {",
            "     // Safe to pass, since Function can't have a null instance parameter",
            "     bar(Object::toString);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testMapWithCustomPut() { // See https://github.com/uber/NullAway/issues/389
    compilationHelper
        .addSourceLines(
            "Item.java",
            "package com.uber.lib.unannotated.collections;",
            "public class Item<K,V> {",
            " public final K key;",
            " public final V value;",
            " public Item(K k, V v) {",
            "  this.key = k;",
            "  this.value = v;",
            " }",
            "}")
        .addSourceLines(
            "MapLike.java",
            "package com.uber.lib.unannotated.collections;",
            "import java.util.HashMap;",
            "// Too much work to implement java.util.Map from scratch",
            "public class MapLike<K,V> extends HashMap<K,V> {",
            " public MapLike() {",
            "   super();",
            " }",
            " public void put(Item<K,V> item) {",
            "   put(item.key, item.value);",
            " }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.lib.unannotated.collections.Item;",
            "import com.uber.lib.unannotated.collections.MapLike;",
            "public class Test {",
            " public static MapLike test_389(@Nullable Item<String, String> item) {",
            "  MapLike<String, String> map = new MapLike<String, String>();",
            "  if (item != null) {", // Required to trigger dataflow analysis
            "    map.put(item);",
            "  }",
            "  return map;",
            " }",
            "}")
        .doTest();
  }

  @Test
  public void defaultLibraryModelsObjectNonNull() {
    compilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "public class Test {",
            "  String foo(@Nullable Object o) {",
            "    if (Objects.nonNull(o)) {",
            "     return o.toString();",
            "    };",
            "    return \"\";",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void checkForNullSupport() {
    compilationHelper
        // This is just to check the behavior is the same between @Nullable and @CheckForNull
        .addSourceLines(
            "TestNullable.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class TestNullable {",
            "  @Nullable",
            "  Object nullable = new Object();",
            "  public void setNullable(@Nullable Object nullable) {this.nullable = nullable;}",
            "  // BUG: Diagnostic contains: dereferenced expression nullable is @Nullable",
            "  public void run() {System.out.println(nullable.toString());}",
            "}")
        .addSourceLines(
            "TestCheckForNull.java",
            "package com.uber;",
            "import javax.annotation.CheckForNull;",
            "class TestCheckForNull {",
            "  @CheckForNull",
            "  Object checkForNull = new Object();",
            "  public void setCheckForNull(@CheckForNull Object checkForNull) {this.checkForNull = checkForNull;}",
            "  // BUG: Diagnostic contains: dereferenced expression checkForNull is @Nullable",
            "  public void run() {System.out.println(checkForNull.toString());}",
            "}")
        .doTest();
  }

  @Test
  public void orElseLibraryModelSupport() {
    // Checks both Optional.orElse(...) support itself and the general nullImpliesNullParameters
    // Library Models mechanism for encoding @Contract(!null -> !null) as a library model.
    compilationHelper
        .addSourceLines(
            "TestOptionalOrElseNegative.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import java.util.Optional;",
            "class TestOptionalOrElseNegative {",
            "  public Object foo(Optional<Object> o) {",
            "    return o.orElse(\"Something\");",
            "  }",
            "  public @Nullable Object bar(Optional<Object> o) {",
            "    return o.orElse(null);",
            "  }",
            "}")
        .addSourceLines(
            "TestOptionalOrElsePositive.java",
            "package com.uber;",
            "import java.util.Optional;",
            "class TestOptionalOrElsePositive {",
            "  public Object foo(Optional<Object> o) {",
            "    // BUG: Diagnostic contains: returning @Nullable expression",
            "    return o.orElse(null);",
            "  }",
            "  public void bar(Optional<Object> o) {",
            "    // BUG: Diagnostic contains: dereferenced expression o.orElse(null) is @Nullable",
            "    System.out.println(o.orElse(null).toString());",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void requiresNonNullInterpretation() {
    compilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.RequiresNonNull;",
            "class Foo {",
            "  @Nullable Item nullableItem;",
            "  @RequiresNonNull(\"nullableItem\")",
            "  public void run() {",
            "    nullableItem.call();",
            "    nullableItem = null;",
            "    // BUG: Diagnostic contains: dereferenced expression nullableItem is @Nullable",
            "    nullableItem.call();",
            "     ",
            "  }",
            "  @RequiresNonNull(\"this.nullableItem\")",
            "  public void test() {",
            "    nullableItem.call();",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  /**
   * Tests that we properly report errors for {@code @RequiresNonNull} or {@code @EnsuresNonNull}
   * annotations mentioning static fields
   */
  @Test
  public void requiresEnsuresNonNullStaticFields() {
    compilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.RequiresNonNull;",
            "import com.uber.nullaway.annotations.EnsuresNonNull;",
            "class Foo {",
            "  @Nullable static Item nullableItem;",
            "  @RequiresNonNull(\"nullableItem\")",
            "  // BUG: Diagnostic contains: For @RequiresNonNull annotation, cannot find instance field",
            "  public static void run() {",
            "    // BUG: Diagnostic contains: dereferenced expression nullableItem is @Nullable",
            "    nullableItem.call();",
            "     ",
            "  }",
            "  @RequiresNonNull(\"this.nullableItem\")",
            "  // BUG: Diagnostic contains: For @RequiresNonNull annotation, cannot find instance field",
            "  public static void test() {",
            "    // BUG: Diagnostic contains: dereferenced expression nullableItem is @Nullable",
            "    nullableItem.call();",
            "  }",
            "  @EnsuresNonNull(\"nullableItem\")",
            "  // BUG: Diagnostic contains: For @EnsuresNonNull annotation, cannot find instance field",
            "  public static void test2() {",
            "    nullableItem = new Item();",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void supportRequiresNonNullOverridingTest() {
    compilationHelper
        .addSourceLines(
            "SuperClass.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.RequiresNonNull;",
            "class SuperClass {",
            "  @Nullable Item a;",
            "  @RequiresNonNull(\"a\")",
            "  public void test0() {",
            "    a.call();",
            "  }",
            "  public void test1() {",
            "  }",
            "  @RequiresNonNull(\"a\")",
            "  public void test2() {",
            "    a.call();",
            "  }",
            "  @RequiresNonNull(\"a\")",
            "  public void test3() {",
            "    a.call();",
            "  }",
            "  @RequiresNonNull(\"a\")",
            "  public void test4() {",
            "    a.call();",
            "  }",
            "}")
        .addSourceLines(
            "ChildLevelOne.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.RequiresNonNull;",
            "class ChildLevelOne extends SuperClass {",
            "  @Nullable Item b;",
            "  public void test0() { }",
            "  public void test1() {",
            "    // BUG: Diagnostic contains: dereferenced expression a is @Nullable",
            "    a.call();",
            "  }",
            "  public void test2() {",
            "    // BUG: Diagnostic contains: dereferenced expression a is @Nullable",
            "    a.call();",
            "  }",
            "  @RequiresNonNull(\"a\")",
            "  public void test3() {",
            "    a.call();",
            "  }",
            "  @RequiresNonNull(\"b\")",
            "  // BUG: Diagnostic contains: precondition inheritance is violated, method in child class cannot have a stricter precondition than its closest overridden method, adding @requiresNonNull for fields [a] makes this method precondition stricter",
            "  public void test4() {",
            "    // BUG: Diagnostic contains: dereferenced expression a is @Nullable",
            "    a.call();",
            "    b.call();",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void ensuresNonNullInterpretation() {
    compilationHelper
        .addSourceLines(
            "Foo.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNull;",
            "class Foo {",
            "  @Nullable Item nullItem;",
            "  Foo foo = new Foo();",
            "  @EnsuresNonNull(\"nullItem\")",
            "  public void test1() {",
            "    nullItem = new Item();",
            "  }",
            "  @EnsuresNonNull(\"nullItem\")",
            "  // BUG: Diagnostic contains: test2() is annotated with @EnsuresNonNull annotation, it indicates that all fields in the annotation parameter must be guaranteed to be nonnull at exit point. However, the method's body fails to ensure this for the following fields: [nullItem]",
            "  public void test2() {",
            "  }",
            "  @EnsuresNonNull(\"this.nullItem\")",
            "  public void test3() {",
            "    test1();",
            "  }",
            "  @EnsuresNonNull(\"other.nullItem\")",
            "  // BUG: Diagnostic contains: currently @EnsuresNonNull supports only class fields of the method receiver: other.nullItem is not supported",
            "  public void test4() {",
            "    nullItem = new Item();",
            "  }",
            "  @EnsuresNonNull(\"nullItem\")",
            "  // BUG: Diagnostic contains: method: test5() is annotated with @EnsuresNonNull annotation, it indicates that all fields in the annotation parameter must be guaranteed to be nonnull at exit point. However, the method's body fails to ensure this for the following fields: [nullItem]",
            "  public void test5() {",
            "    this.foo.test1();",
            "  }",
            "  @EnsuresNonNull(\"nullItem\")",
            "  public void test6() {",
            "    this.test1();",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void supportEnsuresNonNullOverridingTest() {
    compilationHelper
        .addSourceLines(
            "SuperClass.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNull;",
            "class SuperClass {",
            "  @Nullable Item a;",
            "  @EnsuresNonNull(\"a\")",
            "  public void test1() {",
            "    a = new Item();",
            "  }",
            "  @EnsuresNonNull(\"a\")",
            "  public void test2() {",
            "    a = new Item();",
            "  }",
            "  @EnsuresNonNull(\"a\")",
            "  public void noAnnotation() {",
            "    a = new Item();",
            "  }",
            "}")
        .addSourceLines(
            "ChildLevelOne.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.EnsuresNonNull;",
            "class ChildLevelOne extends SuperClass {",
            "  @Nullable Item b;",
            "  @EnsuresNonNull(\"b\")",
            "  // BUG: Diagnostic contains: postcondition inheritance is violated, this method must guarantee that all fields written in the @EnsuresNonNull annotation of overridden method SuperClass.test1 are @NonNull at exit point as well. Fields [a] must explicitly appear as parameters at this method @EnsuresNonNull annotation",
            "  public void test1() {",
            "    b = new Item();",
            "  }",
            "  @EnsuresNonNull({\"b\", \"a\"})",
            "  public void test2() {",
            "    super.test2();",
            "    b = new Item();",
            "  }",
            "  // BUG: Diagnostic contains: postcondition inheritance is violated, this method must guarantee that all fields written in the @EnsuresNonNull annotation of overridden method SuperClass.noAnnotation are @NonNull at exit point as well. Fields [a] must explicitly appear as parameters at this method @EnsuresNonNull annotation",
            "  public void noAnnotation() {",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .doTest();
  }

  @Test
  public void supportEnsuresAndRequiresNonNullContract() {
    compilationHelper
        .addSourceLines(
            "Content.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.annotations.RequiresNonNull;",
            "import com.uber.nullaway.annotations.EnsuresNonNull;",
            "class Content {",
            "  @Nullable Item nullItem;",
            "  @RequiresNonNull(\"nullItem\")",
            "  public void run() {",
            "    nullItem.call();",
            "  }",
            "  @EnsuresNonNull(\"nullItem\")",
            "  public void init() {",
            "    nullItem = new Item();",
            "  }",
            "  public void test1() {",
            "    init();",
            "    run();",
            "  }",
            "  public void test2() {",
            "    Content content = new Content();",
            "    content.init();",
            "    content.run();",
            "  }",
            "  public void test3() {",
            "    Content content = new Content();",
            "    init();",
            "    Content other = new Content();",
            "    other.init();",
            "    // BUG: Diagnostic contains: Expected field nullItem to be non-null at call site",
            "    content.run();",
            "  }",
            "}")
        .addSourceLines(
            "Item.java", "package com.uber;", "class Item {", "  public void call() { }", "}")
        .addSourceLines(
            "Box.java",
            "package com.uber;",
            "class Box {",
            "  Content content = new Content();",
            "}")
        .addSourceLines(
            "Office.java",
            "package com.uber;",
            "class Office {",
            "  Box box = new Box();",
            "  public void test1() {",
            "    Office office1 = new Office();",
            "    Office office2 = new Office();",
            "    office1.box.content.init();",
            "    // BUG: Diagnostic contains: Expected field nullItem to be non-null at call site",
            "    office2.box.content.run();",
            "  }",
            "  public void test2() {",
            "    Office office1 = new Office();",
            "    office1.box.content.init();",
            "    office1.box.content.run();",
            "  }",
            "  public void test3() {",
            "    Box box = new Box();",
            "    this.box.content.init();",
            "    this.box.content.run();",
            "    // BUG: Diagnostic contains: Expected field nullItem to be non-null at call site",
            "    box.content.run();",
            "  }",
            "  public void test4(int i) {",
            "    Office office1 = new Office();",
            "    Office office2 = new Office();",
            "    boolean b = i > 10;",
            "    (b ? office1.box.content : office1.box.content).init();",
            "    // BUG: Diagnostic contains: Expected field nullItem to be non-null at call site",
            "    office1.box.content.run();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void overridingNativeModelsInAnnotatedCodeDoesNotPropagateTheModel() {
    // See https://github.com/uber/NullAway/issues/445
    compilationHelper
        .addSourceLines(
            "NonNullGetMessage.java",
            "package com.uber;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "class NonNullGetMessage extends RuntimeException {",
            "  NonNullGetMessage(final String message) {",
            "     super(message);",
            "  }",
            "  @Override",
            "  public String getMessage() {",
            "    return Objects.requireNonNull(super.getMessage());",
            "  }",
            "  public static void foo(NonNullGetMessage e) {",
            "    expectsNonNull(e.getMessage());",
            "  }",
            "  public static void expectsNonNull(String str) {",
            "    System.out.println(str);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void overridingNativeModelsInAnnotatedCodeDoesNotGenerateSafetyHoles() {
    // See https://github.com/uber/NullAway/issues/445
    compilationHelper
        .addSourceLines(
            "NonNullGetMessage.java",
            "package com.uber;",
            "import java.util.Objects;",
            "import javax.annotation.Nullable;",
            "class NonNullGetMessage extends RuntimeException {",
            "  NonNullGetMessage(@Nullable String message) {",
            "     super(message);",
            "  }",
            "  @Override",
            "  public String getMessage() {",
            "    // BUG: Diagnostic contains: returning @Nullable expression",
            "    return super.getMessage();",
            "  }",
            "}")
        .doTest();
  }
}
