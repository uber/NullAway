package com.uber.nullaway.generics;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.VisitorState;
import com.google.errorprone.suppliers.Supplier;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.uber.nullaway.Config;
import com.uber.nullaway.LibraryModels.PolyNullLocation;
import com.uber.nullaway.LibraryModels.PolyNullLocation.Parameter;
import com.uber.nullaway.LibraryModels.PolyNullLocation.Position;
import com.uber.nullaway.LibraryModels.PolyNullLocation.Return;
import com.uber.nullaway.NullabilityUtil;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.handlers.Handler;
import com.uber.nullaway.librarymodel.NestedTypePathUpdater;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.type.NullType;
import org.jspecify.annotations.Nullable;

/** Constructs and applies polymorphic-nullness constraints for modeled method locations. */
final class PolyNullInference {

  private static final Supplier<Name> POLY_NULL_VARIABLE_NAME =
      VisitorState.memoize(state -> state.getName("$PolyNull$0"));

  /** Formats a diagnostic for incompatible linked-nullness constraints on a modeled method. */
  static String inferenceFailureMessage(Symbol.MethodSymbol methodSymbol) {
    return String.format(
        "inference failure: linked nullness for call to %s constrained to be both @NonNull and"
            + " @Nullable",
        methodSymbol.getSimpleName());
  }

  /** The modeled method-type overlay and shared PolyNull variable for one call. */
  record PolyNullInferenceContext(
      Type.MethodType inferenceMethodType,
      ImmutableList<PolyNullLocation> locations,
      Type.TypeVar inferenceVariable) {

    /** Returns whether at least one modeled parameter location applies to the call. */
    boolean hasInputLocations() {
      return locations.stream().anyMatch(location -> location.position() instanceof Parameter);
    }

    /** Returns whether the overlay contains at least one modeled return location. */
    boolean hasReturnLocations() {
      return locations.stream().anyMatch(location -> location.position() instanceof Return);
    }
  }

  private PolyNullInference() {}

  /** Applies a resolved PolyNull annotation to every modeled parameter and return location. */
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"}) // deliberate reference equality checks
  static Type.MethodType applyToMethodType(
      Type.MethodType methodType, ImmutableSet<PolyNullLocation> locations, Type annotationType) {
    boolean changed = false;
    ListBuffer<Type> updatedParameterTypes = new ListBuffer<>();
    int parameterIndex = 0;
    for (com.sun.tools.javac.util.List<Type> remaining = methodType.argtypes;
        remaining.nonEmpty();
        remaining = remaining.tail, parameterIndex++) {
      Type parameterType = remaining.head;
      Type updatedParameterType =
          applyToType(parameterType, new Parameter(parameterIndex), locations, annotationType);
      updatedParameterTypes.append(updatedParameterType);
      changed |= updatedParameterType != parameterType;
    }
    Type returnType = methodType.restype;
    Type updatedReturnType = applyToReturnType(returnType, locations, annotationType);
    changed |= updatedReturnType != returnType;
    return changed
        ? new Type.MethodType(
            updatedParameterTypes.toList(), updatedReturnType, methodType.thrown, methodType.tsym)
        : methodType;
  }

  /** Applies a resolved PolyNull annotation to every modeled location within a return type. */
  static Type applyToReturnType(
      Type returnType, ImmutableSet<PolyNullLocation> locations, Type annotationType) {
    return applyToType(returnType, new Return(), locations, annotationType);
  }

  /** Resolves PolyNull nullness by invocation after a shared generic-inference solver run. */
  static IdentityHashMap<MethodInvocationTree, Nullness> resolveNullnessByInvocation(
      IdentityHashMap<MethodInvocationTree, PolyNullInferenceContext> contexts,
      ConstraintSolver.Solution solution,
      Config config) {
    IdentityHashMap<MethodInvocationTree, Nullness> nullnessByInvocation = new IdentityHashMap<>();
    for (Map.Entry<MethodInvocationTree, PolyNullInferenceContext> entry : contexts.entrySet()) {
      Nullness resolved = resolveContext(entry.getKey(), entry.getValue(), solution, config);
      if (resolved != null) {
        nullnessByInvocation.put(entry.getKey(), resolved);
      }
    }
    return nullnessByInvocation;
  }

