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
import javax.annotation.Nullable;

/** Created by msridhar on 3/7/17. */
public class CheckFieldInitPositiveCases {

  static class T1 {

    Object f;

    // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field f (line 33) is
    // initialized
    T1() {}
  }

  static class T2 {

    Object f, g;

    // BUG: Diagnostic contains: initializer method does not guarantee @NonNull fields f (line 42),
    // g (line 42) are
    // initialized
    T2() {}
  }

  static class T3 {

    // BUG: Diagnostic contains: @NonNull field CheckFieldInitPositiveCases$T3.f not initialized
    Object f;
  }

  static class T4 {

    // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
    Object f = null;

    @Nullable
    static Object returnNull() {
      return null;
    }

    // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
    Object g = returnNull();
  }

  static class T5 {

    Object f;

    // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field f (line 72) is
    // initialized
    T5(boolean b) {
      if (b) {
        this.f = new Object();
      }
    }
  }

  static class T6 {

    Object f;

    T6() {
      // to test detection of this() call
      this(false);
    }

    // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field f (line 85) is
    // initialized
    T6(boolean b) {}
  }

  static class T7 {

    Object f;
    Object g;

    // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field f (line 99) is
    // initialized
    T7(boolean b) {
      if (b) {
        init();
      }
      g = new Object();
    }

    // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field g (line 100)
    // is
    // initialized
    T7() {
      init();
      init2();
    }

    private void init() {
      f = new Object();
    }

    public void init2() {
      g = new Object();
    }
  }

  static class T8 {

    Object f;

    @Initializer
    // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field f (line 130)
    // is
    // initialized
    public void init() {}
  }

  static class T9 {

    // BUG: Diagnostic contains: @NonNull static field CheckFieldInitPositiveCases$T9.f not
    // initialized
    static Object f;

    static {
    }
  }

  static class T10 {

    // BUG: Diagnostic contains: @NonNull static field CheckFieldInitPositiveCases$T10.f not
    // initialized
    static Object f;

    static {
    }

    @Initializer
    static void init() {}
  }

  static class T11 {

    // BUG: Diagnostic contains: @NonNull static field CheckFieldInitPositiveCases$T11.f not
    // initialized
    static Object f;

    static {
    }

    @Initializer
    static void init(Object f) {
      f = new Object(); // Wrong f
    }
  }

  public Object getT12() {
    return (new Object() {
      /*T12*/
      // BUG: Diagnostic contains: f not initialized
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
