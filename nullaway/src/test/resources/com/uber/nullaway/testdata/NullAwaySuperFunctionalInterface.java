/*
 * Copyright (c) 2017 Brandon Mintern
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

public class NullAwaySuperFunctionalInterface {

  public void passLambda() {
    runLambda1(() -> {});
    runLambda2(() -> {});
  }

  private void runLambda1(F1 f) {
    f.call();
  }

  private void runLambda2(F2 f) {
    f.call();
  }

  @FunctionalInterface
  private static interface F2 extends M0, F1 {}

  private static interface M0 {}

  @FunctionalInterface
  private static interface F1 extends F0 {}

  @FunctionalInterface
  private static interface F0 {
    void call();
  }
}
