package com.uber.nullaway.generics;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.common.base.Verify;
import com.google.errorprone.VisitorState;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.CapturedType;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.code.Type.TypeVar;
import com.sun.tools.javac.code.Type.WildcardType;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.handlers.Handler;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.type.NullType;
import javax.lang.model.type.TypeVariable;
import org.jspecify.annotations.Nullable;

/**
 * An implementation of {@link ConstraintSolver} that uses a work-list algorithm to propagate
 * nullability constraints over a graph of type variables and their sub-/supertype relationships.
 */
public final class ConstraintSolverImpl implements ConstraintSolver {
  private final Config config;
  private final Handler handler;
  private final VisitorState state;

  /** Type variables belonging to the calls participating in this inference problem. */
  private final Set<Element> inferenceVariables = new LinkedHashSet<>();

  /** Inference variables registered more than once, by several calls of one declaration. */
  private final Set<Element> sharedInferenceVariables = new LinkedHashSet<>();

  public ConstraintSolverImpl(Config config, VisitorState state, NullAway analysis) {
    this.config = config;
    this.handler = analysis.getHandler();
    this.state = state;
  }

  /* ───────────────────── internal enums & data ───────────────────── */

  private enum NullnessState {
    UNKNOWN,
    NONNULL,
    NULLABLE
  }

  /** Per-variable state (nullability, sub-/supertype edges). */
  private static final class VarState {
    /**
     * Indicates whether the type variable has a @Nullable upper bound, and thus can be @Nullable
     * itself. Not strictly necessary for constraint solving, but allows us to give a more useful
     * diagnostic if we get a contradiction due to the @NonNull upper bound, which could be helpful
     * in the future.
     */
    final boolean nullableAllowed;

    NullnessState nullness = NullnessState.UNKNOWN;

    /**
     * Whether a type variable that is not under inference, carrying no annotation and bounded by a
     * type that admits null, flowed into this variable. Such a flow fixes no nullness, but it means
     * the variable takes that type variable's nullness rather than the non-null default.
     */
    boolean parametric;

    /**
     * Whether one of the type variables that set {@link #parametric} has a bound that is explicitly
     * nullable, rather than nullable by default in unannotated code.
     */
    boolean parametricNullable;

    /** Important to use a LinkedHashSet here for determinism in error messages. */
    final Set<Element> supertypes = new LinkedHashSet<>();

    /** Important to use a LinkedHashSet here for determinism in error messages. */
    final Set<Element> subtypes = new LinkedHashSet<>();

    VarState(boolean nullableAllowed) {
      this.nullableAllowed = nullableAllowed;
    }
  }

  /**
   * All variables seen so far. Important to use a LinkedHashMap here for determinism in error
   * messages.
   */
  private final Map<Element, VarState> vars = new LinkedHashMap<>();

  /* ───────────────────── public API ───────────────────── */

  @Override
  public void registerInferenceVariable(Element typeVariable) {
    if (!inferenceVariables.add(typeVariable)) {
      sharedInferenceVariables.add(typeVariable);
    }
  }

  @Override
  public void addSubtypeConstraint(Type subtype, Type supertype, boolean localVariableType)
      throws UnsatisfiableConstraintsException {
    subtype.accept(new AddSubtypeConstraintsVisitor(localVariableType), supertype);
  }

  class AddSubtypeConstraintsVisitor extends Types.DefaultTypeVisitor<@Nullable Void, Type> {
    private boolean localVariableType;

    /**
     * Wildcard containment checks currently in progress, keyed by the formal wildcard. Used to stop
     * recursion through self-referential bounds.
     */
    private final IdentityHashMap<WildcardType, Set<Type>> activeWildcardContainments =
        new IdentityHashMap<>();

    AddSubtypeConstraintsVisitor(boolean localVariableType) {
      this.localVariableType = localVariableType;
    }

    @Override
    public @Nullable Void visitType(Type subtype, Type supertype) {
      if (config.handleWildcardGenerics() && supertype instanceof WildcardType supertypeWildcard) {
        Verify.verify(!localVariableType, "A local variable should not have a wildcard type");
        constrainSubtypeToWildcard(subtype, supertypeWildcard);
        return null;
      }
      // handle flow into a type variable. The check for !(subtype instanceof TypeVar) is a
      // small optimization, as that case should be handled in visitTypeVar.
      if (!localVariableType && (supertype instanceof TypeVar) && !(subtype instanceof TypeVar)) {
        directlyConstrainTypePair(subtype, supertype);
      }
      return null;
    }

