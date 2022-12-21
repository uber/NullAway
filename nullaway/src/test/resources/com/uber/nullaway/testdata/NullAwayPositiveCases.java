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

package com.uber.nullaway.testdata;

import java.util.List;
import javax.annotation.Nullable;

public class NullAwayPositiveCases {

  public Object returnsNull() {
    // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return
    // type
    return null;
  }

  public Object sometimesReturnsNull(boolean random) {
    if (random) {
      // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return
      // type
      return null;
    }
    return new Object();
  }

  public Object assignmentExpression() {
    Object x = new Object();
    // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return
    // type
    return (x = null);
  }

  public Object sometimesReturnsNull2(boolean random) {
    Object x = null;
    if (random) {
      x = new Object();
    }
    // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return
    // type
    return x;
  }

  @Nullable static Object sField = null;

  public Object returnsNullStaticField() {
    // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return
    // type
    return sField;
  }

  static class Inner {
    @Nullable Object f1 = null;
    Object f2 = new Object();

    @Nullable
    public Object getF1() {
      return f1;
    }

    public Object testCond() {
      if (f1 != null) {
        f1.toString();
      }
      // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return
      // type
      return f1;
    }

    public void testCond2() {
      if (f1 == null) {
        // BUG: Diagnostic contains: dereferenced expression
        f1.toString();
      }
    }

    public void testCond3() {
      if (f2 != null) {
        // BUG: Diagnostic contains: dereferenced expression
        f1.toString();
      }
    }

    public void checkCondOnThis4() {
      if (this.f2 == null || this.f1 != null) {
        return;
      }
      // BUG: Diagnostic contains: dereferenced expression
      this.f1.toString();
    }

    public void checkCondOnThis5() {
      if (this.f2 == null && this.f1 == null) {
        // BUG: Diagnostic contains: dereferenced expression
        this.f1.toString();
      }
    }

    public void checkCondOnThis6() {
      if (!(this.f1 != null)) {
        // BUG: Diagnostic contains: dereferenced expression
        this.f1.toString();
      }
    }

    public Object badCondCheck(Inner other) {
      if (this.getF1() == null) {
        return new Object();
      }
      // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return
      // type
      return other.getF1();
    }

    public void methodToOverride(@Nullable Object o1) {}

    public Object returnToOverride() {
      return new Object();
    }
  }

  static class InnerSub extends Inner {
    @Override
    // BUG: Diagnostic contains: parameter o1 is @NonNull, but parameter in superclass method
    public void methodToOverride(Object o1) {}

    @Override
    @Nullable
    // BUG: Diagnostic contains: method returns @Nullable, but superclass method
    public Object returnToOverride() {
      return null;
    }
  }

  public Object returnsNullInstanceField() {
    // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return
    // type
    return (new Inner()).f1;
  }

  @Nullable
  Object returnsNullAnnot() {
    return null;
  }

  public Object wrapper() {
    // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return
    // type
    return returnsNullAnnot();
  }

  public Object checkLocalStrongUpdate() {
    Object x = null;
    Object y = x;
    x = new Object();
    // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return
    // type
    return y;
  }

  public Object checkSimpleDataFlow(boolean random) {
    Object y = null, z = new Object();
    Object x = (random) ? y : z;
    // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return
    // type
    return x;
  }

  public void nonNullParam(Object x) {}

  public void callNonNull() {
    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
    nonNullParam(null);
  }

  public void nonNullTwoParams(@Nullable Object x, Object y) {}

  public void callNonNullTwoParams() {
    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
    nonNullTwoParams(new Object(), null);
  }

  public static void staticMultiParam(Object x, @Nullable Object y, Object z) {}

  public void callStaticMulti1() {
    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
    staticMultiParam(new Object(), new Object(), null);
  }

  public void callStaticMulti2() {
    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
    staticMultiParam(null, new Object(), new Object());
    Object fizzbuzz = null;
    // BUG: Diagnostic contains: passing @Nullable parameter 'fizzbuzz' where @NonNull is required
    staticMultiParam(fizzbuzz, new Object(), new Object());
  }

  class Inner2 {
    Object f1 = new Object();

    public void badWrite() {
      // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
      f1 = null;
    }
  }

  static Object sField2 = new Object();

  static void badStaticWrite() {
    Inner x = new Inner();
    // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
    sField2 = x.f1;
  }

  static void invokeOnNull() {
    Object x = null;
    // BUG: Diagnostic contains: dereferenced expression
    x.toString();
  }

  static void invokeOnNull2(@Nullable Object p) {
    // BUG: Diagnostic contains: dereferenced expression p is @Nullable
    p.toString();
  }

  static void derefNullable(@Nullable Inner x) {
    // BUG: Diagnostic contains: dereferenced expression x is @Nullable
    Object y = x.f2;
  }

  static void derefFieldNoCheck(Inner i1) {
    // BUG: Diagnostic contains: dereferenced expression
    i1.f1.toString();
  }

  static void arrayDeref(@Nullable Object[] objArr) {
    // BUG: Diagnostic contains: dereferenced expression
    Object x = objArr[0];
  }

  static Object badMethodCondCheck(Inner i1, Inner i2) {
    if (i1.getF1() == null) {
      return new Object();
    }
    // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return
    // type
    return i2.getF1();
  }

  static void onlyReportOne(@Nullable Inner i1) {
    // BUG: Diagnostic contains: dereferenced expression
    i1.f2.hashCode();
    i1.f2.toString();
  }

