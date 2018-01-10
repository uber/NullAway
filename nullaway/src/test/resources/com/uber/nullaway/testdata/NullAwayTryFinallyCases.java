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

import java.io.IOException;
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
      // This should be an error, but isn't.
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
      /// ToDo: This should be safe
      // BUG: Diagnostic contains: dereferenced expression
      System.out.println(o.toString()); // Safe.
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
      // This should be an error, but isn't.
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

  public void derefOnFinallySafe2(@Nullable Object o) {
    try {
      if (o == null) {
        doesNotThrowException();
        return;
      }
    } finally {
      // No interproc, it believes doesNotThrowException() can throw an exception.
      // BUG: Diagnostic contains: dereferenced expression
      System.out.println(o.toString());
    }
  }

  public void derefOnFinallyUnsafe2(@Nullable Object o) {
    try {
      if (o == null) {
        throwsException();
        return;
      }
    } finally {
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

  class Initializers {

    Object f;
    Object g;

    /// ToDo: Fix or work-around for this one.
    // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field g is
    // initialized
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
        g = new Object();
        throwsException(); // Even ok with this call, since we are intra-proc.
      } finally {
        System.out.println("No-op");
      }
      g = new Object(); // This is ok, because we are ignoring the actual exceptional exit from the
      // method... mmh
    }
  }
}
