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
   * <p>Omits a variable if any occurrence has no matching type, has an erroneous type, still
   * contains one of {@code variables}, or disagrees with another occurrence. A later match does not
   * restore an omitted variable. Variables with no occurrences are also omitted. Declaration bounds
   * are not used to fill missing entries, and fixed type-variable bounds are not traversed.
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
  private static final class Matcher
      extends Types.DefaultTypeVisitor<@Nullable Void, @Nullable Type> {
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
      if (attributed instanceof Type.ForAll quantified) {
        match(declaration, quantified.qtype);
      } else {
        declaration.accept(this, attributed);
      }
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

    /** Ignores synthesized captures, whose bounds are not attributed instantiations. */
    @Override
    public @Nullable Void visitCapturedType(
        Type.CapturedType declaration, @Nullable Type attributed) {
      return null;
    }

    /** Records a requested variable unless any of its occurrences has rejected it. */
    @Override
    public @Nullable Void visitTypeVar(Type.TypeVar declaration, @Nullable Type attributed) {
      Element symbol = declaration.asElement();
      if (!variables.contains(symbol) || rejected.contains(symbol)) {
        return null;
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

    /** Matches enclosing types and arguments only for the same non-raw attributed class. */
    @Override
    public @Nullable Void visitClassType(Type.ClassType declaration, @Nullable Type attributed) {
      // These are two views of the same executable, not an arbitrary subtype comparison.
      Type.ClassType actual =
          attributed instanceof Type.ClassType c && declaration.tsym.equals(c.tsym) && !c.isRaw()
              ? c
              : null;
      match(declaration.getEnclosingType(), actual == null ? null : actual.getEnclosingType());
      matchLists(
          declaration.getTypeArguments(), actual == null ? List.of() : actual.getTypeArguments());
      return null;
    }

    /** Preserves class-position matching for error types, which are also class types. */
    @Override
    public @Nullable Void visitErrorType(Type.ErrorType declaration, @Nullable Type attributed) {
      return visitClassType(declaration, attributed);
    }

    /** Matches bounded wildcards only when their bound kinds agree. */
    @Override
    public @Nullable Void visitWildcardType(
        Type.WildcardType declaration, @Nullable Type attributed) {
      if (!declaration.isUnbound()) {
        match(
            declaration.type,
            attributed instanceof Type.WildcardType actual && declaration.kind == actual.kind
                ? actual.type
                : null);
      }
      return null;
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
