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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import java.util.Objects;
import javax.annotation.Nullable;

/** Provides models for library routines for the null checker. */
public interface LibraryModels {

  /**
   * @return map from the names of null-rejecting methods to the indexes of the arguments that
   *     aren't permitted to be null.
   */
  ImmutableSetMultimap<MethodRef, Integer> failIfNullParameters();

  /**
   * @return map from the names of methods with @Nullable parameters to the indexes of the arguments
   *     that are @Nullable.
   *     <p>This is taken into account for override checks, requiring methods that override the
   *     methods listed here to take @Nullable parameters on the same indexes. The main use for this
   *     is to document which API callbacks can be passed null values.
   */
  ImmutableSetMultimap<MethodRef, Integer> explicitlyNullableParameters();

  /**
   * @return map from the names of methods with @NonNull parameters to the indexes of the arguments
   *     that are @NonNull.
   *     <p>Note that these methods are different from the {@link #failIfNullParameters()} methods,
   *     in that we expect the null checker to ensure that the parameters passed to these methods
   *     are @NonNull. In contrast, the null checker does no such enforcement for methods in {@link
   *     #failIfNullParameters()}, it just learns that after the call the relevant parameters cannot
   *     be null.
   */
  ImmutableSetMultimap<MethodRef, Integer> nonNullParameters();

  /**
   * @return map from the names of null-querying methods to the indexes of the arguments that are
   *     compared against null.
   */
  ImmutableSetMultimap<MethodRef, Integer> nullImpliesTrueParameters();

  /** @return set of library methods that may return null */
  ImmutableSet<MethodRef> nullableReturns();

  /** @return set of library methods that are assumed not to return null */
  ImmutableSet<MethodRef> nonNullReturns();

  /**
   * representation of a method as a qualified class name + a signature for the method
   *
   * <p>The formatting of a method signature should match the result of calling {@link
   * Symbol.MethodSymbol#toString()} on the corresponding symbol. See {@link
   * com.uber.nullaway.handlers.LibraryModelsHandler.DefaultLibraryModels} for examples. Basic
   * principles:
   *
   * <ul>
   *   <li>signature is a method name plus argument types, e.g., <code>foo(java.lang.Object,
   *  java.lang.String)</code>
   *   <li>constructor for class Foo looks like <code>Foo(java.lang.String)</code>
   *   <li>If the method has its own type parameters, they need to be declared, like <code>
   *       &lt;T&gt;checkNotNull(T)</code>
   *   <li>Type bounds matter for generics, e.g., <code>addAll(java.lang.Iterable&lt;? extends
   *   E&gt;)
   *  </code>
   * </ul>
   */
  final class MethodRef {

    public final String clazz;
    public final String methodSig;

    private MethodRef(String clazz, String methodSig) {
      this.clazz = clazz;
      this.methodSig = methodSig;
    }

    /**
     * @param clazz containing class
     * @param methodSig method signature in the appropriate format (see class docs)
     * @return corresponding {@link MethodRef}
     */
    public static MethodRef methodRef(Class<?> clazz, String methodSig) {
      return methodRef(clazz.getName(), methodSig);
    }

    /**
     * @param clazz containing class
     * @param methodSig method signature in the appropriate format (see class docs)
     * @return corresponding {@link MethodRef}
     */
    public static MethodRef methodRef(String clazz, String methodSig) {
      Preconditions.checkArgument(
          isValidMethodSig(methodSig), methodSig + " is not a valid method signature");
      return new MethodRef(clazz, methodSig);
    }

    private static boolean isValidMethodSig(String methodSig) {
      // some basic checking to make sure it's not just a method name
      return methodSig.contains("(") && methodSig.contains(")");
    }

    public static MethodRef fromSymbol(Symbol.MethodSymbol symbol) {
      return methodRef(symbol.owner.getQualifiedName().toString(), symbol.toString());
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof MethodRef) {
        MethodRef other = (MethodRef) obj;
        return clazz.equals(other.clazz) && methodSig.equals(other.methodSig);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hash(clazz, methodSig);
    }

    @Override
    public String toString() {
      return "MethodRef{" + "clazz='" + clazz + '\'' + ", methodSig='" + methodSig + '\'' + '}';
    }
  }

  /** utility methods for dealing with library models */
  final class LibraryModelUtil {

    private LibraryModelUtil() {}

    public static boolean hasNullableReturn(
        LibraryModels models, Symbol.MethodSymbol symbol, Types types) {
      // need to check if symbol is in the model or if it overrides a method in the model
      return methodInSet(symbol, types, models.nullableReturns()) != null;
    }

    public static boolean hasNonNullReturn(
        LibraryModels models, Symbol.MethodSymbol symbol, Types types) {
      // need to check if symbol is in the model or if it overrides a method in the model
      return methodInSet(symbol, types, models.nonNullReturns()) != null;
    }

    @Nullable
    private static Symbol.MethodSymbol methodInSet(
        Symbol.MethodSymbol symbol, Types types, ImmutableCollection<MethodRef> memberNames) {
      if (memberNames.contains(MethodRef.fromSymbol(symbol))) {
        return symbol;
      }
      for (Symbol.MethodSymbol superSymbol : ASTHelpers.findSuperMethods(symbol, types)) {
        if (memberNames.contains(MethodRef.fromSymbol(superSymbol))) {
          return superSymbol;
        }
      }
      return null;
    }
  }
}
