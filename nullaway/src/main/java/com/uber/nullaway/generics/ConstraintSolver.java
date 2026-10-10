package com.uber.nullaway.generics;

import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.lang.model.element.Element;
import org.jspecify.annotations.Nullable;

/**
 * An interface for solving constraints on type variables, such as subtype relationships between
 * types. This is used to determine the nullability of inferred type arguments at generic method
 * calls, constructor calls using the diamond operator, etc.
 */
public interface ConstraintSolver {

  /**
   * An inference variable: a type variable whose type argument is being inferred at a particular
   * site. The same declared type variable can be inferred at several sites within one inference
   * problem, e.g., for {@code chooseFirst(id(t), id(s))}, where the two calls to {@code id} need
   * separate inference variables for the type variable of {@code id}.
   *
   * @param typeVariable the declared type variable
   * @param site the tree whose type argument is being inferred: a generic method invocation, a
   *     diamond constructor call, or a method reference to a generic method
   */
  record InferenceVariable(Element typeVariable, Tree site) {}

  /**
   * Explicit source expression, the site receiving its constraint, and its original attributed
   * type. For a call argument, {@code ownerSite} is that call, including when {@code expression} is
   * a nested invocation. For a lambda return, it is the root call receiving the lambda argument
   * when available, otherwise the lambda itself. Neither ownership nor {@code originalType} is
   * rewritten when evidence propagates through another inference variable.
   */
  record SourceOrigin(Tree expression, Tree ownerSite, Type originalType) {}

  /**
   * Immutable monotone source proof, represented by a reduced ordered decision DAG rather than
   * distributed alternatives. Canonical roots give semantic equality and finite fixed points even
   * when provenance traverses cycles. Origin order is diagnostic preference, not Boolean identity.
   * Factoring avoids enumerating independent choices; arbitrary decision DAGs can still be large.
   */
  final class SourceSupport {
    private final Factory factory;
    private final int root;
    private final List<SourceOrigin> origins;

    /** Accepts legacy alternatives without distributing subsequent conjunctions. */
    public SourceSupport(List<List<SourceOrigin>> alternatives) {
      this.factory = new Factory();
      int proof = 0;
      Set<SourceOrigin> context = new LinkedHashSet<>();
      for (List<SourceOrigin> alternative : alternatives) {
        context.addAll(alternative);
        proof = factory.apply(proof, factory.conjunction(alternative).root, false);
      }
      this.root = proof;
      this.origins = List.copyOf(context);
    }

    /** Takes a snapshot of a canonical root and its independently ordered diagnostic context. */
    private SourceSupport(Factory factory, int root, List<SourceOrigin> origins) {
      this.factory = factory;
      this.root = root;
      this.origins = List.copyOf(new LinkedHashSet<>(origins));
    }

    /** Returns flattened origin context, never an enumeration of satisfying proofs. */
    public List<SourceOrigin> origins() {
      return origins;
    }

    /** Returns the exact disjunction, sharing canonical nodes in this support's factory. */
    public SourceSupport or(SourceSupport other) {
      return combine(other, false);
    }

    /** Returns the exact conjunction without a Cartesian product of proofs. */
    public SourceSupport and(SourceSupport other) {
      return combine(other, true);
    }

    /** Imports only when compatibility callers combine supports from different factories. */
    private SourceSupport combine(SourceSupport other, boolean conjunction) {
      int combined = factory.apply(root, factory.importRoot(other), conjunction);
      Set<SourceOrigin> context = new LinkedHashSet<>(origins);
      context.addAll(other.origins);
      return new SourceSupport(factory, combined, List.copyOf(context));
    }

    /**
     * Selects one wholly available witness in origin insertion order. Empty support is a present
     * empty witness; impossible support is absent. Memoization visits shared nodes only once and
     * never enumerates alternative proofs. The predicate is evaluated once per encountered origin.
     */
    public Optional<List<SourceOrigin>> availableSupport(Predicate<SourceOrigin> available) {
      Map<SourceOrigin, Integer> priority = new HashMap<>();
      for (int i = 0; i < origins.size(); i++) {
        priority.put(origins.get(i), i);
      }
      return witness(root, available, priority, new HashMap<>(), new HashMap<>());
    }

