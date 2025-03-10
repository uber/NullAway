/*
 * Copyright (c) 2024 Uber Technologies, Inc.
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

/** subtype of {@link AbstractSymbolLocation} targeting local variables. */
public class LocalVariableLocation extends AbstractSymbolLocation {

  /** Symbol of the targeted local variable. */
  private final Symbol.VarSymbol localVariableSymbol;

  public LocalVariableLocation(Symbol target) {
    super(ElementKind.LOCAL_VARIABLE, target);
    this.localVariableSymbol = (Symbol.VarSymbol) target;
  }

  @Override
  public String tabSeparatedToString(SerializationAdapter adapter) {
    Symbol.MethodSymbol enclosingMethod = (Symbol.MethodSymbol) localVariableSymbol.owner;
    return String.join(
        "\t",
        type.toString(),
        Serializer.serializeSymbol(enclosingClass, adapter),
        Serializer.serializeSymbol(enclosingMethod, adapter),
        Serializer.serializeSymbol(localVariableSymbol, adapter),
        "null",
        path != null ? path.toString() : "null");
  }
}
