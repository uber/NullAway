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

import static com.uber.nullaway.testdata.OtherStuff.OtherEnum.TOP;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class NullAwayNegativeCases {

  public Object doesNotReturnNull() {
    return new Object();
  }

  public Object assignmentExpression() {
    Object x = null;
    return (x = new Object());
  }

  @Nullable
  public Object returnsNullButAnnotatedWithNullable() {
    return null;
  }

  public Object methodWrapper() {
    return doesNotReturnNull();
  }

  static Object sField = new Object();

  public Object returnNonNullField() {
    return sField;
  }

  @Nullable static Object sField2 = null;

  public Object checkConditionWithLocal() {
    Object x = sField2;
    if (x != null) {
      return x;
    } else {
      return new Object();
    }
  }

  public Object checkConditionOnStatic() {
    if (sField2 != null) {
      return sField2;
    } else {
      return new Object();
    }
  }

  public Object checkConditionOnStatic2() {
    if (sField2 == null) {
      sField2 = new Object();
    }
    return sField2;
  }

  static class Inner2 {

    @Nullable Object f3 = null;

    @Nullable
    public Object getF3() {
      return f3;
    }
  }

  static class Inner {
    @Nullable Object f1 = null;
    Object f2 = new Object();
    Inner2 nested = new Inner2();

    public Inner2 getNested() {
      return nested;
    }

    public void safeWrite() {
      f2 = new Object();
    }

    @Nullable
    public Object getF1() {
      return f1;
    }

    public void checkCondOnThis() {
      if (this.f1 != null) {
        this.f1.toString();
      }
      if (f1 != null) {
        f1.toString();
      }
      if (this.f1 != null) {
        f1.toString();
      }
      if (f1 != null) {
        this.f1.toString();
      }
      if (this.f1 == null) {
      } else {
        this.f1.toString();
      }
      if (null != f1) {
        this.f1.toString();
      }
      if (null == f1) {
      } else {
        f1.toString();
      }
      Object l = this.f1;
      if (null != l) {
        l.toString();
      }
      if (null != this.getF1()) {
        this.getF1().toString();
      }
    }

    public void checkCondOnThis2() {
      if (this.f1 == null) {
        return;
      }
      this.f1.toString();
    }

    public void checkCondOnThis3() {
      if (this.f1 == null) {
        throw new RuntimeException();
      }
      this.f1.toString();
    }

    public void checkCondOnThis4() {
      if (this.f2 == null || this.f1 == null) {
        return;
      }
      this.f1.toString();
    }

    public void checkCondOnThis5() {
      if (this.f2 == null && this.f1 != null) {
        this.f1.toString();
      }
    }

    public void checkCondOnThis6() {
      if (!(this.f1 == null)) {
        this.f1.toString();
      }
    }

    public Object lazyInit() {
      if (f1 == null) {
        f1 = new Object();
      }
      return f1;
    }

    public Object lazyInit2() {
      if (f1 == null) {
        f1 = annotatedReturn();
      }
      return f1;
    }

    public Object condExprVar() {
      Object x = this.f1;
      return (x == null) ? new Object() : x;
    }

    public Object condExpFields() {
      return (this.f1 == null) ? new Object() : this.f1;
    }

    public Object methodInvokeCheckOnThis() {
      if (getF1() == null) {
        return new Object();
      }
      return this.getF1();
    }

    public Object methodInvokeCheckOnThis2() {
      if (this.getF1() == null) {
        return new Object();
      }
      return getF1();
    }

    public Object methodInvokeCheckOnThis3() {
      if (this.getF1() == null) {
        return new Object();
      }
      Object x = getF1();
      return x;
    }

    public void nonNullParam(Object y) {
      this.f1 = y;
      this.f1.toString();
    }

    public void fieldNullWithCond(boolean b) {
      if (f1 == null) return;
      if (b) {
        f1.toString();
      } else {
        f1.hashCode();
      }
    }

    public void nested1() {
      if (getNested().getF3() != null) {
        getNested().getF3().toString();
      }
      if (nested.f3 != null) {
        nested.f3.toString();
      }
      if (this.getNested().f3 != null) {
        this.getNested().f3.toString();
      }
    }

    public static void doNothing() {}

    public void methodToOverride(Object o1) {}

    @Nullable
    public Object returnToOverride() {
      return null;
    }
  }

  static class InnerSub extends Inner {
    // ok to make parameter @Nullable when @NonNull in superclass
    @Override
    public void methodToOverride(@Nullable Object o1) {}

    // ok to return @NonNull when @Nullable in superclass
    @Override
    public Object returnToOverride() {
      return new Object();
    }
  }

  public Object methodInvokeCheck() {
    Inner i1 = new Inner();
    if (i1.getF1() == null) {
      return new Object();
    }
    return i1.getF1();
  }

  public void staticInvoke() {
    Inner.doNothing();
  }

  public Object checkNonNullInstanceField() {
    Inner x = new Inner();
    return x.f2;
  }

  public Object checkLocalStrongUpdate() {
    Object x = null;
    x = new Object();
    Object y = x;
    return y;
  }

  public void nonNullParam(Object x) {}

  public void callNonNull() {
    nonNullParam(new Object());
  }

  public void nonNullTwoParams(@Nullable Object x, Object y) {}

  public void callNonNullTwoParams() {
    nonNullTwoParams(null, new Object());
    nonNullTwoParams(new Object(), new Object());
  }

  public static void staticMultiParam(Object x, @Nullable Object y, Object z) {}

  public void callStaticMulti() {
    staticMultiParam(new Object(), new Object(), new Object());
    staticMultiParam(new Object(), null, new Object());
  }

  static void invokeOnNullWithCheck(@Nullable Object p) {
    if (p != null) {
      p.toString();
    }
  }

  static void derefWithCheck(@Nullable Inner i1) {
    if (i1 != null) {
      Object x = i1.f1;
    }
  }

  static void derefWithCheck2(Inner i1) {
    Object x = i1.f1;
    if (x != null) {
      x.toString();
    }
    i1.f2.toString();
  }

  static Object multiDimArrayDeref(Object[][] multiDim) {
    Object[] first = multiDim[0];
    // NOTE: if we were being sound, I think this should be
    // an error.  Keeping this as a test just to detect
    // changes in behavior
    (new Inner()).f2 = multiDim[0];
    Object x = first[0];
    return x;
  }

  static boolean invokeLibMethod() {
    Object x = null;
    Object y = new Object();
    return y.equals(x);
  }

  static String invokeLibMethod2() {
    Object x = null;
    List<Object> l = new ArrayList<>();
    l.add(x);
    return l.get(0).toString();
  }

  static void primArg(int x) {}

  static void callPrimArg() {
    primArg(10);
  }

  enum TestEnum {
    VAL1,
    VAL2
  };

  static TestEnum getOne() {
    return TestEnum.VAL1;
  }

  static void nonNullAnnot(@Nonnull Object o) {
    o.toString();
    annotatedReturn().toString();
  }

  @Nonnull
  static Object annotatedReturn() {
    return new Object();
  }

  static void nonNullStrParam(@Nonnull String foo) {}

  static void callWithLibResult() {
    nonNullStrParam(String.format("%s", "hello"));
  }

  enum FooEnum {
    FIRST,
    SECOND
  };

  static void knownNonNull() {
    nonNullAnnot(List.class);
    nonNullAnnot(FooEnum.FIRST.toString());
  }

  static Map.Entry qualifiedClassRef() {
    return new Map.Entry() {
      @Override
      public Object getKey() {
        return new Object();
      }

      @Override
      public Object getValue() {
        return new Object();
      }

      @Override
      public Object setValue(Object value) {
        return new Object();
      }
    };
  }

  static String fromAnnotation() {
    return TestAnnot.TEST_STR;
  }

  static Void testVoidType() {
    return null;
  }

  static String stringConcat() {
    String x = "hello ";
    String y = "world";
    return x + y;
  }

  static Object parenthesized() {
    return (new Object());
  }

  static Object casts(boolean b) {
    if (b) {
      return (Object) ("hello");
    } else {
      return (Object) String.class;
    }
  }

  static Boolean boxing(boolean d) {
    boolean a = !d;
    // nonsense
    if (d) {
      return a && d;
    } else if (!a) {
      return !d || a;
    } else {
      return !a;
    }
  }

  static Number moreBoxing() {
    int a = 3, b = 5;
    if (b > 7) {
      return b - a;
    } else {
      return b % a;
    }
  }

  static Object testStaticImport() {
    if (TOP != null) {
      return TOP;
    }
    return new Object();
  }

  static void outer(final Object o1) {
    Runnable r =
        new Runnable() {
          @Override
          public void run() {
            o1.toString();
          }
        };
    r.run();
  }

  static void iofcheck(@Nullable Object o, Inner i1) {
    if (o instanceof String) {
      o.hashCode();
    }
    if (i1.f1 instanceof String) {
      i1.f1.toString();
    }
    if (!(i1.getF1() instanceof String)) {
      return;
    }
    i1.getF1().toString();
  }

  static void checkNotNull(@Nullable Object o) {
    Preconditions.checkNotNull(o);
    o.toString();
  }

  static void isEmpty(@Nullable String s) {
    if (!Strings.isNullOrEmpty(s)) {
      s.hashCode();
    }
  }

  static void checkConditionWithInstanceField() {
    Inner x = new Inner();
    if (x.f1 != null) {
      x.f1.toString();
    }
    if (x.nested.getF3() != null) {
      x.nested.getF3().toString();
    }
  }

  static void tryException(@Nullable Exception exc) {
    try {
      if (exc != null) {
        throw exc;
      }
    } catch (Exception e) {
      e.toString();
    }
  }

  static Object tryFinally() {
    Object x = new Object();
    try {
      return new Object();
    } finally {
      if (x != null) {
        return x;
      }
    }
  }

  static class ClassWithConstructor {

    @Nullable Object f;
    Object g;

    ClassWithConstructor(@Nullable Object f, Object g) {
      this.f = f;
      this.g = g;
    }

    static ClassWithConstructor create() {
      return new ClassWithConstructor(null, new Object());
    }
  }

  static void checkAtomicRef() {
    Object x = new Object();
    AtomicReference<Object> ref = new AtomicReference<>(x);
    if (ref.get() != null) {
      ref.get().hashCode();
    }
  }

  @Nullable private String s;

  String getStr() {
    return Strings.isNullOrEmpty(s) ? "hello" : s;
  }

  static void callArrayClone(Object[] arr) {
    arr.clone();
  }

  @Nullable
  static Boolean noUnboxingTests() {
    Boolean b = null;
    Boolean c = b;
    Object o = c;
    Boolean d = (Boolean) o;
    return d;
  }

  static class NullableContainer {
    @Nullable private Object ref;

    public NullableContainer() {
      ref = null;
    }

    @Nullable
    public Object get() {
      return ref;
    }

    public void set(Object o) {
      ref = o;
    }
  }

  @Nullable private NullableContainer nullableNullableContainer;

  Object safelyGetContainerRef() {
    Object saferef =
        (nullableNullableContainer != null && nullableNullableContainer.get() != null)
            ? nullableNullableContainer.get()
            : new Object();
    return saferef;
  }
  // Ternary expressions
  // From: https://github.com/google/error-prone/commit/cab5e57d5421cf70f29d231dbf704a864b4b70ae
  static void g(String s, int y) {}

  static void h(String s, int... y) {}

  static void f(boolean b) {
    int x = b ? 0 : 1;
    Integer y = b ? 0 : null;
    g("", b ? 1 : 0);
    h("", 1, b ? 1 : 0);
    h("", 1, b ? 1 : 0, 3);
    int z = 0 + (b ? 0 : 1);
    boolean t = Integer.valueOf(0) == (b ? 0 : null);
    t = (b ? 0 : null) == Integer.valueOf(0);
  }
}