    /** Chooses the insertion-preferred satisfying branch and retains only its required premises. */
    private Optional<List<SourceOrigin>> witness(
        int node,
        Predicate<SourceOrigin> available,
        Map<SourceOrigin, Integer> priority,
        Map<Integer, Boolean> availability,
        Map<Integer, Optional<List<SourceOrigin>>> memo) {
      if (node == 0) {
        return Optional.empty();
      }
      if (node == 1) {
        return Optional.of(List.of());
      }
      Optional<List<SourceOrigin>> previous = memo.get(node);
      if (previous != null) {
        return previous;
      }
      Factory.Decision decision = factory.decision(node);
      SourceOrigin origin = factory.variables.get(decision.variable());
      Optional<List<SourceOrigin>> low =
          witness(decision.low(), available, priority, availability, memo);
      Optional<List<SourceOrigin>> result = low;
      if (availability.computeIfAbsent(decision.variable(), unused -> available.test(origin))) {
        Optional<List<SourceOrigin>> high =
            witness(decision.high(), available, priority, availability, memo);
        if (high.isPresent()) {
          List<SourceOrigin> premises = new ArrayList<>(high.get());
          premises.add(origin);
          premises.sort(
              java.util.Comparator.comparingInt(
                  source -> Objects.requireNonNull(priority.get(source))));
          List<SourceOrigin> candidate = List.copyOf(premises);
          if (low.isEmpty() || prefer(candidate, low.get(), priority)) {
            result = Optional.of(candidate);
          }
        }
      }
      memo.put(node, result);
      return result;
    }

    /** Compares witnesses lexicographically by insertion priority, preferring a shorter prefix. */
    private static boolean prefer(
        List<SourceOrigin> first, List<SourceOrigin> second, Map<SourceOrigin, Integer> priority) {
      for (int i = 0; i < Math.min(first.size(), second.size()); i++) {
        int comparison =
            Integer.compare(
                Objects.requireNonNull(priority.get(first.get(i))),
                Objects.requireNonNull(priority.get(second.get(i))));
        if (comparison != 0) {
          return comparison < 0;
        }
      }
      return first.size() < second.size();
    }

    /** Counts reachable nonterminal nodes for solver proof tests, without expanding witnesses. */
    int nodeCount() {
      Set<Integer> visited = new LinkedHashSet<>();
      ArrayDeque<Integer> work = new ArrayDeque<>();
      work.add(root);
      while (!work.isEmpty()) {
        int node = work.removeFirst();
        if (node > 1 && visited.add(node)) {
          Factory.Decision decision = factory.decision(node);
          work.add(decision.low());
          work.add(decision.high());
        }
      }
      return visited.size();
    }

    /** Compares Boolean meaning, not construction history or diagnostic preference. */
    @Override
    public boolean equals(@Nullable Object other) {
      return other instanceof SourceSupport support && root == factory.importRoot(support);
    }

    /** Factory-local node IDs cannot provide a hash stable across compatibility factories. */
    @Override
    public int hashCode() {
      return 0;
    }

    /**
     * Solver-scoped interning and apply cache for monotone source proofs only. Variable order is
     * first source insertion; terminals 0/1 contain no compiler objects. Nothing is interned in a
     * global table, and adding nodes never changes an existing support's root or witness order.
     */
    static final class Factory {
      private final List<SourceOrigin> variables = new ArrayList<>();
      private final Map<SourceOrigin, Integer> variableIndices = new LinkedHashMap<>();
      private final List<Decision> nodes = new ArrayList<>();
      private final Map<Decision, Integer> unique = new HashMap<>();
      private final Map<Combination, Integer> applications = new HashMap<>();

      private record Decision(int variable, int low, int high) {}

      private record Combination(int first, int second, boolean conjunction) {}

      /** Builds one conjunctive premise in this factory, including the source-less identity. */
      SourceSupport conjunction(List<SourceOrigin> origins) {
        int root = 1;
        for (SourceOrigin origin : origins) {
          root = apply(root, atom(origin), true);
        }
        return new SourceSupport(this, root, origins);
      }

      /** Counts all allocated decision nodes, including shared intermediate roots, for tests. */
      int allocatedNodeCount() {
        return nodes.size();
      }

      /** Interns an origin in stable insertion order and returns its single-variable decision. */
      private int atom(SourceOrigin origin) {
        Integer variable = variableIndices.get(origin);
        if (variable == null) {
          variable = variables.size();
          variableIndices.put(origin, variable);
          variables.add(origin);
        }
        return node(variable, 0, 1);
      }

