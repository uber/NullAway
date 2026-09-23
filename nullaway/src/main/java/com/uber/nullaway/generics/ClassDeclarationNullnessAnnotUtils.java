package com.uber.nullaway.generics;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.Config;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.type.DeclaredType;
import org.jspecify.annotations.Nullable;

/**
 * Utility to deal with nullability annotations within {@code extends} or {@code implements} clauses
 * in class declarations, e.g., {@code class Foo extends Supplier<@Nullable T2>}.
 */
public class ClassDeclarationNullnessAnnotUtils {

  /**
   * Returns the type of {@code subtype} viewed as {@code supertypeSymbol}, preserving explicit
   * nullness annotations in {@code extends} and {@code implements} clauses.
   *
   * <p>This method differs from {@link Types#asSuper(Type, Symbol)} in that it applies each
   * inheritance-edge substitution separately with NullAway's annotation-preserving substitution.
   * Consequently, it preserves annotations nested inside a type argument, such as the annotation in
   * {@code class C<T> implements I<List<@Nullable T>>}.
   *
   * @param subtype the type to start from
   * @param supertypeSymbol the symbol of the desired supertype
   * @param types the types instance
   * @param config the NullAway config
   * @return the annotated supertype, or {@code null} if no inheritance path exists or a raw type
   *     prevents annotation-preserving substitution
   */
  public static @Nullable Type getAnnotatedSupertype(
      DeclaredType subtype, Symbol.ClassSymbol supertypeSymbol, Types types, Config config) {
    Type currentType = (Type) subtype;
    if (currentType.tsym.equals(supertypeSymbol)) {
      return currentType;
    }
    List<DeclaredType> path = inheritancePath(subtype, supertypeSymbol, types);
    if (path.isEmpty()) {
      return null;
    }
    for (int i = 1; i < path.size(); i++) {
      Type.ClassType currentClassType = (Type.ClassType) currentType;
      Type.ClassType currentFormalType = (Type.ClassType) currentClassType.tsym.type;
      if (currentClassType.isRaw()) {
        return null;
      }
      currentType =
          TypeSubstitutionUtils.subst(
              types,
              (Type) path.get(i),
              currentFormalType.allparams(),
              currentClassType.allparams(),
              config);
    }
    return currentType;
  }

  private static boolean dfsWithFormals(
      Type.ClassType currentFormal,
      Symbol.ClassSymbol targetOwner,
      Types types,
      List<DeclaredType> out,
      Set<Symbol.ClassSymbol> seen) {

    if (!seen.add((Symbol.ClassSymbol) currentFormal.tsym)) {
      return false; // avoid visiting paths redundantly
    }

    for (Type supFormal : types.directSupertypes(currentFormal)) {
      DeclaredType dt = (DeclaredType) supFormal; // version from the extends / implements clause
      if (dt.asElement().equals(targetOwner)) {
        out.add(dt);
        return true;
      }
      if (dfsWithFormals((Type.ClassType) ((Type) dt).tsym.type, targetOwner, types, out, seen)) {
        out.add(dt);
        return true;
      }
    }
    return false;
  }

  /**
   * Computes the inheritance path from {@code t} to {@code supertypeSymbol}. Each {@link
   * DeclaredType} in the list is the type as it appears in the relevant {@code extends} or {@code
   * implements} clause along the path, including any annotations on type arguments in the clauses.
   *
   * @param t the type to start from
   * @param supertypeSymbol the supertype symbol
   * @param types the types instance
   * @return the inheritance path from {@code t} to {@code supertypeSymbol}
   */
  private static List<DeclaredType> inheritancePath(
      DeclaredType t, Symbol.ClassSymbol supertypeSymbol, Types types) {
    List<DeclaredType> reversed = new ArrayList<>();
    if (dfsWithFormals(
        (Type.ClassType) ((Type) t).tsym.type, supertypeSymbol, types, reversed, new HashSet<>())) {

      Collections.reverse(reversed);
      // prepend the concrete start‑type
      List<DeclaredType> res = new ArrayList<>(reversed.size() + 1);
      res.add(t);
      res.addAll(reversed);
      return Collections.unmodifiableList(res);
    }
    return Collections.emptyList();
  }
}
