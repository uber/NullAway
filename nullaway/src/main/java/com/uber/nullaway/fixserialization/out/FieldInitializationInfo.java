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

package com.uber.nullaway.fixserialization.out;

import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.fixserialization.Serializer;
import com.uber.nullaway.fixserialization.adapters.SerializationAdapter;
import com.uber.nullaway.fixserialization.location.SymbolLocation;

/**
 * Stores information regarding a method that initializes a class field and leaves it
 * {@code @NonNull} at exit point.
 */
public class FieldInitializationInfo {

  /** Symbol of the initializer method. */
  private final SymbolLocation initializerMethodLocation;

  /** Symbol of the initialized class field. */
  private final Symbol field;

  public FieldInitializationInfo(Symbol.MethodSymbol initializerMethod, Symbol field) {
    this.initializerMethodLocation = SymbolLocation.createLocationFromSymbol(initializerMethod);
    this.field = field;
  }

  /**
   * Returns string representation of content of an object.
   *
   * @param adapter adapter used to serialize symbols.
   * @return string representation of contents of an object in a line seperated by tabs.
   */
  public String tabSeparatedToString(SerializationAdapter adapter) {
    return initializerMethodLocation.tabSeparatedToString(adapter)
        + '\t'
        + Serializer.serializeSymbol(field, adapter);
  }

  /**
   * Creates header of an output file containing all {@link FieldInitializationInfo} written in
   * string which values are separated by tabs.
   *
   * @return string representation of the header separated by tabs.
   */
  public static String header() {
    return SymbolLocation.header() + '\t' + "field";
  }
}
