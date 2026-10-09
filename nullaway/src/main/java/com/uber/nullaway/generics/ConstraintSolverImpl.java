package com.uber.nullaway.generics;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;
import static com.uber.nullaway.generics.TypeMetadataBuilder.TYPE_METADATA_BUILDER;

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
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.CodeAnnotationInfo;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.handlers.Handler;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.type.NullType;
import javax.lang.model.type.TypeMirror;
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
   * List, Map, Set)} to the inference variable it represents. Only type variables with these
   * symbols are treated as inference variables; all other type variables are fixed.
   */
  private final Map<Element, InferenceVariable> inferenceVariables = new LinkedHashMap<>();

  /** Fresh type variables created for each site, keyed by declared type variable. */
  private final Map<Tree, Map<Element, Type.TypeVar>> freshTypeVariablesForSite =
      new LinkedHashMap<>();

  /** Fresh type variable corresponding to each inference variable. */
  private final Map<InferenceVariable, Type.TypeVar> freshTypeVariableForInferenceVariable =
      new LinkedHashMap<>();

  /** Authoritative Java shapes supplied by attribution, never inferred from nullness bounds. */
  private final Map<InferenceVariable, Type> javacInstantiations = new LinkedHashMap<>();

  /** Effective upper-bound nullability after receiver/class substitution. */
  private final Map<InferenceVariable, Boolean> nullableAllowedForInferenceVariable =
      new LinkedHashMap<>();

  /** Variables whose declaration upper bounds are authoritative nullness contracts. */
  private final Set<InferenceVariable> nullnessMarkedDeclarationBounds = new LinkedHashSet<>();

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

    /** Structural relationships survive explicit occurrence-level root nullness overrides. */
    final Set<InferenceVariable> structuralSupertypes = new LinkedHashSet<>();

    final Set<InferenceVariable> structuralSubtypes = new LinkedHashSet<>();

    /**
     * Structured evidence {@code S} with a constraint {@code S <: var}, including arrays, non-raw
     * classes, and symbolic fixed type variables. Non-generic subclasses are kept because alignment
     * to a generic supertype can expose nested nullability.
     */
    final List<Type> lowerBoundTypes = new ArrayList<>();

    /** Structural fingerprints used to deduplicate {@link #lowerBoundTypes}. */
    final Set<String> lowerBoundKeys = new LinkedHashSet<>();

    /** Fixed lower uses that constrain this variable's root, not an annotated projection of it. */
    final List<Type> rootFixedLowerBounds = new ArrayList<>();

    final Set<String> rootFixedLowerBoundKeys = new LinkedHashSet<>();

    /** Fixed type-variable lower uses retained as declaration-diagnostic provenance. */
    final List<Type> fixedTypeVariableLowerBounds = new ArrayList<>();

    /** Structural fingerprints used to deduplicate fixed type-variable provenance. */
    final Set<String> fixedTypeVariableLowerBoundKeys = new LinkedHashSet<>();

    /**
     * Structured types {@code S} with a constraint {@code var <: S}, in the order the constraints
     * were added. A type appearing in both this list and {@link #lowerBoundTypes} arises from an
     * equality constraint, e.g., between invariant type arguments.
     */
    final List<Type> upperBoundTypes = new ArrayList<>();

    /** Structural fingerprints used to deduplicate {@link #upperBoundTypes}. */
    final Set<String> upperBoundKeys = new LinkedHashSet<>();

    VarState(boolean nullableAllowed) {
      this.nullableAllowed = nullableAllowed;
    }
  }

  /**
   * All variables seen so far. Important to use a LinkedHashMap here for determinism in error
   * messages.
   */
  private final Map<InferenceVariable, VarState> vars = new LinkedHashMap<>();

  /** Deferred containment obligations, checked after every call's lower bounds are available. */
  private final Set<NonNullWildcardRequirement> nonNullWildcardRequirements = new LinkedHashSet<>();

  /** A wildcard's actual upper bound must fit an inferred variable whose bound excludes null. */
  private record NonNullWildcardRequirement(Type actual, InferenceVariable required) {}

  /** Incremented whenever a variable, structured bound, or variable edge is added. */
  private int structuredConstraintVersion = 0;

  /** Variables currently being structurally resolved for invariant comparison. */
  private final Set<InferenceVariable> invariantResolutionInProgress = new LinkedHashSet<>();

  /** Diagnostic annotation sources selected independently of structured candidate construction. */
  private final Map<InferenceVariable, Type> declarationBoundFallbacks = new LinkedHashMap<>();

  /** Active directed type pairs, separated by comparison mode and compared by identity. */
  private final Map<String, IdentityHashMap<Type, Set<Type>>> activeBoundComparisons =
      new LinkedHashMap<>();

  /** Stable per-run IDs for symbols appearing in structural fingerprints. */
  private final IdentityHashMap<Symbol, Integer> fingerprintSymbolIds = new IdentityHashMap<>();

  /* ───────────────────── public API ───────────────────── */

  @Override
  public Map<Element, Type.TypeVar> registerInferenceVariables(
      Tree site,
      List<? extends Element> typeVariables,
      Map<? extends Element, ? extends Type> instantiatedUpperBounds,
      Set<? extends Element> variablesWithNullnessMarkedBounds,
      Map<? extends Element, ? extends Type> javacInstantiations) {
    Map<Element, Type.TypeVar> existing = freshTypeVariablesForSite.get(site);
    if (existing != null) {
      recordJavacInstantiations(site, existing.keySet(), javacInstantiations);
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
    Set<Element> receiverInstantiatedBounds = new LinkedHashSet<>();
    for (Map.Entry<Element, Type.TypeVar> entry : fresh.entrySet()) {
      Type.TypeVar declared = (Type.TypeVar) ((Symbol) entry.getKey()).type;
      Type instantiatedUpperBound = instantiatedUpperBounds.get(entry.getKey());
      Type upperBound =
          instantiatedUpperBound != null ? instantiatedUpperBound : declared.getUpperBound();
      Symbol.TypeVariableSymbol declaredSymbol = (Symbol.TypeVariableSymbol) entry.getKey();
      boolean ownerIsUnannotated =
          CodeAnnotationInfo.instance(state.context)
              .isSymbolUnannotated(declaredSymbol.owner, config, handler);
      if (instantiatedUpperBound != null
          && !ownerIsUnannotated
          && !types.isSameType(instantiatedUpperBound, declared.getUpperBound())) {
        receiverInstantiatedBounds.add(entry.getKey());
      }
      entry
          .getValue()
          .setUpperBound(
              TypeSubstitutionUtils.substituteTypeVariables(upperBound, fresh, types, config));
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
      boolean modeledNullable = hasNullableUpperBoundOverride(entry.getKey());
      if ((modeledNullable
              || (!receiverInstantiatedBounds.contains(entry.getKey())
                  && declaredNullable
                      != GenericsUtils.upperBoundIsNullable(freshVar.tsym, config, handler, state)))
          && !upperBound.isCompound()) {
        freshVar.setUpperBound(
            TypeSubstitutionUtils.typeWithAnnot(
                upperBound,
                declaredNullable
                    ? GenericsChecks.getSyntheticNullableAnnotType(state)
                    : GenericsChecks.getSyntheticNonNullAnnotType(state)));
      }
      InferenceVariable inferenceVariable = new InferenceVariable(entry.getKey(), site);
      inferenceVariables.put(freshVar.tsym, inferenceVariable);
      freshTypeVariableForInferenceVariable.put(inferenceVariable, freshVar);
      if (variablesWithNullnessMarkedBounds.contains(entry.getKey())) {
        nullnessMarkedDeclarationBounds.add(inferenceVariable);
      }
      if (variablesWithNullnessMarkedBounds.contains(entry.getKey())
          && receiverInstantiatedBounds.contains(entry.getKey())) {
        nullableAllowedForInferenceVariable.put(
            inferenceVariable,
            modeledNullable || upperBoundAllowsNullable(freshVar.getUpperBound()));
      }
    }
    freshTypeVariablesForSite.put(site, fresh);
    recordJavacInstantiations(site, fresh.keySet(), javacInstantiations);
    for (Element variable : fresh.keySet()) {
      getState(new InferenceVariable(variable, site));
    }
    return fresh;
  }

  /** Records supplied shapes without discarding earlier shapes on repeated registration. */
  private void recordJavacInstantiations(
      Tree site, Set<Element> variables, Map<? extends Element, ? extends Type> instantiations) {
    for (Element variable : variables) {
      Type shape = instantiations.get(variable);
      if (shape != null) {
        javacInstantiations.put(new InferenceVariable(variable, site), shape);
      }
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
        // Non-static inner types can carry inference variables only in their enclosing type.
        subtypeAsSuper.getEnclosingType().accept(this, supertype.getEnclosingType());
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
        if (inferenceVariableForStructure(subtype) == null
            && !(subtype instanceof CapturedType)
            && isStructuredType(supertype)
            && beginBoundComparison("fixed-constraints", subtype, supertype)) {
          try {
            // A fixed caller variable can constrain nested inference through its declaration
            // contract, without ever using that contract as its replacement shape.
            subtype.getUpperBound().accept(this, supertype);
          } finally {
            endBoundComparison("fixed-constraints", subtype, supertype);
          }
        }
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
            Type supertypeUpperBound =
                GenericsUtils.wildcardUpperBound(supertypeWildcard, state, config, handler);
            InferenceVariable required = inferenceVariableForUse(supertypeUpperBound);
            if (supertypeWildcard.kind == BoundKind.EXTENDS
                && !(subtypeTypeArg instanceof CapturedType)
                && required != null
                && !getState(required).nullableAllowed) {
              nonNullWildcardRequirements.add(
                  new NonNullWildcardRequirement(subtypeUpperBound, required));
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
  public Solution solve() throws UnsatisfiableConstraintsException {
    prepareStructuredConstraints();
    constrainNonNullWildcardRequirements();

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

    // Validation must not depend on whether candidate construction can unfold a cycle or merge
    // the lower bounds into a single annotation source.
    declarationBoundFallbacks.clear();
    Map<InferenceVariable, Type> validatedFallbacks = new LinkedHashMap<>();
    for (InferenceVariable inferenceVar : vars.keySet()) {
      Type fallback = validateDeclarationBounds(inferenceVar);
      if (fallback != null) {
        validatedFallbacks.put(inferenceVar, fallback);
      }
    }
    // Publish only after validating every variable, so diagnostic fallbacks cannot hide lower
    // evidence during validation of a dependent variable.
    declarationBoundFallbacks.putAll(validatedFallbacks);

    /* ---------- build and certify the final solution ---------- */
    Map<InferenceVariable, Type> result = new LinkedHashMap<>();
    Map<InferenceVariable, Type> inferredTypes = new LinkedHashMap<>();
    Set<InferenceVariable> incomplete = new LinkedHashSet<>();
    Set<InferenceVariable> inconsistent = new LinkedHashSet<>();
    for (InferenceVariable inferenceVar : vars.keySet()) {
      result.put(
          inferenceVar,
          inferredType(inferenceVar, inferredTypes, new LinkedHashSet<>(), incomplete));
      if (declarationBoundFallbacks.containsKey(inferenceVar)) {
        inconsistent.add(inferenceVar);
      }
    }
    validateSolution(result, incomplete, inconsistent);
    propagateUncertifiedDependencies(incomplete, inconsistent);
    return new Solution(result, incomplete, inconsistent);
  }

  /**
   * Computes the nullness-annotated type inferred for {@code inferenceVar}, after nullability
   * propagation has reached a fixed point.
   *
   * <p>With an attributed Java shape, each resolved lower-bound source (or upper-bound source when
   * there are no lowers, with declaration bounds preceding contextual bounds) is projected onto
   * that shape before merging annotations. This permits distinct nominal lower types to share
   * javac's chosen supertype without inventing a Java shape. Covariant array components merge
   * nullness; invariant conflicts retain diagnostic evidence and are rejected by independent
   * validation. Without a shape, the legacy annotation source remains available, but the variable
   * is incomplete. Unconstrained fixed caller variables stay symbolic.
   *
   * @param inferenceVar the inference variable
   * @param memo already computed inferred types
   * @param inProgress variables whose inferred types are currently being computed, to guard against
   *     cycles, e.g., from a constraint {@code List<T> <: T}
   * @param incomplete variables whose structure cannot be certified
   * @return the inferred type or diagnostic annotation source
   */
  private Type inferredType(
      InferenceVariable inferenceVar,
      Map<InferenceVariable, Type> memo,
      Set<InferenceVariable> inProgress,
      Set<InferenceVariable> incomplete) {
    Type memoized = memo.get(inferenceVar);
    if (memoized != null) {
      return memoized;
    }
    VarState st = castToNonNull(vars.get(inferenceVar));
    Type declaredTypeVariable = (Type) inferenceVar.typeVariable().asType();
    Type shape = javacInstantiations.get(inferenceVar);
    if (shape == null || !hasCertifiedJavaStructure(shape, new IdentityHashMap<>())) {
      incomplete.add(inferenceVar);
    }
    Type scalarSource =
        shape == null
            ? TypeSubstitutionUtils.typeWithAnnot(
                declaredTypeVariable,
                st.nullness == NullnessState.NULLABLE
                    ? GenericsChecks.getSyntheticNullableAnnotType(state)
                    : GenericsChecks.getSyntheticNonNullAnnotType(state))
            : inferredRoot(shape, st);
    if (shape == null && hasStructuredDependencyCycle(inferenceVar)) {
      incomplete.add(inferenceVar);
      return scalarSource;
    }
    if (!inProgress.add(inferenceVar)) {
      // Attributed shapes can break structural recursion. The seed is not certification: every
      // substituted bound is still checked against the final solution below.
      return scalarSource;
    }
    try {
      StructuredBounds bounds = structuredBounds(inferenceVar);
      List<Type> sources;
      Type declarationFallback = declarationBoundFallbacks.get(inferenceVar);
      if (declarationFallback != null) {
        sources = List.of(declarationFallback);
      } else if (shape == null) {
        Type legacySource = structuredBound(inferenceVar);
        sources = legacySource == null ? List.of() : List.of(legacySource);
      } else if (bounds.lower().isEmpty()) {
        // Preserve the declaration contract as the diagnostic source when a contextual upper
        // conflicts with it. Every upper still participates in independent solution validation.
        sources = new ArrayList<>(bounds.declaredUpper());
        for (Type upper : bounds.upper()) {
          addIfNotIdentical(sources, upper);
        }
      } else {
        sources = bounds.lower();
      }
      Type candidate = null;
      for (Type source : sources) {
        Map<Element, Type> replacements = new LinkedHashMap<>();
        for (Map.Entry<Element, InferenceVariable> entry : inferenceVariables.entrySet()) {
          TypeVarWithSymbolCollector collector = new TypeVarWithSymbolCollector(entry.getKey());
          source.accept(collector, null);
          if (!collector.getMatches().isEmpty()) {
            replacements.put(
                entry.getKey(), inferredType(entry.getValue(), memo, inProgress, incomplete));
          }
        }
        Type resolved =
            TypeSubstitutionUtils.substituteTypeVariables(
                source, replacements, state.getTypes(), config);
        Type evidence = inferredRoot(resolved, st);
        Type projected;
        if (shape == null) {
          projected = evidence;
        } else if (shape instanceof TypeVar && st.nullness == NullnessState.UNKNOWN) {
          // The projection helper interprets absent root annotations as non-null. Preserve a
          // symbolic caller variable instead; it has no nested positions to overlay.
          projected = shape;
        } else {
          projected =
              TypeSubstitutionUtils.updateTypeWithInferredNullability(
                  markNestedTypesNonNull(shape),
                  declaredTypeVariable,
                  Map.of(inferenceVar.typeVariable(), evidence),
                  state,
                  config);
        }
        if (candidate == null) {
          candidate = projected;
        } else {
          Type merged = mergeCovariantLowerBounds(candidate, projected, false);
          if (merged == null) {
            // Keep the first diagnostic source; validation visits all sources independently.
            incomplete.add(inferenceVar);
          } else {
            candidate = merged;
          }
        }
      }
      Type result = candidate == null ? scalarSource : inferredRoot(candidate, st);
      memo.put(inferenceVar, result);
      return result;
    } finally {
      inProgress.remove(inferenceVar);
    }
  }

  /**
   * Applies wildcard containment obligations after nested calls have contributed their evidence. A
   * fixed variable with a nullable bound remains symbolic for nullable-accepting calls, but no
   * instantiation of a non-null-bounded wildcard variable can contain all its possible values.
   */
  private void constrainNonNullWildcardRequirements() {
    for (NonNullWildcardRequirement requirement : nonNullWildcardRequirements) {
      if (hasNullableFixedLowerBound(requirement.actual())) {
        throw new NonNullWildcardBoundViolationException(requirement.required());
      }
    }
  }

  /**
   * Finds explicitly nullable fixed-variable evidence through root-inference subtype edges. Using
   * structural edges here would ignore occurrence-level non-null projections. The graph is complete
   * before this walk, so the result does not depend on whether an outer call was visited first.
   */
  private boolean hasNullableFixedLowerBound(Type actual) {
    if (Nullness.hasNonNullAnnotation(actual.getAnnotationMirrors().stream(), config)) {
      return false;
    }
    InferenceVariable variable = inferenceVariableForUse(actual);
    if (variable == null) {
      return actual instanceof TypeVar
          && !(actual instanceof CapturedType)
          && inferenceVariableForStructure(actual) == null
          && explicitlyNullableBound(actual, new LinkedHashSet<>());
    }
    Deque<InferenceVariable> work = new ArrayDeque<>();
    Set<InferenceVariable> visited = new LinkedHashSet<>();
    work.add(variable);
    visited.add(variable);
    while (!work.isEmpty()) {
      VarState current = castToNonNull(vars.get(work.removeFirst()));
      for (Type lower : current.rootFixedLowerBounds) {
        if (lower instanceof TypeVar
            && !(lower instanceof CapturedType)
            && inferenceVariableForStructure(lower) == null
            && !Nullness.hasNonNullAnnotation(lower.getAnnotationMirrors().stream(), config)
            && explicitlyNullableBound(lower, new LinkedHashSet<>())) {
          return true;
        }
      }
      for (InferenceVariable subtype : current.subtypes) {
        if (visited.add(subtype)) {
          work.add(subtype);
        }
      }
    }
    return false;
  }

  /**
   * Reads explicit nullable-bound evidence without treating an unmarked default as nullable. Root
   * projections take precedence over inherited bounds; an intersection permits null only if all
   * components do. Cyclic bounds provide no additional evidence.
   */
  private boolean explicitlyNullableBound(Type type, Set<Element> visiting) {
    if (Nullness.hasNonNullAnnotation(type.getAnnotationMirrors().stream(), config)) {
      return false;
    }
    if (isKnownNullable(type)) {
      return true;
    }
    if (type instanceof Type.IntersectionClassType intersection) {
      for (TypeMirror component : intersection.getBounds()) {
        if (!explicitlyNullableBound((Type) component, visiting)) {
          return false;
        }
      }
      return true;
    }
    if (!(type instanceof TypeVar variable)
        || type instanceof CapturedType
        || !visiting.add(variable.asElement())) {
      return false;
    }
    try {
      return hasNullableUpperBoundOverride(variable.asElement())
          || explicitlyNullableBound(variable.getUpperBound(), visiting);
    } finally {
      visiting.remove(variable.asElement());
    }
  }

  /** Applies solved root nullness without imposing a default on symbolic fixed variables. */
  private Type inferredRoot(Type source, VarState st) {
    Type annotated = markNestedTypesNonNull(source);
    if (source instanceof TypeVar
        && inferenceVariableForStructure(source) == null
        && st.nullness == NullnessState.UNKNOWN) {
      return annotated;
    }
    return TypeSubstitutionUtils.typeWithAnnot(
        annotated,
        st.nullness == NullnessState.NULLABLE
            ? GenericsChecks.getSyntheticNullableAnnotType(state)
            : GenericsChecks.getSyntheticNonNullAnnotType(state));
  }

  /**
   * Checks whether a supplied Java shape contains only resolved structure. Fixed type variables are
   * symbolic leaves: their upper bounds are contracts, not replacement shapes. Raw types, captures,
   * errors, fresh inference variables, and recursive structural objects are uncertified.
   */
  private boolean hasCertifiedJavaStructure(Type type, IdentityHashMap<Type, Boolean> visiting) {
    if (type.isErroneous() || type instanceof CapturedType || visiting.put(type, true) != null) {
      return false;
    }
    try {
      if (type instanceof TypeVar) {
        return inferenceVariableForStructure(type) == null;
      }
      if (type instanceof Type.ArrayType array) {
        return hasCertifiedJavaStructure(array.elemtype, visiting);
      }
      if (type instanceof WildcardType wildcard) {
        if (!config.handleWildcardGenerics()) {
          return false;
        }
        Type bound =
            wildcard.kind == BoundKind.UNBOUND
                ? GenericsUtils.wildcardUpperBound(wildcard, state, config, handler)
                : wildcard.type;
        return bound != null && hasCertifiedJavaStructure(bound, visiting);
      }
      if (type instanceof ClassType classType) {
        if (classType.isRaw()) {
          return false;
        }
        if (classType instanceof Type.IntersectionClassType intersection) {
          for (TypeMirror bound : intersection.getBounds()) {
            if (!hasCertifiedJavaStructure((Type) bound, visiting)) {
              return false;
            }
          }
        }
        for (Type argument : classType.getTypeArguments()) {
          if (!hasCertifiedJavaStructure(argument, visiting)) {
            return false;
          }
        }
        return hasCertifiedJavaStructure(classType.getEnclosingType(), visiting);
      }
      return type.isPrimitive() || type.hasTag(TypeTag.NONE);
    } finally {
      visiting.remove(type);
    }
  }

  /**
   * Validates every structured lower, upper, and declaration bound after substitution. Pairwise
   * validation remains independent of candidate construction, so a failed merge or unknown sibling
   * cannot hide a concrete conflict. Root occurrence checks stay with ordinary compatibility
   * checks.
   */
  private void validateSolution(
      Map<InferenceVariable, Type> result,
      Set<InferenceVariable> incomplete,
      Set<InferenceVariable> inconsistent) {
    Map<Element, Type> substitutions = new LinkedHashMap<>();
    inferenceVariables.forEach(
        (symbol, variable) -> substitutions.put(symbol, castToNonNull(result.get(variable))));
    for (InferenceVariable variable : result.keySet()) {
      StructuredBounds bounds = structuredBounds(variable);
      Type candidate = result.get(variable);
      List<Type> lowerBounds = substituteBounds(bounds.lower(), substitutions);
      List<Type> upperBounds = substituteBounds(bounds.upper(), substitutions);
      for (Type lower : lowerBounds) {
        recordBoundRelation(
            variable, nullabilitySubtype(lower, candidate, false), incomplete, inconsistent);
        for (Type upper : upperBounds) {
          recordBoundRelation(
              variable, nullabilitySubtype(lower, upper, false), incomplete, inconsistent);
        }
      }
      for (Type upper : upperBounds) {
        recordBoundRelation(
            variable, nullabilitySubtype(candidate, upper, false), incomplete, inconsistent);
      }
      for (Type upper : substituteBounds(bounds.declaredUpper(), substitutions)) {
        recordBoundRelation(
            variable, nullabilitySubtype(candidate, upper, false), incomplete, inconsistent);
      }
      for (InferenceVariable supertype : castToNonNull(vars.get(variable)).structuralSupertypes) {
        recordBoundRelation(
            variable,
            nullabilitySubtype(candidate, castToNonNull(result.get(supertype)), false),
            incomplete,
            inconsistent);
      }
    }
  }

  /** Substitutes solved fresh variables while preserving explicit occurrence annotations. */
  private List<Type> substituteBounds(List<Type> bounds, Map<Element, Type> substitutions) {
    List<Type> result = new ArrayList<>();
    for (Type bound : bounds) {
      result.add(
          TypeSubstitutionUtils.substituteTypeVariables(
              bound, substitutions, state.getTypes(), config));
    }
    return result;
  }

  /** Records uncertified relations without discarding annotation evidence used for diagnostics. */
  private static void recordBoundRelation(
      InferenceVariable variable,
      BoundRelation relation,
      Set<InferenceVariable> incomplete,
      Set<InferenceVariable> inconsistent) {
    if (relation == BoundRelation.UNKNOWN) {
      incomplete.add(variable);
    } else if (relation == BoundRelation.VIOLATED) {
      inconsistent.add(variable);
    }
  }

  /** Propagates uncertified evidence through structural dependencies to a fixed point. */
  private void propagateUncertifiedDependencies(
      Set<InferenceVariable> incomplete, Set<InferenceVariable> inconsistent) {
    boolean changed;
    do {
      changed = false;
      for (Map.Entry<InferenceVariable, VarState> entry : vars.entrySet()) {
        Set<InferenceVariable> dependencies = directStructuredDependencies(entry.getKey());
        dependencies.addAll(entry.getValue().structuralSubtypes);
        dependencies.addAll(entry.getValue().structuralSupertypes);
        for (InferenceVariable dependency : dependencies) {
          if (incomplete.contains(dependency)) {
            changed |= incomplete.add(entry.getKey());
          }
          if (inconsistent.contains(dependency)) {
            changed |= inconsistent.add(entry.getKey());
          }
        }
      }
    } while (changed);
  }

  /**
   * Returns whether structured evidence reachable from {@code start} contains a dependency cycle.
   * Without authoritative Java shapes, cyclic components cannot supply a certified unfolding.
   * Independent bound validation still visits concrete positions within cyclic declarations.
   */
  private boolean hasStructuredDependencyCycle(InferenceVariable start) {
    return hasStructuredDependencyCycle(start, new LinkedHashSet<>(), new LinkedHashSet<>());
  }

  /** Performs depth-first cycle detection over direct structured-bound dependencies. */
  private boolean hasStructuredDependencyCycle(
      InferenceVariable current,
      Set<InferenceVariable> visiting,
      Set<InferenceVariable> completed) {
    if (completed.contains(current)) {
      return false;
    }
    if (!visiting.add(current)) {
      return true;
    }
    for (InferenceVariable dependency : directStructuredDependencies(current)) {
      if (hasStructuredDependencyCycle(dependency, visiting, completed)) {
        return true;
      }
    }
    visiting.remove(current);
    completed.add(current);
    return false;
  }

  /** Returns inference variables occurring directly in this variable's structured evidence. */
  private Set<InferenceVariable> directStructuredDependencies(InferenceVariable inferenceVar) {
    Set<InferenceVariable> dependencies = new LinkedHashSet<>();
    List<Type> evidence = new ArrayList<>();
    VarState st = vars.get(inferenceVar);
    if (st != null) {
      evidence.addAll(st.lowerBoundTypes);
      evidence.addAll(st.upperBoundTypes);
    }
    Type.TypeVar freshTypeVariable = freshTypeVariableForInferenceVariable.get(inferenceVar);
    if (freshTypeVariable != null && nullnessMarkedDeclarationBounds.contains(inferenceVar)) {
      evidence.add(freshTypeVariable.getUpperBound());
    }
    for (Map.Entry<Element, InferenceVariable> entry : inferenceVariables.entrySet()) {
      for (Type type : evidence) {
        TypeVarWithSymbolCollector collector = new TypeVarWithSymbolCollector(entry.getKey());
        type.accept(collector, null);
        if (!collector.getMatches().isEmpty()) {
          dependencies.add(entry.getValue());
          break;
        }
      }
    }
    return dependencies;
  }

  private enum BoundRelation {
    SATISFIED,
    VIOLATED,
    UNKNOWN
  }

  private enum BoundNullness {
    NONNULL,
    NULLABLE,
    UNKNOWN
  }

  /**
   * Structured bounds relevant to one inference variable. {@code declaredUpper} is a subset of
   * {@code upper} retained separately so declaration violations can use a conservative diagnostic
   * fallback without changing the established reporting site for contextual constraints.
   */
  private record StructuredBounds(List<Type> lower, List<Type> upper, List<Type> declaredUpper) {}

  /**
   * Adds constraints implied by declared upper bounds and by every structured lower/upper pair.
   *
   * <p>The latter constraints are transitive consequences of {@code lower <: variable <: upper}.
   * Generating them before nullness propagation lets dependent bounds such as {@code U extends
   * Box<T>} constrain {@code T}, rather than defaulting {@code T} before the structured candidate
   * is checked.
   */
  private void prepareStructuredConstraints() {
    int versionBeforePass;
    do {
      versionBeforePass = structuredConstraintVersion;
      for (InferenceVariable inferenceVar : new ArrayList<>(vars.keySet())) {
        addDeclaredUpperBoundEdge(inferenceVar);
        StructuredBounds bounds = structuredBounds(inferenceVar);
        for (Type lower : bounds.lower()) {
          for (Type upper : bounds.upper()) {
            lower.accept(new AddSubtypeConstraintsVisitor(false), upper);
          }
        }
      }
    } while (structuredConstraintVersion != versionBeforePass);
  }

  /** Adds a variable edge for a declared dependent upper bound such as {@code U extends T}. */
  private void addDeclaredUpperBoundEdge(InferenceVariable inferenceVar) {
    if (!nullnessMarkedDeclarationBounds.contains(inferenceVar)) {
      return;
    }
    Type.TypeVar freshTypeVariable = freshTypeVariableForInferenceVariable.get(inferenceVar);
    if (freshTypeVariable == null) {
      return;
    }
    Type upperBound = freshTypeVariable.getUpperBound();
    InferenceVariable structuralUpper = inferenceVariableForStructure(upperBound);
    if (structuralUpper != null) {
      addStructuralVariableEdge(inferenceVar, structuralUpper);
    }
    InferenceVariable upperVariable = inferenceVariableForUse(upperBound);
    if (upperVariable != null) {
      addInferenceVariableEdge(inferenceVar, upperVariable);
    }
  }

  /** Adds {@code subtype <: supertype}, recording whether the structural graph changed. */
  private void addInferenceVariableEdge(InferenceVariable subtype, InferenceVariable supertype) {
    addStructuralVariableEdge(subtype, supertype);
    boolean changed = getState(subtype).supertypes.add(supertype);
    changed |= getState(supertype).subtypes.add(subtype);
    if (changed) {
      structuredConstraintVersion++;
    }
  }

  /** Adds structural subtype evidence without constraining occurrence-overridden root nullness. */
  private void addStructuralVariableEdge(InferenceVariable subtype, InferenceVariable supertype) {
    boolean changed = getState(subtype).structuralSupertypes.add(supertype);
    changed |= getState(supertype).structuralSubtypes.add(subtype);
    if (changed) {
      structuredConstraintVersion++;
    }
  }

  /**
   * Returns a deterministic structured annotation source for comparison and legacy diagnostics, or
   * {@code null} when the evidence cannot be reconciled. This is not a completion certificate.
   *
   * <p>All reachable lower and upper bounds participate. Lower bounds are merged only through
   * covariant positions; generic type arguments remain invariant. If there are no lower bounds, the
   * most specific compatible upper bound is used. Unknown structure remains uncertified. A
   * contextual upper-bound mismatch is retained as lower-bound annotation evidence so the
   * established ordinary argument or method-reference check reports the incompatibility.
   */
  private @Nullable Type structuredBound(InferenceVariable inferenceVar) {
    Type declarationFallback = declarationBoundFallbacks.get(inferenceVar);
    if (declarationFallback != null) {
      return declarationFallback;
    }
    StructuredBounds bounds = structuredBounds(inferenceVar);
    Type candidate = null;
    for (Type lower : bounds.lower()) {
      if (candidate == null) {
        candidate = lower;
        continue;
      }
      Type merged = mergeCovariantLowerBounds(candidate, lower, false);
      if (merged == null) {
        return null;
      }
      candidate = merged;
    }
    if (candidate == null) {
      for (Type upper : bounds.upper()) {
        candidate = candidate == null ? upper : moreSpecificUpperBound(candidate, upper);
        if (candidate == null) {
          return null;
        }
      }
    }
    if (candidate == null) {
      return null;
    }
    for (Type lower : bounds.lower()) {
      BoundRelation relation = nullabilitySubtype(lower, candidate, false);
      if (relation == BoundRelation.VIOLATED) {
        return null;
      }
      if (relation == BoundRelation.UNKNOWN) {
        return null;
      }
    }
    for (Type upper : bounds.upper()) {
      BoundRelation relation = nullabilitySubtype(candidate, upper, false);
      if (relation == BoundRelation.VIOLATED) {
        // Declaration violations are handled independently before constructing any candidate.
        // Keep lower evidence for contextual mismatches so ordinary compatibility checks report it.
        return candidate;
      }
      if (relation == BoundRelation.UNKNOWN) {
        return null;
      }
    }
    return candidate;
  }

  /**
   * Validates every known lower against every declaration bound, independently of candidate merging
   * and cycle fallback. A representable upper-bound source preserves ordinary argument diagnostics;
   * otherwise the existing exception lets GenericsChecks report the proven violation. Returns the
   * diagnostic annotation source, or {@code null} when no violation is proven.
   */
  private @Nullable Type validateDeclarationBounds(InferenceVariable inferenceVar) {
    StructuredBounds bounds = structuredBounds(inferenceVar);
    Type offendingLower = null;
    Type offendingUpper = null;
    for (Type lower : bounds.lower()) {
      for (Type upper : bounds.declaredUpper()) {
        if (nullabilitySubtype(lower, upper, false) == BoundRelation.VIOLATED
            && offendingLower == null) {
          offendingLower = lower;
          offendingUpper = upper;
        }
      }
    }
    if (offendingLower == null) {
      return null;
    }
    Type fallback = validatedUpperBoundFallback(bounds.upper());
    VarState st = vars.get(inferenceVar);
    boolean representable =
        fallback != null
            && !hasStructuredDependencyCycle(inferenceVar)
            && (st == null || st.fixedTypeVariableLowerBounds.isEmpty());
    if (fallback != null) {
      for (Type lower : bounds.lower()) {
        representable &= canOverlayBound(lower, fallback);
      }
    }
    if (!representable) {
      throw new NestedUpperBoundViolationException(
          inferenceVar.typeVariable(),
          inferenceVar.site(),
          declarationViolationSource(inferenceVar, offendingLower),
          castToNonNull(offendingUpper));
    }
    return castToNonNull(fallback);
  }

  /**
   * Checks recursively whether annotation overlay preserves corresponding nominal positions. In
   * particular, array covariance must not permit positional copying between unrelated generic
   * component shapes, even when their argument counts happen to match.
   */
  private boolean canOverlayBound(Type target, Type source) {
    if (!beginBoundComparison("overlay", target, source)) {
      return false;
    }
    try {
      return hasCorrespondingOverlayStructure(target, source);
    } finally {
      endBoundComparison("overlay", target, source);
    }
  }

  /** Checks array components, class arguments, and enclosing types for safe positional overlay. */
  private boolean hasCorrespondingOverlayStructure(Type target, Type source) {
    if (target instanceof Type.ArrayType targetArray
        && source instanceof Type.ArrayType sourceArray) {
      return canOverlayBound(targetArray.elemtype, sourceArray.elemtype);
    }
    if (target instanceof ClassType targetClass && source instanceof ClassType sourceClass) {
      if (!targetClass.tsym.equals(sourceClass.tsym)
          || targetClass.isRaw()
          || sourceClass.isRaw()
          || targetClass.getTypeArguments().size() != sourceClass.getTypeArguments().size()) {
        return false;
      }
      for (int i = 0; i < targetClass.getTypeArguments().size(); i++) {
        if (!canOverlayBound(
            targetClass.getTypeArguments().get(i), sourceClass.getTypeArguments().get(i))) {
          return false;
        }
      }
      return canOverlayBound(targetClass.getEnclosingType(), sourceClass.getEnclosingType());
    }
    if (target instanceof TypeVar targetVariable && source instanceof TypeVar sourceVariable) {
      return inferenceVariableForStructure(target) == null
          && inferenceVariableForStructure(source) == null
          && !(target instanceof CapturedType)
          && !(source instanceof CapturedType)
          && targetVariable.tsym.equals(sourceVariable.tsym);
    }
    if (target instanceof WildcardType targetWildcard
        && source instanceof WildcardType sourceWildcard) {
      return config.handleWildcardGenerics()
          && targetWildcard.kind == sourceWildcard.kind
          && (targetWildcard.kind == BoundKind.UNBOUND
              || (targetWildcard.type != null
                  && sourceWildcard.type != null
                  && canOverlayBound(targetWildcard.type, sourceWildcard.type)));
    }
    // Unresolved variables and differing wildcard forms cannot be copied positionally.
    if (target instanceof TypeVar
        || source instanceof TypeVar
        || target instanceof WildcardType
        || source instanceof WildcardType) {
      return false;
    }
    return !(target instanceof ClassType)
        && !(source instanceof ClassType)
        && !(target instanceof Type.ArrayType)
        && !(source instanceof Type.ArrayType)
        && state.getTypes().isSameType(target, source);
  }

  /** Returns the source-level lower type to display for a declaration-bound violation. */
  private Type declarationViolationSource(InferenceVariable inferenceVar, Type candidate) {
    VarState st = vars.get(inferenceVar);
    return st != null && !st.fixedTypeVariableLowerBounds.isEmpty()
        ? st.fixedTypeVariableLowerBounds.get(0)
        : candidate;
  }

  /**
   * Returns a structured upper bound satisfying every upper bound, for use as a diagnostic fallback
   * when lower bounds violate a declaration bound. This fallback causes ordinary argument
   * compatibility checking to report the offending lower bound; it is never selected merely by
   * lower-bound order.
   */
  private @Nullable Type validatedUpperBoundFallback(List<Type> upperBounds) {
    Type fallback = null;
    for (Type upper : upperBounds) {
      fallback = fallback == null ? upper : moreSpecificUpperBound(fallback, upper);
      if (fallback == null) {
        return null;
      }
    }
    if (fallback == null) {
      return null;
    }
    Type validatedFallback = fallback;
    for (Type upper : upperBounds) {
      if (nullabilitySubtype(validatedFallback, upper, false) != BoundRelation.SATISFIED) {
        return null;
      }
    }
    return validatedFallback;
  }

  /**
   * Collects all structured bounds flowing to and from {@code inferenceVar} in breadth-first order.
   */
  private StructuredBounds structuredBounds(InferenceVariable inferenceVar) {
    return new StructuredBounds(
        collectStructuredBounds(inferenceVar, true),
        collectStructuredBounds(inferenceVar, false),
        collectDeclaredUpperBounds(inferenceVar));
  }

  /** Collects declaration/model structured upper bounds reachable through supertype edges. */
  private List<Type> collectDeclaredUpperBounds(InferenceVariable start) {
    List<Type> result = new ArrayList<>();
    Deque<InferenceVariable> worklist = new ArrayDeque<>();
    Set<InferenceVariable> visited = new LinkedHashSet<>();
    worklist.add(start);
    visited.add(start);
    while (!worklist.isEmpty()) {
      InferenceVariable current = worklist.removeFirst();
      Type.TypeVar freshTypeVariable = freshTypeVariableForInferenceVariable.get(current);
      if (freshTypeVariable != null && nullnessMarkedDeclarationBounds.contains(current)) {
        addStructuredDeclaredUpperBounds(result, freshTypeVariable.getUpperBound());
      }
      VarState st = vars.get(current);
      if (st != null) {
        for (InferenceVariable supertype : st.structuralSupertypes) {
          if (visited.add(supertype)) {
            worklist.add(supertype);
          }
        }
      }
    }
    return result;
  }

  /**
   * Collects structured lower bounds through subtype edges or upper bounds through supertype edges.
   * Declared structured upper bounds, including each component of an intersection bound, are added
   * while collecting upper bounds.
   */
  private List<Type> collectStructuredBounds(InferenceVariable start, boolean lower) {
    List<Type> result = new ArrayList<>();
    Deque<InferenceVariable> worklist = new ArrayDeque<>();
    Set<InferenceVariable> visited = new LinkedHashSet<>();
    worklist.add(start);
    visited.add(start);
    while (!worklist.isEmpty()) {
      InferenceVariable current = worklist.removeFirst();
      VarState st = vars.get(current);
      if (st == null) {
        continue;
      }
      for (Type bound : lower ? st.lowerBoundTypes : st.upperBoundTypes) {
        addIfNotIdentical(result, bound);
      }
      if (!lower) {
        Type.TypeVar freshTypeVariable = freshTypeVariableForInferenceVariable.get(current);
        if (freshTypeVariable != null && nullnessMarkedDeclarationBounds.contains(current)) {
          addStructuredDeclaredUpperBounds(result, freshTypeVariable.getUpperBound());
        }
      }
      for (InferenceVariable next : lower ? st.structuralSubtypes : st.structuralSupertypes) {
        if (visited.add(next)) {
          worklist.add(next);
        }
      }
    }
    return result;
  }

  /** Adds the structured components of a declared upper bound to {@code result}. */
  private void addStructuredDeclaredUpperBounds(List<Type> result, Type upperBound) {
    if (upperBound instanceof Type.IntersectionClassType intersectionType) {
      for (TypeMirror component : intersectionType.getBounds()) {
        addStructuredDeclaredUpperBounds(result, (Type) component);
      }
    } else if (isStructuredType(upperBound)
        || (upperBound instanceof TypeVar && inferenceVariableForStructure(upperBound) == null)) {
      addIfNotIdentical(result, upperBound);
    }
  }

  /** Returns whether {@code boundType} can expose nested nullability during alignment. */
  private static boolean isStructuredType(Type boundType) {
    return boundType instanceof Type.ArrayType
        || (boundType instanceof ClassType classType && !classType.isRaw());
  }

  /** Adds {@code type} unless the same javac type object is already present. */
  private static void addIfNotIdentical(List<Type> types, Type type) {
    if (!containsIdentical(types, type)) {
      types.add(type);
    }
  }

  /**
   * Merges two lower bounds at a covariant position. Arrays recurse covariantly into their
   * components; class type arguments must have identical nullability because Java generics are
   * invariant. Returns {@code null} when no safe, order-independent annotation source is known.
   */
  private @Nullable Type mergeCovariantLowerBounds(Type first, Type second, boolean mergeTopLevel) {
    if (first instanceof Type.ArrayType firstArray
        && second instanceof Type.ArrayType secondArray) {
      Type component =
          mergeCovariantLowerBounds(
              firstArray.getComponentType(), secondArray.getComponentType(), true);
      if (component == null) {
        return null;
      }
      Type merged = TYPE_METADATA_BUILDER.createArrayType(firstArray, component);
      return mergeTopLevel ? mergeLowerBoundTopLevel(merged, first, second) : merged;
    }
    Type firstAsSecond = alignSubtypeWithSupertype(first, second);
    Type secondAsFirst = alignSubtypeWithSupertype(second, first);
    Type chosen;
    Type otherAligned;
    if (firstAsSecond != null) {
      chosen = second;
      otherAligned = firstAsSecond;
    } else if (secondAsFirst != null) {
      chosen = first;
      otherAligned = secondAsFirst;
    } else {
      return null;
    }
    if (!canOverlayBound(first, chosen)
        || !canOverlayBound(second, chosen)
        || sameInvariantStructure(chosen, otherAligned, false) != BoundRelation.SATISFIED) {
      return null;
    }
    return mergeTopLevel ? mergeLowerBoundTopLevel(chosen, first, second) : chosen;
  }

  /**
   * Returns {@code subtype} viewed as the base type of {@code supertype}, or {@code null} if javac
   * does not establish that relationship.
   */
  private @Nullable Type alignSubtypeWithSupertype(Type subtype, Type supertype) {
    if (!state.getTypes().isSubtype(subtype, supertype)) {
      return null;
    }
    if (subtype instanceof ClassType
        && supertype instanceof ClassType
        && supertype.tsym instanceof Symbol.ClassSymbol superSymbol) {
      return TypeSubstitutionUtils.asSuper(state.getTypes(), subtype, superSymbol, config);
    }
    return subtype;
  }

  /** Applies the least upper nullness of two lower bounds to {@code base}. */
  private @Nullable Type mergeLowerBoundTopLevel(Type base, Type first, Type second) {
    BoundNullness firstNullness = boundNullness(first);
    BoundNullness secondNullness = boundNullness(second);
    if (firstNullness == BoundNullness.UNKNOWN || secondNullness == BoundNullness.UNKNOWN) {
      return sameInferenceVariable(first, second) ? base : null;
    }
    boolean nullable =
        firstNullness == BoundNullness.NULLABLE || secondNullness == BoundNullness.NULLABLE;
    Type annotationSource =
        nullable
            ? firstNullness == BoundNullness.NULLABLE ? first : second
            : firstNullness == BoundNullness.NONNULL ? first : second;
    for (AnnotationMirror annotation : annotationSource.getAnnotationMirrors()) {
      String annotationName = annotation.getAnnotationType().toString();
      if ((nullable && Nullness.isNullableAnnotation(annotationName, config))
          || (!nullable && Nullness.isNonNullAnnotation(annotationName, config))) {
        return TypeSubstitutionUtils.typeWithAnnot(base, (Type) annotation.getAnnotationType());
      }
    }
    Type syntheticAnnotation =
        nullable
            ? GenericsChecks.getSyntheticNullableAnnotType(state)
            : GenericsChecks.getSyntheticNonNullAnnotType(state);
    return TypeSubstitutionUtils.typeWithAnnot(base, syntheticAnnotation);
  }

  /** Returns the more specific of two compatible upper bounds, with deterministic tie-breaking. */
  private @Nullable Type moreSpecificUpperBound(Type first, Type second) {
    if (nullabilitySubtype(first, second, false) == BoundRelation.SATISFIED) {
      return first;
    }
    if (nullabilitySubtype(second, first, false) == BoundRelation.SATISFIED) {
      return second;
    }
    return null;
  }

  /**
   * Checks the nullability-aware subtype relation needed for structured bounds. Generic arguments
   * are compared invariantly, while array components are compared covariantly.
   */
  private BoundRelation nullabilitySubtype(Type subtype, Type supertype, boolean compareTopLevel) {
    String mode = compareTopLevel ? "subtype-top" : "subtype-nested";
    if (!beginBoundComparison(mode, subtype, supertype)) {
      return BoundRelation.UNKNOWN;
    }
    try {
      BoundRelation topLevel =
          compareTopLevel
              ? topLevelNullabilitySubtype(subtype, supertype)
              : BoundRelation.SATISFIED;
      return combineBoundRelations(
          topLevel, nestedNullabilitySubtype(subtype, supertype, compareTopLevel));
    } finally {
      endBoundComparison(mode, subtype, supertype);
    }
  }

  /**
   * Checks nested subtype structure without allowing unknown top-level nullness to hide a
   * violation.
   */
  private BoundRelation nestedNullabilitySubtype(
      Type subtype, Type supertype, boolean compareTopLevel) {
    WildcardType formalWildcard = GenericsUtils.asWildcard(supertype);
    WildcardType actualWildcard = GenericsUtils.asWildcard(subtype);
    if (formalWildcard != null || actualWildcard != null) {
      return config.handleWildcardGenerics()
          ? typeArgumentContainment(subtype, supertype)
          : BoundRelation.UNKNOWN;
    }
    if (subtype instanceof TypeVar || supertype instanceof TypeVar) {
      InferenceVariable subtypeVariable = inferenceVariableForStructure(subtype);
      InferenceVariable supertypeVariable = inferenceVariableForStructure(supertype);
      if (subtypeVariable != null && subtypeVariable.equals(supertypeVariable)) {
        return BoundRelation.SATISFIED;
      }
      Type resolvedSubtype = resolveInvariantOccurrence(subtype);
      Type resolvedSupertype = resolveInvariantOccurrence(supertype);
      if (resolvedSubtype == null && resolvedSupertype == null) {
        if (subtype instanceof TypeVar fixedSubtype
            && inferenceVariableForStructure(subtype) == null
            && !(subtype instanceof CapturedType)) {
          if (supertype instanceof TypeVar) {
            return state.getTypes().isSubtype(subtype, supertype)
                ? BoundRelation.SATISFIED
                : BoundRelation.UNKNOWN;
          }
          // Inspect a fixed variable's contract only for checking, never as an inferred shape.
          return nullabilitySubtype(fixedSubtype.getUpperBound(), supertype, false);
        }
        return BoundRelation.UNKNOWN;
      }
      return nullabilitySubtype(
          resolvedSubtype != null ? resolvedSubtype : subtype,
          resolvedSupertype != null ? resolvedSupertype : supertype,
          compareTopLevel);
    }
    if (subtype instanceof Type.ArrayType subtypeArray
        && supertype instanceof Type.ArrayType supertypeArray) {
      return nullabilitySubtype(subtypeArray.elemtype, supertypeArray.elemtype, true);
    }
    if (subtype instanceof ClassType && supertype instanceof ClassType supertypeClass) {
      if (!(supertypeClass.tsym instanceof Symbol.ClassSymbol superSymbol)) {
        return BoundRelation.UNKNOWN;
      }
      Type aligned = TypeSubstitutionUtils.asSuper(state.getTypes(), subtype, superSymbol, config);
      if (!(aligned instanceof ClassType alignedClass)) {
        return BoundRelation.VIOLATED;
      }
      if (alignedClass.isRaw() || supertypeClass.isRaw()) {
        return BoundRelation.UNKNOWN;
      }
      if (alignedClass.getTypeArguments().size() != supertypeClass.getTypeArguments().size()) {
        return BoundRelation.VIOLATED;
      }
      BoundRelation result = BoundRelation.SATISFIED;
      for (int i = 0; i < alignedClass.getTypeArguments().size(); i++) {
        result =
            combineBoundRelations(
                result,
                typeArgumentContainment(
                    alignedClass.getTypeArguments().get(i),
                    supertypeClass.getTypeArguments().get(i)));
      }
      return combineBoundRelations(
          result,
          sameInvariantStructure(
              alignedClass.getEnclosingType(), supertypeClass.getEnclosingType(), true));
    }
    return state.getTypes().isSubtype(subtype, supertype)
        ? BoundRelation.SATISFIED
        : BoundRelation.VIOLATED;
  }

  /** Combines independent positions; a concrete violation dominates any unknown sibling. */
  private static BoundRelation combineBoundRelations(BoundRelation first, BoundRelation second) {
    if (first == BoundRelation.VIOLATED || second == BoundRelation.VIOLATED) {
      return BoundRelation.VIOLATED;
    }
    return first == BoundRelation.UNKNOWN || second == BoundRelation.UNKNOWN
        ? BoundRelation.UNKNOWN
        : BoundRelation.SATISFIED;
  }

  /** Starts a comparison unless the same directed identity pair and mode is already active. */
  private boolean beginBoundComparison(String mode, Type first, Type second) {
    IdentityHashMap<Type, Set<Type>> pairs =
        activeBoundComparisons.computeIfAbsent(mode, unused -> new IdentityHashMap<>());
    Set<Type> seconds =
        pairs.computeIfAbsent(first, unused -> Collections.newSetFromMap(new IdentityHashMap<>()));
    return seconds.add(second);
  }

  /** Removes an active comparison after all its independent positions have been visited. */
  private void endBoundComparison(String mode, Type first, Type second) {
    IdentityHashMap<Type, Set<Type>> pairs = castToNonNull(activeBoundComparisons.get(mode));
    Set<Type> seconds = castToNonNull(pairs.get(first));
    seconds.remove(second);
    if (seconds.isEmpty()) {
      pairs.remove(first);
    }
    if (pairs.isEmpty()) {
      activeBoundComparisons.remove(mode);
    }
  }

  /**
   * Checks directed type-argument containment using the same effective wildcard bounds as
   * constraint generation. Extends bounds are covariant and super bounds contravariant; concrete
   * arguments remain invariant. Wildcard checking is unknown when its feature gate is disabled.
   */
  private BoundRelation typeArgumentContainment(Type actual, Type formal) {
    WildcardType formalWildcard = GenericsUtils.asWildcard(formal);
    WildcardType actualWildcard = GenericsUtils.asWildcard(actual);
    if (formalWildcard == null && actualWildcard == null) {
      return sameInvariantStructure(actual, formal, true);
    }
    if (!config.handleWildcardGenerics() || !beginBoundComparison("containment", actual, formal)) {
      return BoundRelation.UNKNOWN;
    }
    try {
      if (formalWildcard == null) {
        return BoundRelation.UNKNOWN;
      }
      if (formalWildcard.kind == BoundKind.SUPER) {
        Type formalLower = castToNonNull(formalWildcard.getSuperBound());
        if (actualWildcard == null) {
          return nullabilitySubtype(formalLower, actual, true);
        }
        return actualWildcard.kind == BoundKind.SUPER
            ? nullabilitySubtype(formalLower, castToNonNull(actualWildcard.getSuperBound()), true)
            : BoundRelation.UNKNOWN;
      }
      return nullabilitySubtype(
          GenericsUtils.effectiveWildcardUpperBound(actual, state, config, handler),
          GenericsUtils.wildcardUpperBound(formalWildcard, state, config, handler),
          true);
    } finally {
      endBoundComparison("containment", actual, formal);
    }
  }

  /** Checks identical nullability and nested structure for an invariant generic position. */
  private BoundRelation sameInvariantStructure(Type first, Type second, boolean compareTopLevel) {
    String mode = compareTopLevel ? "invariant-top" : "invariant-nested";
    if (!beginBoundComparison(mode, first, second)) {
      return BoundRelation.UNKNOWN;
    }
    try {
      return compareInvariantStructure(first, second, compareTopLevel);
    } finally {
      endBoundComparison(mode, first, second);
    }
  }

  /**
   * Compares all invariant positions, retaining concrete violations alongside unresolved positions.
   */
  private BoundRelation compareInvariantStructure(
      Type first, Type second, boolean compareTopLevel) {
    if (GenericsUtils.asWildcard(first) != null || GenericsUtils.asWildcard(second) != null) {
      return combineBoundRelations(
          typeArgumentContainment(first, second), typeArgumentContainment(second, first));
    }
    BoundRelation result = BoundRelation.SATISFIED;
    if (compareTopLevel) {
      BoundNullness firstNullness = boundNullness(first);
      BoundNullness secondNullness = boundNullness(second);
      if (firstNullness == BoundNullness.UNKNOWN || secondNullness == BoundNullness.UNKNOWN) {
        if (!sameInferenceVariable(first, second)
            && !(first instanceof TypeVar firstVariable
                && second instanceof TypeVar secondVariable
                && firstVariable.tsym.equals(secondVariable.tsym)
                && firstNullness == secondNullness)) {
          result = BoundRelation.UNKNOWN;
        }
      } else if (firstNullness != secondNullness) {
        result = BoundRelation.VIOLATED;
      }
    }
    if (first instanceof TypeVar || second instanceof TypeVar) {
      return combineBoundRelations(
          result, compareTypeVariableStructure(first, second, compareTopLevel));
    }
    if (first instanceof Type.ArrayType firstArray
        && second instanceof Type.ArrayType secondArray) {
      return combineBoundRelations(
          result,
          sameInvariantStructure(
              firstArray.getComponentType(), secondArray.getComponentType(), true));
    }
    if (first instanceof ClassType firstClass && second instanceof ClassType secondClass) {
      if (firstClass.isRaw() || secondClass.isRaw()) {
        return combineBoundRelations(result, BoundRelation.UNKNOWN);
      }
      if (!firstClass.tsym.equals(secondClass.tsym)
          || firstClass.getTypeArguments().size() != secondClass.getTypeArguments().size()) {
        return BoundRelation.VIOLATED;
      }
      for (int i = 0; i < firstClass.getTypeArguments().size(); i++) {
        result =
            combineBoundRelations(
                result,
                sameInvariantStructure(
                    firstClass.getTypeArguments().get(i),
                    secondClass.getTypeArguments().get(i),
                    true));
      }
      return combineBoundRelations(
          result,
          sameInvariantStructure(
              firstClass.getEnclosingType(), secondClass.getEnclosingType(), true));
    }
    return combineBoundRelations(
        result,
        state.getTypes().isSameType(first, second)
            ? BoundRelation.SATISFIED
            : BoundRelation.VIOLATED);
  }

  /**
   * Compares invariant structure involving type variables. Acyclic inference variables are replaced
   * by their reconciled structured evidence; unresolved, distinct, or cyclic variables remain
   * unknown rather than being treated as equal.
   */
  private BoundRelation compareTypeVariableStructure(
      Type first, Type second, boolean compareTopLevel) {
    InferenceVariable firstVariable = inferenceVariableForStructure(first);
    InferenceVariable secondVariable = inferenceVariableForStructure(second);
    if (firstVariable != null && firstVariable.equals(secondVariable)) {
      return BoundRelation.SATISFIED;
    }
    Type resolvedFirst = resolveInvariantOccurrence(first);
    Type resolvedSecond = resolveInvariantOccurrence(second);
    if (resolvedFirst == null && resolvedSecond == null) {
      return first instanceof TypeVar firstTypeVar
              && second instanceof TypeVar secondTypeVar
              && firstTypeVar.tsym.equals(secondTypeVar.tsym)
          ? BoundRelation.SATISFIED
          : BoundRelation.UNKNOWN;
    }
    return sameInvariantStructure(
        resolvedFirst != null ? resolvedFirst : first,
        resolvedSecond != null ? resolvedSecond : second,
        compareTopLevel);
  }

  /** Resolves structural ownership while retaining explicit root annotations on the occurrence. */
  private @Nullable Type resolveInvariantOccurrence(Type occurrence) {
    InferenceVariable variable = inferenceVariableForStructure(occurrence);
    Type resolved = resolveInvariantVariable(variable);
    return resolved == null
        ? null
        : TypeSubstitutionUtils.substituteTypeVariables(
            occurrence, Map.of(occurrence.tsym, resolved), state.getTypes(), config);
  }

  /** Returns reconciled acyclic structure for {@code variable}, or {@code null} if unavailable. */
  private @Nullable Type resolveInvariantVariable(@Nullable InferenceVariable variable) {
    if (variable == null
        || hasStructuredDependencyCycle(variable)
        || !invariantResolutionInProgress.add(variable)) {
      return null;
    }
    try {
      return structuredBound(variable);
    } finally {
      invariantResolutionInProgress.remove(variable);
    }
  }

  /**
   * Returns whether the top-level nullness of {@code subtype} is a subtype of {@code supertype}.
   */
  private BoundRelation topLevelNullabilitySubtype(Type subtype, Type supertype) {
    BoundNullness subtypeNullness = boundNullness(subtype);
    BoundNullness supertypeNullness = boundNullness(supertype);
    if (supertypeNullness == BoundNullness.NULLABLE) {
      return BoundRelation.SATISFIED;
    }
    if (subtypeNullness == BoundNullness.UNKNOWN || supertypeNullness == BoundNullness.UNKNOWN) {
      return sameInferenceVariable(subtype, supertype)
          ? BoundRelation.SATISFIED
          : BoundRelation.UNKNOWN;
    }
    return subtypeNullness == BoundNullness.NULLABLE && supertypeNullness == BoundNullness.NONNULL
        ? BoundRelation.VIOLATED
        : BoundRelation.SATISFIED;
  }

  /** Returns the known top-level nullness of a bound type, consulting solved variable state. */
  private BoundNullness boundNullness(Type type) {
    if (isKnownNullable(type)) {
      return BoundNullness.NULLABLE;
    }
    if (Nullness.hasNonNullAnnotation(type.getAnnotationMirrors().stream(), config)) {
      return BoundNullness.NONNULL;
    }
    InferenceVariable inferenceVariable = inferenceVariableForUse(type);
    if (inferenceVariable != null) {
      VarState st = vars.get(inferenceVariable);
      if (st == null || st.nullness == NullnessState.UNKNOWN) {
        return BoundNullness.UNKNOWN;
      }
      return st.nullness == NullnessState.NULLABLE ? BoundNullness.NULLABLE : BoundNullness.NONNULL;
    }
    return type instanceof TypeVar && !isKnownNonNull(type)
        ? BoundNullness.UNKNOWN
        : BoundNullness.NONNULL;
  }

  /** Returns whether both types denote the same unannotated inference variable. */
  private boolean sameInferenceVariable(Type first, Type second) {
    InferenceVariable firstVariable = inferenceVariableForUse(first);
    return firstVariable != null && firstVariable.equals(inferenceVariableForUse(second));
  }

  /** Returns whether {@code types} contains {@code type} itself (by reference). */
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"})
  private static boolean containsIdentical(List<Type> types, Type type) {
    for (Type t : types) {
      if (t == type) {
        return true;
      }
    }
    return false;
  }

  /**
   * Records structured evidence for a lower or upper bound. Fixed variables remain symbolic;
   * checking may inspect their bounds, but candidate construction must not replace them with those
   * bounds. Their original uses also provide provenance for declaration-bound diagnostics. Arrays
   * and non-raw classes retain nested annotations and supertype-alignment evidence.
   */
  private void recordStructuredBound(
      InferenceVariable inferenceVar, Type boundType, boolean lower) {
    VarState st = getState(inferenceVar);
    if (lower && boundType instanceof TypeVar && inferenceVariableForStructure(boundType) == null) {
      if (st.fixedTypeVariableLowerBoundKeys.add(structuredTypeKey(boundType))) {
        st.fixedTypeVariableLowerBounds.add(boundType);
        structuredConstraintVersion++;
      }
    }
    if (!isStructuredType(boundType)
        && !(boundType instanceof TypeVar)
        && !(boundType instanceof ClassType)) {
      return;
    }
    List<Type> bounds = lower ? st.lowerBoundTypes : st.upperBoundTypes;
    Set<String> keys = lower ? st.lowerBoundKeys : st.upperBoundKeys;
    if (keys.add(structuredTypeKey(boundType))) {
      bounds.add(boundType);
      structuredConstraintVersion++;
    }
  }

  /**
   * Returns a deterministic fingerprint including nested nullness and inference-variable identity.
   */
  private String structuredTypeKey(Type type) {
    StringBuilder result = new StringBuilder();
    appendStructuredTypeKey(type, result, new IdentityHashMap<>());
    return result.toString();
  }

  /** Appends one type node to a structured-bound fingerprint, guarding recursive javac types. */
  private void appendStructuredTypeKey(
      Type type, StringBuilder result, IdentityHashMap<Type, Boolean> active) {
    if (active.put(type, Boolean.TRUE) != null) {
      result.append("cycle@").append(symbolFingerprint(type.tsym));
      return;
    }
    try {
      result.append(type.getKind()).append('[');
      List<String> annotations = new ArrayList<>();
      for (AnnotationMirror annotation : type.getAnnotationMirrors()) {
        annotations.add(annotation.getAnnotationType().toString());
      }
      Collections.sort(annotations);
      annotations.forEach(annotation -> result.append(annotation).append(';'));
      result.append(']');
      if (type instanceof Type.ArrayType arrayType) {
        result.append("array(");
        appendStructuredTypeKey(arrayType.getComponentType(), result, active);
        result.append(')');
      } else if (type instanceof Type.IntersectionClassType intersectionType) {
        result.append("intersection(");
        for (TypeMirror bound : intersectionType.getBounds()) {
          appendStructuredTypeKey((Type) bound, result, active);
          result.append('&');
        }
        result.append(')');
      } else if (type instanceof ClassType classType) {
        result
            .append("class:")
            .append(classType.tsym.getQualifiedName())
            .append('@')
            .append(symbolFingerprint(classType.tsym))
            .append('<');
        for (Type argument : classType.getTypeArguments()) {
          appendStructuredTypeKey(argument, result, active);
          result.append(',');
        }
        result.append('>');
        if (classType.getEnclosingType() instanceof ClassType) {
          result.append(" enclosing(");
          appendStructuredTypeKey(classType.getEnclosingType(), result, active);
          result.append(')');
        }
      } else if (type instanceof WildcardType wildcardType) {
        result.append("wildcard:").append(wildcardType.kind).append('(');
        Type bound =
            wildcardType.kind == BoundKind.SUPER
                ? wildcardType.getSuperBound()
                : wildcardType.getExtendsBound();
        if (bound != null) {
          appendStructuredTypeKey(bound, result, active);
        }
        result.append(')');
      } else if (type instanceof TypeVar typeVariable) {
        result.append("var@").append(symbolFingerprint(typeVariable.tsym));
      } else {
        result.append(type);
      }
    } finally {
      active.remove(type);
    }
  }

  /** Returns a collision-free identity ID for a symbol during this solver run. */
  private int symbolFingerprint(@Nullable Symbol symbol) {
    if (symbol == null) {
      return 0;
    }
    Integer existing = fingerprintSymbolIds.get(symbol);
    if (existing != null) {
      return existing;
    }
    int created = fingerprintSymbolIds.size() + 1;
    fingerprintSymbolIds.put(symbol, created);
    return created;
  }

  /**
   * Returns {@code type} with each nested class or array type that has no explicit nullability
   * annotation marked with a synthetic {@code @NonNull} annotation. The top-level type itself is
   * not changed. Type variables, wildcards, and captured types are left as is, since no annotation
   * on them does not mean non-null.
   */
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"})
  private Type markNestedTypesNonNull(Type type) {
    if (type instanceof WildcardType wildcard
        && wildcard.kind != BoundKind.UNBOUND
        && wildcard.type != null) {
      Type bound = markNonNullIfUnannotated(wildcard.type);
      if (bound == wildcard.type) {
        return type;
      }
      WildcardType result = TYPE_METADATA_BUILDER.createWildcardType(wildcard, bound);
      result.bound = wildcard.bound;
      return result;
    }
    if (type instanceof Type.ArrayType arrayType) {
      Type elemType = arrayType.getComponentType();
      Type newElemType = markNonNullIfUnannotated(elemType);
      return newElemType == elemType
          ? type
          : TYPE_METADATA_BUILDER.createArrayType(arrayType, newElemType);
    }
    if (type instanceof ClassType classType && !classType.isRaw()) {
      boolean changed = false;
      List<Type> newTypeArgs = new ArrayList<>();
      for (Type typeArg : classType.getTypeArguments()) {
        Type newTypeArg = markNonNullIfUnannotated(typeArg);
        changed |= newTypeArg != typeArg;
        newTypeArgs.add(newTypeArg);
      }
      Type enclosingType = classType.getEnclosingType();
      Type newEnclosingType = markNonNullIfUnannotated(enclosingType);
      changed |= newEnclosingType != enclosingType;
      return changed
          ? TYPE_METADATA_BUILDER.createClassType(classType, newEnclosingType, newTypeArgs)
          : type;
    }
    return type;
  }

  /**
   * Like {@link #markNestedTypesNonNull(Type)}, but also marks {@code type} itself if it is an
   * unannotated class or array type.
   */
  private Type markNonNullIfUnannotated(Type type) {
    if (type instanceof WildcardType) {
      return markNestedTypesNonNull(type);
    }
    if (!(type instanceof ClassType) && !(type instanceof Type.ArrayType)) {
      return type;
    }
    Type result = type;
    if (!Nullness.hasNullableAnnotation(type.getAnnotationMirrors().stream(), config)
        && !Nullness.hasNonNullAnnotation(type.getAnnotationMirrors().stream(), config)) {
      result =
          TypeSubstitutionUtils.typeWithAnnot(
              type, GenericsChecks.getSyntheticNonNullAnnotType(state));
    }
    return markNestedTypesNonNull(result);
  }

  /**
   * Adds scalar and structural constraints separately. Explicit occurrence annotations can disable
   * scalar inference without changing structural ownership or the nested declaration contract.
   */
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
      addInferenceVariableEdge(sVar, tVar);
    }

    // Root use-site annotations affect scalar constraints, not the variable's nested contract.
    InferenceVariable sStructure = inferenceVariableForStructure(s);
    InferenceVariable tStructure = inferenceVariableForStructure(t);
    if (tStructure != null) {
      if (sStructure != null) {
        addStructuralVariableEdge(sStructure, tStructure);
      } else {
        recordStructuredBound(tStructure, s, true);
      }
    } else if (sStructure != null) {
      recordStructuredBound(sStructure, t, false);
    }

    if (tVar != null
        && sStructure == null
        && s instanceof TypeVar
        && !(s instanceof CapturedType)) {
      VarState target = getState(tVar);
      if (target.rootFixedLowerBoundKeys.add(structuredTypeKey(s))) {
        target.rootFixedLowerBounds.add(s);
        structuredConstraintVersion++;
      }
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
      throw new UnsatisfiableConstraintsException(
          inferenceVar.typeVariable(), true, inferenceVar.site());
    }
    if (st.nullness != NullnessState.UNKNOWN) {
      throw new UnsatisfiableConstraintsException(
          inferenceVar.typeVariable(), false, inferenceVar.site());
    }
    st.nullness = n;
    return true;
  }

  /* ───────────────────── helpers & stubs ───────────────────── */

  /**
   * Returns whether a handler explicitly models this declared type variable's bound as nullable.
   */
  private boolean hasNullableUpperBoundOverride(Element typeVariable) {
    Symbol.TypeVariableSymbol symbol = (Symbol.TypeVariableSymbol) typeVariable;
    if (symbol.owner instanceof Symbol.MethodSymbol method) {
      int index = method.getTypeParameters().indexOf(symbol);
      return index >= 0 && handler.onOverrideMethodTypeVariableUpperBound(method, index, state);
    }
    if (symbol.owner instanceof Symbol.ClassSymbol clazz) {
      int index = clazz.getTypeParameters().indexOf(symbol);
      return index >= 0 && handler.onOverrideClassTypeVariableUpperBound(clazz.toString(), index);
    }
    return false;
  }

  /** Returns whether an instantiated upper bound permits a nullable inference result. */
  private boolean upperBoundAllowsNullable(Type upperBound) {
    if (Nullness.hasNullableAnnotation(upperBound.getAnnotationMirrors().stream(), config)) {
      return true;
    }
    return upperBound instanceof TypeVar typeVariable
        && GenericsUtils.upperBoundIsNullable(typeVariable.asElement(), config, handler, state);
  }

  /** Creates scalar state once, including for registered variables without constraints. */
  private VarState getState(InferenceVariable inferenceVar) {
    VarState existing = vars.get(inferenceVar);
    if (existing != null) {
      return existing;
    }
    VarState created =
        new VarState(
            nullableAllowedForInferenceVariable.getOrDefault(
                inferenceVar,
                GenericsUtils.upperBoundIsNullable(
                    inferenceVar.typeVariable(), config, handler, state)));
    vars.put(inferenceVar, created);
    structuredConstraintVersion++;
    return created;
  }

  /** Returns structural variable ownership independently of an occurrence's root annotation. */
  private @Nullable InferenceVariable inferenceVariableForStructure(Type type) {
    return type instanceof TypeVar variable && !(type instanceof CapturedType)
        ? inferenceVariables.get(variable.asElement())
        : null;
  }

  /**
   * Returns an inference variable only when its use has no explicit root nullness override.
   * Annotated occurrences still retain structural ownership through {@link
   * #inferenceVariableForStructure(Type)}.
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
