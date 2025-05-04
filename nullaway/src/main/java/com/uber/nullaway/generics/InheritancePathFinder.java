package com.uber.nullaway.generics;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.type.DeclaredType;

/**
 * Find the chain of supertypes (with type arguments) by which 'start' inherits the method
 * 'targetMethod'.
 */
public class InheritancePathFinder {

  private static boolean dfsWithFormals(
      Type.ClassType currentFormal,
      Symbol.ClassSymbol targetOwner,
      Types types,
      List<DeclaredType> out,
      Set<Symbol.ClassSymbol> seen) {

    if (!seen.add((Symbol.ClassSymbol) currentFormal.tsym)) {
      return false; // avoid cycles
    }

    for (Type supFormal : types.directSupertypes(currentFormal)) {
      DeclaredType dt = (DeclaredType) supFormal; // still has T‑vars + annos
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

  @SuppressWarnings("MixedMutabilityReturnType")
  public static List<DeclaredType> inheritancePathKeepingFormals(
      DeclaredType start, // SubSupplier<Foo>
      Symbol.MethodSymbol targetMethod,
      Types types) {

    List<DeclaredType> reversed = new ArrayList<>();
    if (dfsWithFormals(
        (Type.ClassType) ((Type) start).tsym.type, // !! symbol.type
        (Symbol.ClassSymbol) targetMethod.owner,
        types,
        reversed,
        new HashSet<>())) {

      Collections.reverse(reversed);
      // prepend the concrete start‑type so you still know the actual instantiation
      List<DeclaredType> res = new ArrayList<>(reversed.size() + 1);
      res.add(start); // SubSupplier<Foo>
      res.addAll(reversed); // Supplier<@Nullable T2>
      return res;
    }
    return Collections.emptyList();
  }
}
