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

import static com.sun.tools.javac.code.TypeTag.FORALL;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.sun.tools.javac.code.Symbol;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    public final String enclosingClass;
    public final String methodName;
    public final String methodArgs;
    @Nullable public final String genericArgs;

    private MethodRef(
        String enclosingClass, String methodName, String methodArgs, @Nullable String genericArgs) {
      this.enclosingClass = enclosingClass;
      this.methodName = methodName;
      this.methodArgs = methodArgs;
      this.genericArgs = genericArgs;
    }

    private static final Pattern METHOD_SIG_PATTERN = Pattern.compile("^(<.*>)?(\\w+)(\\(.*\\))$");

    /**
     * @param enclosingClass containing class
     * @param methodArgs method signature in the appropriate format (see class docs)
     * @return corresponding {@link MethodRef}
     */
    public static MethodRef methodRef(String enclosingClass, String methodSignature) {
      Matcher matcher = METHOD_SIG_PATTERN.matcher(methodSignature);
      if (matcher.find()) {
        String genericArgs = matcher.group(1);
        String methodName = matcher.group(2);
        String methodArgs = matcher.group(3);
        if (methodName.equals(enclosingClass.substring(enclosingClass.lastIndexOf('.') + 1))) {
          // constructor
          methodName = "<init>";
        }
        return new MethodRef(enclosingClass, methodName, methodArgs, genericArgs);
      } else {
        throw new IllegalArgumentException("malformed method signature " + methodSignature);
      }
    }

    //    public static MethodRef methodRef(String enclosingClass, String methodName, String
    // methodArgs) {
    //      return new MethodRef(enclosingClass, methodName, methodArgs, null);
    //    }
    //
    //    public static MethodRef methodRef(
    //        String enclosingClass, String methodName, String methodArgs, @Nullable String
    // genericArgs) {
    //      return new MethodRef(enclosingClass, methodName, methodArgs, genericArgs);
    //    }

    public static MethodRef fromSymbol(Symbol.MethodSymbol symbol) {
      String methodStr = symbol.toString();
      int openParenInd = methodStr.indexOf('(');
      String genericArgs = null;
      if (symbol.type != null) {
        if (symbol.type.hasTag(FORALL)) genericArgs = "<" + symbol.type.getTypeArguments() + ">";
      }

      return new MethodRef(
          symbol.owner.getQualifiedName().toString(),
          symbol.name.toString(),
          methodStr.substring(openParenInd),
          genericArgs);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      MethodRef methodRef = (MethodRef) o;
      return Objects.equals(enclosingClass, methodRef.enclosingClass)
          && Objects.equals(methodName, methodRef.methodName)
          && Objects.equals(methodArgs, methodRef.methodArgs);
    }

    @Override
    public int hashCode() {
      return Objects.hash(enclosingClass, methodName, methodArgs);
    }

    @Override
    public String toString() {
      return "MethodRef{"
          + "enclosingClass='"
          + enclosingClass
          + '\''
          + ", methodName='"
          + methodName
          + '\''
          + ", methodArgs='"
          + methodArgs
          + '\''
          + '}';
    }
  }
}
