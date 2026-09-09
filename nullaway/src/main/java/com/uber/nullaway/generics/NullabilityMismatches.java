package com.uber.nullaway.generics;

import com.google.errorprone.VisitorState;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.Config;
import com.uber.nullaway.handlers.Handler;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Finds the positions where a source type does not have the nullness a target type requires.
 *
 * <p>The search descends the two types together and reports the smallest pair of nodes that still
 * differs, so that a diagnostic can point at {@code @Nullable String} rather than at the {@code
 * Collection<@Nullable String>} that contains it. A reported node is not descended into, so the
 * positions are disjoint and a caret drawn under one cannot sit inside another.
 *
 * <p>The two sides are addressed differently, because only one of them is traversed as the reader
 * wrote it. The target is, so a mismatch names a {@link TypePath} in it, which reads as prose and
 * also tells the printer where to draw a caret. The source is viewed as the target's supertype at
 * every level, which is a type the reader never wrote, so a mismatch instead carries the source
 * node itself; the caller locates that instance in the type the reader did write.
 */
final class NullabilityMismatches {

  private final GenericsChecks checks;
  private final Config config;
  private final Handler handler;
  private final VisitorState state;

  NullabilityMismatches(GenericsChecks checks, Config config, Handler handler, VisitorState state) {
    this.checks = checks;
    this.config = config;
    this.handler = handler;
    this.state = state;
  }

  /**
   * One position where the source does not have the nullness the target requires.
   *
   * @param path the position in the target, which names the mismatch in prose and carries the
   *     target's caret
   * @param targetNode the smallest node the target requires
   * @param sourceNode the smallest node the source has there
   * @param targetCaret the deepest position the target prints, at or above {@code path}
   * @param sourcePrintedNode the deepest node the source prints for this mismatch, which is the
   *     wildcard itself where the bound at fault is one the wildcard leaves implicit
   * @param sourceCaret the deepest position the source prints, in the source as this traversal
   *     views it, which is the type the caller prints where that node sits nowhere in the type the
   *     reader wrote
   * @param sourceWildcard the source wildcard whose upper bound is at fault, or {@code null} where
   *     the source states the type outright
   * @param targetUpperBound the bound the target requires there, which is what writing an explicit
   *     bound on {@code sourceWildcard} would have to produce
   */
  record Mismatch(
      TypePath path,
      Type targetNode,
      Type sourceNode,
      TypePath targetCaret,
      Type sourcePrintedNode,
      TypePath sourceCaret,
      Type.@Nullable WildcardType sourceWildcard,
      @Nullable Type targetUpperBound) {}

  /**
   * Where a node sits in the target, and the deepest printed position a caret can go under.
   *
   * <p>The two part company as soon as a descent reaches something the printer does not show, such
   * as the upper bound an unbounded wildcard leaves implicit. Every step below such a node is
   * invisible too, so {@link #caret} stops there and stays.
   */
  private record Position(TypePath path, TypePath caret, boolean printed) {

    static Position root() {
      return new Position(TypePath.root(), TypePath.root(), true);
    }

    Position then(TypePath.Step step, boolean stepIsPrinted) {
      TypePath child = path.then(step);
      boolean childIsPrinted = printed && stepIsPrinted;
      return new Position(child, childIsPrinted ? child : caret, childIsPrinted);
    }
  }

  /**
   * The comparison of two upper bounds, which is the one place a difference may be repaired by
   * writing a bound out, and the one place a non-null source is accepted where a nullable one is
   * allowed.
   *
   * @param sourceWildcard the wildcard the source bound came from, or {@code null} where the source
   *     states the type outright
   * @param targetUpperBound the bound the target requires
   */
  private record BoundComparison(
      Type.@Nullable WildcardType sourceWildcard, Type targetUpperBound) {}

  /**
   * Returns every position where {@code sourceType} does not have the nullness {@code targetType}
   * requires, in the order a traversal of the target encounters them.
   */
  List<Mismatch> collect(Type targetType, Type sourceType) {
    List<Mismatch> mismatches = new ArrayList<>();
    collect(targetType, sourceType, Position.root(), sourceType, Position.root(), null, mismatches);
    return mismatches;
  }

