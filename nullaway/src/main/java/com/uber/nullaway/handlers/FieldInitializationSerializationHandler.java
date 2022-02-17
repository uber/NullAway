/*
 * Copyright (c) 2022 Uber Technologies, Inc.
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
import com.uber.nullaway.dataflow.AccessPathNullnessAnalysis;
import com.uber.nullaway.fixserialization.FixSerializationConfig;

/**
 * This handler is used to serialize information regarding methods that initialize a class field.
 * These information helps to detect initializer methods in class.
 */
public class FieldInitializationSerializationHandler extends BaseNoOpHandler {

  private final FixSerializationConfig config;

  FieldInitializationSerializationHandler(FixSerializationConfig config) {
    this.config = config;
  }

  @Override
  public void serializeClassFieldInitializationInfo(
      Symbol.MethodSymbol methodSymbol,
      Symbol.VarSymbol field,
      Trees trees,
      AccessPathNullnessAnalysis analysis,
      VisitorState state) {}
}
