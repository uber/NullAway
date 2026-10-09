package com.uber.nullaway.generics;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.common.base.Verify;
import com.google.errorprone.VisitorState;
import com.sun.source.tree.Tree;
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
import java.util.List;
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

  /**
   * Maps the symbol of each fresh type variable created by {@link #registerInferenceVariables(Tree,
   * List)} to the inference variable it represents. Only type variables with these symbols are
   * treated as inference variables; all other type variables are fixed.
   */
  private final Map<Element, InferenceVariable> inferenceVariables = new LinkedHashMap<>();

  /** Fresh type variables created for each site, keyed by declared type variable. */
  private final Map<Tree, Map<Element, Type.TypeVar>> freshTypeVariablesForSite =
      new LinkedHashMap<>();

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

    /** Important to use a LinkedHashSet here for determinism in error messages. */
    final Set<InferenceVariable> supertypes = new LinkedHashSet<>();

    /** Important to use a LinkedHashSet here for determinism in error messages. */
    final Set<InferenceVariable> subtypes = new LinkedHashSet<>();

    VarState(boolean nullableAllowed) {
      this.nullableAllowed = nullableAllowed;
    }
  }

  /**
   * All variables seen so far. Important to use a LinkedHashMap here for determinism in error
   * messages.
   */
  private final Map<InferenceVariable, VarState> vars = new LinkedHashMap<>();

  /* ───────────────────── public API ───────────────────── */

  @Override
  public Map<Element, Type.TypeVar> registerInferenceVariables(
      Tree site, List<? extends Element> typeVariables) {
    Map<Element, Type.TypeVar> existing = freshTypeVariablesForSite.get(site);
    if (existing != null) {
      return existing;
    }
    // First create all the fresh type variables, since the upper bound of one type variable can
    // refer to others, e.g., <T extends Comparable<T>> or <T, U extends T>.
    Map<Element, Type.TypeVar> fresh = new LinkedHashMap<>();
    for (Element typeVariable : typeVariables) {
      Symbol.TypeVariableSymbol symbol = (Symbol.TypeVariableSymbol) typeVariable;
      Type.TypeVar declared = (Type.TypeVar) symbol.type;
      fresh.put(
          typeVariable, new Type.TypeVar(symbol.name, symbol.owner, declared.getLowerBound()));
    }
    Types types = state.getTypes();
    for (Map.Entry<Element, Type.TypeVar> entry : fresh.entrySet()) {
      Type.TypeVar declared = (Type.TypeVar) ((Symbol) entry.getKey()).type;
      entry
          .getValue()
          .setUpperBound(
              TypeSubstitutionUtils.substituteTypeVariables(
                  declared.getUpperBound(), fresh, types, config));
    }
    // The fresh type variables are not among their owner's type parameters, so upper bound
    // nullability coming from library models (which are keyed on type parameter index) is not
    // visible on them. When the declared and fresh upper bound nullability differ, make the fresh
    // upper bound nullability explicit. This matters when a fresh type variable is later seen as a
    // fixed type, e.g., by a separate inference problem for a call inside a lambda body.
    for (Map.Entry<Element, Type.TypeVar> entry : fresh.entrySet()) {
      Type.TypeVar freshVar = entry.getValue();
      boolean declaredNullable =
          GenericsUtils.upperBoundIsNullable(entry.getKey(), config, handler, state);
      Type upperBound = freshVar.getUpperBound();
      if (declaredNullable
              != GenericsUtils.upperBoundIsNullable(freshVar.tsym, config, handler, state)
          && !upperBound.isCompound()) {
        freshVar.setUpperBound(
            TypeSubstitutionUtils.typeWithAnnot(
                upperBound,
                declaredNullable
                    ? GenericsChecks.getSyntheticNullableAnnotType(state)
                    : GenericsChecks.getSyntheticNonNullAnnotType(state)));
      }
      inferenceVariables.put(freshVar.tsym, new InferenceVariable(entry.getKey(), site));
    }
    freshTypeVariablesForSite.put(site, fresh);
    return fresh;
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
      if (config.handleWildcardGenerics()) {
        WildcardType supertypeWildcard = GenericsUtils.asWildcard(supertype);
        if (supertypeWildcard != null) {
          Verify.verify(!localVariableType, "A local variable should not have a wildcard type");
          constrainSubtypeToWildcard(subtype, supertypeWildcard);
          return null;
        }
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
      WildcardType supertypeWildcard = GenericsUtils.asWildcard(supertypeTypeArg);
      if (supertypeWildcard != null) {
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
     * bound to be a subtype of {@code S}. For {@code ? super S}, concrete actual arguments require
     * {@code S <: subtypeTypeArg}; {@code ? super T} actual arguments require {@code S <: T}. Other
     * actual wildcard forms place no useful nullability constraint.
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
            subtypeUpperBound.accept(
                this, GenericsUtils.wildcardUpperBound(supertypeWildcard, state, config, handler));
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
  public Map<InferenceVariable, InferredNullability> solve()
      throws UnsatisfiableConstraintsException {
    /* ---------- work-list propagation of nullability ---------- */
    Deque<InferenceVariable> work = new ArrayDeque<>();
    vars.forEach(
        (inferenceVar, st) -> {
          if (st.nullness != NullnessState.UNKNOWN) {
            work.add(inferenceVar);
          }
        });

    while (!work.isEmpty()) {
      InferenceVariable inferenceVar = work.removeFirst();
      VarState st = castToNonNull(vars.get(inferenceVar));

      switch (st.nullness) {
        case NONNULL -> {
          /* S <: tv  &  tv NONNULL  ⇒  S NONNULL */
          for (InferenceVariable sub : st.subtypes) {
            if (updateNullness(sub, NullnessState.NONNULL)) {
              work.add(sub);
            }
          }
        }
        case NULLABLE -> {
          /* tv <: T  &  tv NULLABLE  ⇒  T NULLABLE */
          for (InferenceVariable sup : st.supertypes) {
            if (updateNullness(sup, NullnessState.NULLABLE)) {
              work.add(sup);
            }
          }
        }
        default ->
            // UNKNOWN
            throw new RuntimeException(
                "Unexpected nullness state: " + st.nullness + " for " + inferenceVar);
      }
    }

    /* ---------- build final solution map ---------- */
    Map<InferenceVariable, InferredNullability> result = new LinkedHashMap<>();
    vars.forEach(
        (inferenceVar, st) -> {
          // Note: if the nullness state is UNKNOWN, we infer NONNULL arbitrarily
          // TODO does this matter?  should we use NULLABLE instead?
          result.put(
              inferenceVar,
              st.nullness == NullnessState.NULLABLE
                  ? InferredNullability.NULLABLE
                  : InferredNullability.NONNULL);
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
    InferenceVariable sVar = inferenceVariableForUse(s);
    InferenceVariable tVar = inferenceVariableForUse(t);
    if (sVar != null && tVar != null) {
      getState(sVar).supertypes.add(tVar);
      getState(tVar).subtypes.add(sVar);
    }

    /* top-level nullability rules */
    if (isKnownNonNull(t) && sVar != null) {
      updateNullness(sVar, NullnessState.NONNULL);
    }
    if (isKnownNullable(s) && tVar != null) {
      updateNullness(tVar, NullnessState.NULLABLE);
    }
  }

  /* ───────────────────── nullability bookkeeping ───────────────────── */

  /**
   * Force {@code var} to {@code n}. Returns true if state changed.
   *
   * <p>Only a registered inference variable with no explicit nullness annotation takes a
   * constraint; see {@link #inferenceVariableForUse(Type)}. For other types, including enclosing
   * type parameters and explicitly annotated type-variable uses, no constraint is introduced, and
   * the normal type compatibility checks report any incompatibility.
   *
   * @throws UnsatisfiableConstraintsException if the constraint leads to a contradiction
   */
  private boolean updateNullness(InferenceVariable inferenceVar, NullnessState n)
      throws UnsatisfiableConstraintsException {
    VarState st = getState(inferenceVar);

    if (st.nullness == n) {
      return false;
    }
    if (n == NullnessState.NULLABLE && !st.nullableAllowed) {
      throw new UnsatisfiableConstraintsException(inferenceVar.typeVariable(), true);
    }
    if (st.nullness != NullnessState.UNKNOWN) {
      throw new UnsatisfiableConstraintsException(inferenceVar.typeVariable());
    }
    st.nullness = n;
    return true;
  }

  /* ───────────────────── helpers & stubs ───────────────────── */

  private VarState getState(InferenceVariable inferenceVar) {
    return vars.computeIfAbsent(
        inferenceVar,
        v ->
            new VarState(
                GenericsUtils.upperBoundIsNullable(v.typeVariable(), config, handler, state)));
  }

  /**
   * If {@code t} is a use of an inference variable without a nullness override, returns that
   * inference variable. Otherwise, returns {@code null}.
   *
   * @param t the type
   * @return the inference variable used by {@code t}, or {@code null} if {@code t} should be
   *     treated as a fixed type for inference
   */
  private @Nullable InferenceVariable inferenceVariableForUse(Type t) {
    if (!(t instanceof TypeVar tv)) {
      return null;
    }
    InferenceVariable inferenceVar = inferenceVariables.get(tv.asElement());
    // Only treat as an inference variable if the use _doesn't_ have an explicit @Nullable or
    // @NonNull annotation.
    if (inferenceVar == null
        || Nullness.hasNullableAnnotation(tv.getAnnotationMirrors().stream(), config)
        || Nullness.hasNonNullAnnotation(tv.getAnnotationMirrors().stream(), config)) {
      return null;
    }
    return inferenceVar;
  }

  /** Returns whether this use denotes an inference variable without a nullness override. */
  private boolean treatAsTypeVariableForInference(Type t) {
    return inferenceVariableForUse(t) != null;
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