  /** Resolves the shared PolyNull variable for one invocation. */
  static @Nullable Nullness resolveContext(
      MethodInvocationTree invocationTree,
      PolyNullInferenceContext inferenceContext,
      ConstraintSolver.Solution solution,
      Config config) {
    ConstraintSolver.InferenceVariable variable =
        new ConstraintSolver.InferenceVariable(
            inferenceContext.inferenceVariable().asElement(), invocationTree);
    Type inferred = solution.inferredTypes().get(variable);
    if (inferred == null
        || solution.incompleteVariables().contains(variable)
        || solution.inconsistentVariables().contains(variable)) {
      return null;
    }
    return TypeSubstitutionUtils.hasNullableRoot(inferred, config)
        ? Nullness.NULLABLE
        : Nullness.NONNULL;
  }

  /**
   * Creates a method-type overlay with one shared variable at applicable modeled PolyNull
   * locations.
   *
   * <p>Locations whose parameter index or type path does not exist in the method type are skipped.
   * The returned context's {@code locations()} contains only locations that were replaced, and a
   * top-level location on the varargs array of a call that passes its elements individually. That
   * array is created by javac and is non-null, so the location constrains nothing, and the overlay
   * keeps the array type for the elements to be checked against its component type.
   */
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"}) // deliberate reference equality checks
  static PolyNullInferenceContext createContext(
      Symbol.MethodSymbol methodSymbol,
      MethodInvocationTree invocationTree,
      Type.MethodType methodType,
      ImmutableSet<PolyNullLocation> locations,
      Type nullableAnnotationType,
      VisitorState state) {
    int individuallyPassedVarargsIndex =
        methodSymbol.isVarArgs() && NullabilityUtil.isVarArgsCall(invocationTree)
            ? methodType.argtypes.size() - 1
            : -1;
    Map<Integer, java.util.List<PolyNullLocation>> locationsByParameter = new LinkedHashMap<>();
    ImmutableList.Builder<PolyNullLocation> appliedLocations = ImmutableList.builder();
    Type.TypeVar inferenceVariable =
        createInferenceVariable(methodSymbol, nullableAnnotationType, state);
    for (PolyNullLocation location : locations) {
      if (!(location.position() instanceof Parameter parameter)) {
        continue;
      }
      int parameterIndex = parameter.index();
      locationsByParameter
          .computeIfAbsent(parameterIndex, unused -> new ArrayList<>())
          .add(location);
    }
    ListBuffer<Type> updatedParameterTypes = new ListBuffer<>();
    int parameterIndex = 0;
    for (com.sun.tools.javac.util.List<Type> remaining = methodType.argtypes;
        remaining.nonEmpty();
        remaining = remaining.tail, parameterIndex++) {
      Type updated = remaining.head;
      for (PolyNullLocation location :
          locationsByParameter.getOrDefault(parameterIndex, java.util.List.of())) {
        if (parameterIndex == individuallyPassedVarargsIndex && location.typePath().isEmpty()) {
          appliedLocations.add(location);
          continue;
        }
        Type replaced =
            NestedTypePathUpdater.replaceType(updated, location.typePath(), inferenceVariable);
        if (replaced != updated) {
          updated = replaced;
          appliedLocations.add(location);
        }
      }
      updatedParameterTypes.append(updated);
    }
    Type updatedReturnType = methodType.restype;
    for (PolyNullLocation location : locations) {
      if (location.position() instanceof Return) {
        Type replaced =
            NestedTypePathUpdater.replaceType(
                updatedReturnType, location.typePath(), inferenceVariable);
        if (replaced != updatedReturnType) {
          updatedReturnType = replaced;
          appliedLocations.add(location);
        }
      }
    }
    return new PolyNullInferenceContext(
        new Type.MethodType(
            updatedParameterTypes.toList(), updatedReturnType, methodType.thrown, methodType.tsym),
        appliedLocations.build(),
        inferenceVariable);
  }

  /**
   * Substitutes call-specific variables into the overlay while retaining the original PolyNull
   * variable as the key used to look up its solution and identify inference failures.
   */
  static PolyNullInferenceContext substituteInferenceVariables(
      PolyNullInferenceContext context,
      Map<Element, Type.TypeVar> inferenceVariables,
      VisitorState state,
      Config config) {
    return new PolyNullInferenceContext(
        (Type.MethodType)
            TypeSubstitutionUtils.substituteTypeVariables(
                context.inferenceMethodType(), inferenceVariables, state.getTypes(), config),
        context.locations(),
        context.inferenceVariable());
  }

