/*
 * Copyright (c) 2017-2021 Uber Technologies, Inc.
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

import static com.uber.nullaway.NullabilityUtil.NULLMARKED;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.util.Context;

/**
 * A helper cache class to keep track of (inner) classes marked with @NullMarked and to resolve
 * their outermost class. Used by {@link NullabilityUtil}.
 */
public final class NullMarkedCache {

  private static final Context.Key<NullMarkedCache> NULL_MARKED_CACHE_KEY = new Context.Key<>();

  private static final int MAX_CACHE_SIZE = 200;

  private final Cache<Symbol.ClassSymbol, NullMarkedCacheRecord> innerCache =
      CacheBuilder.newBuilder().maximumSize(MAX_CACHE_SIZE).build();

  private NullMarkedCache() {}

  public static NullMarkedCache instance(Context context) {
    NullMarkedCache cache = context.get(NULL_MARKED_CACHE_KEY);
    if (cache == null) {
      cache = new NullMarkedCache();
      context.put(NULL_MARKED_CACHE_KEY, cache);
    }
    return cache;
  }
  /**
   * Retrieve the (outermostClass, isNullMarked) record for a given class symbol.
   *
   * <p>This method is recursive, using the cache on the way up and populating it on the way down.
   *
   * @param classSymbol The class to query, possibly an inner class
   * @return A record including the outermost class in which the given class is nested, as well as
   *     boolean flag noting whether itself or any of the classes containing it are @NullMarked (See
   *     Record)
   */
  public NullMarkedCacheRecord get(Symbol.ClassSymbol classSymbol) {
    NullMarkedCacheRecord record = innerCache.getIfPresent(classSymbol);
    if (record != null) {
      return record;
    }
    if (classSymbol.getNestingKind().isNested()) {
      Symbol.ClassSymbol enclosingClass = ASTHelpers.enclosingClass(classSymbol);
      // enclosingSymbol can be null in weird cases like for array methods
      if (enclosingClass != null) {
        NullMarkedCacheRecord recordForEnclosing = get(enclosingClass);
        record =
            new NullMarkedCacheRecord(
                recordForEnclosing.outermostClassSymbol,
                recordForEnclosing.isNullMarked || classSymbol.getAnnotation(NULLMARKED) != null);
      }
    }
    if (record == null) {
      // We are already at the outermost class (we can find), so let's create a record for it
      record =
          new NullMarkedCacheRecord(classSymbol, classSymbol.getAnnotation(NULLMARKED) != null);
    }
    innerCache.put(classSymbol, record);
    return record;
  }

  /**
   * Immutable record holding the outermost class symbol and @NullMarked state (annotated or not)
   * for a given (possibly inner) class. Note that this doesn't take into account classes take are
   * null-annotated due to package level annotations or configuration flags.
   *
   * <p>The class being referenced by the record is not represented by this object, but rather the
   * key used to retrieve it.
   */
  public static final class NullMarkedCacheRecord {
    public final Symbol.ClassSymbol outermostClassSymbol;
    public final boolean isNullMarked;

    public NullMarkedCacheRecord(Symbol.ClassSymbol outermostClassSymbol, boolean isAnnotated) {
      this.outermostClassSymbol = outermostClassSymbol;
      this.isNullMarked = isAnnotated;
    }
  }
}
