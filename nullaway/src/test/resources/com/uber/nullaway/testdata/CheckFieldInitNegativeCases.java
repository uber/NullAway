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
import javax.inject.Inject;
import org.junit.Before;
import org.junit.BeforeClass;

/** Created by msridhar on 3/8/17. */
public class CheckFieldInitNegativeCases {

  class T1 {

    boolean boolField;

    Object f = new Object();

    Object g;

    Object h;

    Object k;

    @Inject Object m;

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
}
