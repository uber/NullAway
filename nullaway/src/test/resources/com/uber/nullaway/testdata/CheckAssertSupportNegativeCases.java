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
}
