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
            "      // BUG: Diagnostic contains: dereferenced expression l.get(0).getId()",
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
  public void jarinferLoadStubsTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.nullaway.[a-zA-Z0-9.]+.unannotated",
                "-cp",
                "../jar-infer/test-java-lib-jarinfer/build/libs/test-java-lib-jarinfer.jar"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.jarinfer.toys.unannotated.Toys;",
            "class Test {",
            "  void test1(@Nullable String s) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 's'",
            "    Toys.test1(s, \"let's\", \"try\");",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void jarinferNullableReturnsTest() {
    compilationHelper
        .setArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.nullaway.[a-zA-Z0-9.]+.unannotated",
                "-XepOpt:NullAway:JarInferUseReturnAnnotations=true",
                "-cp",
                "../jar-infer/test-java-lib-jarinfer/build/libs/test-java-lib-jarinfer.jar"))
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import com.uber.nullaway.jarinfer.toys.unannotated.Toys;",
            "class Test {",
            "  void test1(@Nullable String s) {",
            "    // BUG: Diagnostic contains: passing @Nullable parameter 'Toys.getString(false, s)'",
            "    Toys.test1(Toys.getString(false, s), \"let's\", \"try\");",
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
  public void localsCanBeNullOnClosure() {
    compilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  public Runnable outer(@Nullable Object o, int p) {",
            "    final Object outerVar = o;",
            "    if (p > 0) {",
            "      return (new Runnable() {",
            "        // BUG: Diagnostic contains: assigning @Nullable",
            "        final Object someField = outerVar;",
            "        @Override",
            "        public void run() {",
            "          // BUG: Diagnostic contains: dereferenced expression outerVar",
            "          System.out.println(outerVar.toString());",
            "        }",
            "      });",
            "    } else {",
            "      // BUG: Diagnostic contains: dereferenced expression outerVar",
            "      return () -> { outerVar.hashCode(); };",
            "    }",
            "  }",
            "}")
        .doTest();
  }
}
