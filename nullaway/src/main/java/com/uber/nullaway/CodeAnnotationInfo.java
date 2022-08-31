/*
 * Copyright (c) 2017-2022 Uber Technologies, Inc.
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

import static com.uber.nullaway.NullabilityUtil.NULLMARKED_SIMPLE_NAME;
import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.util.Context;
import java.util.HashMap;
import java.util.Map;
import javax.lang.model.element.ElementKind;

/**
 * Provides APIs for querying whether code is annotated for nullness checking, and for related
 * queries on what annotations are present on a class and/or enclosing classes. Makes use of caching
 * internally for performance.
 */
public final class CodeAnnotationInfo {

  private static final Context.Key<CodeAnnotationInfo> ANNOTATION_INFO_KEY = new Context.Key<>();

  private static final int MAX_CLASS_CACHE_SIZE = 200;

  private final Cache<Symbol.ClassSymbol, ClassCacheRecord> classCache =
      CacheBuilder.newBuilder().maximumSize(MAX_CLASS_CACHE_SIZE).build();

  private CodeAnnotationInfo() {}

  /**
   * Get the CodeAnnotationInfo for the given javac context. We ensure there is one instance per
   * context (as opposed to using static fields) to avoid memory leaks.
   */
  public static CodeAnnotationInfo instance(Context context) {
    CodeAnnotationInfo annotationInfo = context.get(ANNOTATION_INFO_KEY);
    if (annotationInfo == null) {
      annotationInfo = new CodeAnnotationInfo();
      context.put(ANNOTATION_INFO_KEY, annotationInfo);
    }
    return annotationInfo;
  }

  /**
   * Checks if a symbol comes from an annotated package, as determined by either configuration flags
   * (e.g. {@code -XepOpt:NullAway::AnnotatedPackages}) or package level annotations (e.g. {@code
   * org.jspecify.nullness.NullMarked}).
   *
   * @param outermostClassSymbol symbol for class (must be an outermost class)
   * @param config NullAway config
   * @return true if the class is from a package that should be treated as properly annotated
   *     according to our convention (every possibly null parameter / return / field
   *     annotated @Nullable), false otherwise
   */
  private static boolean fromAnnotatedPackage(
      Symbol.ClassSymbol outermostClassSymbol, Config config) {
    final String className = outermostClassSymbol.getQualifiedName().toString();
    if (!config.fromExplicitlyAnnotatedPackage(className)
        && !ASTHelpers.hasDirectAnnotationWithSimpleName(
            outermostClassSymbol.packge(), NullabilityUtil.NULLMARKED_SIMPLE_NAME)) {
      // By default, unknown code is unannotated unless @NullMarked or configured as annotated by
      // package name
      return false;
    }
    if (config.fromExplicitlyUnannotatedPackage(className)) {
      // Any code explicitly marked as unannotated in our configuration is unannotated, no matter
      // what.
      return false;
    }
    // Finally, if we are here, the code was marked as annotated (either by configuration or
    // @NullMarked) and nothing overrides it.
    return true;
  }

  /**
   * Check if a symbol comes from generated code.
   *
   * @param symbol symbol for entity
   * @return true if symbol represents an entity contained in a class annotated with
   *     {@code @Generated}; false otherwise
   */
  public boolean isGenerated(Symbol symbol, Config config) {
    Symbol.ClassSymbol outermostClassSymbol =
        get(castToNonNull(ASTHelpers.enclosingClass(symbol)), config).outermostClassSymbol;
    return ASTHelpers.hasDirectAnnotationWithSimpleName(outermostClassSymbol, "Generated");
  }

  /**
   * Check if a symbol comes from unannotated code.
   *
   * @param symbol symbol for entity
   * @param config NullAway config
   * @return true if symbol represents an entity contained in a class that is unannotated; false
   *     otherwise
   */
  public boolean isSymbolUnannotated(Symbol symbol, Config config) {
    Symbol.ClassSymbol classSymbol;
    if (symbol instanceof Symbol.ClassSymbol) {
      classSymbol = (Symbol.ClassSymbol) symbol;
    } else {
      classSymbol = castToNonNull(ASTHelpers.enclosingClass(symbol));
    }
    final ClassCacheRecord classCacheRecord = get(classSymbol, config);
    boolean inAnnotatedClass = classCacheRecord.isNullnessAnnotated;
    if (symbol.getKind().equals(ElementKind.METHOD)
        || symbol.getKind().equals(ElementKind.CONSTRUCTOR)) {
      return !classCacheRecord.isMethodNullnessAnnotated((Symbol.MethodSymbol) symbol);
    } else {
      return !inAnnotatedClass;
    }
  }

  /**
   * Check whether a class should be treated as nullness-annotated.
   *
   * @param classSymbol The symbol for the class to be checked
   * @return Whether this class should be treated as null-annotated, taking into account annotations
   *     on enclosing classes, the containing package, and other NullAway configuration like
   *     annotated packages
   */
  public boolean isClassNullAnnotated(Symbol.ClassSymbol classSymbol, Config config) {
    return get(classSymbol, config).isNullnessAnnotated;
  }

