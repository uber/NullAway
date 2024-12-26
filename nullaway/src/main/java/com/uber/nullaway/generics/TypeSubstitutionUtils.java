package com.uber.nullaway.generics;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.List;

/** Utility method related to substituting type arguments for type variables. */
public class TypeSubstitutionUtils {

  /**
   * Returns the type of {@code sym} as a member of {@code t}.
   *
   * @param types the {@link Types} instance
   * @param t the enclosing type
   * @param sym the symbol
   * @return the type of {@code sym} as a member of {@code t}
   */
  public static Type memberType(Types types, Type t, Symbol sym) {
    return types.memberType(t, sym);
  }

  /**
   * Substitutes the types in {@code to} for the types in {@code from} in {@code t}.
   *
   * @param types the {@link Types} instance
   * @param t the type to which to perform the substitution
   * @param from the types that will be substituted out
   * @param to the types that will be substituted in
   * @return the type resulting from the substitution
   */
  public static Type subst(Types types, Type t, List<Type> from, List<Type> to) {
    return types.subst(t, from, to);
  }
}