    @Override
    public @Nullable Void visitClassType(ClassType subtype, Type supertype) {
      if (supertype instanceof ClassType) {
        Type subtypeAsSuper =
            TypeSubstitutionUtils.asSuper(
                state.getTypes(), subtype, (Symbol.ClassSymbol) supertype.tsym, config);
        if (subtypeAsSuper == null || subtypeAsSuper.isRaw() || supertype.isRaw()) {
          return visitType(subtype, supertype);
        }
        // recursing, so set localVariableType to false
        localVariableType = false;
        // constrain type arguments to have identical nullability
        com.sun.tools.javac.util.List<Type> subtypeTypeArguments =
            subtypeAsSuper.getTypeArguments();
        com.sun.tools.javac.util.List<Type> supertypeTypeArguments = supertype.getTypeArguments();
        int numTypeArgs = supertypeTypeArguments.size();
        Verify.verify(numTypeArgs == subtypeTypeArguments.size());
        for (int i = 0; i < numTypeArgs; i++) {
          Type supertypeTypeArg = supertypeTypeArguments.get(i);
          Type subtypeTypeArg = subtypeTypeArguments.get(i);
          constrainTypeArgumentContainment(subtypeTypeArg, supertypeTypeArg);
        }
      }
      // if supertype is not a ClassType, we still call visitType to handle the case where
      // supertype is a TypeVar or a wildcard
      return visitType(subtype, supertype);
    }

    @Override
    public @Nullable Void visitArrayType(Type.ArrayType subtype, Type supertype) {
      if (supertype instanceof Type.ArrayType superArrayType) {
        // recursing, so set localVariableType to false
        localVariableType = false;
        Type subtypeComponentType = subtype.elemtype;
        Type superComponentType = superArrayType.elemtype;
        // arrays have covariant subtyping; so only constrain in one direction
        subtypeComponentType.accept(this, superComponentType);
      }
      // if supertype is not an ArrayType, we still call visitType to handle the case where
      // supertype is a TypeVar or a wildcard
      return visitType(subtype, supertype);
    }

    @Override
    public @Nullable Void visitTypeVar(TypeVar subtype, Type supertype) {
      if (!localVariableType) {
        directlyConstrainTypePair(subtype, supertype);
      }
      return visitType(subtype, supertype);
    }

    @Override
    public @Nullable Void visitCapturedType(CapturedType subtype, Type supertype) {
      return visitTypeVar(subtype, supertype);
    }

    @Override
    public @Nullable Void visitWildcardType(WildcardType subtype, Type supertype) {
      if (config.handleWildcardGenerics()) {
        Verify.verify(!localVariableType, "A wildcard type cannot be assigned to a local variable");
        constrainWildcardToSupertype(subtype, supertype);
      }
      return null;
    }

    /**
     * Adds nullability constraints for containment of one type argument by another during generic
     * class/interface subtyping. For non-wildcard arguments, NullAway requires identical
     * nullability. When either side is a wildcard, containment is reduced to constraints between
     * the wildcard bound and the opposing argument.
     */
    private void constrainTypeArgumentContainment(Type subtypeTypeArg, Type supertypeTypeArg) {
      if (!config.handleWildcardGenerics()) {
        equateTypeArguments(subtypeTypeArg, supertypeTypeArg);
        return;
      }
      // A captured formal is a type variable, not a wildcard containment target. Expanding
      // its backing wildcard can repeatedly unfold self-referential bounds (see issue #1934).
      if (supertypeTypeArg instanceof WildcardType supertypeWildcard) {
        constrainContainedByWildcard(subtypeTypeArg, supertypeWildcard);
        return;
      }
      WildcardType subtypeWildcard = GenericsUtils.asWildcard(subtypeTypeArg);
      if (subtypeWildcard != null) {
        constrainWildcardToSupertype(subtypeWildcard, supertypeTypeArg);
        return;
      }
      equateTypeArguments(subtypeTypeArg, supertypeTypeArg);
    }

    private void equateTypeArguments(Type subtypeTypeArg, Type supertypeTypeArg) {
      // constrain in both directions
      // TODO should we have a more optimized way to equate two types?  this just makes each
      //  type a subtype of the other
      subtypeTypeArg.accept(this, supertypeTypeArg);
      supertypeTypeArg.accept(this, subtypeTypeArg);
    }

