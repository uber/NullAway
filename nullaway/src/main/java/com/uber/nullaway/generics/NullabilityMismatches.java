package com.uber.nullaway.generics;

import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Records each position where {@link CheckIdenticalNullabilityVisitor} rejects the nullness of a
 * source type, so that a diagnostic names the positions the check itself rejected.
 *
 * <p>The visitor reports to this class as it compares: the step it takes into each side before it
 * descends, and the two nodes of every comparison it rejects. A rejected node is not descended
 * into, so the recorded positions are disjoint. Two of them can still share one caret, where both
 * lie below a bound the printer does not show and the caret rises to the wildcard that hides it.
 *
 * <p>Each side is followed in two ways. The steps the check took name the position for the {@code
 * path} line, in the classes it compared. The type the reader wrote is where a caret goes, and the
 * check does not always compare that type: it views a source {@code ArrayList<@Nullable String>} as
 * the {@code List<@Nullable String>} a target {@code List<String>} names. So every step is also
 * followed in the written type: by position where the written node has the shape of the compared
 * one, and by instance where it does not, since a node the view carried over from the written type
 * is the same {@code Type} object there. A node the view built itself, such as the {@code
 * List<@Nullable String>} a subclass declared as {@code Sub extends Base<List<@Nullable String>>}
 * passes to {@code Base}, sits nowhere in the written type and gets no caret there.
 */
final class NullabilityMismatches {

  /** What the check required of the source node at a rejected position. */
  enum Relation {
    /** The same nullness as the target node, as for a type argument the source states outright. */
    SAME,
    /** A subtype of the target node, as under an upper bound or for the element of an array. */
    SUBTYPE,
    /** A supertype of the target node, which is the lower bound of a {@code ? super} wildcard. */
    SUPERTYPE;

    /** Returns the relation with the two sides swapped. */
    Relation swapped() {
      return switch (this) {
        case SAME -> SAME;
        case SUBTYPE -> SUPERTYPE;
        case SUPERTYPE ->
            SUBTYPE; // diff-coverage: ignore -- the visitor rejects only SAME and SUBTYPE
      };
    }
  }

  /**
   * One position where the check rejected the nullness of the source.
   *
   * @param relation what the check required of the source node
   * @param path the steps the check took into the target, which the {@code path} line names
   * @param targetNode the node the target has there
   * @param sourceNode the node the source has there
   * @param targetNodePath where {@code targetNode} sits in the target as written, or {@code null}
   *     where it sits nowhere a printer shows, which is what a rewrite of the target has to address
   * @param targetCaret where the target's caret goes, or {@code null} where the target gets none
   * @param sourceCaret where the source's caret goes in the source as written, or {@code null}
   *     where the node the check compared sits nowhere in that type
   * @param viewCaret where the source's caret goes in {@link #viewedSource()}, or {@code null}
   * @param sourcePrintedNode the node under {@code sourceCaret}; where there is no such caret, the
   *     deepest node the compared view of the source prints. It is the wildcard itself where the
   *     node at fault lies below a bound the wildcard leaves implicit
   * @param sourceWildcard the source wildcard whose upper bound the check compared at this
   *     position, or {@code null} where the source states the type outright or the comparison was
   *     not one of upper bounds
   * @param targetUpperBound the upper bound the target requires there, which writing an explicit
   *     bound on {@code sourceWildcard} would have to produce
   */
  record Mismatch(
      Relation relation,
      TypePath path,
      Type targetNode,
      Type sourceNode,
      @Nullable TypePath targetNodePath,
      @Nullable TypePath targetCaret,
      @Nullable TypePath sourceCaret,
      @Nullable TypePath viewCaret,
      Type sourcePrintedNode,
      Type.@Nullable WildcardType sourceWildcard,
      @Nullable Type targetUpperBound) {}

