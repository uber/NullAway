/*
 * Copyright (c) 2017 Uber Technologies, Inc.
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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;

/**
 * Provides a default implementation(returning appropriate empty sets) of every method defined by
 * the LibraryModels interface.
 *
 * <p>Useful Library Model classes can subclass this class and implement only the methods for the
 * hooks they actually need to take action on, rather than having to implement the entire
 * LibraryModels interface. Additionally, we can add extensibility points without breaking existing
 * library models, as long as we define the corresponding default behavior here.
 */
public abstract class EmptyLibraryModels implements LibraryModels {
  @Override
  public ImmutableSetMultimap<MethodRef, Integer> failIfNullParameters() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> explicitlyNullableParameters() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> nonNullParameters() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> nullImpliesTrueParameters() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> nullImpliesFalseParameters() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> nullImpliesNullParameters() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSet<MethodRef> nullableReturns() {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSet<MethodRef> nonNullReturns() {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSetMultimap<String, Integer> typeVariablesWithNullableUpperBounds() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> castToNonNullMethods() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSet<FieldRef> nullableFields() {
    return ImmutableSet.of();
  }
}
