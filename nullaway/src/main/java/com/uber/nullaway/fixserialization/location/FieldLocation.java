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

package com.uber.nullaway.fixserialization.location;

import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.fixserialization.Serializer;
import com.uber.nullaway.fixserialization.adapters.SerializationAdapter;
import javax.lang.model.element.ElementKind;

/** subtype of {@link AbstractSymbolLocation} targeting class fields. */
public class FieldLocation extends AbstractSymbolLocation {

  /** Symbol of targeted class field */
  protected final Symbol.VarSymbol variableSymbol;

  public FieldLocation(Symbol target) {
    super(ElementKind.FIELD, target);
    variableSymbol = (Symbol.VarSymbol) target;
  }

  @Override
  public String tabSeparatedToString(SerializationAdapter adapter) {
    return String.join(
        "\t",
        type.toString(),
        Serializer.serializeSymbol(enclosingClass, adapter),
        "null",
        Serializer.serializeSymbol(variableSymbol, adapter),
        "null",
        path != null ? path.toString() : "null");
  }
}