      /** Eliminates irrelevant tests and interns every remaining ordered decision. */
      private int node(int variable, int low, int high) {
        if (low == high) {
          return low;
        }
        Decision key = new Decision(variable, low, high);
        Integer existing = unique.get(key);
        if (existing != null) {
          return existing;
        }
        int id = nodes.size() + 2;
        nodes.add(key);
        unique.put(key, id);
        return id;
      }

      /** Reads an immutable nonterminal; terminal IDs are handled before this call. */
      private Decision decision(int id) {
        return nodes.get(id - 2);
      }

      /** Applies AND/OR by ordered Shannon decomposition, memoizing shared subproblems. */
      private int apply(int first, int second, boolean conjunction) {
        if (first > second) {
          return apply(second, first, conjunction);
        }
        if (first == second || (conjunction && first == 0) || (!conjunction && first == 1)) {
          return first;
        }
        if (conjunction && first == 1) {
          return second;
        }
        if (!conjunction && first == 0) {
          return second;
        }
        Combination key = new Combination(first, second, conjunction);
        Integer previous = applications.get(key);
        if (previous != null) {
          return previous;
        }
        Decision a = decision(first);
        Decision b = decision(second);
        int variable = Math.min(a.variable(), b.variable());
        int low =
            apply(
                a.variable() == variable ? a.low() : first,
                b.variable() == variable ? b.low() : second,
                conjunction);
        int high =
            apply(
                a.variable() == variable ? a.high() : first,
                b.variable() == variable ? b.high() : second,
                conjunction);
        int result = node(variable, low, high);
        applications.put(key, result);
        return result;
      }

      /** Reuses solver-local roots; compatibility imports preserve semantics under this order. */
      @SuppressWarnings("ReferenceEquality") // identity defines an interning scope
      private int importRoot(SourceSupport support) {
        return support.factory == this
            ? support.root
            : importNode(support.factory, support.root, new HashMap<>());
      }

      /** Transfers a monotone decision as low OR (origin AND high), without negation or DNF. */
      private int importNode(Factory source, int root, Map<Integer, Integer> memo) {
        if (root <= 1) {
          return root;
        }
        Integer previous = memo.get(root);
        if (previous != null) {
          return previous;
        }
        Decision decision = source.decision(root);
        int origin = atom(source.variables.get(decision.variable()));
        int low = importNode(source, decision.low(), memo);
        int high = importNode(source, decision.high(), memo);
        int result = apply(low, apply(origin, high, true), false);
        memo.put(root, result);
        return result;
      }
    }
  }

  /**
   * A solver bound with alternative supporting proofs and flattened diagnostic context. Flattened
   * origins retain insertion order and transitive context; they are not conjunctive requirements.
   */
  record BoundEvidence(Type type, List<SourceOrigin> origins, SourceSupport support) {
    /** Treats the supplied origins as one conjunctive proof, including source-less evidence. */
    public BoundEvidence(Type type, List<SourceOrigin> origins) {
      this(type, origins, new SourceSupport(List.of(origins)));
    }

    /** Deduplicates diagnostic context without changing the supporting alternatives. */
    public BoundEvidence {
      Set<SourceOrigin> context = new LinkedHashSet<>(origins);
      context.addAll(support.origins());
      origins = List.copyOf(context);
    }

    /** Selects a wholly available supporting derivation, not arbitrary diagnostic context. */
    public Optional<List<SourceOrigin>> availableSupport(Predicate<SourceOrigin> available) {
      return support.availableSupport(available);
    }
  }

  /**
   * A proven invariant disagreement at javac's shape, retaining both original solver bounds. A
   * frontend must select an available support for each bound rather than vetoing all evidence on an
   * unavailable flattened context origin. When reporting at {@code variable.site()}, prefer origins
   * in that chosen support with that exact {@code ownerSite}, rather than substituting a transitive
   * inner-leaf expression for a direct argument. This choice requires only explicit owner identity,
   * not AST recovery or type-to-source matching.
   */
  record LowerBoundConflict(
      InferenceVariable variable, BoundEvidence first, BoundEvidence second, Type shape) {}

  /**
   * External unresolved inputs affecting variable values or raw/fixed site inputs. Site provenance
   * does not imply that unrelated variables at that site depend on those inputs.
   */
  record InputDependencies(Set<InferenceVariable> variables, Set<Tree> sites) {
    /** Takes immutable, insertion-ordered snapshots of both dependency sets. */
    public InputDependencies {
      variables = Collections.unmodifiableSet(new LinkedHashSet<>(variables));
      sites = Collections.unmodifiableSet(new LinkedHashSet<>(sites));
    }

