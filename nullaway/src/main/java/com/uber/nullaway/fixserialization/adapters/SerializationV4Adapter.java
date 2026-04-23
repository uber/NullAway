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

import com.uber.nullaway.fixserialization.SerializationService;
import com.uber.nullaway.fixserialization.out.ErrorInfo;
import java.util.Map;

/**
 * Adapter for serialization version 4.
 *
 * <p>Updates to previous version (version 3):
 *
 * <ol>
 *   <li>Serialized errors contain an extra column {@code infos} carrying structured key/value
 *       metadata used by downstream tools (e.g. Annotator) to synthesize fixes.
 * </ol>
 *
 * <p>The {@code infos} column encodes the map as {@code key=value,key=value}, with backslash
 * escaping applied to any literal backslash, equals sign, or comma within a key or value. The
 * resulting string is further passed through {@link SerializationService#escapeSpecialCharacters}
 * to make it safe for the outer TSV format.
 */
public class SerializationV4Adapter extends SerializationV3Adapter {

  @Override
  public int getSerializationVersion() {
    return 4;
  }

  @Override
  public String getErrorsOutputFileHeader() {
    return super.getErrorsOutputFileHeader() + "\t" + "infos";
  }

  @Override
  public String serializeError(ErrorInfo errorInfo) {
    return super.serializeError(errorInfo) + "\t" + serializeInfos(errorInfo.getInfos());
  }

  private static String serializeInfos(Map<String, String> infos) {
    if (infos.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (Map.Entry<String, String> entry : infos.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      appendEscaped(sb, entry.getKey());
      sb.append('=');
      appendEscaped(sb, entry.getValue());
    }
    return SerializationService.escapeSpecialCharacters(sb.toString());
  }

  private static void appendEscaped(StringBuilder sb, String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\' || c == '=' || c == ',') {
        sb.append('\\');
      }
      sb.append(c);
    }
  }
}
