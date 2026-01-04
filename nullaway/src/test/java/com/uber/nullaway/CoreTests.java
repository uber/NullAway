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

import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link com.uber.nullaway.NullAway}. */
@RunWith(JUnit4.class)
public class CoreTests extends NullAwayTestsBase {

  @Test
  public void coreNullabilityPositiveCases() {
    defaultCompilationHelper
        .addSourceLines(
            "NullAwayPositiveCases.java",
            """
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
                        }""")
        .doTest();
  }

  @Test
  public void nullabilityAnonymousClass() {
    defaultCompilationHelper
        .addSourceLines(
            "NullAwayAnonymousClass.java",
            """
            package com.uber.nullaway.testdata;

            import javax.annotation.Nullable;

            public class NullAwayAnonymousClass {

              static class Inner {

                void takeParams(Object o1, Object o2) {}
              }

              static void anonymousClass(final Inner x) {

                Runnable r =
                    new Runnable() {

                      // BUG: Diagnostic contains: @NonNull field NullAwayAnonymousClass$1.f not initialized
                      private Object f;

                      @Override
                      public void run() {
                        Object y = new Object();
                        // BUG: Diagnostic contains: passing @Nullable parameter 'create()' where @NonNull is
                        // required
                        x.takeParams(y, create());
                      }

                      @Nullable
                      private Object create() {
                        return new Object();
                      }
                    };
              }
            }
            """)
        .doTest();
  }

  @Test
  public void coreNullabilityNegativeCases() {
    defaultCompilationHelper
        .addSourceLines(
            "NullAwayNegativeCases.java",
            """
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
                }""")
        .addSourceLines(
            "OtherStuff.java",
            """
                package com.uber.nullaway.testdata;

                public class OtherStuff {

                  public static enum OtherEnum {
                    TOP,
                    BOTTOM
                  }
                }
                """)
        .addSourceLines(
            "TestAnnot.java",
            """
            package com.uber.nullaway.testdata;
            import static java.lang.annotation.RetentionPolicy.CLASS;
            import java.lang.annotation.Retention;
            @Retention(CLASS)
            public @interface TestAnnot {
              String TEST_STR = "test_str";
            }
            """)
        .addSourceLines(
            "UnannotatedClass.java",
            """
                    package com.uber.nullaway.testdata.unannotated;
                    import javax.annotation.Nullable;
                    public class UnannotatedClass {
                      private Object field;
                      @Nullable public Object maybeNull;
                      // should get no initialization error
                      public UnannotatedClass() {}
                      /**
                       * This is an identity method, without Nullability annotations.
                       *
                       * @param x
                       * @return
                       */
                      public static Object foo(Object x) {
                        return x;
                      }

                      /**
                       * This invokes foo() with null, with would not be allowed in an annotated package.
                       *
                       * @return
                       */
                      public static Object bar() {
                        return foo(null);
                      }
                    }
                    """)
        .doTest();
  }

  @Test
  public void assertSupportPositiveCases() {
    defaultCompilationHelper
        .addSourceLines(
            "CheckAssertSupportPositiveCases.java",
            """
                package com.uber.nullaway.testdata;

                import javax.annotation.Nullable;

                public class CheckAssertSupportPositiveCases {

                  class T1 {
                    @Nullable Object obj;
                  }

                  void someMethod() {
                    T1 t1 = new T1();
                    assert t1.obj == null;
                    // BUG: Diagnostic contains: dereferenced expression
                    t1.obj.toString();
                  }

                  void someMethod2() {
                    T1 t1 = new T1();
                    assert t1 != null;
                    // BUG: Diagnostic contains: dereferenced expression
                    t1.obj.toString();
                  }
                }""")
        .doTest();
  }

