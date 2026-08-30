/*
 * Copyright (c) 2026 Uber Technologies, Inc.
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

package com.uber.nullaway.tools;

/**
 * Says how {@link DualModeCompilationTestHelper} presents the classes a test snippet depends on.
 *
 * <p>NullAway reads nullability information from two places: the source code javac is compiling,
 * and the class files it loads from the classpath. The two paths disagree whenever an annotation
 * fails to survive compilation, so running the same snippet both ways turns an ordinary test into a
 * metamorphic one.
 */
public enum TestMode {
  /** Every source file of the test is compiled in one pass, with NullAway analyzing all of them. */
  SOURCE,
  /**
   * The source files a test snippet depends on are compiled to class files first, and NullAway sees
   * only the remaining files as source. {@link DualModeCompilationTestHelper} skips a test that
   * cannot be split this way.
   */
  BYTECODE
}