    /**
     * Adds constraints for type-argument containment where the formal argument is a wildcard. For
     * {@code ? extends S} and {@code ?}, containment requires the actual argument's effective upper
     * bound to be a subtype of {@code S}. For {@code ? extends U}, where {@code U} is an inference
     * variable whose bound excludes null, an uncaptured actual that is an unannotated type variable
     * with an explicitly nullable bound also constrains {@code U} to be {@code @Nullable}, which no
     * solution satisfies. For {@code ? super S}, concrete actual arguments require {@code S <:
     * subtypeTypeArg}; {@code ? super T} actual arguments require {@code S <: T}. Other actual
     * wildcard forms place no useful nullability constraint.
     *
     * <p>Self-referential bounds such as {@code N extends Node<?>} can lead back to the exact same
     * containment check. Re-entering a check that is already in progress adds no constraints, since
     * the outer visit of that pair is already adding them.
     */
    private void constrainContainedByWildcard(Type subtypeTypeArg, WildcardType supertypeWildcard) {
      Set<Type> activeSubtypeArguments = activeWildcardContainments.get(supertypeWildcard);
      if (activeSubtypeArguments == null) {
        activeSubtypeArguments = Collections.newSetFromMap(new IdentityHashMap<>());
        activeWildcardContainments.put(supertypeWildcard, activeSubtypeArguments);
      } else if (activeSubtypeArguments.contains(subtypeTypeArg)) {
        return;
      }
      activeSubtypeArguments.add(subtypeTypeArg);
      try {
        switch (supertypeWildcard.kind) {
          case UNBOUND, EXTENDS -> {
            Type subtypeUpperBound =
                GenericsUtils.effectiveWildcardUpperBound(subtypeTypeArg, state, config, handler);
            Type supertypeUpperBound =
                GenericsUtils.wildcardUpperBound(supertypeWildcard, state, config, handler);
            if (supertypeWildcard.kind == BoundKind.EXTENDS
                && !(subtypeTypeArg instanceof CapturedType)
                && admitsNullThroughExplicitBound(subtypeUpperBound)
                && treatAsTypeVariableForInference(supertypeUpperBound)
                && !getState(supertypeUpperBound.asElement()).nullableAllowed) {
              // The containment check judges such a type variable by its nullable bound, which no
              // instantiation of a variable bounded by a non-null type can contain. Where the bound
              // allows null the constraint is left out: the solver infers one nullness per
              // variable, so a @Nullable U would substitute @Nullable T for U where javac inferred
              // T, and List<T> copy = copyOf(in) would then be reported.
              constrainAsNullable(supertypeUpperBound);
            }
            subtypeUpperBound.accept(this, supertypeUpperBound);
          }
          case SUPER -> {
            Type supertypeLowerBound = castToNonNull(supertypeWildcard.getSuperBound());
            WildcardType subtypeWildcard = GenericsUtils.asWildcard(subtypeTypeArg);
            if (subtypeWildcard != null) {
              if (subtypeWildcard.kind == BoundKind.SUPER) {
                supertypeLowerBound.accept(this, castToNonNull(subtypeWildcard.getSuperBound()));
              }
              // the subtype wildcard could have an extends bound, but as far as I know we do not
              // need to generate constraints for this case
              // TODO revisit if needed
            } else {
              supertypeLowerBound.accept(this, subtypeTypeArg);
            }
          }
        }
      } finally {
        activeSubtypeArguments.remove(subtypeTypeArg);
        if (activeSubtypeArguments.isEmpty()) {
          activeWildcardContainments.remove(supertypeWildcard);
        }
      }
    }

    /**
     * Adds constraints for a top-level subtype relation {@code subtype <: supertypeWildcard}. For
     * {@code ? extends S} and {@code ?}, this reduces to {@code subtype <: S}. A {@code ? super S}
     * supertype places no useful nullability constraint on {@code subtype}.
     */
    private void constrainSubtypeToWildcard(Type subtype, WildcardType supertypeWildcard) {
      if (supertypeWildcard.kind != BoundKind.SUPER) {
        subtype.accept(
            this, GenericsUtils.wildcardUpperBound(supertypeWildcard, state, config, handler));
      }
    }

    /**
     * Adds constraints for a top-level subtype relation {@code subtypeWildcard <: supertype}. For
     * {@code ? extends S} and {@code ?}, this reduces to {@code S <: supertype}. For {@code ? super
     * S}, use the lower bound and reduce to {@code S <: supertype}.
     */
    private void constrainWildcardToSupertype(WildcardType subtypeWildcard, Type supertype) {
      if (subtypeWildcard.kind == BoundKind.SUPER) {
        castToNonNull(subtypeWildcard.getSuperBound()).accept(this, supertype);
      } else {
        GenericsUtils.wildcardUpperBound(subtypeWildcard, state, config, handler)
            .accept(this, supertype);
      }
    }
  }