    /** Returns whether neither variable values nor raw/fixed inputs depend on foreign inference. */
    public boolean isIndependentForSite(Tree site) {
      return !sites.contains(site)
          && variables.stream().noneMatch(variable -> variable.site().equals(site));
    }
  }

  /**
   * Inferred annotation sources and their certification status. Sources for incomplete or
   * inconsistent variables are retained for ordinary compatibility diagnostics, not for caching as
   * successful inference. All collections are immutable snapshots in insertion order.
   */
  record Solution(
      Map<InferenceVariable, Type> inferredTypes,
      Set<InferenceVariable> incompleteVariables,
      Set<InferenceVariable> inconsistentVariables,
      List<LowerBoundConflict> lowerConflicts,
      InputDependencies inputDependencies) {
    /** Preserves callers that do not supply external-input dependencies. */
    public Solution(
        Map<InferenceVariable, Type> inferredTypes,
        Set<InferenceVariable> incompleteVariables,
        Set<InferenceVariable> inconsistentVariables,
        List<LowerBoundConflict> lowerConflicts) {
      this(
          inferredTypes,
          incompleteVariables,
          inconsistentVariables,
          lowerConflicts,
          new InputDependencies(Set.of(), Set.of()));
    }

    /** Preserves callers that do not supply lower-bound conflict facts. */
    public Solution(
        Map<InferenceVariable, Type> inferredTypes,
        Set<InferenceVariable> incompleteVariables,
        Set<InferenceVariable> inconsistentVariables) {
      this(inferredTypes, incompleteVariables, inconsistentVariables, List.of());
    }

    /** Takes immutable, insertion-ordered snapshots of the supplied inference result. */
    public Solution {
      inferredTypes = Collections.unmodifiableMap(new LinkedHashMap<>(inferredTypes));
      incompleteVariables = Collections.unmodifiableSet(new LinkedHashSet<>(incompleteVariables));
      Set<InferenceVariable> inconsistent = new LinkedHashSet<>(inconsistentVariables);
      lowerConflicts = List.copyOf(lowerConflicts);
      lowerConflicts.forEach(conflict -> inconsistent.add(conflict.variable()));
      inconsistentVariables = Collections.unmodifiableSet(inconsistent);
    }

    /** Returns whether every registered variable has a certified, consistent inferred type. */
    public boolean isComplete() {
      return incompleteVariables.isEmpty() && inconsistentVariables.isEmpty();
    }

    /** Returns whether every variable registered at {@code site} is complete and consistent. */
    public boolean isCompleteForSite(Tree site) {
      return incompleteVariables.stream().noneMatch(variable -> variable.site().equals(site))
          && inconsistentVariables.stream().noneMatch(variable -> variable.site().equals(site));
    }
  }

  /**
   * Registers the type variables whose nullability is inferred at {@code site}. Must be called
   * before adding constraints involving these variables; type variables not registered via this
   * method are treated as fixed types.
   *
   * <p>Returns a fresh type variable for each declared type variable. Callers must substitute these
   * fresh type variables for the declared ones in all types used to generate constraints for {@code
   * site}, so that each occurrence of a type variable in a constraint identifies the site it
   * belongs to. The fresh type variables have the same name, owner, and upper bound nullability as
   * the declared type variables. Calling this method again for the same site returns the same fresh
   * type variables.
   *
   * @param site the tree whose type arguments are inferred
   * @param typeVariables the declared type variables inferred at {@code site}
   * @param instantiatedUpperBounds upper bounds after receiver/class substitutions, keyed by the
   *     corresponding declared type variable; an explicit nullable library-model override takes
   *     precedence over the substituted bound's top-level nullness
   * @param variablesWithNullnessMarkedBounds variables whose declaration upper-bound annotations
   *     are an authoritative nullness contract
   * @return a map from each declared type variable to its fresh type variable for {@code site}
   */
  default Map<Element, Type.TypeVar> registerInferenceVariables(
      Tree site,
      List<? extends Element> typeVariables,
      Map<? extends Element, ? extends Type> instantiatedUpperBounds,
      Set<? extends Element> variablesWithNullnessMarkedBounds) {
    return registerInferenceVariables(
        site, typeVariables, instantiatedUpperBounds, variablesWithNullnessMarkedBounds, Map.of());
  }

