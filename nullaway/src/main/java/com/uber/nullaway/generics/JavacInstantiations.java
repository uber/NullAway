package com.uber.nullaway.generics;

import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import org.jspecify.annotations.Nullable;

/** Recovers javac's method type-variable instantiations without interpreting their annotations. */
final class JavacInstantiations {

  private JavacInstantiations() {}

  /**
   * Recovers method type-variable instantiations by matching a receiver-substituted declaration
   * against javac's attributed method-select type.
   *
   * <p>For example, matching {@code <T> List<T> pair(T, T)} against {@code List<String>
   * pair(String, String)} yields {@code T -> String}: both parameters and the return type agree on
   * the instantiation. This compares two views of the same method, not arbitrary subtypes.
   *
   * <p>The result may be partial. A variable with no matched occurrence is omitted. If any
   * occurrence is missing, erroneous, unresolved, or conflicts with another occurrence, that
   * variable is rejected entirely, even if a later occurrence matches. Other variables can still be
   * recovered. Declaration bounds are never substituted for missing instantiations, and bounds of
   * fixed type variables are not traversed. The solver separately decides whether it supports a
   * recovered type.
   *
   * @param declaration the method declaration type after substituting receiver type arguments
   * @param attributed javac's attributed type of the method select for the same method invocation
   * @param variables the method type-variable elements whose instantiations should be recovered;
   *     other type variables are treated as fixed
   * @param types javac's type utilities, used to compare repeated instantiations for equality
   * @return a possibly partial map from the requested variables to their consistent instantiations;
   *     absent or rejected variables have no entry
   */
  static Map<Element, Type> inferInstantiations(
      Type declaration, Type attributed, List<? extends Element> variables, Types types) {
    Matcher matcher = new Matcher(variables, types);
    matcher.match(declaration, attributed);
    return matcher.instantiations;
  }

  /** Matches syntactic type positions, deliberately not traversing type-variable bounds. */
  private static final class Matcher {
    private final Set<Element> variables;
    private final Types types;
    private final Map<Element, Type> instantiations = new LinkedHashMap<>();
    private final Set<Element> rejected = new LinkedHashSet<>();

    Matcher(List<? extends Element> variables, Types types) {
      this.variables = new LinkedHashSet<>(variables);
      this.types = types;
    }

    /** Records consistent variable instantiations at corresponding type positions. */
    private void match(Type declaration, @Nullable Type attributed) {
      if (declaration instanceof Type.ForAll quantified) {
        match(quantified.qtype, attributed);
      } else if (attributed instanceof Type.ForAll quantified) {
        match(declaration, quantified.qtype);
      } else if (declaration instanceof Type.CapturedType) {
        // Synthesized capture bounds are not attributed instantiations.
      } else if (declaration instanceof Type.TypeVar variable) {
        Element symbol = variable.asElement();
        if (!variables.contains(symbol) || rejected.contains(symbol)) {
          return;
        }
        Type previous = instantiations.get(symbol);
        if (attributed == null
            || attributed.isErroneous()
            || containsInferenceVariable(attributed)
            || (previous != null && !types.isSameType(previous, attributed))) {
          rejected.add(symbol);
          instantiations.remove(symbol);
        } else {
          instantiations.put(symbol, attributed);
        }
      } else if (declaration instanceof Type.MethodType method) {
        Type.MethodType actual = attributed instanceof Type.MethodType m ? m : null;
        matchLists(method.argtypes, actual == null ? List.of() : actual.argtypes);
        match(method.restype, actual == null ? null : actual.restype);
        matchLists(method.thrown, actual == null ? List.of() : actual.thrown);
      } else if (declaration instanceof Type.ArrayType array) {
        match(array.elemtype, attributed instanceof Type.ArrayType a ? a.elemtype : null);
      } else if (declaration instanceof Type.ClassType clazz) {
        // These are two views of the same executable, not an arbitrary subtype comparison.
        Type.ClassType actual =
            attributed instanceof Type.ClassType c && clazz.tsym.equals(c.tsym) && !c.isRaw()
                ? c
                : null;
        match(clazz.getEnclosingType(), actual == null ? null : actual.getEnclosingType());
        matchLists(
            clazz.getTypeArguments(), actual == null ? List.of() : actual.getTypeArguments());
      } else if (declaration instanceof Type.WildcardType wildcard && !wildcard.isUnbound()) {
        if (attributed instanceof Type.WildcardType actual) {
          match(wildcard.type, wildcard.kind == actual.kind ? actual.type : null);
        } else {
          match(wildcard.type, null);
        }
      }
    }

    /**
     * Rejects an entire unmatched list rather than silently truncating its variable occurrences.
     */
    private void matchLists(List<Type> declaration, List<Type> attributed) {
      boolean sameSize = declaration.size() == attributed.size();
      for (int i = 0; i < declaration.size(); i++) {
        match(declaration.get(i), sameSize ? attributed.get(i) : null);
      }
    }

    /** Detects unresolved listed variables without traversing a fixed variable's bounds. */
    private boolean containsInferenceVariable(Type type) {
      if (type instanceof Type.TypeVar variable) {
        return variables.contains(variable.asElement());
      }
      if (type instanceof Type.ArrayType array) {
        return containsInferenceVariable(array.elemtype);
      }
      if (type instanceof Type.ClassType clazz) {
        if (containsInferenceVariable(clazz.getEnclosingType())) {
          return true;
        }
        for (Type argument : clazz.getTypeArguments()) {
          if (containsInferenceVariable(argument)) {
            return true;
          }
        }
      }
      if (type instanceof Type.WildcardType wildcard && !wildcard.isUnbound()) {
        return containsInferenceVariable(wildcard.type);
      }
      return false;
    }
  }
}
