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

import static com.uber.nullaway.testdata.Util.castToNonNull;

import com.uber.nullaway.annotations.Initializer;
import javax.annotation.Nullable;

public class ReadBeforeInitNegativeCases {

  class T1 {

    Object f;
    Object g;

    T1() {
      f = new Object();
      g = new Object();
      System.out.println(f.toString());
      System.out.println(g.toString());
    }

    T1(boolean b) {
      if (b) {
        f = new Object();
      } else {
        f = "hello";
      }
      System.out.println(f.toString());
      if (b) {
        g = "hello";
        System.out.println(g.toString());
      }
      g = "goodbye";
    }
  }

  class T2 {

    Object f = new Object();

    T2() {
      System.out.println(f.toString());
    }
  }

  class T3 {

    Object f;

    T3() {
      System.out.println(f.toString());
    }

    {
      f = new Object();
    }
  }

  static class StaticStuff {

    static Object f;

    static void foo() {
      System.out.println(f.toString());
      System.out.println(g.toString());
    }

    static Object g = "fizz";

    static {
      f = new Object();
    }

    static Object h;

    static {
      h = "hello";
      h.toString();
    }
  }

  class AnonymousInner {

    Runnable r1, r2;
    Object f;

    AnonymousInner() {
      r1 =
          new Runnable() {
            @Override
            public void run() {
              System.out.println(f.toString());
            }
          };
      r2 = () -> System.out.println(f.toString());
      // false negative that we miss
      r2.run();
      f = new Object();
    }
  }

  class InvokePrivate {

    Object f;

    InvokePrivate() {
      initF();
      f.toString();
    }

    private void initF() {
      f = "boo";
    }
  }

  static class ReadSuppressedStaticFromConstructor {

    @SuppressWarnings("NullAway.Init")
    static Object foo;

    ReadSuppressedStaticFromConstructor() {
      foo.toString();
    }
  }

  static class NestedWrite {

    NestedWrite foo;
    Object baz;

    NestedWrite() {
      this.foo = new NestedWrite();
      this.foo.toString();
      this.baz = new Object();
      this.baz.hashCode();
    }

    NestedWrite(NestedWrite other) {
      // safe, as other has already been initialized
      other.foo.baz = new Object();
      this.foo = new NestedWrite();
      this.baz = new Object();
    }
  }

  static class SingleInitializer {

    Object f;

    SingleInitializer() {
      f = new Object();
    }

    @Initializer
    public void init() {
      f.toString();
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
      } else {
        f = "hi";
      }
      g = new Object();
    }

    @Initializer
    public void init() {
      g.toString();
      f.toString();
    }
  }

  static class SingleStaticInitializer {

    static Object f;
    static Object g;

    @Initializer
    static void init() {
      g.toString();
      f.toString();
    }

    static {
      f = new Object();
      g = new Object();
    }
  }

  static class CompareToNullInInit {

    Object f;

    @Initializer
    void init() {
      if (f == null) {
        f = new Object();
      }
    }
  }

  static class CompareToNullInInit2 {

    Object f;

    @Initializer
    void init() {
      if (null == f) {
        f = new Object();
      }
    }
  }

  static class CompareToNullInInit3 {

    Object f;

    @Initializer
    void init() {
      if (!(f != null)) {
        f = new Object();
      }
    }
  }

  static class CastToNonNullTest {

    Object castF;
    Object castG;

    @Initializer
    void init(@Nullable Object o) {
      if (o != null) {
        castF = castToNonNull(castF);
        castG = castToNonNull(castG);
        return;
      }
      castF = "hi";
      castG = "bye";
    }
  }

  // https://github.com/uber/NullAway/issues/347
  static class ReadInsideAssert {

    Object f;

    public ReadInsideAssert(Object o) {
      this.f = o;
      if (this.f.toString() != "") throw new Error();
      assert this.f.toString() != "";
    }
  }
}
