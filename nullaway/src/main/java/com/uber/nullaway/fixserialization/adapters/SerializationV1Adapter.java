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

package com.uber.nullaway.fixserialization.adapters;

import static com.uber.nullaway.fixserialization.out.ErrorInfo.EMPTY_NONNULL_TARGET_LOCATION_STRING;

import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.fixserialization.SerializationService;
import com.uber.nullaway.fixserialization.Serializer;
import com.uber.nullaway.fixserialization.location.SymbolLocation;
import com.uber.nullaway.fixserialization.out.ErrorInfo;

/** Adapter for version 1. Base version for serializations. */
public class SerializationV1Adapter implements SerializationAdapter {

  @Override
  public String getErrorsOutputFileHeader() {
    return String.join(
        "\t",
        "message_type",
        "message",
        "enc_class",
        "enc_member",
        "target_kind",
        "target_class",
        "target_method",
        "param",
        "index",
        "uri");
  }

  @Override
  public String serializeError(ErrorInfo errorInfo) {
    return String.join(
        "\t",
        errorInfo.getErrorMessage().getMessageType().toString(),
        SerializationService.escapeSpecialCharacters(errorInfo.getErrorMessage().getMessage()),
        Serializer.serializeSymbol(errorInfo.getRegionClass(), this),
        Serializer.serializeSymbol(errorInfo.getRegionMember(), this),
        (errorInfo.getNonnullTarget() != null
            ? SymbolLocation.createLocationFromSymbol(errorInfo.getNonnullTarget())
                .tabSeparatedToString(this)
            : EMPTY_NONNULL_TARGET_LOCATION_STRING));
  }

  @Override
  public int getSerializationVersion() {
    return 1;
  }

  @Override
  public String serializeMethodSignature(Symbol.MethodSymbol methodSymbol) {
    return methodSymbol.toString();
  }
}
