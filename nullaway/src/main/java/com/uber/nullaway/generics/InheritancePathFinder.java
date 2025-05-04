package com.uber.nullaway.generics;

import static com.uber.nullaway.Nullness.hasNullableAnnotation;

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
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

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

  public static Set<Symbol.TypeVariableSymbol> ofDeclaringType(
      List<DeclaredType> path, Types types, Config config) {

    if (path.isEmpty()) {
      return Collections.emptySet();
    }

    /* ------------------------------------------------------------------
     * STEP 0 – handle the concrete type itself (index 0)                */
    DeclaredType concrete = path.get(0);
    Type.ClassType concreteCt = (Type.ClassType) concrete;
    Symbol.ClassSymbol concreteSym = (Symbol.ClassSymbol) concreteCt.tsym;

    List<? extends Symbol.TypeVariableSymbol> concreteFormals = concreteSym.getTypeParameters();
    List<? extends TypeMirror> concreteActuals = concrete.getTypeArguments();

    Set<Symbol.TypeVariableSymbol> nullableSoFar = new HashSet<>();

    for (int i = 0; i < concreteActuals.size(); i++) {
      if (hasNullableAnnotation(concreteActuals.get(i).getAnnotationMirrors().stream(), config)) {
        nullableSoFar.add(concreteFormals.get(i));
      }
    }

    /* ------------------------------------------------------------------
     * STEP 1 … N – walk the supertypes, propagating upward              */
    for (int idx = 1; idx < path.size(); idx++) {
      DeclaredType dt = path.get(idx); // current super‑type
      Type.ClassType ct = (Type.ClassType) dt;
      Symbol.ClassSymbol cls = (Symbol.ClassSymbol) ct.tsym;

      List<? extends Symbol.TypeVariableSymbol> formals = cls.getTypeParameters();
      List<? extends TypeMirror> actuals = dt.getTypeArguments();

      Set<Symbol.TypeVariableSymbol> nullableHere = new HashSet<>();

      for (int j = 0; j < actuals.size(); j++) {
        TypeMirror arg = actuals.get(j);

        boolean nullable =
            hasNullableAnnotation(arg.getAnnotationMirrors().stream(), config) // rule (1)
                || (arg.getKind() == TypeKind.TYPEVAR // rule (2)
                    && nullableSoFar.contains(((Type.TypeVar) arg).tsym));

        if (nullable) {
          nullableHere.add(formals.get(j));
        }
      }

      /* If this is the declaring type, we’re done. */
      if (idx == path.size() - 1) {
        return Collections.unmodifiableSet(nullableHere);
      }

      /* Otherwise push info upward. */
      nullableSoFar = nullableHere;
    }

    // unreachable
    return Collections.emptySet();
  }
}
