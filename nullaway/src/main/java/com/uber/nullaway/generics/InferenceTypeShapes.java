package com.uber.nullaway.generics;

import com.google.errorprone.VisitorState;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.code.Type.CapturedType;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.code.Type.ForAll;
import com.sun.tools.javac.code.Type.MethodType;
import com.sun.tools.javac.code.Type.TypeVar;
import com.sun.tools.javac.code.Type.WildcardType;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.Config;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeVariable;
import org.jspecify.annotations.Nullable;

/** Recovers javac's nominal instantiations without inferring their nullness. */
final class InferenceTypeShapes {

  private InferenceTypeShapes() {}

  /**
   * Matches a declaration against its javac-attributed shape, recovering only variables in {@code
   * vars}.
   *
   * <p>For calls, the declaration should be the receiver-substituted executable type and the
   * attributed type should be the invocation's method-select type (or attributed constructor
   * signature). Class instantiations can instead be recovered by matching the constructed class's
   * declaration against its attributed constructed type. For anonymous classes, callers must supply
   * their existing view of the constructed class, not the anonymous class declaration.
   *
   * <p>Returned types retain javac's metadata, but their annotations are provisional, not nullness
   * evidence. Repeated occurrences must have the same nominal shape, ignoring annotations; a
   * conflicting or unresolved variable is omitted. Variables absent from the compared shapes are
   * not defaulted to their declaration bounds. Fixed, non-listed type variables remain fixed.
   */
  static Map<Element, Type> inferInstantiations(
      Type declaration,
      Type attributed,
      List<? extends Element> vars,
      VisitorState state,
      Config config) {
    Matcher matcher = new Matcher(vars, state.getTypes(), config);
    matcher.match(declaration, attributed);
    return matcher.instantiations;
  }

  /** Stateful matching for one pair of attributed and declaration shapes. */
  private static final class Matcher {
    private final Set<Element> variables;
    private final Types types;
    private final Config config;
    private final Map<Element, Type> instantiations = new LinkedHashMap<>();
    private final Set<Element> rejected = new LinkedHashSet<>();
    private final IdentityHashMap<Type, Set<Type>> seen = new IdentityHashMap<>();

    private Matcher(List<? extends Element> vars, Types types, Config config) {
      this.variables = new LinkedHashSet<>(vars);
      this.types = types;
      this.config = config;
    }

    /** Traverses corresponding shapes, never replacing a fixed variable with its bound. */
    private void match(@Nullable Type declaration, @Nullable Type attributed) {
      if (declaration == null || attributed == null) {
        rejectVariables(declaration);
        return;
      }
      if (!enter(seen, declaration, attributed)) {
        return;
      }
      if (declaration instanceof ForAll quantified) {
        match(quantified.qtype, attributed);
        return;
      }
      if (attributed instanceof ForAll quantified) {
        match(declaration, quantified.qtype);
        return;
      }
      if (declaration.isErroneous() || attributed.isErroneous()) {
        rejectVariables(declaration);
        return;
      }
      // A capture's originating wildcard is explicit shape evidence; its synthesized bounds are
      // not.
      if (declaration instanceof CapturedType capture) {
        match(capture.wildcard, attributed);
        return;
      }
      if (declaration instanceof TypeVar variable) {
        Element symbol = variableSymbol(variable);
        if (variables.contains(symbol)) {
          record(symbol, attributed);
        }
        return;
      }
      if (declaration instanceof WildcardType wildcard) {
        matchWildcard(wildcard, attributed);
        return;
      }
      if (declaration instanceof MethodType method && attributed instanceof MethodType actual) {
        matchLists(method.getParameterTypes(), actual.getParameterTypes());
        match(method.getReturnType(), actual.getReturnType());
        matchLists(method.getThrownTypes(), actual.getThrownTypes());
        return;
      }
      if (declaration instanceof ArrayType array && attributed instanceof ArrayType actual) {
        match(array.elemtype, actual.elemtype);
        return;
      }
      if (declaration instanceof ClassType clazz && attributed instanceof ClassType actual) {
        Type aligned = actual;
        if (!clazz.tsym.equals(actual.tsym) && clazz.tsym instanceof Symbol.ClassSymbol symbol) {
          aligned = TypeSubstitutionUtils.asSuper(types, actual, symbol, config);
        }
        if (!(aligned instanceof ClassType alignedClass) || !clazz.tsym.equals(alignedClass.tsym)) {
          rejectVariables(declaration);
          return;
        }
        match(clazz.getEnclosingType(), alignedClass.getEnclosingType());
        matchLists(clazz.getTypeArguments(), alignedClass.getTypeArguments());
        return;
      }
      rejectVariables(declaration);
    }

    /** Matches only explicit wildcard bounds, including a capture's original wildcard. */
    private void matchWildcard(WildcardType declaration, Type attributed) {
      if (attributed instanceof CapturedType capture) {
        matchWildcard(declaration, capture.wildcard);
        return;
      }
      if (declaration.isUnbound()) {
        return;
      }
      if (attributed instanceof WildcardType wildcard) {
        if (declaration.kind != wildcard.kind) {
          rejectVariables(declaration);
        } else {
          match(declaration.type, wildcard.type);
        }
        return;
      }
      // A concrete adapted bound is useful, but a fixed type variable's upper bound is not
      // evidence.
      if (attributed instanceof TypeVar) {
        rejectVariables(declaration);
        return;
      }
      match(declaration.type, attributed);
    }