  /**
   * The written type a comparison is followed in, and how far it could be followed.
   *
   * <p>{@code LOCATED}: {@code node} sits at {@code path} and the printer shows it. {@code HIDDEN}:
   * the comparison went below a bound the printer does not show, and {@code node} is the wildcard
   * at {@code path} that hides it, which is where a caret goes for everything below. {@code LOST}:
   * the comparison reached a node the written type does not hold, and {@code node} is the nearest
   * enclosing node it does hold, at {@code path}.
   */
  private record Track(State state, Type node, TypePath path) {

    enum State {
      LOCATED,
      HIDDEN,
      LOST
    }

    static Track located(Type node, TypePath path) {
      return new Track(State.LOCATED, node, path);
    }
  }

  /**
   * The upper bounds whose comparison is the current one.
   *
   * @param sourceWildcard the source wildcard the source bound belongs to, or {@code null} where
   *     the source states the type outright
   * @param targetUpperBound the upper bound the target requires
   */
  private record BoundComparison(
      Type.@Nullable WildcardType sourceWildcard, Type targetUpperBound) {}

  /**
   * Where the comparison stands. The visitor saves one before a descent and restores it after.
   *
   * @param targetSteps the steps taken into the target, named for the classes compared
   * @param target the target as written
   * @param source the source as written
   * @param view the source viewed as the target's class at the root, or {@code null} where that
   *     view is the written source itself
   * @param sourceShown the deepest node of the compared source a printer would show
   * @param swapped whether the visitor's left side is the source, as inside the lower bound of a
   *     {@code ? super} wildcard, where the check asks whether that bound is a subtype of the
   *     source
   * @param bound the upper bounds this comparison is of, or {@code null} where it is not of bounds
   */
  record Frame(
      TypePath targetSteps,
      Track target,
      Track source,
      @Nullable Track view,
      Type sourceShown,
      boolean swapped,
      @Nullable BoundComparison bound) {}

  private Frame frame;

  private @Nullable Type viewedSource;

  private final List<Mismatch> mismatches = new ArrayList<>();

  /**
   * Starts a record of the comparison of {@code sourceType} against {@code targetType}, as written.
   */
  NullabilityMismatches(Type targetType, Type sourceType) {
    this.frame =
        new Frame(
            TypePath.root(),
            Track.located(targetType, TypePath.root()),
            Track.located(sourceType, TypePath.root()),
            null,
            sourceType,
            false,
            null);
  }

  /** Returns the rejected positions, in the order the check compared them. */
  List<Mismatch> mismatches() {
    return mismatches;
  }

  /**
   * Returns the source viewed as the class the target names, where the check compared that view at
   * the root rather than the written source, and {@code null} otherwise.
   */
  @Nullable Type viewedSource() {
    return viewedSource;
  }

  /** Puts back a frame an {@code enter} method returned. */
  void restore(Frame saved) {
    frame = saved;
  }

  /**
   * Notes that the check compares {@code lhs} against {@code rhsView}, the right side {@code rhs}
   * viewed as the class of {@code lhs}. At the root of the source, a view of another class is what
   * a caret goes into where the written source does not hold the compared node. Returns the frame
   * to restore once the comparison ends.
   */
  Frame enterClassComparison(Type rhs, Type rhsView) {
    Frame saved = frame;
    if (frame.view() == null
        && !frame.swapped()
        && frame.targetSteps().isRoot()
        && !rhsView.tsym.equals(rhs.tsym)) {
      viewedSource = rhsView;
      frame =
          new Frame(
              frame.targetSteps(),
              frame.target(),
              frame.source(),
              Track.located(rhsView, TypePath.root()),
              frame.sourceShown(),
              frame.swapped(),
              frame.bound());
    }
    return saved;
  }

  /** Steps into type argument {@code index} of {@code lhs} and of {@code rhsView}. */
  Frame enterTypeArgument(int index, Type.ClassType lhs, Type.ClassType rhsView) {
    return enter(
        new Step(TypePath.Step.Kind.TYPE_ARGUMENT, index, lhs, lhs.getTypeArguments().get(index)),
        new Step(
            TypePath.Step.Kind.TYPE_ARGUMENT,
            index,
            rhsView,
            rhsView.getTypeArguments().get(index)),
        null);
  }

