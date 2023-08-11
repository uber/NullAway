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

import com.sun.source.util.TreePath;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.fixserialization.Serializer;
import com.uber.nullaway.fixserialization.adapters.SerializationAdapter;
import com.uber.nullaway.fixserialization.location.SymbolLocation;
import java.util.Objects;

/** Stores information suggesting adding @Nullable on an element in source code. */
public class SuggestedNullableFixInfo {

  /** SymbolLocation of the target element in source code. */
  private final SymbolLocation symbolLocation;

  /** Error which will be resolved by this type change. */
  private final ErrorMessage errorMessage;

  private final ClassAndMemberInfo classAndMemberInfo;

  public SuggestedNullableFixInfo(
      TreePath path, SymbolLocation symbolLocation, ErrorMessage errorMessage) {
    this.symbolLocation = symbolLocation;
    this.errorMessage = errorMessage;
    this.classAndMemberInfo = new ClassAndMemberInfo(path);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SuggestedNullableFixInfo)) {
      return false;
    }
    SuggestedNullableFixInfo suggestedNullableFixInfo = (SuggestedNullableFixInfo) o;
    return Objects.equals(symbolLocation, suggestedNullableFixInfo.symbolLocation)
        && Objects.equals(
            errorMessage.getMessageType().toString(),
            suggestedNullableFixInfo.errorMessage.getMessageType().toString());
  }

  @Override
  public int hashCode() {
    return Objects.hash(symbolLocation, errorMessage.getMessageType().toString());
  }

  /**
   * returns string representation of content of an object.
   *
   * @param adapter adapter used to serialize symbols.
   * @return string representation of contents of an object in a line separated by tabs.
   */
  public String tabSeparatedToString(SerializationAdapter adapter) {
    return String.join(
        "\t",
        symbolLocation.tabSeparatedToString(adapter),
        errorMessage.getMessageType().toString(),
        "nullable",
        Serializer.serializeSymbol(classAndMemberInfo.getClazz(), adapter),
        Serializer.serializeSymbol(classAndMemberInfo.getMember(), adapter));
  }

  /** Finds the class and member of program point where triggered this type change. */
  public void initEnclosing() {
    classAndMemberInfo.findValues();
  }

  /**
   * Creates header of an output file containing all {@link SuggestedNullableFixInfo} written in
   * string which values are separated by tabs.
   *
   * @return string representation of the header separated by tabs.
   */
  public static String header() {
    return String.join(
        "\t", SymbolLocation.header(), "reason", "annotation", "rootClass", "rootMethod");
  }
}
