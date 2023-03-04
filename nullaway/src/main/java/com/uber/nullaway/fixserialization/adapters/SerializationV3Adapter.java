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
import static java.util.stream.Collectors.joining;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.util.Name;
import com.uber.nullaway.fixserialization.SerializationService;
import com.uber.nullaway.fixserialization.Serializer;
import com.uber.nullaway.fixserialization.location.SymbolLocation;
import com.uber.nullaway.fixserialization.out.ErrorInfo;

/**
 * Adapter for serialization version 3.
 *
 * <p>Updates to previous version (version 1):
 *
 * <ul>
 *   <li>Serialized errors contain an extra column indicating the offset of the program point where
 *       the error is reported.
 *   <li>Serialized errors contain an extra column indicating the path to the containing source file
 *       where the error is reported
 *   <li>Type arguments and Type use annotations are excluded from the serialized method signatures.
 * </ul>
 */
public class SerializationV3Adapter implements SerializationAdapter {

  @Override
  public String getErrorsOutputFileHeader() {
    return String.join(
        "\t",
        "message_type",
        "message",
        "enc_class",
        "enc_member",
        "offset",
        "path",
        "target_kind",
        "target_class",
        "target_method",
        "target_param",
        "target_index",
        "target_path");
  }

  @Override
  public String serializeError(ErrorInfo errorInfo) {
    return String.join(
        "\t",
        errorInfo.getErrorMessage().getMessageType().toString(),
        SerializationService.escapeSpecialCharacters(errorInfo.getErrorMessage().getMessage()),
        Serializer.serializeSymbol(errorInfo.getRegionClass(), this),
        Serializer.serializeSymbol(errorInfo.getRegionMember(), this),
        String.valueOf(errorInfo.getOffset()),
        errorInfo.getPath() != null ? errorInfo.getPath().toString() : "null",
        (errorInfo.getNonnullTarget() != null
            ? SymbolLocation.createLocationFromSymbol(errorInfo.getNonnullTarget())
                .tabSeparatedToString(this)
            : EMPTY_NONNULL_TARGET_LOCATION_STRING));
  }

  @Override
  public int getSerializationVersion() {
    return 3;
  }

  @Override
  public String serializeMethodSignature(Symbol.MethodSymbol methodSymbol) {
    StringBuilder sb = new StringBuilder();
    if (methodSymbol.isConstructor()) {
      // For constructors, method's simple name is <init> and not the enclosing class, need to
      // locate the enclosing class.
      Symbol.ClassSymbol encClass = methodSymbol.owner.enclClass();
      Name name = encClass.getSimpleName();
      if (name.isEmpty()) {
        // An anonymous class cannot declare its own constructor. Based on this assumption and our
        // use case, we should not serialize the method signature.
        throw new RuntimeException(
            "Did not expect method serialization for anonymous class constructor: "
                + methodSymbol
                + ", in anonymous class: "
                + encClass);
      }
      sb.append(name);
    } else {
      // For methods, we use the name of the method.
      sb.append(methodSymbol.getSimpleName());
    }
    sb.append(
        methodSymbol.getParameters().stream()
            .map(
                parameter ->
                    // check if array
                    (parameter.type instanceof Type.ArrayType)
                        // if is array, get the element type and append "[]"
                        ? ((Type.ArrayType) parameter.type).elemtype.tsym + "[]"
                        // else, just get the type
                        : parameter.type.tsym.toString())
            .collect(joining(",", "(", ")")));
    return sb.toString();
  }
}