  /** Steps into the enclosing type of {@code lhs} and of {@code rhsView}. */
  Frame enterEnclosingType(Type.ClassType lhs, Type.ClassType rhsView) {
    return enter(
        new Step(TypePath.Step.Kind.ENCLOSING_TYPE, -1, lhs, lhs.getEnclosingType()),
        new Step(TypePath.Step.Kind.ENCLOSING_TYPE, -1, rhsView, rhsView.getEnclosingType()),
        null);
  }

  /** Steps into the element type of two arrays. */
  Frame enterArrayElement(Type.ArrayType lhs, Type.ArrayType rhs) {
    return enter(
        new Step(TypePath.Step.Kind.ARRAY_ELEMENT, -1, lhs, lhs.elemtype),
        new Step(TypePath.Step.Kind.ARRAY_ELEMENT, -1, rhs, rhs.elemtype),
        null);
  }

  /**
   * Steps into the upper bounds the check compares. A side whose wildcard is {@code null} states
   * its type outright and takes no step.
   */
  Frame enterUpperBounds(
      @Nullable Type lhsWildcard, Type lhsBound, @Nullable Type rhsWildcard, Type rhsBound) {
    BoundComparison bound =
        lhsWildcard == null || frame.swapped()
            ? null
            : new BoundComparison(
                rhsWildcard == null ? null : GenericsUtils.asWildcard(rhsWildcard), lhsBound);
    return enter(
        lhsWildcard == null
            ? null
            : new Step(TypePath.Step.Kind.WILDCARD_BOUND, -1, lhsWildcard, lhsBound),
        rhsWildcard == null
            ? null
            : new Step(TypePath.Step.Kind.WILDCARD_BOUND, -1, rhsWildcard, rhsBound),
        bound);
  }

  /**
   * Steps into the lower bound of {@code lhsWildcard} and, where {@code rhsWildcard} is not {@code
   * null}, of that wildcard too, and swaps the sides: the check goes on to ask whether {@code
   * rhsBound} is a subtype of {@code lhsBound}, so its left side is then the source.
   */
  Frame enterLowerBounds(
      Type lhsWildcard, Type lhsBound, @Nullable Type rhsWildcard, Type rhsBound) {
    Frame saved =
        enter(
            new Step(TypePath.Step.Kind.WILDCARD_LOWER_BOUND, -1, lhsWildcard, lhsBound),
            rhsWildcard == null
                ? null
                : new Step(TypePath.Step.Kind.WILDCARD_LOWER_BOUND, -1, rhsWildcard, rhsBound),
            null);
    frame =
        new Frame(
            frame.targetSteps(),
            frame.target(),
            frame.source(),
            frame.view(),
            frame.sourceShown(),
            !frame.swapped(),
            null);
    return saved;
  }

  /**
   * Records that the check rejected {@code rhsNode} where {@code lhsNode} stands, because the right
   * side does not stand in {@code relation} to the left.
   */
  void reject(Relation relation, Type lhsNode, Type rhsNode) {
    boolean swapped = frame.swapped();
    Type targetNode = swapped ? rhsNode : lhsNode;
    Type sourceNode = swapped ? lhsNode : rhsNode;
    Located target = locate(frame.target());
    Located source = locate(frame.source());
    Located view = frame.view() == null ? null : locate(frame.view());
    BoundComparison bound = frame.bound();
    mismatches.add(
        new Mismatch(
            swapped ? relation.swapped() : relation,
            frame.targetSteps(),
            targetNode,
            sourceNode,
            target == null || target.hidden() ? null : target.path(),
            target == null ? null : target.path(),
            source == null ? null : source.path(),
            view == null ? null : view.path(),
            source != null ? source.node() : frame.sourceShown(),
            bound == null ? null : bound.sourceWildcard(),
            bound == null ? null : bound.targetUpperBound()));
  }

  /**
   * One step of the comparison into one side.
   *
   * @param kind the kind of step
   * @param index the type argument stepped into, or {@code -1}
   * @param parent the node the comparison steps from
   * @param child the node it steps to
   */
  private record Step(TypePath.Step.Kind kind, int index, Type parent, Type child) {}

