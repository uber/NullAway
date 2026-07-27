/*
 * Copyright (c) 2025 Uber Technologies, Inc.
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

import com.uber.nullaway.fixserialization.Serializer;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Structured metadata about a nullable expression at an error site, used by the Annotator for
 * automatic code-fix generation.
 *
 * @param expression source text of the nullable expression.
 * @param kind symbol kind (e.g. "field", "parameter", "method").
 * @param enclosingClass serialized enclosing class of the symbol.
 * @param isAnnotated whether the symbol is from annotated code.
 * @param symbol serialized symbol name.
 * @param position source offset of the expression.
 */
public record NullableExpressionInfo(
    String expression,
    String kind,
    String enclosingClass,
    boolean isAnnotated,
    String symbol,
    int position) {

  public void writeXml(XMLStreamWriter writer) throws XMLStreamException {
    writer.writeStartElement("nullableExpressionInfo");
    Serializer.writeTextElement(writer, "expression", expression);
    Serializer.writeTextElement(writer, "kind", kind);
    Serializer.writeTextElement(writer, "class", enclosingClass);
    Serializer.writeTextElement(writer, "isAnnotated", Boolean.toString(isAnnotated));
    Serializer.writeTextElement(writer, "symbol", symbol);
    Serializer.writeTextElement(writer, "position", Integer.toString(position));
    writer.writeEndElement();
  }
}
