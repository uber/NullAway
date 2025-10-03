package com.uber.nullaway.generics;

import static com.uber.nullaway.Nullness.isNonNullAnnotation;
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
 * Utility to deal with nullability annotations within {@code extends} or {@code implements} clauses
 * in class declarations, e.g., {@code class Foo extends Supplier<@Nullable T2>}.
 */
public class ClassDeclarationNullnessAnnotUtils {

  /**
   * Given a type {@code t} and a method symbol {@code method}, let {@code t'} be the supertype of
   * {@code t} that declares {@code method}. This method returns a map from each type variable
   * {@code X} of {@code t'} to the nullness annotation for {@code X} implied by the inheritance
   * path from {@code t} to {@code t'}, accounting for nullness annotations in {@code extends} /
   * {@code implements} clauses. If there is no entry for {@code X}, the inheritance path adds no
   * annotation to {@code X}.
   *
   * @param t the type to start from
   * @param method the method symbol
   * @param types the types instance
   * @param config the NullAway config
   * @return the map from type variable symbols to their nullness annotations
   */
  public static Map<Symbol.TypeVariableSymbol, AnnotationMirror> getAnnotsOnTypeVarsFromSubtypes(
      DeclaredType t, Symbol.MethodSymbol method, Types types, Config config) {
    List<DeclaredType> path = inheritancePath(t, method, types);
    return annotsFromSubtypesForInheritancePath(path, config);
  }

  /**
   * Given a type {@code t} and a symbol {@code supertypeSymbol} for a supertype {@code t'} of
   * {@code t}. This method returns a map from each type variable {@code X} of {@code t'} to the
   * nullness annotation for {@code X} implied by the inheritance path from {@code t} to {@code t'},
   * accounting for nullness annotations in {@code extends} / {@code implements} clauses. If there
   * is no entry for {@code X}, the inheritance path adds no annotation to {@code X}.
   *
   * @param t the type to start from
   * @param supertypeSymbol the supertype symbol
   * @param types the types instance
   * @param config the NullAway config
   * @return the map from type variable symbols to their nullness annotations
   */
  public static Map<Symbol.TypeVariableSymbol, AnnotationMirror> getAnnotsOnTypeVarsFromSubtypes(
      DeclaredType t, Symbol.ClassSymbol supertypeSymbol, Types types, Config config) {
    List<DeclaredType> path = inheritancePath(t, supertypeSymbol, types);
    return annotsFromSubtypesForInheritancePath(path, config);
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
   * Computes the inheritance path from {@code t} to the class that declares {@code method}. Each
   * {@link DeclaredType} in the list is the type as it appears in the relevant {@code extends} or
   * {@code implements} clause along the path, including any annotations on type arguments in the
   * clauses.
   *
   * @param t the type to start from
   * @param method the method symbol
   * @param types the types instance
   * @return the inheritance path from {@code t} to the class that declares {@code method}
   */
  private static List<DeclaredType> inheritancePath(
      DeclaredType t, Symbol.MethodSymbol method, Types types) {

    return inheritancePath(t, (Symbol.ClassSymbol) method.owner, types);
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

  private static Map<Symbol.TypeVariableSymbol, AnnotationMirror>
      annotsFromSubtypesForInheritancePath(List<DeclaredType> path, Config config) {

    if (path.isEmpty()) {
      return Collections.emptyMap();
    }

    Map<Symbol.TypeVariableSymbol, AnnotationMirror> typeVarToAnnotations = new LinkedHashMap<>();
    for (int idx = 0; idx < path.size(); idx++) {
      DeclaredType dt = path.get(idx);
      Type.ClassType ct = (Type.ClassType) dt;
      Symbol.ClassSymbol cls = (Symbol.ClassSymbol) ct.tsym;

      List<? extends Symbol.TypeVariableSymbol> formals = cls.getTypeParameters();
      List<? extends TypeMirror> actuals = dt.getTypeArguments();

      Map<Symbol.TypeVariableSymbol, AnnotationMirror> curNullnessAnnotations =
          new LinkedHashMap<>();

      for (int j = 0; j < actuals.size(); j++) {
        TypeMirror arg = actuals.get(j);
        // if there is a direct nullness annotation, that wins
        AnnotationMirror nullnessAnnotation =
            getNullnessAnnotation(arg.getAnnotationMirrors(), config);
        // otherwise, see if we have a type variable argument with a previous nullness annotation
        if (nullnessAnnotation == null) {
          Symbol.TypeSymbol argtsym = ((Type) arg).tsym;
          if (argtsym instanceof Symbol.TypeVariableSymbol) {
            nullnessAnnotation = typeVarToAnnotations.get(argtsym);
          }
        }
        if (nullnessAnnotation != null) {
          curNullnessAnnotations.put(formals.get(j), nullnessAnnotation);
        }
      }
      /* Push info upward. */
      typeVarToAnnotations = curNullnessAnnotations;
    }
    return Collections.unmodifiableMap(typeVarToAnnotations);
  }

  private static @Nullable AnnotationMirror getNullnessAnnotation(
      List<? extends AnnotationMirror> annotations, Config config) {
    for (AnnotationMirror annotation : annotations) {
      String annotTypeStr = annotation.getAnnotationType().toString();
      if (isNullableAnnotation(annotTypeStr, config) || isNonNullAnnotation(annotTypeStr, config)) {
        return annotation;
      }
    }
    return null;
  }
}
