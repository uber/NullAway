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

package com.uber.nullaway.fixserialization.adapters;

/**
 * Adapter for serialization version 4.
 *
 * <p>Switches the error log from TSV ({@code errors.tsv}) to XML ({@code errors.xml}) to carry the
 * structured Annotator auto-fix metadata. The TSV-related methods inherited from {@link
 * SerializationV3Adapter} are not used at this version: {@code Serializer} routes V4+ writes
 * through {@code ErrorInfo#appendXml} instead.
 */
public class SerializationV4Adapter extends SerializationV3Adapter {

  @Override
  public int getSerializationVersion() {
    return 4;
  }
}