  @Override
  public Map<Element, InferredNullability> solve() throws UnsatisfiableConstraintsException {
    /* ---------- work-list propagation of nullability ---------- */
    Deque<Element> work = new ArrayDeque<>();
    vars.forEach(
        (tv, st) -> {
          if (st.nullness != NullnessState.UNKNOWN) {
            work.add(tv);
          }
        });

    while (!work.isEmpty()) {
      Element typeVarElement = work.removeFirst();
      VarState st = castToNonNull(vars.get(typeVarElement));

      switch (st.nullness) {
        case NONNULL -> {
          /* S <: tv  &  tv NONNULL  ⇒  S NONNULL */
          for (Element sub : st.subtypes) {
            if (updateNullness(sub, NullnessState.NONNULL)) {
              work.add(sub);
            }
          }
        }
        case NULLABLE -> {
          /* tv <: T  &  tv NULLABLE  ⇒  T NULLABLE */
          for (Element sup : st.supertypes) {
            if (updateNullness(sup, NullnessState.NULLABLE)) {
              work.add(sup);
            }
          }
        }
        default ->
            // UNKNOWN
            throw new RuntimeException(
                "Unexpected nullness state: " + st.nullness + " for " + typeVarElement);
      }
    }

    /* a variable above a parametric one takes the same type variable's nullness */
    vars.forEach(
        (tv, st) -> {
          if (st.parametric) {
            work.add(tv);
          }
        });
    while (!work.isEmpty()) {
      VarState st = castToNonNull(vars.get(work.removeFirst()));
      for (Element sup : st.supertypes) {
        VarState supState = castToNonNull(vars.get(sup));
        if (!supState.parametric || (st.parametricNullable && !supState.parametricNullable)) {
          supState.parametric = true;
          supState.parametricNullable |= st.parametricNullable;
          work.add(sup);
        }
      }
    }

    /* ---------- build final solution map ---------- */
    Map<Element, InferredNullability> result = new LinkedHashMap<>();
    vars.forEach(
        (tv, st) -> {
          result.put(
              tv,
              switch (st.nullness) {
                case NULLABLE -> InferredNullability.NULLABLE;
                case NONNULL -> InferredNullability.NONNULL;
                // nothing pushed the variable toward null, so non-null is the least solution,
                // unless a type variable that may be null flowed in and decides its nullness
                case UNKNOWN -> {
                  if (!st.parametric) {
                    yield InferredNullability.NONNULL;
                  }
                  // a shared variable may stand for a class type at one call and for the type
                  // variable at another, so a flag set at one call cannot decide the other
                  yield st.parametricNullable
                          && !sharedInferenceVariables.contains(tv)
                          && GenericsUtils.boundIsExplicitlyNullable(tv, config, handler, state)
                      ? InferredNullability.TYPE_VARIABLE_OR_NULLABLE
                      : InferredNullability.TYPE_VARIABLE_OR_NONNULL;
                }
              });
        });
    return result;
  }

  private void directlyConstrainTypePair(Type s, Type t) throws UnsatisfiableConstraintsException {
    Verify.verify(
        s instanceof TypeVariable || t instanceof TypeVariable,
        "At least one argument must be a type variable but got %s and %s",
        s,
        t);
    /* variable-to-variable edge */
    if (treatAsTypeVariableForInference(s) && treatAsTypeVariableForInference(t)) {
      TypeVariable sv = (TypeVariable) s;
      TypeVariable tv = (TypeVariable) t;
      getState(sv.asElement()).supertypes.add(tv.asElement());
      getState(tv.asElement()).subtypes.add(sv.asElement());
    }

    /* top-level nullability rules */
    if (isKnownNonNull(t)) {
      constrainAsNonNull(s);
    }
    if (isKnownNullable(s)) {
      constrainAsNullable(t);
    }
    if (treatAsTypeVariableForInference(t)
        && s instanceof TypeVar fixedTypeVar
        && !treatAsTypeVariableForInference(s)
        && !isKnownNullable(s)
        && !isKnownNonNull(s)) {
      VarState st = getState(t.asElement());
      st.parametric = true;
      if (GenericsUtils.boundIsExplicitlyNullable(
          fixedTypeVar.asElement(), config, handler, state)) {
        st.parametricNullable = true;
      }
    }
  }

  /* ───────────────────── nullability bookkeeping ───────────────────── */

