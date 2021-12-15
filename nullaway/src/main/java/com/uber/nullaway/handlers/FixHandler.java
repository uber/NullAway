/*
 * Copyright (c) 2021 Uber Technologies, Inc.
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

package com.uber.nullaway.handlers;

import com.google.errorprone.VisitorState;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.autofix.AutoFixConfig;
import com.uber.nullaway.autofix.fixer.Fixer;

public class FixHandler extends BaseNoOpHandler {

  private final AutoFixConfig config;
  private final Fixer fixer;

  public FixHandler(AutoFixConfig config) {
    this.config = config;
    this.fixer = new Fixer(config);
  }

  @Override
  public void fixElement(VisitorState state, Symbol target, ErrorMessage errorMessage) {
    Trees trees = Trees.instance(JavacProcessingEnvironment.instance(state.context));
    if (config.canFixElement(trees, target)) {
      fixer.fix(errorMessage, target, state);
    }
  }
}