  static void onlyReportOneInv(@Nullable Object o) {
    // BUG: Diagnostic contains: dereferenced expression
    o.toString();
    o.hashCode();
  }

  static void onlyReportOneArray(@Nullable Object[] arr) {
    // BUG: Diagnostic contains: dereferenced expression
    arr[3] = new Object();
    arr.toString();
  }

  static void onlyReportOneField(@Nullable Inner i) {
    // BUG: Diagnostic contains: dereferenced expression
    i.f1 = null;
    i.toString();
  }

  static void testCast(@Nullable Object o) {
    String x = (String) o;
    // BUG: Diagnostic contains: dereferenced expression
    x.toString();
  }

  interface I1 {

    Object returnToOverride();
  }

  static class Implementor implements I1 {

    @Nullable
    // BUG: Diagnostic contains: method returns @Nullable, but superclass method
    public Object returnToOverride() {
      return null;
    }
  }

  static class ClassWithConstructor {

    Object f;

    ClassWithConstructor(Object f) {
      this.f = f;
    }

    static ClassWithConstructor create() {
      // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
      return new ClassWithConstructor(null);
    }
  }

  static void unboxingTests() {
    Integer x = null;
    // BUG: Diagnostic contains: unboxing
    boolean b = (x == 0);
    Boolean y = null;
    // BUG: Diagnostic contains: unboxing
    boolean c = !y;
    Boolean z = null;
    // BUG: Diagnostic contains: unboxing
    int d = z ? 3 : 4;
    Integer w = null;
    // BUG: Diagnostic contains: unboxing
    int e = ~w;
  }

  static void unboxingTests2() {
    Boolean x = null;
    // BUG: Diagnostic contains: unboxing
    if (x) {
      return;
    }
    Boolean y = null;
    // BUG: Diagnostic contains: unboxing
    while (y) {
      return;
    }
    Boolean z = null;
    // BUG: Diagnostic contains: unboxing
    for (; z; ) {
      return;
    }
  }

  static void takeBool(boolean b) {}

  static void unboxingTest4() {
    Boolean b = null;
    // BUG: Diagnostic contains: unboxing
    takeBool(b);
  }

  static boolean unboxingTest5() {
    Boolean a = null;
    // BUG: Diagnostic contains: unboxing
    boolean x = a;
    boolean y = false;
    Boolean c = null;
    // BUG: Diagnostic contains: unboxing
    y = c;
    Boolean b = null;
    // BUG: Diagnostic contains: unboxing
    return b;
  }

  // Ternary expressions
  // From: https://github.com/google/error-prone/commit/cab5e57d5421cf70f29d231dbf704a864b4b70ae
  static void g(String s, int y) {}

  static void h(String s, int... y) {}

  static void f(boolean b) {
    // BUG: Diagnostic contains: unboxing
    int x = b ? 0 : null;
    // BUG: Diagnostic contains: unboxing
    long l = b ? null : 0;
    // BUG: Diagnostic contains: unboxing
    g("", b ? null : 0);
    // SHOULDBUSG: Diagnostic contains: unboxing
    // BUG: Diagnostic contains: passing @Nullable parameter 'b ? null : 0' where @NonNull is
    // required
    h("", 1, b ? null : 0);
    // SHOULDBUSG: Diagnostic contains: unboxing
    // BUG: Diagnostic contains: passing @Nullable parameter 'b ? null : 0' where @NonNull is
    // required
    h("", 1, b ? null : 0, 3);
    // BUG: Diagnostic contains: unboxing
    int z = 0 + (b ? null : 1);
    // BUG: Diagnostic contains: unboxing
    z = (b ? null : 1) + 0;
  }

  static class InitializerBlockCoreTests {

    String nonNullField = "";
    static String nonNullSField = "";

    {
      String s = "";
      if (true) {
        s = null;
      }
      // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
      nonNullField = s;
    }

    static {
      String s = "";
      if (true) {
        s = null;
      }
      // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
      nonNullSField = s;
    }
  }

  static class CFNullable {

    static int fizz(@org.checkerframework.checker.nullness.qual.Nullable String str) {
      // BUG: Diagnostic contains: dereferenced expression
      return str.hashCode();
    }

    static void fizz2(
        Object o, @org.checkerframework.checker.nullness.qual.Nullable Object p, Object q) {}

    static void caller() {
      // BUG: Diagnostic contains: passing @Nullable parameter 'null'
      fizz2(new Object(), new Object(), null);
    }

    Object retNonNull() {
      return new Object();
    }
  }

  static class CFExtends extends CFNullable {

    @Override
    @org.checkerframework.checker.nullness.qual.Nullable
    // BUG: Diagnostic contains: method returns @Nullable, but superclass
    Object retNonNull() {
      return null;
    }
  }

  static class TestAnon {

    TestAnon(Object p) {}
  }

  static TestAnon testAnon(@Nullable Object q) {
    // BUG: Diagnostic contains: passing @Nullable parameter 'q' where
    return new TestAnon(q) {};
  }

  static class TestAnon2<T> {

    TestAnon2(List<? extends T> q) {}
  }

  static <Q> TestAnon2<Q> testAnon2(@Nullable List<Q> q) {
    // BUG: Diagnostic contains: passing @Nullable parameter 'q' where
    return new TestAnon2<Q>(q) {};
  }

  static class TestAnon3 {

    TestAnon3(@Nullable Integer p) {}

    TestAnon3(String p) {}
  }

  static TestAnon3 testAnon3(@Nullable String q) {
    // BUG: Diagnostic contains: passing @Nullable parameter 'q' where
    return new TestAnon3(q) {};
  }
}