  /** Adds a call-result subtype constraint using the invocation's shared PolyNull variable. */
  static void addResultConstraints(
      ConstraintSolver solver,
      PolyNullInferenceContext inferenceContext,
      Type targetType,
      boolean assignedToLocal) {
    if (!inferenceContext.hasReturnLocations()
        || targetType.getKind() == javax.lang.model.type.TypeKind.VOID) {
      return;
    }
    solver.addSubtypeConstraint(
        inferenceContext.inferenceMethodType().getReturnType(), targetType, assignedToLocal);
  }

  /** Returns the invocation whose PolyNull variable is {@code typeVariable}, or null if absent. */
  static @Nullable MethodInvocationTree getInvocationForInferenceVariable(
      IdentityHashMap<MethodInvocationTree, PolyNullInferenceContext> contexts,
      Element typeVariable) {
    for (Map.Entry<MethodInvocationTree, PolyNullInferenceContext> entry : contexts.entrySet()) {
      if (Objects.equals(entry.getValue().inferenceVariable().asElement(), typeVariable)) {
        return entry.getKey();
      }
    }
    return null;
  }

  /** Creates the nullable-bounded synthetic type variable for one PolyNull invocation. */
  private static Type.TypeVar createInferenceVariable(
      Symbol.MethodSymbol methodSymbol, Type nullableAnnotationType, VisitorState state) {
    Symbol.TypeVariableSymbol symbol =
        new PolyNullVariableSymbol(POLY_NULL_VARIABLE_NAME.get(state), methodSymbol);
    Type nullableObject =
        TypeSubstitutionUtils.typeWithAnnot(state.getSymtab().objectType, nullableAnnotationType);
    Type.TypeVar variable = new Type.TypeVar(symbol, nullableObject, state.getSymtab().botType);
    symbol.type = variable;
    return variable;
  }

  /** Distinguishes qualifier variables from Java type variables without relying on their name. */
  private static final class PolyNullVariableSymbol extends Symbol.TypeVariableSymbol {
    PolyNullVariableSymbol(Name name, Symbol.MethodSymbol owner) {
      super(0, name, Type.noType, owner);
    }
  }

  /**
   * Keeps PolyNull's qualifier-only edges out of the full solver's nominal structural graph. A
   * modeled String and Integer can share nullness without sharing a Java type. Ordinary constraints
   * still go through the full solver, and scalar facts are imposed on each variable's own type,
   * preserving its shape, declaration checks, provenance, and certification.
   */
  static final class Solver implements ConstraintSolver {
    private final ConstraintSolver delegate;
    private final VisitorState state;
    private final Config config;
    private final Handler handler;
    private final Map<Element, InferenceVariable> variables = new LinkedHashMap<>();
    private final Map<InferenceVariable, Type.TypeVar> freshVariables = new LinkedHashMap<>();
    private final Map<Element, Type> shapes = new LinkedHashMap<>();
    private final Set<Element> polySymbols = new LinkedHashSet<>();
    private final java.util.List<ScalarConstraint> scalarConstraints = new ArrayList<>();
    private final Map<InferenceVariable, Nullness> forced = new LinkedHashMap<>();
    private final Set<InferenceVariable> foreignInputs = new LinkedHashSet<>();
    private final Set<Tree> foreignSites = new LinkedHashSet<>();
    private final Set<InferenceVariable> opaqueVariables = new LinkedHashSet<>();

    private record ScalarConstraint(Type subtype, Type supertype, @Nullable SourceOrigin origin) {}

    Solver(ConstraintSolver delegate, VisitorState state, Config config, Handler handler) {
      this.delegate = delegate;
      this.state = state;
      this.config = config;
      this.handler = handler;
    }

    /**
     * Registers qualifier carriers alongside ordinary fresh variables and their attributed shapes.
     */
    @Override
    public Map<Element, Type.TypeVar> registerInferenceVariables(
        Tree site,
        java.util.List<? extends Element> typeVariables,
        Map<? extends Element, ? extends Type> bounds,
        Set<? extends Element> markedBounds,
        Map<? extends Element, ? extends Type> javacInstantiations) {
      Map<Element, Type> instantiations = new LinkedHashMap<>(javacInstantiations);
      for (Element variable : typeVariables) {
        if (variable instanceof PolyNullVariableSymbol) {
          // This shape is only a carrier for the solved qualifier, never a replacement for a
          // modeled Java type. No nominal bounds from modeled locations reach this variable.
          instantiations.put(variable, state.getSymtab().objectType);
        }
      }
      Map<Element, Type.TypeVar> fresh =
          delegate.registerInferenceVariables(
              site, typeVariables, bounds, markedBounds, instantiations);
      fresh.forEach(
          (declaration, type) -> {
            InferenceVariable variable = new InferenceVariable(declaration, site);
            variables.put(type.asElement(), variable);
            freshVariables.put(variable, type);
            Type shape = instantiations.get(declaration);
            if (shape != null) {
              shapes.put(type.asElement(), shape);
            }
            if (declaration instanceof PolyNullVariableSymbol) {
              polySymbols.add(type.asElement());
            }
          });
      return fresh;
    }

