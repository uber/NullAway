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
import com.uber.nullaway.testdata.unannotated.UnannotatedClass;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.checkerframework.checker.nullness.compatqual.NullableDecl;

public class NullAwayNegativeCases {

  public Object doesNotReturnNull() {
    return new Object();
  }

  public Object assignmentExpression() {
    Object x = null;
    return (x = new Object());
  }

  public void assignmentExpression2(@Nullable String[] a0) {
    String[] a1;
    Inner inner = new Inner();
    if (((a1 = a0) != null) && (a1.length > 0)) {}

    if ((null != (a1 = a0)) && (a1.length > 0)) {}

    if (((inner.f1 = a0) != null) && (((String[]) inner.f1).length > 0)) {}
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
    if (b == 0) {
      return b - a;
    } else if (b == 1) {
      return b % a;
    } else if (b == 2) {
      return b & a;
    } else if (b == 3) {
      return b | a;
    } else if (b == 4) {
      return b ^ a;
    } else if (b == 5) {
      return ~a;
    } else {
      return 10;
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

  static void checkNotNullWithMessage(@Nullable Object o) {
    Preconditions.checkNotNull(o, "should not be null");
    o.toString();
  }

  static void checkNotNullWithTemplateMessage(
      @Nullable Object o1,
      @Nullable Object o2,
      @Nullable Object o3,
      @Nullable Object o4,
      @Nullable Object o5,
      @Nullable Object o6,
      @Nullable Object o7,
      @Nullable Object o8,
      @Nullable Object o9,
      @Nullable Object o10,
      @Nullable Object o11,
      @Nullable Object o12,
      @Nullable Object o13,
      @Nullable Object o14,
      @Nullable Object o15,
      @Nullable Object o16,
      @Nullable Object o17,
      @Nullable Object o18,
      @Nullable Object o19,
      @Nullable Object o20,
      @Nullable Object o21,
      @Nullable Object o22,
      @Nullable Object o23) {
    Preconditions.checkNotNull(o1, "%s %s %s %s %s", "a", "b", "c", "d", "e");
    o1.toString();

    Preconditions.checkNotNull(o2, "%s", 'a');
    o2.toString();

    Preconditions.checkNotNull(o3, "%s", 1);
    o3.toString();

    Preconditions.checkNotNull(o4, "%s", 1L);
    o4.toString();

    Preconditions.checkNotNull(o5, "%s", "a");
    o5.toString();

    Preconditions.checkNotNull(o6, "%s %s", 'a', 'b');
    o6.toString();

    Preconditions.checkNotNull(o7, "%s %s", 'a', 1);
    o7.toString();

    Preconditions.checkNotNull(o8, "%s %s", 'a', 1L);
    o8.toString();

    Preconditions.checkNotNull(o9, "%s %s", 'a', "a");
    o9.toString();

    Preconditions.checkNotNull(o10, "%s %s", 1, 'a');
    o10.toString();

    Preconditions.checkNotNull(o11, "%s %s", 1, 1);
    o11.toString();

    Preconditions.checkNotNull(o12, "%s %s", 1, 1L);
    o12.toString();

    Preconditions.checkNotNull(o13, "%s %s", 1, "a");
    o13.toString();

    Preconditions.checkNotNull(o14, "%s %s", 1L, 'a');
    o14.toString();

    Preconditions.checkNotNull(o15, "%s %s", 1L, 1);
    o15.toString();

    Preconditions.checkNotNull(o16, "%s %s", 1L, 1L);
    o16.toString();

    Preconditions.checkNotNull(o17, "%s %s", 1L, "a");
    o17.toString();

    Preconditions.checkNotNull(o18, "%s %s", "a", 'a');
    o18.toString();

    Preconditions.checkNotNull(o19, "%s %s", "a", 1);
    o19.toString();

    Preconditions.checkNotNull(o20, "%s %s", "a", 1L);
    o20.toString();

    Preconditions.checkNotNull(o21, "%s %s", "a", "a");
    o21.toString();

    Preconditions.checkNotNull(o22, "%s %s %s", "a", "a", "a");
    o22.toString();

    Preconditions.checkNotNull(o23, "%s %s %s %s", "a", "a", "a", "a");
    o23.toString();
  }

  static void requireNonNull(@Nullable Object o, @Nullable Object p, @Nullable Object q) {
    Objects.requireNonNull(o);
    o.toString();
    Objects.requireNonNull(p, "should be non null");
    p.toString();
    Objects.requireNonNull(q, () -> "should be non null");
    q.toString();
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

  // see https://github.com/uber/NullAway/issues/39
  final class Container {
    private void sender() {
      int someValue = 0;
      receiver(-someValue);
      receiver(+someValue);
      receiver(++someValue);
      receiver(--someValue);
    }

    private void receiver(Integer i) {
      /* NOP */
    }
  }

  static class CFNullable {

    @org.checkerframework.checker.nullness.qual.Nullable String cfNullableString;

    CFNullable() {}

    static int fizz(@org.checkerframework.checker.nullness.qual.Nullable String str) {
      if (str != null) {
        return str.hashCode();
      }
      return 10;
    }

    static void bizz() {
      fizz(null);
    }

    @org.checkerframework.checker.nullness.qual.Nullable
    Object retNullMaybe() {
      return null;
    }
  }

  static class CFExtends extends CFNullable {

    @Override
    Object retNullMaybe() {
      return new Object();
    }
  }

  static class CFNullableDecl {

    @NullableDecl String cfNullableString;

    CFNullableDecl() {}

    static int fizz(@NullableDecl String str) {
      if (str != null) {
        return str.hashCode();
      }
      return 10;
    }

    static void bizz() {
      fizz(null);
    }

    @NullableDecl
    Object retNullMaybe() {
      return null;
    }
  }

  static class CFExtends2 extends CFNullableDecl {

    @Override
    Object retNullMaybe() {
      return new Object();
    }
  }

  static class ExcNullField {

    void foo() {
      UnannotatedClass e = new UnannotatedClass();
      // no error since not annotated
      e.maybeNull.hashCode();
      UnannotatedClass f = new UnannotatedClass();
      Object y = f.maybeNull;
      // no error since not annotated
      y.hashCode();
    }

    void weird() {
      Class klazz = int.class;
      klazz.hashCode();
    }
  }

  static class TestAnon {

    TestAnon(@Nullable Object p) {}
  }

  static TestAnon testAnon(@Nullable Object q) {
    return new TestAnon(q) {};
  }

  static class TestAnon2<T> {

    TestAnon2(@Nullable List<? extends T> q) {}
  }

  static <Q> TestAnon2<Q> testAnon2(@Nullable List<Q> q) {
    return new TestAnon2<Q>(q) {};
  }

  static class TestAnon3 {

    TestAnon3(@Nullable Integer p) {}

    TestAnon3(String p) {}
  }

  static TestAnon3 testAnon3(@Nullable Integer q) {
    return new TestAnon3(q) {};
  }

  // https://github.com/uber/NullAway/issues/104

  static class TwoParamIterator<T, R> implements Iterator<T> {
    @Override
    public boolean hasNext() {
      return false;
    }

    @Override
    public T next() {
      return (T) new Object();
    }
  }

  static class TwoParamCollection<T, R> implements Iterable<T> {
    @Override
    public TwoParamIterator<T, R> iterator() {
      return new TwoParamIterator<T, R>();
    }
  }

  static void testTwoParamIter() {
    TwoParamCollection<String, String> c = new TwoParamCollection<>();
    for (String s : c) {
      s.hashCode();
    }
  }

  static String boxAndDeref(Integer boxed) {
    return boxed.toString();
  }

  static String testNoCrashOnUnboxedShifts(int n) {
    Integer m = n << 2;
    String s = "";
    s += boxAndDeref(m);
    s += boxAndDeref(m <<= 2);
    s += boxAndDeref(n >>= 1);
    s += boxAndDeref(m >>>= 4);
    s += boxAndDeref(n >>> 3);
    s += boxAndDeref(~n);
    return s;
  }

  // Cases from issue https://github.com/uber/NullAway/issues/366
  class AnotherClass {
    class Inner {
      int x;
    }
  }

  AnotherClass.Inner anotherMethod() {
    AnotherClass anotherClass = new AnotherClass();
    return anotherClass.new Inner() {
      int getX() {
        return x;
      }
    };
  }

  class YetAnotherClass {
    class Inner {
      int x;

      Inner(int __x) {
        x = __x;
      }

      int getX() {
        return 3;
      }
    }
  }

  YetAnotherClass.Inner yetAnotherMethod() {
    YetAnotherClass yetAnotherClass = new YetAnotherClass();
    return yetAnotherClass.new Inner(4) {
      @Override
      int getX() {
        return 5;
      }
    };
  }
}