  /**
   * Takes one step on each side the visitor names, and returns the frame from before it. A {@code
   * null} step leaves that side where it is.
   */
  private Frame enter(@Nullable Step lhs, @Nullable Step rhs, @Nullable BoundComparison bound) {
    Frame saved = frame;
    Step targetStep = saved.swapped() ? rhs : lhs;
    Step sourceStep = saved.swapped() ? lhs : rhs;
    TypePath targetSteps = saved.targetSteps();
    Track target = saved.target();
    if (targetStep != null) {
      targetSteps = targetSteps.then(comparedStep(targetStep));
      target = follow(target, targetStep);
    }
    Track source = saved.source();
    Track view = saved.view();
    Type sourceShown = saved.sourceShown();
    if (sourceStep != null) {
      source = follow(source, sourceStep);
      view = view == null ? null : follow(view, sourceStep);
      if (isShown(sourceStep.kind(), sourceStep.parent())) {
        sourceShown = sourceStep.child();
      }
    }
    frame = new Frame(targetSteps, target, source, view, sourceShown, saved.swapped(), bound);
    return saved;
  }

  /** Names {@code step} for the {@code path} line, in the class the comparison stepped from. */
  private static TypePath.Step comparedStep(Step step) {
    return switch (step.kind()) {
      case TYPE_ARGUMENT -> typeArgumentStep(step.parent(), step.index());
      case WILDCARD_BOUND -> TypePath.Step.wildcardBound();
      case WILDCARD_LOWER_BOUND -> TypePath.Step.wildcardLowerBound();
      case ARRAY_ELEMENT -> TypePath.Step.arrayElement();
      case ENCLOSING_TYPE -> TypePath.Step.enclosingType();
    };
  }

  /**
   * Whether the printer shows the node {@code kind} steps to from {@code parent}: only the bound a
   * wildcard states is printed, and the upper bound of {@code ?} and of {@code ? super X} is not.
   */
  private static boolean isShown(TypePath.Step.Kind kind, Type parent) {
    if (kind != TypePath.Step.Kind.WILDCARD_BOUND) {
      return true;
    }
    Type.WildcardType wildcard = GenericsUtils.asWildcard(parent);
    return wildcard != null && wildcard.kind == BoundKind.EXTENDS;
  }

  /**
   * Follows {@code step} of the comparison in the written type {@code track} follows: by position
   * where the written node has the shape of the compared one, and otherwise by finding the compared
   * child as an instance inside the nearest written node.
   */
  private static Track follow(Track track, Step step) {
    switch (track.state()) {
      case HIDDEN -> {
        return track;
      }
      case LOST -> {
        return relocate(track, step.child());
      }
      case LOCATED -> {
        Track child = childByPosition(track, step);
        return child != null ? child : relocate(track, step.child());
      }
    }
    throw new AssertionError(
        track.state()); // diff-coverage: ignore -- the switch covers every state
  }