  /**
   * Compares one node of the target with the node the source has there, and appends what it finds
   * to {@code mismatches}.
   *
   * @param target the node the target requires
   * @param source the node the source has there, in the view this traversal took of it
   * @param targetPosition where {@code target} sits in the target, and the deepest position the
   *     target prints at or above it
   * @param sourcePrintedNode the deepest node the source prints for this position, which stays on a
   *     wildcard whose bound the printed form does not show
   * @param sourcePosition the same as {@code targetPosition}, in the source as viewed here
   * @param bound the bound comparison this descent is part of, or {@code null} where the two nodes
   *     are compared as type arguments, which decides both what a repair could rewrite and whether
   *     a non-null source is accepted where a nullable one is allowed
   * @param mismatches the list to append to
   */
  private void collect(
      Type target,
      Type source,
      Position targetPosition,
      Type sourcePrintedNode,
      Position sourcePosition,
      @Nullable BoundComparison bound,
      List<Mismatch> mismatches) {
    Type.WildcardType targetWildcard = GenericsUtils.asWildcard(target);
    Type.WildcardType sourceWildcard = GenericsUtils.asWildcard(source);
    if (targetWildcard != null && sourceWildcard != null) {
      // the check compares the effective upper bounds, and only an `? extends X` wildcard prints
      // the bound it is compared by: `?` and `? super X` take one from a type variable
      Type targetUpperBound =
          GenericsUtils.wildcardUpperBound(targetWildcard, state, config, handler);
      Type sourceUpperBound =
          GenericsUtils.wildcardUpperBound(sourceWildcard, state, config, handler);
      collect(
          targetUpperBound,
          sourceUpperBound,
          targetPosition.then(
              TypePath.Step.wildcardBound(), targetWildcard.kind == BoundKind.EXTENDS),
          sourceWildcard.kind == BoundKind.EXTENDS ? sourceUpperBound : sourcePrintedNode,
          sourcePosition.then(
              TypePath.Step.wildcardBound(), sourceWildcard.kind == BoundKind.EXTENDS),
          new BoundComparison(sourceWildcard, targetUpperBound),
          mismatches);
      return;
    }
    if (targetWildcard != null && targetWildcard.kind == BoundKind.EXTENDS) {
      // a type argument the source states outright, against a bound the target states, as with
      // List<?> against ? extends List<? extends Object>
      collect(
          targetWildcard.type,
          source,
          targetPosition.then(TypePath.Step.wildcardBound(), true),
          sourcePrintedNode,
          sourcePosition,
          new BoundComparison(null, targetWildcard.type),
          mismatches);
      return;
    }
    if (sourceWildcard != null || targetWildcard != null) {
      // a wildcard on one side only, in a position javac itself rejects before NullAway runs
      return;
    }
    boolean sourceIsNullable = checks.isNullableAnnotated(source);
    boolean targetIsNullable = checks.isNullableAnnotated(target);
    if (sourceIsNullable != targetIsNullable && (sourceIsNullable || bound == null)) {
      // the smallest pair that differs. An upper bound accepts a non-null source where a nullable
      // one is allowed; a type argument the source states outright has to match
      mismatches.add(
          new Mismatch(
              targetPosition.path(),
              target,
              source,
              targetPosition.caret(),
              sourcePrintedNode,
              sourcePosition.caret(),
              bound == null ? null : bound.sourceWildcard(),
              bound == null ? null : bound.targetUpperBound()));
      return;
    }
    if (target instanceof Type.ClassType targetClassType
        && source instanceof Type.ClassType sourceClassType) {
      collectFromClassTypes(
          targetClassType, sourceClassType, targetPosition, sourcePosition, mismatches);
      return;
    }
    if (target instanceof Type.ArrayType targetArrayType
        && source instanceof Type.ArrayType sourceArrayType) {
      collect(
          targetArrayType.elemtype,
          sourceArrayType.elemtype,
          targetPosition.then(TypePath.Step.arrayElement(), true),
          sourceArrayType.elemtype,
          sourcePosition.then(TypePath.Step.arrayElement(), true),
          null,
          mismatches);
    }
  }