  /**
   * Retrieve the (outermostClass, isNullMarked) record for a given class symbol.
   *
   * <p>This method is recursive, using the cache on the way up and populating it on the way down.
   *
   * @param classSymbol The class to query, possibly an inner class
   * @return A record including the outermost class in which the given class is nested, as well as
   *     boolean flag noting whether it should be treated as nullness-annotated, taking into account
   *     annotations on enclosing classes, the containing package, and other NullAway configuration
   *     like annotated packages
   */
  private ClassCacheRecord get(Symbol.ClassSymbol classSymbol, Config config) {
    ClassCacheRecord record = classCache.getIfPresent(classSymbol);
    if (record != null) {
      return record;
    }
    if (classSymbol.getNestingKind().isNested()) {
      Symbol owner = classSymbol.owner;
      Preconditions.checkNotNull(owner, "Symbol.owner should only be null for modules!");
      Symbol.MethodSymbol enclosingMethod = null;
      if (owner.getKind().equals(ElementKind.METHOD)
          || owner.getKind().equals(ElementKind.CONSTRUCTOR)) {
        enclosingMethod = (Symbol.MethodSymbol) owner; // Potentially null!
      }
      Symbol.ClassSymbol enclosingClass = ASTHelpers.enclosingClass(classSymbol);
      // enclosingClass can be null in weird cases like for array methods
      if (enclosingClass != null) {
        ClassCacheRecord recordForEnclosing = get(enclosingClass, config);
        record =
            new ClassCacheRecord(
                recordForEnclosing.outermostClassSymbol,
                (recordForEnclosing.isNullnessAnnotated
                        || (enclosingMethod != null
                            && recordForEnclosing.isMethodNullnessAnnotated(enclosingMethod))
                        || ASTHelpers.hasDirectAnnotationWithSimpleName(
                            classSymbol, NullabilityUtil.NULLMARKED_SIMPLE_NAME))
                    && !shouldTreatAsUnannotated(classSymbol, config));
      }
    }
    if (record == null) {
      // We are already at the outermost class (we can find), so let's create a record for it
      record = new ClassCacheRecord(classSymbol, isAnnotatedTopLevelClass(classSymbol, config));
    }
    classCache.put(classSymbol, record);
    return record;
  }

  private boolean shouldTreatAsUnannotated(Symbol.ClassSymbol classSymbol, Config config) {
    if (config.isUnannotatedClass(classSymbol)) {
      return true;
    } else if (config.treatGeneratedAsUnannotated()) {
      // Generated code is or isn't excluded, depending on configuration
      // Note: In the future, we might want finer grain controls to distinguish code that is
      // generated with nullability info and without. Possibly using the same mechanism as we
      // will need to support @NullUnmarked.
      if (ASTHelpers.hasDirectAnnotationWithSimpleName(classSymbol, "Generated")) {
        return true;
      }
      ImmutableSet<String> generatedCodeAnnotations = config.getGeneratedCodeAnnotations();
      if (classSymbol.getAnnotationMirrors().stream()
          .map(anno -> anno.getAnnotationType().toString())
          .anyMatch(generatedCodeAnnotations::contains)) {
        return true;
      }
    }
    return false;
  }

  private boolean isAnnotatedTopLevelClass(Symbol.ClassSymbol classSymbol, Config config) {
    // first, check if the class has a @NullMarked annotation or comes from an annotated package
    if ((ASTHelpers.hasDirectAnnotationWithSimpleName(
            classSymbol, NullabilityUtil.NULLMARKED_SIMPLE_NAME)
        || fromAnnotatedPackage(classSymbol, config))) {
      // make sure it's not explicitly configured as unannotated
      return !shouldTreatAsUnannotated(classSymbol, config);
    }
    return false;
  }

  /**
   * Immutable record holding the outermost class symbol and the nullness-annotated state for a
   * given (possibly inner) class.
   *
   * <p>The class being referenced by the record is not represented by this object, but rather the
   * key used to retrieve it.
   */
  private static final class ClassCacheRecord {
    public final Symbol.ClassSymbol outermostClassSymbol;
    public final boolean isNullnessAnnotated;
    public final Map<Symbol.MethodSymbol, Boolean> methodNullnessCache;

    public ClassCacheRecord(Symbol.ClassSymbol outermostClassSymbol, boolean isAnnotated) {
      this.outermostClassSymbol = outermostClassSymbol;
      this.isNullnessAnnotated = isAnnotated;
      this.methodNullnessCache = new HashMap<>();
    }

    public boolean isMethodNullnessAnnotated(Symbol.MethodSymbol methodSymbol) {
      methodNullnessCache.computeIfAbsent(
          methodSymbol,
          m -> {
            if (this.isNullnessAnnotated) {
              // ToDo: check for @NullUnmarked
              return true;
            } else {
              return ASTHelpers.hasDirectAnnotationWithSimpleName(m, NULLMARKED_SIMPLE_NAME);
            }
          });
      return methodNullnessCache.get(methodSymbol);
    }
  }
}