  /**
   * Registers inference variables with javac's authoritative Java instantiations. Nullness evidence
   * is projected onto these shapes; the solver never replaces their Java structure. Missing shapes
   * yield incomplete results, even if an annotation source is available.
   *
   * @param site the inference site
   * @param typeVariables the declared variables inferred at the site
   * @param instantiatedUpperBounds receiver/class-substituted declaration upper bounds
   * @param variablesWithNullnessMarkedBounds declarations with authoritative nullness bounds
   * @param javacInstantiations attributed Java type arguments, keyed by declared variable element
   * @return the fresh variables, as in the four-argument overload
   */
  Map<Element, Type.TypeVar> registerInferenceVariables(
      Tree site,
      List<? extends Element> typeVariables,
      Map<? extends Element, ? extends Type> instantiatedUpperBounds,
      Set<? extends Element> variablesWithNullnessMarkedBounds,
      Map<? extends Element, ? extends Type> javacInstantiations);

  /** Returns an immutable snapshot of the fresh symbols currently owned by this solver. */
  Set<Element> inferenceVariableSymbols();

  /**
   * Registers explicitly foreign unresolved inference symbols before consuming their types. Stable
   * caller type parameters must not be registered. Owned fresh symbols are never external.
   */
  void registerExternalInferenceSymbols(Set<? extends Element> symbols);

  /** Returns whether the type, including nested positions and bounds, consumes foreign symbols. */
  boolean hasExternalInputDependency(Type type);

  /**
   * Records a raw signature/result input consuming foreign symbols at a site, without seeding
   * unrelated variable values merely because they share that site.
   */
  void recordInferenceSiteInput(Tree site, Type type);

  /**
   * Returns a live immutable dependency snapshot, also usable after constraint generation throws.
   * This status is independent of completeness and consistency.
   */
  InputDependencies inputDependencies();

  /**
   * Exception thrown when the constraints added to the solver are determined to be unsatisfiable.
   *
   * <p>This is an unchecked exception since in our current solver implementation it needs to be
   * thrown from an implementation of javac's TypeVisitor interface, which does not allow checked
   * exceptions.
   */
  class UnsatisfiableConstraintsException extends RuntimeException {
    /** Declared type variable on which the contradiction was detected */
    private final Element typeVariable;

    /** Whether a {@code @Nullable} constraint conflicts with the type variable's upper bound. */
    private final boolean causedByNonNullUpperBound;

    /** Site owning the conflicting inference variable, when available. */
    private final @Nullable Tree inferenceSite;

    public UnsatisfiableConstraintsException(Element typeVariable) {
      this(typeVariable, false);
    }

    public UnsatisfiableConstraintsException(
        Element typeVariable, boolean causedByNonNullUpperBound) {
      this(typeVariable, causedByNonNullUpperBound, null);
    }

    /** Records the declaration, root-bound cause, and owning inference site of a contradiction. */
    public UnsatisfiableConstraintsException(
        Element typeVariable, boolean causedByNonNullUpperBound, @Nullable Tree inferenceSite) {
      this.typeVariable = typeVariable;
      this.causedByNonNullUpperBound = causedByNonNullUpperBound;
      this.inferenceSite = inferenceSite;
    }

    public @Nullable Tree getInferenceSite() {
      return inferenceSite;
    }

    public Element getTypeVariable() {
      return typeVariable;
    }

    public boolean isCausedByNonNullUpperBound() {
      return causedByNonNullUpperBound;
    }
  }

  /**
   * A fixed-variable bound cannot satisfy a non-null wildcard inference requirement. Unlike a
   * contextual scalar contradiction, this deferred proof belongs to the wildcard's own call site.
   */
  class NonNullWildcardBoundViolationException extends UnsatisfiableConstraintsException {
    /** Records the variable whose non-null bound conflicts with the actual's fixed bound. */
    public NonNullWildcardBoundViolationException(InferenceVariable variable) {
      super(variable.typeVariable(), true, variable.site());
    }
  }

  /**
   * Indicates a proven nested-nullness violation of a declaration upper bound for which no safe
   * annotation source can preserve ordinary compatibility diagnostics. This includes cyclic
   * inference, non-generic subclasses of annotated generic bounds, and incompatible nominal shapes
   * nested inside arrays. The caller reports the violation; the solver does not emit diagnostics.
   */
  class NestedUpperBoundViolationException extends UnsatisfiableConstraintsException {
    private final Tree site;
    private final Type lowerBound;
    private final Type upperBound;