  /** Descends into the type arguments and the enclosing type of two class types. */
  private void collectFromClassTypes(
      Type.ClassType target,
      Type.ClassType source,
      Position targetPosition,
      Position sourcePosition,
      List<Mismatch> mismatches) {
    if (!(target.tsym instanceof Symbol.ClassSymbol targetSymbol)) {
      return;
    }
    Type sourceAsSuper =
        TypeSubstitutionUtils.asSuper(state.getTypes(), source, targetSymbol, config);
    if (!(sourceAsSuper instanceof Type.ClassType sourceAsTarget)
        || sourceAsSuper.isRaw()
        || target.isRaw()) {
      return;
    }
    List<Type> targetArguments = target.getTypeArguments();
    List<Type> sourceArguments = sourceAsTarget.getTypeArguments();
    if (targetArguments.size() != sourceArguments.size()) {
      return;
    }
    List<Type> formals = target.tsym.type.getTypeArguments();
    for (int i = 0; i < targetArguments.size(); i++) {
      Type.TypeVar formal =
          i < formals.size() && formals.get(i) instanceof Type.TypeVar typeVar ? typeVar : null;
      collect(
          targetArguments.get(i),
          sourceArguments.get(i),
          targetPosition.then(TypePath.Step.typeArgument(i, formal, targetSymbol), true),
          sourceArguments.get(i),
          sourcePosition.then(TypePath.Step.typeArgument(i, formal, targetSymbol), true),
          null,
          mismatches);
    }
    collect(
        target.getEnclosingType(),
        sourceAsTarget.getEnclosingType(),
        targetPosition.then(TypePath.Step.enclosingType(), true),
        sourceAsTarget.getEnclosingType(),
        sourcePosition.then(TypePath.Step.enclosingType(), true),
        null,
        mismatches);
  }

  /**
   * Returns where {@code node} occurs inside {@code type}, or {@code null} where it occurs nowhere
   * or at more than one position.
   *
   * <p>Identity, not equality: the caller has a node the traversal reached and wants the place the
   * printer will print it, and javac hands back one instance for a plain reference such as {@code
   * String}. Where an instance sits at two positions nothing says which one was compared, and a
   * caret under a guess is worse than none, so the search reports neither.
   */
  static @Nullable TypePath pathOfInstance(Type type, Type node) {
    List<TypePath> found = new ArrayList<>();
    findInstance(type, node, TypePath.root(), found);
    return found.size() == 1 ? found.get(0) : null;
  }

  /**
   * Appends to {@code found} every position of {@code type} that holds {@code node} itself, walking
   * the type the way {@link GenericTypePrettyPrintingVisitor} prints it so that a position found
   * here is one that printer can mark.
   */
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"})
  private static void findInstance(Type type, Type node, TypePath path, List<TypePath> found) {
    if (type == node) {
      found.add(path);
      return;
    }
    if (type instanceof Type.CapturedType capturedType) {
      // the printer treats a capture and the wildcard it captured as one node, and so does the
      // traversal above, so neither is a step of its own
      findInstance(capturedType.wildcard, node, path, found);
      return;
    }
    if (type instanceof Type.WildcardType wildcardType) {
      if (wildcardType.kind != BoundKind.UNBOUND) {
        findInstance(wildcardType.type, node, path.then(TypePath.Step.wildcardBound()), found);
      }
      return;
    }
    if (type instanceof Type.ArrayType arrayType) {
      findInstance(arrayType.elemtype, node, path.then(TypePath.Step.arrayElement()), found);
      return;
    }
    if (type instanceof Type.ClassType classType) {
      List<Type> typeArguments = classType.getTypeArguments();
      for (int i = 0; i < typeArguments.size(); i++) {
        findInstance(
            typeArguments.get(i),
            node,
            path.then(TypePath.Step.typeArgument(i, null, null)),
            found);
      }
      findInstance(
          classType.getEnclosingType(), node, path.then(TypePath.Step.enclosingType()), found);
    }
  }
}