    @Override
    public Set<Element> inferenceVariableSymbols() {
      return delegate.inferenceVariableSymbols();
    }

    @Override
    public void registerExternalInferenceSymbols(Set<? extends Element> symbols) {
      delegate.registerExternalInferenceSymbols(symbols);
    }

    /** Includes foreign dependencies transported through qualifier-only edges. */
    @Override
    public boolean hasExternalInputDependency(Type type) {
      if (delegate.hasExternalInputDependency(type)) {
        return true;
      }
      Set<Element> dependent = new LinkedHashSet<>();
      InputDependencies dependencies = inputDependencies();
      variables.forEach(
          (symbol, variable) -> {
            if (dependencies.variables().contains(variable)) {
              dependent.add(symbol);
            }
          });
      TypeVarWithSymbolCollector collector = new TypeVarWithSymbolCollector(dependent);
      type.accept(collector, null);
      return !collector.getMatches().isEmpty();
    }

    /** Retains fixed-site foreign inputs as well as variable-level qualifier dependencies. */
    @Override
    public void recordInferenceSiteInput(Tree site, Type type) {
      delegate.recordInferenceSiteInput(site, type);
      if (hasExternalInputDependency(type)) {
        foreignSites.add(site);
      }
    }

    /** Combines full-type ownership facts with the live scalar dependency graph. */
    @Override
    public InputDependencies inputDependencies() {
      InputDependencies inputs = delegate.inputDependencies();
      Set<InferenceVariable> dependent = new LinkedHashSet<>(inputs.variables());
      dependent.addAll(foreignInputs);
      propagateStatus(dependent);
      Set<Tree> sites = new LinkedHashSet<>(inputs.sites());
      sites.addAll(foreignSites);
      return new InputDependencies(dependent, sites);
    }

    @Override
    public void addSubtypeConstraint(Type subtype, Type supertype, boolean localVariableType) {
      reduce(subtype, supertype, localVariableType, null, new IdentityHashMap<>());
    }

    @Override
    public void addSubtypeConstraint(
        Type subtype, Type supertype, boolean localVariableType, SourceOrigin origin) {
      reduce(subtype, supertype, localVariableType, origin, new IdentityHashMap<>());
    }

    /**
     * Finds a modeled qualifier occurrence without treating its bound as a nominal substitution.
     */
    private boolean containsPoly(Type type) {
      if (polySymbols.isEmpty()) {
        return false;
      }
      TypeVarWithSymbolCollector collector = new TypeVarWithSymbolCollector(polySymbols);
      type.accept(collector, null);
      return !collector.getMatches().isEmpty();
    }

