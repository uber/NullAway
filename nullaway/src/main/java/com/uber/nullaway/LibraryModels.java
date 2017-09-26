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

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import java.util.Objects;
import javax.annotation.Nullable;

/** Provides models for library routines for the null checker */
public interface LibraryModels {

  /**
   * @return map from the names of null-rejecting methods to the indexes of the arguments that
   *     aren't permitted to be null.
   */
  ImmutableSetMultimap<MemberName, Integer> failIfNullParameters();

  /**
   * @return map from the names of methods with @NonNull parameters to the indexes of the arguments
   *     that are @NonNull.
   *     <p>Note that these methods are different from the {@link #failIfNullParameters()} methods,
   *     in that we expect the null checker to ensure that the parameters passed to these methods
   *     are @NonNull. In contrast, the null checker does no such enforcement for methods in {@link
   *     #failIfNullParameters()}, it just learns that after the call the relevant parameters cannot
   *     be null.
   */
  ImmutableSetMultimap<MemberName, Integer> nonNullParameters();

  /**
   * @return map from the names of null-querying methods to the indexes of the arguments that are
   *     compared against null.
   */
  ImmutableSetMultimap<MemberName, Integer> nullImpliesTrueParameters();

  /** @return set of library methods that may return null */
  ImmutableSet<MemberName> nullableReturns();

  /** representation of a member name as a qualified class name + a name for the member */
  final class MemberName {

    public final String clazz;
    public final String member;

    private MemberName(String clazz, String member) {
      this.clazz = clazz;
      this.member = member;
    }

    public static MemberName member(Class<?> clazz, String member) {
      return member(clazz.getName(), member);
    }

    public static MemberName member(String clazz, String member) {
      return new MemberName(clazz, member);
    }

    public static MemberName fromSymbol(Symbol.MethodSymbol symbol) {
      return member(symbol.owner.getQualifiedName().toString(), symbol.getSimpleName().toString());
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof MemberName) {
        MemberName other = (MemberName) obj;
        return clazz.equals(other.clazz) && member.equals(other.member);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hash(clazz, member);
    }

    @Override
    public String toString() {
      return "MemberName{" + "clazz='" + clazz + '\'' + ", member='" + member + '\'' + '}';
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

    @Nullable
    private static Symbol.MethodSymbol methodInSet(
        Symbol.MethodSymbol symbol, Types types, ImmutableCollection<MemberName> memberNames) {
      if (memberNames.contains(MemberName.fromSymbol(symbol))) {
        return symbol;
      }
      for (Symbol.MethodSymbol superSymbol : ASTHelpers.findSuperMethods(symbol, types)) {
        if (memberNames.contains(MemberName.fromSymbol(superSymbol))) {
          return superSymbol;
        }
      }
      return null;
    }
  }
}
