package com.uber.nullaway.generics;

import static com.uber.nullaway.Nullness.isNullableAnnotation;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.Config;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import org.jspecify.annotations.Nullable;

/**
 * Utility to deal with nullability annotations within class declarations, e.g., {@code class Foo
 * extends Supplier<@Nullable T2>}.
 */
public class ClassDeclarationNullnessAnnotUtils {

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
      return Collections.unmodifiableList(res);
    }
    return Collections.emptyList();
  }

  public static Map<Symbol.TypeVariableSymbol, AnnotationMirror> ofDeclaringType(
      List<DeclaredType> path, Config config) {

    if (path.isEmpty()) {
      return Collections.emptyMap();
    }

    /* ------------------------------------------------------------------
     * STEP 0 – handle the concrete type itself (index 0)                */
    DeclaredType concrete = path.get(0);
    Type.ClassType concreteCt = (Type.ClassType) concrete;
    Symbol.ClassSymbol concreteSym = (Symbol.ClassSymbol) concreteCt.tsym;

    List<? extends Symbol.TypeVariableSymbol> concreteFormals = concreteSym.getTypeParameters();
    List<? extends TypeMirror> concreteActuals = concrete.getTypeArguments();

    Map<Symbol.TypeVariableSymbol, AnnotationMirror> annotationsSoFar = new LinkedHashMap<>();

    for (int i = 0; i < concreteActuals.size(); i++) {
      TypeMirror actual = concreteActuals.get(i);
      AnnotationMirror nullableAnnotation =
          getNullableAnnotation(actual.getAnnotationMirrors(), config);
      if (nullableAnnotation != null) {
        annotationsSoFar.put(concreteFormals.get(i), nullableAnnotation);
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

      Map<Symbol.TypeVariableSymbol, AnnotationMirror> nullableHere = new LinkedHashMap<>();

      for (int j = 0; j < actuals.size(); j++) {
        TypeMirror arg = actuals.get(j);
        AnnotationMirror nullableAnnotation = null;
        Symbol.TypeSymbol argtsym = ((Type) arg).tsym;
        if (argtsym instanceof Symbol.TypeVariableSymbol) {
          nullableAnnotation = annotationsSoFar.get(argtsym);
        }
        if (nullableAnnotation == null) {
          nullableAnnotation = getNullableAnnotation(arg.getAnnotationMirrors(), config);
        }
        if (nullableAnnotation != null) {
          nullableHere.put(formals.get(j), nullableAnnotation);
        }
      }
      /* Push info upward. */
      annotationsSoFar = nullableHere;
    }
    return Collections.unmodifiableMap(annotationsSoFar);
  }

  private static @Nullable AnnotationMirror getNullableAnnotation(
      List<? extends AnnotationMirror> annotations, Config config) {
    for (AnnotationMirror annotation : annotations) {
      if (isNullableAnnotation(annotation.getAnnotationType().toString(), config)) {
        return annotation;
      }
    }
    return null;
  }
}
