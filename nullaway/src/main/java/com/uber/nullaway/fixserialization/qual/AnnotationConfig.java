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

package com.uber.nullaway.fixserialization.qual;

/** Container object of any {@code @Nonnull} and {@code @Nullable} annotations. */
public class AnnotationConfig {

  private Annotation nonNull;
  private Annotation nullable;

  /** Container object of annotation. */
  public static class Annotation {
    /** Fully qualified name. */
    private final String fullName;

    private Annotation(String fullName) {
      this.fullName = fullName;
    }

    /**
     * Getter method for {@link Annotation#fullName}.
     *
     * @return fullName of the annotation.
     */
    public String getFullName() {
      return fullName;
    }

    @Override
    public String toString() {
      return fullName;
    }
  }

  public AnnotationConfig() {
    setFullNames("javax.annotation.Nonnull", "javax.annotation.Nullable");
  }

  public AnnotationConfig(String nullable, String nonNull) {
    this();
    if (nullable == null || nullable.equals("") || nonNull == null || nonNull.equals("")) {
      return;
    }
    setFullNames(nonNull, nullable);
  }

  public void setFullNames(String nonnullFullName, String nullableFullName) {
    nonNull = new Annotation(nonnullFullName);
    nullable = new Annotation(nullableFullName);
  }

  public Annotation getNonNull() {
    return nonNull;
  }

  public Annotation getNullable() {
    return nullable;
  }
}
