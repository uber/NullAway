package com.uber.nullaway.generics;

import com.google.common.collect.ImmutableList;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * A position inside a printed type, as the sequence of descents that reaches it from the root.
 *
 * <p>A position rather than an instance: javac hands back one {@code Type} instance for a plain
 * reference such as {@code String}, and substitution can put one instance at two type-argument
 * positions, so a node cannot be named by identity. The same path addresses the node for two
 * purposes that have to agree, a caret drawn under it by {@link GenericTypePrettyPrintingVisitor}
 * and the prose that names it in a diagnostic, which is why the steps are those the printer takes.
 */
final class TypePath {

  /** One descent into a type. */
  record Step(
      Kind kind, int index, Type.@Nullable TypeVar formal, Symbol.@Nullable ClassSymbol owner) {

    enum Kind {
      /** Into the type argument at {@link Step#index}. */
      TYPE_ARGUMENT,
      /** Into the declared upper bound of a wildcard. */
      WILDCARD_BOUND,
      /** Into the element type of an array. */
      ARRAY_ELEMENT,
      /** Into the enclosing instance type of an inner class. */
      ENCLOSING_TYPE
    }

    static Step typeArgument(
        int index, Type.@Nullable TypeVar formal, Symbol.@Nullable ClassSymbol owner) {
      return new Step(Kind.TYPE_ARGUMENT, index, formal, owner);
    }

    static Step wildcardBound() {
      return new Step(Kind.WILDCARD_BOUND, -1, null, null);
    }

    static Step arrayElement() {
      return new Step(Kind.ARRAY_ELEMENT, -1, null, null);
    }

    static Step enclosingType() {
      return new Step(Kind.ENCLOSING_TYPE, -1, null, null);
    }

    /**
     * Whether this step reaches the same node as {@code other}, ignoring the names carried for
     * prose. Two steps of the same kind and index address one node however they were built.
     */
    boolean reachesSameNodeAs(Step other) {
      return kind == other.kind && index == other.index;
    }

    /** Names this step for a reader, as {@code Map type argument K} or {@code array element}. */
    String describe() {
      return switch (kind) {
        case TYPE_ARGUMENT ->
            formal == null || owner == null
                ? "type argument " + (index + 1)
                : owner.getSimpleName() + " type argument " + formal.tsym.getSimpleName();
        case WILDCARD_BOUND -> "wildcard upper bound";
        case ARRAY_ELEMENT -> "array element";
        case ENCLOSING_TYPE -> "enclosing type";
      };
    }
  }

  private static final TypePath ROOT = new TypePath(ImmutableList.of());

  private final ImmutableList<Step> steps;

  private TypePath(ImmutableList<Step> steps) {
    this.steps = steps;
  }

  /** The path of the type itself, which no step has descended from. */
  static TypePath root() {
    return ROOT;
  }

  /** Returns this path extended by {@code step}. */
  TypePath then(Step step) {
    return new TypePath(ImmutableList.<Step>builder().addAll(steps).add(step).build());
  }

  boolean isRoot() {
    return steps.isEmpty();
  }

  /** Whether this path and {@code other} address the same node. */
  boolean reachesSameNodeAs(TypePath other) {
    if (steps.size() != other.steps.size()) {
      return false;
    }
    for (int i = 0; i < steps.size(); i++) {
      if (!steps.get(i).reachesSameNodeAs(other.steps.get(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Names this path for a reader, as {@code Map type argument V -> List type argument E -> wildcard
   * upper bound}. The arrow is ASCII so that the diagnostic survives a console encoding that has no
   * arrow character.
   */
  String describe() {
    return steps.stream().map(Step::describe).collect(Collectors.joining(" -> "));
  }
}
