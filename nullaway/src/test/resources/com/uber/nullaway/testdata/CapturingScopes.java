/*
 * Copyright (c) 2017 Uber Technologies Inc.
 *
 * Permission is hereby granted free of charge to any person obtaining a copy
 * of this software and associated documentation files (the Software) to deal
 * in the Software without restriction including without limitation the rights
 * to use copy modify merge publish distribute sublicense and/or sell
 * copies of the Software and to permit persons to whom the Software is
 * furnished to do so subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED AS IS WITHOUT WARRANTY OF ANY KIND EXPRESS OR
 * IMPLIED INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM DAMAGES OR OTHER
 * LIABILITY WHETHER IN AN ACTION OF CONTRACT TORT OR OTHERWISE ARISING FROM
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

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
}
