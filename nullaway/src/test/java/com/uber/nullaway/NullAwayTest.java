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
            "-XepOpt:NullAway:ExcludedClassAnnotations=" + "com.uber.nullaway.testdata.TestAnnot",
            "-XepOpt:NullAway:CastToNonNullMethod=com.uber.nullaway.testdata.Util.castToNonNull"));
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
}
