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

import com.facebook.infer.annotation.Initializer;

public class ReadBeforeInitPositiveCases {

  class T1 {

    Object f;
    Object g;

    T1() {
      // BUG: Diagnostic contains: read of @NonNull field f before
      System.out.println(f.toString());
      f = new Object();
      // BUG: Diagnostic contains: read of @NonNull field g before
      System.out.println(g.toString());
      g = new Object();
    }

    T1(boolean b) {
      if (b) {
        f = new Object();
      }
      // BUG: Diagnostic contains: read of @NonNull field f before
      System.out.println(f.toString());
      f = new Object();
      g = new Object();
    }
  }

  static class T2 {

    Object f;
    Object f2;

    {
      // BUG: Diagnostic contains: read of @NonNull field f before
      System.out.println(f.toString());
    }

    // BUG: Diagnostic contains: read of @NonNull field f2 before
    Object g = f2;

    // BUG: Diagnostic contains: read of @NonNull field f2 before
    Object h = str(f2);

    T2() {
      f = "hi";
      f2 = "byte";
    }

    static String str(Object o) {
      return o.toString();
    }
  }

  static class StaticStuff {

    static Object f;
    static Object f2;

    static {
      // BUG: Diagnostic contains: read of @NonNull field f before
      System.out.println(f.toString());
    }

    // BUG: Diagnostic contains: read of @NonNull field f2 before
    static Object g = f2;

    static {
      f = "hi";
      f2 = "byte";
    }
  }

  class InvokePrivate {

    Object f;
    Object g;

    InvokePrivate() {
      // BUG: Diagnostic contains: read of @NonNull field f before
      f.toString();
      initF();
      initG();
      g.toString();
    }

    private void initF() {
      f = "boo";
    }

    private void initG() {
      g = "boo";
    }
  }

  static class StoreInLocal {

    Object f;

    StoreInLocal() {
      // BUG: Diagnostic contains: read of @NonNull field f before
      Object x = this.f;
      x.toString();
      this.f = new Object();
    }
  }

  static class NestedWrite {

    NestedWrite foo;
    Object baz;

    NestedWrite() {
      // BUG: Diagnostic contains: read of @NonNull field foo before
      this.foo.baz = new Object();
      this.foo = new NestedWrite();
      this.baz = new Object();
    }
  }

  static class SingleInitializer {

    Object f;

    @Initializer
    public void init() {
      // BUG: Diagnostic contains: read of @NonNull field f before
      f.toString();
      f = new Object();
    }
  }

  static class SingleInitializer2 {

    Object f;
    Object g;

    SingleInitializer2() {
      f = new Object();
      g = new Object();
    }

    SingleInitializer2(boolean b) {
      if (b) {
        f = new Object();
      }
      g = new Object();
    }

    @Initializer
    public void init() {
      g.toString();
      // BUG: Diagnostic contains: read of @NonNull field f before
      f.toString();
    }
  }

  static class SingleStaticInitializer {

    static Object f;
    static Object g;

    @Initializer
    static void init() {
      g.toString();
      // BUG: Diagnostic contains: read of @NonNull field f before
      f.toString();
    }

    static {
      g = new Object();
    }
  }

  static class StaticCallTest {

    Object f;

    @Initializer
    void init() {
      // BUG: Diagnostic contains: read of @NonNull field f before
      f = Util.id(f);
    }
  }
}