    /**
     * Reduces only relations containing PolyNull; all other relations retain full solver semantics.
     */
    private void reduce(
        Type subtype,
        Type supertype,
        boolean local,
        @Nullable SourceOrigin origin,
        IdentityHashMap<Type, Set<Type>> active) {
      if (!containsPoly(subtype) && !containsPoly(supertype)) {
        if (origin == null) {
          delegate.addSubtypeConstraint(subtype, supertype, local);
        } else {
          delegate.addSubtypeConstraint(subtype, supertype, local, origin);
        }
        return;
      }
      Set<Type> targets =
          active.computeIfAbsent(
              subtype, unused -> Collections.newSetFromMap(new IdentityHashMap<>()));
      if (!targets.add(supertype)) {
        return;
      }
      try {
        if (((subtype instanceof Type.TypeVar && polySymbols.contains(subtype.asElement()))
                || (supertype instanceof Type.TypeVar
                    && polySymbols.contains(supertype.asElement())))
            && !(config.handleWildcardGenerics()
                && (supertype instanceof Type.WildcardType
                    || GenericsUtils.asWildcard(subtype) != null))) {
          if (!local) {
            scalarConstraints.add(new ScalarConstraint(subtype, supertype, origin));
            InferenceVariable lower = variableForUse(subtype);
            InferenceVariable upper = variableForUse(supertype);
            if (delegate.hasExternalInputDependency(subtype)
                || delegate.hasExternalInputDependency(supertype)) {
              if (lower != null) {
                foreignInputs.add(lower);
              }
              if (upper != null) {
                foreignInputs.add(upper);
              }
            }
            propagateScalars(null);
          }
          return;
        }
        if (config.handleWildcardGenerics() && supertype instanceof Type.WildcardType wildcard) {
          if (wildcard.kind == BoundKind.SUPER) {
            Type actual = GenericsUtils.asWildcard(subtype);
            if (actual instanceof Type.WildcardType actualWildcard) {
              if (actualWildcard.kind == BoundKind.SUPER) {
                reduce(wildcard.type, actualWildcard.type, false, origin, active);
              }
            } else {
              reduce(wildcard.type, subtype, false, origin, active);
            }
          } else {
            reduce(
                GenericsUtils.effectiveWildcardUpperBound(subtype, state, config, handler),
                GenericsUtils.wildcardUpperBound(wildcard, state, config, handler),
                false,
                origin,
                active);
          }
        } else if (config.handleWildcardGenerics() && GenericsUtils.asWildcard(subtype) != null) {
          Type.WildcardType wildcard = Objects.requireNonNull(GenericsUtils.asWildcard(subtype));
          reduce(
              wildcard.kind == BoundKind.SUPER
                  ? wildcard.type
                  : GenericsUtils.wildcardUpperBound(wildcard, state, config, handler),
              supertype,
              false,
              origin,
              active);
        } else if (subtype instanceof Type.TypeVar && shapes.containsKey(subtype.asElement())) {
          reduce(
              Objects.requireNonNull(shapes.get(subtype.asElement())),
              supertype,
              local,
              origin,
              active);
        } else if (supertype instanceof Type.TypeVar && shapes.containsKey(supertype.asElement())) {
          reduce(
              subtype,
              Objects.requireNonNull(shapes.get(supertype.asElement())),
              local,
              origin,
              active);
        } else if (subtype instanceof Type.ArrayType lower
            && supertype instanceof Type.ArrayType upper) {
          reduce(lower.elemtype, upper.elemtype, false, origin, active);
        } else if (subtype instanceof Type.ClassType && supertype instanceof Type.ClassType upper) {
          Type aligned =
              TypeSubstitutionUtils.asSuper(
                  state.getTypes(), subtype, (Symbol.ClassSymbol) upper.tsym, config);
          if (aligned == null || aligned.isRaw() || upper.isRaw()) {
            return;
          }
          java.util.List<Type> lowerArguments = aligned.getTypeArguments();
          java.util.List<Type> upperArguments = upper.getTypeArguments();
          for (int i = 0; i < lowerArguments.size() && i < upperArguments.size(); i++) {
            Type lower = lowerArguments.get(i);
            Type higher = upperArguments.get(i);
            reduce(lower, higher, false, origin, active);
            if (!config.handleWildcardGenerics()
                || (!(higher instanceof Type.WildcardType)
                    && GenericsUtils.asWildcard(lower) == null)) {
              reduce(higher, lower, false, origin, active);
            }
          }
          if (aligned.getEnclosingType() instanceof Type.ClassType enclosing
              && upper.getEnclosingType() instanceof Type.ClassType upperEnclosing
              && !enclosing.isRaw()
              && !upperEnclosing.isRaw()) {
            reduce(enclosing, upperEnclosing, false, origin, active);
          }
        } else {
          // An unavailable Java shape cannot certify the corresponding qualifier projection.
          TypeVarWithSymbolCollector collector = new TypeVarWithSymbolCollector(variables.keySet());
          subtype.accept(collector, null);
          for (Type.TypeVar variable : collector.getMatches()) {
            opaqueVariables.add(Objects.requireNonNull(variables.get(variable.asElement())));
          }
          collector = new TypeVarWithSymbolCollector(variables.keySet());
          supertype.accept(collector, null);
          for (Type.TypeVar variable : collector.getMatches()) {
            opaqueVariables.add(Objects.requireNonNull(variables.get(variable.asElement())));
          }
        }
      } finally {
        targets.remove(supertype);
      }
    }

    /** Returns the owned scalar variable unless an occurrence explicitly overrides its nullness. */
    private @Nullable InferenceVariable variableForUse(Type type) {
      return type instanceof Type.TypeVar
              && !TypeSubstitutionUtils.hasNullableRoot(type, config)
              && !TypeSubstitutionUtils.hasExplicitNonNullRoot(type, config)
          ? variables.get(type.asElement())
          : null;
    }