  @Test
  public void assertSupportNegativeCases() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AssertsEnabled=true"))
        .addSourceLines(
            "CheckAssertSupportNegativeCases.java",
            """
                package com.uber.nullaway.testdata;

                import javax.annotation.Nullable;

                public class CheckAssertSupportNegativeCases {

                  class T1 {
                    @Nullable Object obj;
                  }

                  void someMethod() {

                    @Nullable Object x = null;

                    T1 t1 = new T1();
                    assert t1.obj != null;
                    t1.obj.toString();

                    T2 t2 = new T2();
                    assert t2.f() != null;
                    assert t2.f().g() != null;
                    t2.f().g().toString();

                    assert x != null;
                    x.toString();
                  }

                  class T2 {
                    @Nullable
                    T3 f() {
                      return new T3();
                    }
                  }

                  class T3 {
                    @Nullable
                    Object g() {
                      return null;
                    }
                  }
                }""")
        .doTest();
  }

  @Test
  public void testGenericAnonymousInner() {
    defaultCompilationHelper
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
  public void erasedIterator() {
    // just checking for crash
    defaultCompilationHelper
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
    defaultCompilationHelper
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
    defaultCompilationHelper
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
  public void unboxingWhenCallingUnmarked() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "class Test {",
            "  @NullUnmarked",
            "  static void foo(int x) {}",
            "  void bar() {",
            "    Integer y = null;",
            "    // BUG: Diagnostic contains: unboxing of a @Nullable value",
            "    foo(y);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void arrayAccessDataflowTest() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  static class Foo {",
            "    @Nullable String f;",
            "  }",
            "  static Foo[] arr = new Foo[10];",
            "  static void fizz() {",
            "    int i = 0;",
            "    if (arr[i].f != null) {",
            "      //TODO: This should raise an error in non-JSpecify mode",
            "      arr[i].f.toString();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void cfNullableArrayField() {
    defaultCompilationHelper
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
  public void switchOnNullable() {
    defaultCompilationHelper
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
  public void testCastToNonNull() {
    defaultCompilationHelper
        .addSourceLines(
            "Util.java",
            """
                package com.uber.nullaway.testdata;

                import javax.annotation.Nullable;

                public class Util {

                  public static <T> T castToNonNull(@Nullable T x) {
                    if (x == null) {
                      throw new RuntimeException();
                    }
                    return x;
                  }

                  public static <T> T castToNonNull(@Nullable T x, String msg) {
                    if (x == null) {
                      throw new RuntimeException(msg);
                    }
                    return x;
                  }

                  public static <T> T castToNonNull(String msg, @Nullable T x, int counter) {
                    // counter is needed to distinguish this method from the previous one when T == String
                    if (x == null) {
                      throw new RuntimeException(msg);
                    }
                    return x;
                  }

                  public static <T> T id(T x) {
                    return x;
                  }
                }""")
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
  public void testCastToNonNullExtraArgsWarning() {
    defaultCompilationHelper
        .addSourceLines(
            "Util.java",
            """
                package com.uber.nullaway.testdata;

                import javax.annotation.Nullable;

                public class Util {

                  public static <T> T castToNonNull(@Nullable T x) {
                    if (x == null) {
                      throw new RuntimeException();
                    }
                    return x;
                  }

                  public static <T> T castToNonNull(@Nullable T x, String msg) {
                    if (x == null) {
                      throw new RuntimeException(msg);
                    }
                    return x;
                  }

                  public static <T> T castToNonNull(String msg, @Nullable T x, int counter) {
                    // counter is needed to distinguish this method from the previous one when T == String
                    if (x == null) {
                      throw new RuntimeException(msg);
                    }
                    return x;
                  }

                  public static <T> T id(T x) {
                    return x;
                  }
                }""")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "import static com.uber.nullaway.testdata.Util.castToNonNull;",
            "class Test {",
            "  Object test1(Object o) {",
            "    // BUG: Diagnostic contains: passing known @NonNull parameter 'o' to CastToNonNullMethod",
            "    return castToNonNull(o, \"o should be @Nullable but never actually null\");",
            "  }",
            "  Object test2(Object o) {",
            "    // BUG: Diagnostic contains: passing known @NonNull parameter 'o' to CastToNonNullMethod",
            "    return castToNonNull(\"o should be @Nullable but never actually null\", o, 0);",
            "  }",
            "  Object test3(@Nullable Object o) {",
            "    // Expected use of cast",
            "    return castToNonNull(o, \"o should be @Nullable but never actually null\");",
            "  }",
            "  Object test4(@Nullable Object o) {",
            "    // Expected use of cast",
            "    return castToNonNull(o, \"o should be @Nullable but never actually null\");",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testReadStaticInConstructor() {
    defaultCompilationHelper
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
    makeTestHelperWithArgs(
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
    makeTestHelperWithArgs(
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
    defaultCompilationHelper
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
  public void testCapturingScopes() {
    defaultCompilationHelper
        .addSourceLines(
            "CapturingScopes.java",
            """
            package com.uber.nullaway.testdata;

            import java.util.HashMap;
            import java.util.function.Function;
            import javax.annotation.Nullable;

            public class CapturingScopes {

              static class TestSimple {
                public Runnable outer(@Nullable Object o, int p) {
                  final Object outerVar = o;
                  if (p > 0) {
                    return (new Runnable() {
                      // BUG: Diagnostic contains: assigning @Nullable
                      final Object someField = outerVar;

                      @Override
                      public void run() {
                        // BUG: Diagnostic contains: dereferenced expression outerVar
                        System.out.println(outerVar.toString());
                      }
                    });
                  } else {
                    return () -> {
                      // BUG: Diagnostic contains: dereferenced expression outerVar
                      outerVar.hashCode();
                    };
                  }
                }
              }

              static class TestShadowing {

                public void outer(@Nullable Object o, Object p) {
                  final Object var1 = o;
                  final Object var2 = p;
                  final Object var3 = o;
                  class Local {
                    Object var1 = new Object();
                    @Nullable Object var2 = null;

                    void foo() {
                      // safe
                      var1.toString();
                      // BUG: Diagnostic contains: dereferenced expression var2
                      var2.hashCode();
                      // BUG: Diagnostic contains: dereferenced expression var3
                      var3.hashCode();
                    }
                  }
                }
              }

              static class TestDoubleNesting {

                public void outer(@Nullable Object o) {
                  Object var1 = o;
                  class Local1 {
                    void foo() {
                      Object var2 = o;
                      class Local2 {
                        void bar() {
                          // BUG: Diagnostic contains: dereferenced expression var1
                          var1.hashCode();
                          // BUG: Diagnostic contains: dereferenced expression var2
                          var2.hashCode();
                        }
                      }
                    }
                  }
                }
              }

              static class TestDoubleNestingLambda {

                public void outer() {
                  Function<String, String> f =
                      (x) -> {
                        Object o = null;
                        // BUG: Diagnostic contains: dereferenced expression o
                        Function<String, String> g = (y) -> x.toString() + o.toString() + y.toString();
                        return g.apply("hello");
                      };
                  f.apply("bye");
                }
              }

              static class TestDoubleBraceInit {

                public void outer(@Nullable Object o) {
                  HashMap<String, String> map =
                      new HashMap<String, String>() {
                        {
                          // BUG: Diagnostic contains: dereferenced expression o is @Nullable
                          put("hi", o.toString());
                        }
                      };
                }
              }

              static class TestNegative {

                public void outer(Object o) {
                  class Local {

                    String foo() {
                      return o.toString();
                    }
                  }
                  Function<String, String> f = (x) -> x.toString() + o.toString();
                }
              }

              static class NestedClassInAnonymous {

                public void outer(@Nullable Object o) {
                  Runnable r =
                      new Runnable() {

                        class Inner {
                          void foo() {
                            // BUG: Diagnostic contains: dereferenced expression o is @Nullable
                            o.toString();
                          }
                        }

                        @Override
                        public void run() {
                          (new Inner()).foo();
                        }
                      };
                  r.run();
                }
              }
            }""")
        .doTest();
  }

  @Test
  public void testEnhancedFor() {
    defaultCompilationHelper
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
  public void testMapWithCustomPut() { // See https://github.com/uber/NullAway/issues/389
    defaultCompilationHelper
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
  public void derefNullableTernary() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "public class Test {",
            "  public void derefTernary(boolean b) {",
            "    Object o1 = null, o2 = new Object();",
            "    // BUG: Diagnostic contains: dereferenced expression (b ? o1 : o2) is @Nullable",
            "    (b ? o1 : o2).toString();",
            "    // BUG: Diagnostic contains: dereferenced expression (b ? o2 : o1) is @Nullable",
            "    (b ? o2 : o1).toString();",
            "    // This case is safe",
            "    (b ? o2 : o2).toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testCustomNullableAnnotation() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:CustomNullableAnnotations=qual.Null"))
        .addSourceLines("qual/Null.java", "package qual;", "public @interface Null {", "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import qual.Null;",
            "class Test {",
            "   @Null Object foo;", // No error, should detect @Null
            "   @Null Object baz(){",
            "     bar(foo);",
            "     return null;", // No error, should detect @Null
            "   }",
            "   String bar(@Null Object item){",
            "     // BUG: Diagnostic contains: dereferenced expression item is @Nullable",
            "     return item.toString();",
            "   }",
            "}")
        .doTest();
  }

  @Test
  public void testCustomNonnullAnnotation() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:UnannotatedClasses=com.uber.Other",
                "-XepOpt:NullAway:CustomNonnullAnnotations=qual.NoNull",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines("qual/NoNull.java", "package qual;", "public @interface NoNull {", "}")
        .addSourceLines(
            "Other.java",
            "package com.uber;",
            "import qual.NoNull;",
            "public class Other {",
            "   void bar(@NoNull Object item) { }",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "   Other other = new Other();",
            "   void foo(){",
            "     // BUG: Diagnostic contains: passing @Nullable parameter 'null'",
            "     other.bar(null);",
            "   }",
            "}")
        .doTest();
  }

  @Test
  public void testMapGetChainWithCast() {
    defaultCompilationHelper
        .addSourceLines(
            "Constants.java",
            "package com.uber;",
            "public class Constants {",
            "   public static final String KEY_1 = \"key1\";",
            "   public static final String KEY_2 = \"key2\";",
            "   public static final String KEY_3 = \"key3\";",
            "}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Map;",
            "class Test {",
            "   boolean withoutCast(Map<String, Map<String, Map<String, Object>>> topLevelMap){",
            "     return topLevelMap.get(Constants.KEY_1) == null ",
            "       || topLevelMap.get(Constants.KEY_1).get(Constants.KEY_2) == null",
            "       || topLevelMap.get(Constants.KEY_1).get(Constants.KEY_2).get(Constants.KEY_3) == null;",
            "   }",
            "   boolean withCast(Map<String, Object> topLevelMap){",
            "     return topLevelMap.get(Constants.KEY_1) == null ",
            "       || ((Map<String,Object>) topLevelMap.get(Constants.KEY_1)).get(Constants.KEY_2) == null",
            "       || ((Map<String,Object>) ",
            "              ((Map<String,Object>) topLevelMap.get(Constants.KEY_1)).get(Constants.KEY_2))",
            "                .get(Constants.KEY_3) == null;",
            "   }",
            "}")
        .doTest();
  }

  @Test
  public void testMapPutAndPutIfAbsent() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Map;",
            "class Test {",
            "   Object testPut(String key, Object o, Map<String, Object> m){",
            "     m.put(key, o);",
            "     return m.get(key);",
            "   }",
            "   Object testPutIfAbsent(String key, Object o, Map<String, Object> m){",
            "     m.putIfAbsent(key, o);",
            "     return m.get(key);",
            "   }",
            "}")
        .doTest();
  }

  @Test
  public void testMapComputeIfAbsent() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Map;",
            "import java.util.function.Function;",
            // Need JSpecify (vs javax) for annotating generics
            "import org.jspecify.annotations.Nullable;",
            "class Test {",
            "   Object testComputeIfAbsent(String key, Function<String, Object> f, Map<String, Object> m){",
            "     m.computeIfAbsent(key, f);",
            "     return m.get(key);",
            "   }",
            "   Object testComputeIfAbsentLambda(String key, Map<String, Object> m){",
            "     m.computeIfAbsent(key, k -> k);",
            "     return m.get(key);",
            "   }",
            "   Object testComputeIfAbsentNull(String key, Function<String, @Nullable Object> f, Map<String, Object> m){",
            "     m.computeIfAbsent(key, f);",
            "     // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type",
            "     return m.get(key);",
            "   }",
            "   // ToDo: should error somewhere, but doesn't, due to limited checking of generics",
            "   Object testComputeIfAbsentNullLambda(String key, Map<String, Object> m){",
            "     m.computeIfAbsent(key, k -> null);",
            "     return m.get(key);",
            "   }",
            "}")
        .doTest();
  }

  @Test
  public void testMapWithMapGetKey() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Map;",
            "import java.util.function.Function;",
            "class Test {",
            "   String testMapWithMapGetKey(Map<String,String> m1, Map<String,String> m2) {",
            "     if (m1.containsKey(\"s1\")) {",
            "       if (m2.containsKey(m1.get(\"s1\"))) {",
            "         return m2.get(m1.get(\"s1\")).toString();",
            "       }",
            "     }",
            "     return \"no\";",
            "   }",
            "}")
        .doTest();
  }

  @Test
  public void tryFinallySupport() {
    defaultCompilationHelper
        .addSourceLines(
            "NullAwayTryFinallyCases.java",
            """
            package com.uber.nullaway.testdata;

            import java.io.IOException;
            import java.util.stream.Stream;
            import javax.annotation.Nullable;

            // This code tests our, admitedly quite unsound, handling of try{ ... }finally{ ... } blocks.
            public class NullAwayTryFinallyCases {

              public void tryFinallyMininal(@Nullable Object o) {
                try {
                } finally {
                  // BUG: Diagnostic contains: dereferenced expression
                  System.out.println(o.toString());
                }
              }

              public void tryFinallyMininalRet(@Nullable Object o) {
                try {
                  return;
                } finally {
                  // BUG: Diagnostic contains: dereferenced expression
                  System.out.println(o.toString());
                }
              }

              public void tryFinallyMininalThrow(@Nullable Object o) {
                try {
                  throw new Error();
                } finally {
                  // BUG: Diagnostic contains: dereferenced expression
                  System.out.println(o.toString());
                }
              }

              public void tryFinallyThrowWFix(@Nullable Object o) {
                try {
                  o = new Object();
                  throw new Error();
                } finally {
                  /// ToDo: This could potentially be safe, or, pedantically, we could be assuming an
                  // OutOfMemoryError is possible
                  /// and thus o might be uninitialized. Consider ignoring unchecked exceptions in the CFG
                  // somehow.
                  // BUG: Diagnostic contains: dereferenced expression
                  System.out.println(o.toString()); // Safe(?)
                }
              }

              public void tryFinallyThrowWFix2(@Nullable Object o) {
                o = new Object();
                try {
                  throw new Error();
                } finally {
                  System.out.println(o.toString()); // Safe.
                }
              }

              public void tryFinallyPassThrough(@Nullable Object o) {
                try {
                  o = new Object();
                } finally {
                  /// ToDo: This could potentially be safe, or, pedantically, we could be assuming an
                  // OutOfMemoryError is possible
                  /// and thus o might be uninitialized. Consider ignoring unchecked exceptions in the CFG
                  // somehow.
                  // BUG: Diagnostic contains: dereferenced expression
                  System.out.println(o.toString()); // Safe(?)
                }
              }

              public void tryCatchMininal(@Nullable Object o) {
                try {
                } catch (Exception e) {
                  System.out.println(o.toString()); // Can't happen. Safe.
                }
              }

              public void tryCatchMininalRet(@Nullable Object o) {
                try {
                  return;
                } catch (Exception e) {
                  System.out.println(o.toString()); // Can't happen. Safe.
                }
              }

              public void tryCatchMininalThrow(@Nullable Object o) {
                try {
                  throw new Error();
                } catch (Exception e) {
                  // BUG: Diagnostic contains: dereferenced expression
                  System.out.println(o.toString());
                }
              }

              public void derefOnFinallyReturn(@Nullable Object o) {
                try {
                  if (o == null) {
                    return;
                  }
                  System.out.println(o.toString()); // Safe
                } finally {
                  // BUG: Diagnostic contains: dereferenced expression
                  System.out.println(o.toString());
                }
              }

              public void derefOnFinallyThrow(@Nullable Object o) {
                try {
                  if (o == null) {
                    throw new Error();
                  }
                  System.out.println(o.toString()); // Safe
                } finally {
                  // BUG: Diagnostic contains: dereferenced expression
                  System.out.println(o.toString());
                }
              }

              public void derefOnFinallyThrowFixBefore(@Nullable Object o) {
                try {
                  if (o == null) {
                    o = new Object();
                    throw new Error();
                  }
                  System.out.println(o.toString()); // Safe
                } finally {
                  /// ToDo: This should be safe
                  // BUG: Diagnostic contains: dereferenced expression
                  System.out.println(o.toString()); // Safe
                }
              }

              public void derefOnFinallySafeOnBothPaths(@Nullable Object o) {
                try {
                  if (o == null) {
                    throw new Exception();
                  }
                  System.out.println(o.toString()); // Safe
                } catch (Exception e) {
                  o = new Object();
                } finally {
                  /// ToDo: This should be safe
                  // BUG: Diagnostic contains: dereferenced expression
                  System.out.println(o.toString());
                }
              }

              private void doesNotThrowException() {
                System.out.println("No-op");
              }

              private void throwsException() {
                throw new Error();
              }

              public void derefOnCatchSafe(@Nullable Object o) {
                try {
                  if (o == null) {
                    doesNotThrowException();
                    return;
                  }
                } catch (Exception e) {
                  // No interproc, it believes doesNotThrowException() can throw an exception.
                  // BUG: Diagnostic contains: dereferenced expression
                  System.out.println(o.toString());
                }
              }

              public void derefOnCatchUnsafe(@Nullable Object o) {
                try {
                  if (o == null) {
                    throwsException();
                    return;
                  }
                } catch (Exception e) {
                  // BUG: Diagnostic contains: dereferenced expression
                  System.out.println(o.toString());
                }
              }

              public void tryMultipleCatch(@Nullable Object o) {
                try {
                  if (o == null) {
                    throw new IOException();
                  } else {
                    throw new Exception();
                  }
                } catch (IOException ioe) {
                  return;
                } catch (Exception e) {
                  /// ToDo: This should be safe, because the previous handler executes instead of this one.
                  // BUG: Diagnostic contains: dereferenced expression
                  System.out.println(o.toString());
                }
              }

              public void tryWithResourcesNoBlock() {
                // Just check that this doesn't blow up
                try (Stream<Integer> stream = Stream.of(1, 2, 3)) {}
              }

              class Initializers {

                Object f;
                Object g;

                Initializers() {
                  f = new Object();
                  try {
                    g = new Object();
                  } finally {
                    System.out.println("No-op");
                  }
                }

                Initializers(Object o1) {
                  f = new Object();
                  try {
                    g = new Object();
                  } finally {
                    System.out.println("No-op");
                  }
                  g = new Object(); // This is ok, because we are ignoring the actual exceptional exit from the
                  // method... mmh
                }

                Initializers(Object o1, Object o2) {
                  f = new Object();
                  try {
                    throwsException(); // Even ok with this call, since we are intra-proc.
                    g = new Object();
                  } finally {
                    System.out.println("No-op");
                  }
                  g = new Object(); // This is ok, because we are ignoring the actual exceptional exit from the
                  // method... mmh
                }

                // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field g (line 206)
                // is
                // initialized
                Initializers(Object o1, Object o2, Object o3) {
                  f = new Object();
                  try {
                    if (o1 == o2) {
                      throw new Exception();
                    }
                    g = new Object();
                  } catch (Exception e) {
                    return; // g might not have been initialized here
                  } finally {
                    System.out.println("No-op");
                  }
                  g = new Object(); // This is ok, because we are ignoring the actual exceptional exit from the
                  // method... mmh
                }

                Initializers(Object o1, Object o2, Object o3, Object o4) {
                  f = new Object();
                  try {
                    if (o1 == o2) {
                      throw new Exception();
                    }
                    g = new Object();
                  } catch (Exception e) {
                    g = new Object();
                    return;
                  } finally {
                    System.out.println("No-op");
                  }
                  g = new Object(); // This is ok, because we are ignoring the actual exceptional exit from the
                  // method... mmh
                }

                Initializers(Object o1, Object o2, Object o3, Object o4, Object o5) {
                  f = new Object();
                  try {
                    if (o1 == o2) {
                      throw new Exception();
                    }
                    g = new Object();
                  } catch (Exception e) {
                    g = new Object();
                    return;
                  } finally {
                    System.out.println("No-op");
                  }
                }

                Initializers(Object o1, Object o2, Object o3, Object o4, Object o5, Object o6) {
                  f = new Object();
                  try {
                    if (o1 == o2) {
                      throw new Error();
                    }
                  } finally {
                    g = new Object();
                  }
                }

                Initializers(
                    Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8) {
                  f = new Object();
                  try {
                    if (o1 == o2) {
                      throw new Error();
                    }
                    return;
                  } finally {
                    g = new Object();
                  }
                }

                Initializers(
                    Object o1,
                    Object o2,
                    Object o3,
                    Object o4,
                    Object o5,
                    Object o6,
                    Object o7,
                    Object o8,
                    Object o9) {
                  f = new Object();
                  try {
                    if (o1 == o2) {
                      throw new Error();
                    }
                    g = new Object(); // This works
                    return;
                  } finally {
                    g = new Object();
                  }
                }
              }

              private boolean nestedCFGConstructionTest(Object o) {
                boolean result = true;
                java.io.BufferedWriter out = null;
                try {
                  try {
                  } finally {
                    out = new java.io.BufferedWriter(new java.io.OutputStreamWriter(System.err));
                  }
                  if (o != null) {
                    out.write(' ');
                  }
                } catch (Exception e) {
                } finally {
                }
                return result;
              }

              private boolean nestedCFGConstructionTest2() throws IOException {
                java.io.BufferedWriter out =
                    new java.io.BufferedWriter(new java.io.OutputStreamWriter(System.err));
                try {
                  try {
                    return true;
                  } finally {
                  }
                } finally {
                  out.write(' ');
                  out.close();
                }
              }

              class IndirectInitialization {
                Object f;

                IndirectInitialization(Object o1) {
                  // Do or do not...
                  init();
                }

                IndirectInitialization(Object o1, Object o2) {
                  // ... but here there is try
                  try {
                    init();
                  } finally {
                  }
                }

                private void init() {
                  this.f = new Object();
                }
              }

              class IndirectInitialization2 {
                Object f;

                //// Fixme: This should work recursivelly
                // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field
                IndirectInitialization2(Object o1) {
                  wrappedInitNoTry();
                }

                //// Fixme: This should work recursivelly
                // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field
                IndirectInitialization2(Object o1, Object o2) {
                  try {
                    wrappedInitTry();
                  } finally {
                  }
                }

                private void wrappedInitNoTry() {
                  init();
                }

                private void wrappedInitTry() {
                  try {
                    init();
                  } finally {
                  }
                }

                private void init() {
                  this.f = new Object();
                }
              }

              class TryWithResourceInitializer {
                TryWithResourceInitializer() {
                  // Just check that this doesn't blow up
                  try (Stream<Integer> stream = Stream.of(1, 2, 3)) {}
                }
              }
            }""")
        .doTest();
  }

  @Test
  public void tryWithResourcesSupport() {
    defaultCompilationHelper
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
    defaultCompilationHelper
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
    defaultCompilationHelper
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
  public void nullableOnJavaLangVoid() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  Void foo1() {",
            "    // temporarily, we treat a Void return type as if it was @Nullable Void",
            "    return null;",
            "  }",
            "  @Nullable Void foo2() {",
            "    return null;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullableOnJavaLangVoidWithCast() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  // Currently unhandled. In fact, it *should* produce an error. This entire test case",
            "  // needs to be rethought once we properly support generics, such that it works on T v",
            "  // when T == @Nullable Void, but not when T == Void. Without generics, though, this is the",
            "  // best we can do.",
            "  @SuppressWarnings(\"NullAway\")",
            "  private Void v = (Void)null;",
            "  Void foo1() {",
            "    // temporarily, we treat a Void return type as if it was @Nullable Void",
            "    return (Void)null;",
            "  }",
            "  // Temporarily, we treat any Void formal as if it were @Nullable Void",
            "  void consumeVoid(Void v) {",
            "  }",
            "  @Nullable Void foo2() {",
            "    consumeVoid(null); // See comment on consumeVoid for why this is allowed",
            "    consumeVoid((Void)null);",
            "    return (Void)null;",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void staticCallZeroArgsNullCheck() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  @Nullable static Object nullableReturn() { return new Object(); }",
            "  void foo() {",
            "    if (nullableReturn() != null) {",
            "      nullableReturn().toString();",
            "    }",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    nullableReturn().toString();",
            "  }",
            "  void foo2() {",
            "    if (Test.nullableReturn() != null) {",
            "      nullableReturn().toString();",
            "    }",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    Test.nullableReturn().toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void primitiveCasts() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "    static void foo(int i) { }",
            "    static void m() {",
            "        Integer i = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        int i2 = (int) i;",
            "        // this is fine",
            "        int i3 = (int) Integer.valueOf(3);",
            "        // BUG: Diagnostic contains: unboxing",
            "        int i4 = ((int) i) + 1;",
            "        // BUG: Diagnostic contains: unboxing",
            "        foo((int) i);",
            "        // try another type",
            "        Double d = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        double d2 = (double) d;",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void unboxingInBinaryTrees() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "class Test {",
            "    static void m1() {",
            "        Integer i = null;",
            "        Integer j = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        int i2 = i + j;",
            "    }",
            "    static void m2() {",
            "        Integer i = null;",
            "        // this is fine",
            "        String s = i + \"hi\";",
            "    }",
            "    static void m3() {",
            "        Integer i = null;",
            "        Integer j = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        int i3 = i - j;",
            "    }",
            "    static void m4() {",
            "        Integer i = null;",
            "        Integer j = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        int i4 = i * j;",
            "    }",
            "    static void m5() {",
            "        Integer i = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        int i5 = i << 2;",
            "    }",
            "    static void m6() {",
            "        Integer i = null;",
            "        Integer j = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        boolean b1 = i <= j;",
            "    }",
            "    static void m7() {",
            "        Boolean x = null;",
            "        Boolean y = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        boolean b2 = x && y;",
            "    }",
            "    static void m8() {",
            "        Integer i = null;",
            "        Integer j = null;",
            "        // this is fine",
            "        boolean b = i == j;",
            "    }",
            "    static void m9() {",
            "        Integer i = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        boolean b = i != 0;",
            "    }",
            "    static void m10() {",
            "        Integer i = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        int j = 3 - i;",
            "    }",
            "    static void m11() {",
            "        Integer i = null;",
            "        Integer j = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        int i2 = i",
            "          +",
            "          // BUG: Diagnostic contains: unboxing",
            "          j;",
            "    }",
            "    static void m12() {",
            "        Integer i = null;",
            "        // BUG: Diagnostic contains: unboxing",
            "        int i2 = i",
            "          +",
            "          // no error here, due to previous unbox of i",
            "          i;",
            "    }",
            "    static void m13() {",
            "        int[] arr = null;",
            "        Integer i = null;",
            "        // BUG: Diagnostic contains: dereferenced",
            "        int i2 = arr[",
            "          // BUG: Diagnostic contains: unboxing",
            "          i];",
            "    }",
            "    static void primitiveArgs(int x, int y) {}",
            "    static void m14() {",
            "        Integer i = null;",
            "        Integer j = null;",
            "        primitiveArgs(",
            "          // BUG: Diagnostic contains: unboxing",
            "          i,",
            "          // BUG: Diagnostic contains: unboxing",
            "          j);",
            "    }",
            "    static void primitiveVarArgs(int... args) {}",
            "    static void m15() {",
            "        Integer i = null;",
            "        Integer j = null;",
            "        primitiveVarArgs(",
            "          // BUG: Diagnostic contains: unboxing",
            "          i,",
            "          // BUG: Diagnostic contains: unboxing",
            "          j);",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void primitiveCastsRememberNullChecks() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Map;",
            "import javax.annotation.Nullable;",
            "import com.google.common.base.Preconditions;",
            "class Test {",
            "    static void foo(int i) { }",
            "    static void m1(@Nullable Integer i) {",
            "        // BUG: Diagnostic contains: unboxing",
            "        int i1 = (int) i;",
            "    }",
            "    static void m2(@Nullable Integer i) {",
            "        if (i != null) {",
            "            // this is fine",
            "            int i2 = (int) i;",
            "        }",
            "    }",
            "    static void m3(@Nullable Integer i) {",
            "        // BUG: Diagnostic contains: unboxing",
            "        int i3 = (int) i;",
            "    }",
            "    static void m4(@Nullable Integer i) {",
            "        Preconditions.checkNotNull(i);",
            "        // this is fine",
            "        int i4 = (int) i;",
            "    }",
            "    static private void consumeInt(int i) { }",
            "    static void m5(@Nullable Integer i) {",
            "        // BUG: Diagnostic contains: unboxing",
            "        consumeInt((int) i);",
            "    }",
            "    static void m6(@Nullable Integer i) {",
            "        Preconditions.checkNotNull(i);",
            "        // this is fine",
            "        consumeInt((int) i);",
            "    }",
            "    static void m7(@Nullable Object o) {",
            "        // BUG: Diagnostic contains: unboxing",
            "        consumeInt((int) o);",
            "    }",
            "    static void m8(@Nullable Object o) {",
            "        Preconditions.checkNotNull(o);",
            "        // this is fine",
            "        consumeInt((int) o);",
            "    }",
            "    static void m9(Map<String,Object> m) {",
            "        // BUG: Diagnostic contains: unboxing",
            "        consumeInt((int) m.get(\"foo\"));",
            "    }",
            "    static void m10(Map<String,Object> m) {",
            "        if(m.get(\"bar\") != null) {",
            "            // this is fine",
            "            consumeInt((int) m.get(\"bar\"));",
            "        }",
            "    }",
            "    static void m11(Map<String,Object> m) {",
            "        Preconditions.checkNotNull(m.get(\"bar\"));",
            "        // this is fine",
            "        consumeInt((int) m.get(\"bar\"));",
            "    }",
            "}")
        .doTest();
  }

  /**
   * This test exposes a failure in CFG construction in Checker Framework 3.41.0 and above. Once a
   * fix for this issue makes it to a Checker Framework release, we can probably remove this test.
   * See https://github.com/typetools/checker-framework/issues/6396.
   */
  @Test
  public void cfgConstructionSymbolCompletionFailure() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import org.apache.spark.sql.SparkSession;",
            "class Test {",
            "  static class X {",
            "    X(SparkSession session) {}",
            "  }",
            "  X run() {",
            "    try (SparkSession session = SparkSession.builder().getOrCreate()) {",
            "      return new X(session);",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testDefaultEqualsInInterfaceTakesNullable() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Test {",
            "  public interface AnInterface {}",
            "  public static boolean foo(AnInterface a, @Nullable AnInterface b) {",
            "    return a.equals(b);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void testStaticImportFromSubclass() {
    defaultCompilationHelper
        .addSourceLines(
            "Superclass.java",
            "package com.uber;",
            "import javax.annotation.Nullable;",
            "class Superclass {",
            "    public static String foo(@Nullable Object ignored) { return \"\"; };",
            "}")
        .addSourceLines(
            "Subclass.java", "package com.uber;", "class Subclass extends Superclass {}")
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import static com.uber.Subclass.foo;",
            "class Test {",
            "    static void m() {",
            "        // Calling foo() the obvious way: safe because @Nullable arg",
            "        System.out.println(Superclass.foo(null));",
            "        // Calling foo() through Subclass: also safe",
            "        System.out.println(Subclass.foo(null));",
            "        // Static import from Subclass: also safe",
            "        System.out.println(foo(null));",
            "    }",
            "}")
        .doTest();
  }

  /** testing for no Checker Framework crash */
  @Test
  public void ternaryBothCasesNull() {
    defaultCompilationHelper
        .addSourceLines(
            "TestCase.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "public class TestCase {",
            "    public static @Nullable String foo(String x) {",
            "        return x.isEmpty() ? null : null;",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void synchronizedDeref() {
    defaultCompilationHelper
        .addSourceLines(
            "TestCase.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "public class TestCase {",
            "  public static void testPositive(@Nullable Object lock) {",
            "    // BUG: Diagnostic contains: synchronized block expression \"lock\" is @Nullable",
            "    synchronized (lock) {}",
            "  }",
            "  public static void testNegative(Object lock) {",
            "    synchronized (lock) {}",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void synchronizedInUnannotatedLambda() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            "public class Foo {",
            "    void foo() {",
            "        Runnable runnable = () -> {",
            "            Object lock = new Object();",
            "            synchronized (lock) {}",
            "        };",
            "    }",
            "}")
        .doTest();
  }

  @Test
  public void enclosingExpressionForNewClassTree() {
    defaultCompilationHelper
        .addSourceLines(
            "Outer.java",
            "package com.uber;",
            "import org.jspecify.annotations.Nullable;",
            "class Outer {",
            "    class Inner {}",
            "    static Inner testNegative(Outer outer) {",
            "        return outer.new Inner() {};",
            "    }",
            "    static Inner testPositive(@Nullable Outer outer) {",
            "        // BUG: Diagnostic contains: dereferenced expression outer is @Nullable",
            "        return outer.new Inner() {};",
            "    }",
            "}")
        .doTest();
  }
}