  /** Force {@code tv} to {@code n}. Returns true if state changed. */
  private boolean updateNullness(Element typeVarElement, NullnessState n)
      throws UnsatisfiableConstraintsException {
    VarState st = getState(typeVarElement);

    if (st.nullness == n) {
      return false;
    }
    if (n == NullnessState.NULLABLE && !st.nullableAllowed) {
      throw new UnsatisfiableConstraintsException(typeVarElement, true);
    }
    if (st.nullness != NullnessState.UNKNOWN) {
      throw new UnsatisfiableConstraintsException(typeVarElement);
    }
    st.nullness = n;
    return true;
  }

  /**
   * Records that {@code t} must be {@code @Nullable}.
   *
   * <p>Only a registered inference variable with no explicit nullness annotation takes a
   * constraint. For other types, including enclosing type parameters and explicitly annotated
   * type-variable uses, this method does not introduce any constraint, and the normal type
   * compatibility checks report any incompatibility.
   *
   * @param t the type to constrain
   * @throws UnsatisfiableConstraintsException if the constraint leads to a contradiction
   */
  private void constrainAsNullable(Type t) throws UnsatisfiableConstraintsException {
    if (treatAsTypeVariableForInference(t)) {
      updateNullness(t.asElement(), NullnessState.NULLABLE);
    }
  }

  /**
   * Records that {@code t} must be {@code @NonNull}.
   *
   * <p>Only a registered inference variable with no explicit nullness annotation takes a
   * constraint. For other types, including enclosing type parameters and explicitly annotated
   * type-variable uses, this method does not introduce any constraint, and the normal type
   * compatibility checks report any incompatibility.
   *
   * @param t the type to constrain
   * @throws UnsatisfiableConstraintsException if the constraint leads to a contradiction
   */
  private void constrainAsNonNull(Type t) throws UnsatisfiableConstraintsException {
    if (treatAsTypeVariableForInference(t)) {
      updateNullness(t.asElement(), NullnessState.NONNULL);
    }
  }

  /* ───────────────────── helpers & stubs ───────────────────── */

  private VarState getState(Element typeVarElement) {
    return vars.computeIfAbsent(
        typeVarElement,
        v -> new VarState(GenericsUtils.upperBoundIsNullable(v, config, handler, state)));
  }

  /** Returns whether this use denotes an inference variable without a nullness override. */
  private boolean treatAsTypeVariableForInference(Type t) {
    if (t instanceof TypeVar tv) {
      // Only treat as a type variable if it _doesn't_ have an explicit @Nullable or @NonNull
      // annotation.
      return inferenceVariables.contains(tv.asElement())
          && !Nullness.hasNullableAnnotation(tv.getAnnotationMirrors().stream(), config)
          && !Nullness.hasNonNullAnnotation(tv.getAnnotationMirrors().stream(), config);
    } else {
      return false;
    }
  }

  /**
   * Returns whether {@code t} is a fixed type variable, not a capture, carrying no nullness
   * annotation at this use, whose bound is explicitly nullable.
   */
  private boolean admitsNullThroughExplicitBound(Type t) {
    return t instanceof TypeVar tv
        && !(t instanceof CapturedType)
        && !inferenceVariables.contains(tv.asElement())
        && !GenericsUtils.hasNullnessAnnotation(t, config)
        && GenericsUtils.boundIsExplicitlyNullable(tv.asElement(), config, handler, state);
  }

  /** Returns whether a type is explicitly nullable or is the null type. */
  private boolean isKnownNullable(Type t) {
    return t instanceof NullType
        || Nullness.hasNullableAnnotation(t.getAnnotationMirrors().stream(), config);
  }

  /**
   * Returns whether a type is known non-null without solving inference constraints.
   *
   * <p>For the null type and types with an explicit {@code @Nullable} annotation, returns {@code
   * false}. All other non-type-variable types are treated as non-null. Type-variable uses with an
   * explicit {@code @NonNull} annotation are also known non-null; unannotated inference variables
   * are not. For unannotated fixed type variables, a non-null upper bound establishes non-nullness,
   * while a nullable upper bound alone establishes neither known-nullable nor known-non-null
   * status.
   */
  private boolean isKnownNonNull(Type t) {
    return !isKnownNullable(t)
        && !treatAsTypeVariableForInference(t)
        && (!(t instanceof TypeVar tv)
            || Nullness.hasNonNullAnnotation(t.getAnnotationMirrors().stream(), config)
            || !GenericsUtils.upperBoundIsNullable(tv.asElement(), config, handler, state));
  }
}