    /** Pairs lists only when their arities agree; truncation would hide contradictory evidence. */
    private void matchLists(List<Type> declaration, List<Type> attributed) {
      if (declaration.size() != attributed.size()) {
        for (Type type : declaration) {
          rejectVariables(type);
        }
        return;
      }
      for (int i = 0; i < declaration.size(); i++) {
        match(declaration.get(i), attributed.get(i));
      }
    }

    /**
     * Records attributed evidence unless it is unresolved or conflicts with an earlier occurrence.
     */
    private void record(Element variable, Type attributed) {
      if (rejected.contains(variable)) {
        return;
      }
      Set<Element> unresolved = new LinkedHashSet<>();
      collectVariables(attributed, unresolved, Collections.newSetFromMap(new IdentityHashMap<>()));
      if (!unresolved.isEmpty() || attributed.isErroneous()) {
        reject(variable);
        return;
      }
      Type previous = instantiations.get(variable);
      if (previous == null) {
        instantiations.put(variable, attributed);
      } else if (!sameShape(previous, attributed, new IdentityHashMap<>())) {
        reject(variable);
      }
    }

    /** Permanently removes all listed variables occurring in a mismatched declaration subtree. */
    private void rejectVariables(@Nullable Type declaration) {
      Set<Element> affected = new LinkedHashSet<>();
      collectVariables(declaration, affected, Collections.newSetFromMap(new IdentityHashMap<>()));
      for (Element variable : affected) {
        reject(variable);
      }
    }

    /** Removes an inconsistent variable so that later occurrences cannot reinstate it. */
    private void reject(Element variable) {
      rejected.add(variable);
      instantiations.remove(variable);
    }

    /** Collects syntactic occurrences, not variables merely mentioned by declaration bounds. */
    private void collectVariables(@Nullable Type type, Set<Element> result, Set<Type> visited) {
      if (type == null || !visited.add(type)) {
        return;
      }
      if (type instanceof CapturedType capture) {
        collectVariables(capture.wildcard, result, visited);
      } else if (type instanceof TypeVar variable) {
        Element symbol = variableSymbol(variable);
        if (variables.contains(symbol)) {
          result.add(symbol);
        }
      } else if (type instanceof ForAll quantified) {
        collectVariables(quantified.qtype, result, visited);
      } else if (type instanceof MethodType method) {
        for (Type parameter : method.getParameterTypes()) {
          collectVariables(parameter, result, visited);
        }
        collectVariables(method.getReturnType(), result, visited);
        for (Type thrown : method.getThrownTypes()) {
          collectVariables(thrown, result, visited);
        }
      } else if (type instanceof ArrayType array) {
        collectVariables(array.elemtype, result, visited);
      } else if (type instanceof WildcardType wildcard) {
        if (!wildcard.isUnbound()) {
          collectVariables(wildcard.type, result, visited);
        }
      } else if (type instanceof ClassType clazz) {
        collectVariables(clazz.getEnclosingType(), result, visited);
        for (Type argument : clazz.getTypeArguments()) {
          collectVariables(argument, result, visited);
        }
      }
    }

    /** Compares exact nominal shapes, without subtype alignment or annotation comparisons. */
    private boolean sameShape(Type left, Type right, Map<Type, Set<Type>> compared) {
      if (!enter(compared, left, right)) {
        return true;
      }
      if (left instanceof CapturedType || right instanceof CapturedType) {
        // Distinct captures cannot be equated just because their bounds happen to agree.
        return left instanceof CapturedType
            && right instanceof CapturedType
            && variableSymbol((TypeVar) left).equals(variableSymbol((TypeVar) right));
      }
      if (left instanceof TypeVar || right instanceof TypeVar) {
        return left instanceof TypeVar leftVariable
            && right instanceof TypeVar rightVariable
            && variableSymbol(leftVariable).equals(variableSymbol(rightVariable));
      }
      if (left instanceof WildcardType || right instanceof WildcardType) {
        return left instanceof WildcardType leftWildcard
            && right instanceof WildcardType rightWildcard
            && leftWildcard.kind == rightWildcard.kind
            && (leftWildcard.isUnbound()
                || sameShape(leftWildcard.type, rightWildcard.type, compared));
      }
      if (left instanceof ArrayType || right instanceof ArrayType) {
        return left instanceof ArrayType leftArray
            && right instanceof ArrayType rightArray
            && sameShape(leftArray.elemtype, rightArray.elemtype, compared);
      }
      if (left instanceof ClassType || right instanceof ClassType) {
        return left instanceof ClassType leftClass
            && right instanceof ClassType rightClass
            && leftClass.tsym.equals(rightClass.tsym)
            && sameShape(leftClass.getEnclosingType(), rightClass.getEnclosingType(), compared)
            && sameShapes(leftClass.getTypeArguments(), rightClass.getTypeArguments(), compared);
      }
      return types.isSameType(left, right);
    }

    /** Checks corresponding type lists for exact, annotation-independent shape agreement. */
    private boolean sameShapes(List<Type> left, List<Type> right, Map<Type, Set<Type>> compared) {
      if (left.size() != right.size()) {
        return false;
      }
      for (int i = 0; i < left.size(); i++) {
        if (!sameShape(left.get(i), right.get(i), compared)) {
          return false;
        }
      }
      return true;
    }
  }

  /** Uses the language-model variable symbol rather than comparing TypeVar wrapper identities. */
  private static Element variableSymbol(TypeVar variable) {
    return ((TypeVariable) variable).asElement();
  }

  /** Registers an identity pair, returning false for an already visited recursive edge. */
  private static boolean enter(Map<Type, Set<Type>> pairs, Type declaration, Type attributed) {
    return pairs
        .computeIfAbsent(declaration, unused -> Collections.newSetFromMap(new IdentityHashMap<>()))
        .add(attributed);
  }
}