    /** Propagates qualifier facts without adding nominal bounds between unrelated Java shapes. */
    private void propagateScalars(@Nullable Solution solution) {
      int previous;
      do {
        previous = forced.size();
        for (ScalarConstraint constraint : scalarConstraints) {
          InferenceVariable lower = variableForUse(constraint.subtype());
          InferenceVariable upper = variableForUse(constraint.supertype());
          Type inferredLower =
              solution != null && lower != null ? solution.inferredTypes().get(lower) : null;
          boolean nullable =
              constraint.subtype() instanceof NullType
                  || TypeSubstitutionUtils.hasNullableRoot(constraint.subtype(), config)
                  || (lower != null && forced.get(lower) == Nullness.NULLABLE)
                  || (inferredLower != null
                      && TypeSubstitutionUtils.hasNullableRoot(inferredLower, config));
          boolean nonnull =
              upper != null
                  ? forced.get(upper) == Nullness.NONNULL
                  : !TypeSubstitutionUtils.hasNullableRoot(constraint.supertype(), config)
                      && (!(constraint.supertype() instanceof Type.TypeVar variable)
                          || TypeSubstitutionUtils.hasExplicitNonNullRoot(variable, config)
                          || !GenericsUtils.upperBoundIsNullable(
                              variable.asElement(), config, handler, state));
          if (nullable && upper != null) {
            force(upper, Nullness.NULLABLE, constraint.origin());
          }
          if (nonnull && lower != null) {
            force(lower, Nullness.NONNULL, constraint.origin());
          }
        }
      } while (forced.size() != previous);
    }

    /**
     * Imposes a root fact on the variable itself, never on an unrelated carrier's nominal shape.
     */
    private void force(
        InferenceVariable variable, Nullness nullness, @Nullable SourceOrigin origin) {
      Nullness previous = forced.putIfAbsent(variable, nullness);
      if (previous != null) {
        if (previous != nullness) {
          throw new UnsatisfiableConstraintsException(
              variable.typeVariable(), false, variable.site());
        }
        return;
      }
      Type fresh = Objects.requireNonNull(freshVariables.get(variable));
      Type annotated =
          TypeSubstitutionUtils.typeWithAnnot(
              fresh,
              nullness == Nullness.NULLABLE
                  ? GenericsChecks.getSyntheticNullableAnnotType(state)
                  : GenericsChecks.getSyntheticNonNullAnnotType(state));
      Type lower = nullness == Nullness.NULLABLE ? annotated : fresh;
      Type upper = nullness == Nullness.NULLABLE ? fresh : annotated;
      if (origin == null) {
        delegate.addSubtypeConstraint(lower, upper, false);
      } else {
        delegate.addSubtypeConstraint(lower, upper, false, origin);
      }
    }

    /** Carries uncertified or foreign scalar dependencies across linked qualifier occurrences. */
    private void propagateStatus(Set<InferenceVariable> status) {
      boolean changed;
      do {
        changed = false;
        for (ScalarConstraint constraint : scalarConstraints) {
          InferenceVariable lower = variableForUse(constraint.subtype());
          InferenceVariable upper = variableForUse(constraint.supertype());
          if (lower != null
              && upper != null
              && (status.contains(lower) || status.contains(upper))) {
            changed |= status.add(lower);
            changed |= status.add(upper);
          }
        }
      } while (changed);
    }

    /**
     * Reaches a shared scalar/full-type fixed point before taking a certified solution snapshot.
     */
    @Override
    public Solution solve() {
      Solution solution;
      int previous;
      do {
        previous = forced.size();
        solution = delegate.solve();
        propagateScalars(solution);
      } while (forced.size() != previous);
      Set<InferenceVariable> incomplete = new LinkedHashSet<>(solution.incompleteVariables());
      incomplete.addAll(opaqueVariables);
      Set<InferenceVariable> inconsistent = new LinkedHashSet<>(solution.inconsistentVariables());
      propagateStatus(incomplete);
      propagateStatus(inconsistent);
      return new Solution(
          solution.inferredTypes(),
          incomplete,
          inconsistent,
          solution.lowerConflicts(),
          inputDependencies());
    }
  }

  /** Applies {@code annotationType} at modeled locations within one method-type component. */
  private static Type applyToType(
      Type type, Position position, ImmutableSet<PolyNullLocation> locations, Type annotationType) {
    Type updated = type;
    for (PolyNullLocation location : locations) {
      if (location.position().equals(position)) {
        updated = NestedTypePathUpdater.addAnnotation(updated, location.typePath(), annotationType);
      }
    }
    return updated;
  }
}