  /**
   * Returns the child {@code step} reaches from the written node of {@code track}, where that node
   * has the shape of the node the comparison stepped from, or {@code null} where it has another.
   */
  private static @Nullable Track childByPosition(Track track, Step step) {
    Type node = track.node();
    switch (step.kind()) {
      case TYPE_ARGUMENT -> {
        if (node instanceof Type.ClassType classType
            && classType.tsym.equals(step.parent().tsym)
            && step.index() < classType.getTypeArguments().size()) {
          return Track.located(
              classType.getTypeArguments().get(step.index()),
              track.path().then(typeArgumentStep(classType, step.index())));
        }
        return null;
      }
      case ENCLOSING_TYPE -> {
        if (node instanceof Type.ClassType classType && classType.tsym.equals(step.parent().tsym)) {
          return Track.located(
              classType.getEnclosingType(), track.path().then(TypePath.Step.enclosingType()));
        }
        return null;
      }
      case ARRAY_ELEMENT -> {
        if (node instanceof Type.ArrayType arrayType) {
          return Track.located(arrayType.elemtype, track.path().then(TypePath.Step.arrayElement()));
        }
        return null; // diff-coverage: ignore -- a step into an array element is taken only from an
        // array
      }
      case WILDCARD_BOUND -> {
        Type.WildcardType wildcard = GenericsUtils.asWildcard(node);
        if (wildcard == null) {
          return null; // diff-coverage: ignore -- a step into a wildcard bound is taken only from a
          // wildcard
        }
        // the upper bound of `?` and `? super X` is not printed, so the caret stays on the wildcard
        return wildcard.kind == BoundKind.EXTENDS
            ? Track.located(wildcard.type, track.path().then(TypePath.Step.wildcardBound()))
            : new Track(Track.State.HIDDEN, node, track.path());
      }
      case WILDCARD_LOWER_BOUND -> {
        Type.WildcardType wildcard = GenericsUtils.asWildcard(node);
        return wildcard != null && wildcard.kind == BoundKind.SUPER
            ? Track.located(wildcard.type, track.path().then(TypePath.Step.wildcardLowerBound()))
            : null; // diff-coverage: ignore -- a step into a lower bound is taken only from a ?
        // super wildcard
      }
    }
    throw new AssertionError(
        step.kind()); // diff-coverage: ignore -- the switch covers every step kind
  }

  /**
   * Finds {@code compared} inside the written node of {@code track}, and returns the track located
   * there, or the track lost at that node where {@code compared} occurs there nowhere or at more
   * than one position.
   */
  private static Track relocate(Track track, Type compared) {
    TypePath relative = pathOfInstance(track.node(), compared);
    return relative == null
        ? new Track(Track.State.LOST, track.node(), track.path())
        : Track.located(compared, track.path().append(relative));
  }

  /**
   * A position in a written type where a compared node is drawn.
   *
   * @param path where the caret goes
   * @param node the printed node at {@code path}
   * @param hidden whether the compared node lies below a bound the node at {@code path} hides
   */
  private record Located(TypePath path, Type node, boolean hidden) {}

  /**
   * Returns where the node the check rejected is drawn in the written type {@code track} follows,
   * or {@code null} where that type does not hold it.
   */
  private static @Nullable Located locate(Track track) {
    return switch (track.state()) {
      case LOCATED -> new Located(track.path(), track.node(), false);
      case HIDDEN -> new Located(track.path(), track.node(), true);
      // follow already looked for the compared node as an instance of the written type, and a
      // track it left lost holds no such instance
      case LOST -> null;
    };
  }

  /**
   * Returns the step into type argument {@code index} of {@code type}, named for the type parameter
   * of its class.
   */
  private static TypePath.Step typeArgumentStep(Type type, int index) {
    List<Type> formals = type.tsym.type.getTypeArguments();
    Type.TypeVar formal =
        index < formals.size() && formals.get(index) instanceof Type.TypeVar typeVar
            ? typeVar
            : null;
    return TypePath.Step.typeArgument(
        index, formal, type.tsym instanceof Symbol.ClassSymbol owner ? owner : null);
  }

  /**
   * Returns where {@code node} occurs inside {@code type}, or {@code null} where it occurs nowhere
   * or at more than one position.
   *
   * <p>Identity, not equality: the caller has a node the comparison reached and wants the place the
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
      // the printer treats a capture and the wildcard it captured as one node, so the capture is
      // not a step of its own
      findInstance(capturedType.wildcard, node, path, found);
      return;
    }
    if (type instanceof Type.WildcardType wildcardType) {
      if (wildcardType.kind != BoundKind.UNBOUND) {
        findInstance(
            wildcardType.type,
            node,
            path.then(
                wildcardType.kind == BoundKind.EXTENDS
                    ? TypePath.Step.wildcardBound()
                    : TypePath.Step.wildcardLowerBound()),
            found);
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
        findInstance(typeArguments.get(i), node, path.then(typeArgumentStep(classType, i)), found);
      }
      findInstance(
          classType.getEnclosingType(), node, path.then(TypePath.Step.enclosingType()), found);
    }
  }
}
