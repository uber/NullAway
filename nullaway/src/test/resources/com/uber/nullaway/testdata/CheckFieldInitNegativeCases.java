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

import com.google.errorprone.annotations.concurrent.LazyInit;
import com.uber.nullaway.annotations.Initializer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

/** Created by msridhar on 3/8/17. */
public class CheckFieldInitNegativeCases {

  class T1 {

    boolean boolField;

    Object f = new Object();

    Object g;

    Object h;

    Object k;

    @jakarta.inject.Inject Object m;

    @javax.inject.Inject Object n;

    @LazyInit Object lazy;

    T1(Object h, Object k, boolean b) {
      g = new Object();
      this.h = h;
      if (b) {
        this.k = k;
      } else {
        this.k = new Object();
      }
    }
  }

  class T2 {

    Object f;

    T2() {}

    @Initializer
    void init() {
      this.f = new Object();
    }
  }

  class T3 {

    Object f, g;

    T3() {}

    @Initializer
    void init1() {
      this.f = new Object();
    }

    @Initializer
    void init2() {
      this.g = new Object();
    }
  }

  class T4 {

    Object f;

    T4() {
      init();
    }

    private void init() {
      f = new Object();
    }
  }

  class T5 {

    Object f;
    Object g;

    @Initializer
    public void init1() {
      init();
      init2();
    }

    private void init() {
      f = new Object();
    }

    public final void init2() {
      g = new Object();
    }
  }

  static class T6 {

    Object f;
    static Object g;

    T6() {}

    @Before
    void init1() {
      this.f = new Object();
    }

    @BeforeClass
    static void init2() {
      T6.g = new Object();
    }
  }

  static class T7 {

    Object f;
    static Object g;

    T7() {}

    @BeforeEach
    void init1() {
      this.f = new Object();
    }

    @BeforeAll
    static void init2() {
      T7.g = new Object();
    }
  }

  final class T8 {

    Object f;

    @Initializer
    public void init1() {
      init();
    }

    public void init() {
      f = new Object();
    }
  }

  final class T9 {

    Object f;

    public T9() {
      init();
    }

    public void init() {
      f = new Object();
    }
  }

  abstract class Super {

    // to test known initializer methods
    abstract void doInit();
  }

  interface SuperInterface {

    // to test known initializer methods
    void doInit2();
  }

  class Sub extends Super implements SuperInterface {

    Object anotherField;
    Object yetAnotherField;

    @Override
    void doInit() {
      anotherField = new Object();
    }

    @Override
    public void doInit2() {
      yetAnotherField = new Object();
    }
  }

  static class StaticInitializerBlock {
    static Object f;

    static {
      f = new Object();
    }
  }

  static class StaticInitializerBlockMultiple {
    static Object f;

    static {
      assert true; // Do nothing
    }

    static {
      f = new Object();
    }

    static {
      assert true; // Do nothing
    }
  }

  static class StaticInitializer {
    static Object f;

    @Initializer
    static void init() {
      f = new Object();
    }
  }

  static class StaticInitializerExplicitClass {
    static Object f;

    @Initializer
    static void init(Object f) {
      StaticInitializerExplicitClass.f = new Object();
    }
  }

  static class InstInitBlock {

    Object f;

    {
      f = new Object();
    }
  }

  static class SuppressWarningsA {

    Object f; // Should be an error, but we are suppressing

    @SuppressWarnings("NullAway")
    SuppressWarningsA() {}
  }

  static class SuppressWarningsB {

    Object f; // Should be an error, but we are suppressing

    @SuppressWarnings("NullAway.Init")
    SuppressWarningsB() {}
  }

  static class SuppressWarningsC {

    @SuppressWarnings("NullAway.Init")
    static Object f; // Should be an error, but we are suppressing

    static {
      assert true; // Do nothing
    }
  }

  static class SuppressWarningsD {

    @SuppressWarnings("NullAway.Init")
    static Object f; // Should be an error, but we are suppressing

    static {
      assert true; // Do nothing
    }

    @Initializer
    static void init() {}
  }

  public class SuppressWarningsE {

    @SuppressWarnings("NullAway.Init")
    private Object f;

    SuppressWarningsE(final Object f) {
      this.setF(f);
    }

    @SuppressWarnings("NullAway.Init")
    protected SuppressWarningsE() {}

    public void setF(final Object f) {
      this.f = f;
    }
  }

  static class MonotonicNonNullUsage {

    @MonotonicNonNull Object f;

    MonotonicNonNullUsage() {}
  }

  @SuppressWarnings("NullAway.Init")
  public Object getSuppressWarningsF() {
    return (new Object() {
      /*SuppressWarningsF*/
      private Object f;

      public Object getF() {
        return f;
      }

      public void setF(Object f) {
        this.f = f;
      }
    });
  }
}
