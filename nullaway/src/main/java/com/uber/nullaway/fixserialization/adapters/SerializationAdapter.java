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

import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.fixserialization.out.ErrorInfo;

/**
 * Adapter for serialization service to provide its output according to the requested serialization
 * version. Outputs are currently produced in TSV format and columns in these files may change
 * future releases. Subclasses of this interface are used to maintain backward compatibility and
 * produce the exact output of previous NullAway versions.
 */
public interface SerializationAdapter {

  /**
   * Latest version number. If version is not defined by the user, NullAway will use the
   * corresponding adapter to this version in its serialization.
   */
  int LATEST_VERSION = 3;

  /**
   * Returns header of "errors.tsv" which contains all serialized {@link ErrorInfo} reported by
   * NullAway.
   *
   * @return Header of "errors.tsv".
   */
  String getErrorsOutputFileHeader();

  /**
   * Serializes contents of the given {@link ErrorInfo} according to the defined header into a
   * string with each field separated by a tab.
   *
   * @param errorInfo Given errorInfo to serialize.
   * @return String representation of the given {@link ErrorInfo}. The returned string should be
   *     ready to get appended to a tsv file as a row.
   */
  String serializeError(ErrorInfo errorInfo);

  /**
   * Returns the associated version number with this adapter.
   *
   * @return Supporting serialization version number.
   */
  int getSerializationVersion();

  /**
   * Serializes the signature of the given {@link Symbol.MethodSymbol} to a string.
   *
   * @param methodSymbol The method symbol to serialize.
   * @return The serialized method symbol.
   */
  String serializeMethodSignature(Symbol.MethodSymbol methodSymbol);
}