    /** Records the inference site and the lower/declaration-bound pair proving the violation. */
    public NestedUpperBoundViolationException(
        Element typeVariable, Tree site, Type lowerBound, Type upperBound) {
      super(typeVariable);
      this.site = site;
      this.lowerBound = lowerBound;
      this.upperBound = upperBound;
    }

    public Tree getSite() {
      return site;
    }

    public Type getLowerBound() {
      return lowerBound;
    }

    public Type getUpperBound() {
      return upperBound;
    }
  }

  /**
   * Add a subtype constraint between two types. Also constrains nested types appropriately (e.g.,
   * generic type parameters of the two types must have identical nullability). Inference variables
   * must appear in the types as the fresh type variables returned by {@link
   * #registerInferenceVariables(Tree, List, Map, Set)}.
   *
   * <p>External-input dependencies are seeded at reduced pairs and whole-variable bounds before
   * scalar updates or bound deduplication. Independent slots do not share dependencies merely
   * because they appear in the same structured relation.
   *
   * @param subtype the subtype
   * @param supertype the supertype
   * @param localVariableType whether this constraint arises from assigning to a local variable. In
   *     such a case, the top-level types are not constrained to be subtypes (as local variable
   *     types are separately inferred), but the nested types are still constrained.
   * @throws UnsatisfiableConstraintsException if the constraints are determined to be unsatisfiable
   */
  void addSubtypeConstraint(Type subtype, Type supertype, boolean localVariableType)
      throws UnsatisfiableConstraintsException;

  /**
   * Adds a constraint with an explicit source owner. Origins survive bound deduplication,
   * substitution, projection, and derived constraints; no type-to-source lookup is performed.
   */
  void addSubtypeConstraint(
      Type subtype, Type supertype, boolean localVariableType, SourceOrigin origin)
      throws UnsatisfiableConstraintsException;

  /**
   * Solves constraints for every registered variable, including unconstrained variables, returning
   * nullness-annotated types together with explicit completion and consistency status.
   *
   * <p>Concrete inferred types carry a synthetic {@code @Nullable} or {@code @NonNull} root
   * annotation (see {@link GenericsChecks#getSyntheticNullableAnnotType}) giving their inferred
   * nullability. An unconstrained symbolic caller type variable retains its original root instead
   * of receiving an inferred default. When the constraints determine the structure of the type
   * argument, e.g., {@code R = Box<@Nullable String>} for a constraint {@code Box<Box<@Nullable
   * String>> <: Box<R>}, the inferred type is that type, including its nested nullability
   * annotations. Covariant array components may be merged, while invariant generic arguments must
   * remain consistent. Every known lower bound is validated against declaration upper bounds
   * independently of candidate selection, including when cycles or unknown structure require
   * fallback. Unknown positions do not suppress a violation proven elsewhere. Wildcard containment
   * follows the wildcard-generics feature gate. When lower-bound evidence conflicts with a
   * contextual constraint, the lower-bound type can be returned as annotation evidence so the
   * existing ordinary argument or method-reference check reports the incompatibility at its
   * established source location. If no safe annotation source can be established, scalar evidence
   * is retained but is not certified as complete. Known javac instantiations supply the Java shape
   * for annotation projection. Every relevant substituted structured bound is checked: unknown
   * relationships make the result incomplete, and violations make it inconsistent. Fixed caller
   * type variables remain symbolic. Diagnostic sources in either status must not be cached as
   * successful inference.
   *
   * <p>Invariant lower disagreements are returned as {@link Solution#lowerConflicts()} rather than
   * thrown. Their owning and dependent variables are inconsistent; independent variables continue
   * to be solved and certified. Reporting policy, including ordinary same-nominal diagnostics,
   * belongs to the caller.
   *
   * @return inferred types, incomplete/inconsistent variable sets, and lower conflict facts
   * @throws NestedUpperBoundViolationException if a declaration-bound violation is proven but no
   *     recursively shape-compatible annotation source can preserve ordinary diagnostics
   * @throws UnsatisfiableConstraintsException if the constraints are determined to be unsatisfiable
   */
  Solution solve() throws UnsatisfiableConstraintsException;
}
