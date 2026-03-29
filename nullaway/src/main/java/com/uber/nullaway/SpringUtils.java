/*
 * Copyright (c) 2026 Uber Technologies, Inc.
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

package com.uber.nullaway;

import com.sun.tools.javac.code.Symbol;
import java.util.regex.Pattern;

/** Utilities for Spring-specific framework reasoning. */
public final class SpringUtils {

  static final String VALUE_ANNOT = "org.springframework.beans.factory.annotation.Value";

  /**
   * Matches a SpEL fragment like {@code #{...}} when it contains {@code null} as a standalone
   * token. This lets us distinguish Spring {@code @Value} expressions that may produce {@code null}
   * from plain property placeholders or string literals containing the letters {@code null}.
   */
  private static final Pattern VALUE_NULL_SPEL_PATTERN =
      Pattern.compile("#\\{[^}]*\\bnull\\b[^}]*}");

  private SpringUtils() {}

  /**
   * Returns true when a field annotated with Spring {@code @Value} should be treated as initialized
   * externally.
   */
  public static boolean isInjectedByValueAnnotation(Symbol fieldSymbol, String annotationName) {
    if (!annotationName.equals(VALUE_ANNOT)) {
      return false;
    }
    String annotationValue = NullabilityUtil.getAnnotationValue(fieldSymbol, VALUE_ANNOT, true);
    return annotationValue == null || !containsNullSpELExpression(annotationValue);
  }

  private static boolean containsNullSpELExpression(String annotationValue) {
    return VALUE_NULL_SPEL_PATTERN.matcher(annotationValue).find();
  }
}
