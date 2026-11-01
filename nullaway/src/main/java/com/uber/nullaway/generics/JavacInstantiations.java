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
final class JavacInstantiations {

  private JavacInstantiations() {}

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

  /**
   * Returns whether a variable occurs in the executable shape itself, excluding declaration bounds.
   * A variable mentioned only in a peer's bound has no observable attributed occurrence to match.
   */
  static boolean containsVariable(Type type, Element variable, VisitorState state, Config config) {
    Matcher matcher = new Matcher(List.of(variable), state.getTypes(), config);
    Set<Element> occurrences = new LinkedHashSet<>();
    matcher.collectVariables(type, occurrences, Collections.newSetFromMap(new IdentityHashMap<>()));
    return !occurrences.isEmpty();
  }

  /** Matches syntactic type positions, deliberately not traversing type-variable bounds. */
  private static final class Matcher
      extends Types.DefaultTypeVisitor<@Nullable Void, @Nullable Type> {
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
      if (attributed instanceof ForAll quantified) {
        match(declaration, quantified.qtype);
        return;
      }
      if (declaration.isErroneous() || attributed.isErroneous()) {
        rejectVariables(declaration);
        return;
      }
      declaration.accept(this, attributed);
    }

    /** Ignores leaf types and other types with no syntactic variable occurrences. */
    @Override
    public @Nullable Void visitType(Type declaration, @Nullable Type attributed) {
      return null;
    }

    /** Matches a quantified declaration's executable type without visiting its variable bounds. */
    @Override
    public @Nullable Void visitForAll(Type.ForAll declaration, @Nullable Type attributed) {
      match(declaration.qtype, attributed);
      return null;
    }

    /** Matches a capture's original wildcard, never its synthesized bounds or capture symbol. */
    @Override
    public @Nullable Void visitCapturedType(
        Type.CapturedType declaration, @Nullable Type attributed) {
      match(declaration.wildcard, attributed);
      return null;
    }

    /** Records a requested variable unless any of its occurrences has rejected it. */
    @Override
    public @Nullable Void visitTypeVar(Type.TypeVar declaration, @Nullable Type attributed) {
      Element symbol = variableSymbol(declaration);
      if (variables.contains(symbol)) {
        if (attributed == null) {
          reject(symbol);
        } else {
          record(symbol, attributed);
        }
      }
      return null;
    }

    /** Matches parameter, return, and thrown types in their original order. */
    @Override
    public @Nullable Void visitMethodType(Type.MethodType declaration, @Nullable Type attributed) {
      Type.MethodType actual = attributed instanceof Type.MethodType m ? m : null;
      matchLists(declaration.argtypes, actual == null ? List.of() : actual.argtypes);
      match(declaration.restype, actual == null ? null : actual.restype);
      matchLists(declaration.thrown, actual == null ? List.of() : actual.thrown);
      return null;
    }

    /** Matches an array's component, rejecting its variables if the array shape is missing. */
    @Override
    public @Nullable Void visitArrayType(Type.ArrayType declaration, @Nullable Type attributed) {
      match(declaration.elemtype, attributed instanceof Type.ArrayType a ? a.elemtype : null);
      return null;
    }

    /** Aligns an attributed class to the declaration before matching enclosing types and arguments. */
    @Override
    public @Nullable Void visitClassType(Type.ClassType declaration, @Nullable Type attributed) {
      Type aligned = attributed;
      if (attributed instanceof ClassType actual
          && !declaration.tsym.equals(actual.tsym)
          && declaration.tsym instanceof Symbol.ClassSymbol symbol) {
        aligned = TypeSubstitutionUtils.asSuper(types, actual, symbol, config);
      }
      Type.ClassType actual =
          aligned instanceof ClassType c && declaration.tsym.equals(c.tsym) && !c.isRaw()
              ? c
              : null;
      match(declaration.getEnclosingType(), actual == null ? null : actual.getEnclosingType());
      matchLists(
          declaration.getTypeArguments(), actual == null ? List.of() : actual.getTypeArguments());
      return null;
    }

    /** Rejects occurrences in error types, which can contain class-position arguments. */
    @Override
    public @Nullable Void visitErrorType(Type.ErrorType declaration, @Nullable Type attributed) {
      rejectVariables(declaration);
      return null;
    }

    /** Matches explicit wildcard bounds, including original captures and concrete adapted bounds. */
    @Override
    public @Nullable Void visitWildcardType(
        Type.WildcardType declaration, @Nullable Type attributed) {
      if (attributed instanceof CapturedType capture) {
        match(declaration, capture.wildcard);
      } else if (!declaration.isUnbound()) {
        if (attributed instanceof WildcardType actual) {
          match(declaration.type, declaration.kind == actual.kind ? actual.type : null);
        } else {
          // A fixed type variable's upper bound is not evidence of an instantiation.
          match(declaration.type, attributed instanceof TypeVar ? null : attributed);
        }
      }
      return null;
    }

    /** Rejects an unmatched list rather than silently truncating its variable occurrences. */
    private void matchLists(List<Type> declaration, List<Type> attributed) {
      boolean sameSize = declaration.size() == attributed.size();
      for (int i = 0; i < declaration.size(); i++) {
        match(declaration.get(i), sameSize ? attributed.get(i) : null);
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
