package com.uber.nullaway.generics;

import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.Nullness;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  /** Annotated candidates and independent certification/ownership status. */
  record Solution(
      Map<InferenceVariable, Type> inferredTypes,
      Set<InferenceVariable> incompleteVariables,
      Set<InferenceVariable> inconsistentVariables,
      InputDependencies inputDependencies) {
    /** Creates a result without external unresolved inputs. */
    public Solution(
        Map<InferenceVariable, Type> types,
        Set<InferenceVariable> incomplete,
        Set<InferenceVariable> inconsistent) {
      this(types, incomplete, inconsistent, new InputDependencies(Set.of(), Set.of()));
    }

    /** Freezes candidate and status collections before any frontend publication. */
    public Solution {
      inferredTypes = Collections.unmodifiableMap(new LinkedHashMap<>(inferredTypes));
      incompleteVariables = Collections.unmodifiableSet(new LinkedHashSet<>(incompleteVariables));
      inconsistentVariables =
          Collections.unmodifiableSet(new LinkedHashSet<>(inconsistentVariables));
    }

    /** Returns whether every registered variable has a certified, consistent candidate. */
    public boolean isComplete() {
      return incompleteVariables.isEmpty() && inconsistentVariables.isEmpty();
    }

    /** Returns whether this site's variables are complete and consistent. */
    public boolean isCompleteForSite(Tree site) {
      return incompleteVariables.stream().noneMatch(variable -> variable.site().equals(site))
          && inconsistentVariables.stream().noneMatch(variable -> variable.site().equals(site));
    }
  }

  /** Registers variables with authoritative declaration bounds and no attributed shapes. */
  default Map<Element, Type.TypeVar> registerInferenceVariables(
      Tree site, List<? extends Element> variables) {
    return registerInferenceVariables(site, variables, Map.of(), Set.copyOf(variables), Map.of());
  }

  /** Registers attributed shapes using the variables' declaration bounds. */
  default Map<Element, Type.TypeVar> registerInferenceVariables(
      Tree site, List<? extends Element> variables, Map<Element, Type> shapes) {
    return registerInferenceVariables(site, variables, Map.of(), Set.copyOf(variables), shapes);
  }

  /**
   * Registers the type variables whose nullability is inferred at {@code site}. Must be called
   * before adding constraints involving these variables; type variables not registered via this
   * method are treated as fixed types.
   *
   * <p>Returns a fresh type variable for each declared type variable. Callers must substitute these
   * fresh type variables for the declared ones in all types used to generate constraints for {@code
   * site}, so that each occurrence of a type variable in a constraint identifies the site it
   * belongs to. The fresh type variables have the same name and owner as the declared type
   * variables, with references in their upper bounds rebound to the fresh variables. Their symbols
   * retain the original declaration for library-modeled bound nullability, even when a separate
   * inference problem treats the fresh variables as fixed types.
   *
   * <p>Registering the same site again returns the same fresh type variables, preserving their
   * identity in existing constraints. Later registrations retain additional bound and javac
   * instantiation inputs; separate inference problems use separate solvers.
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

  /** Returns an established scalar fact, excluding candidate defaults and provisional shapes. */
  @Nullable Nullness knownNullness(InferenceVariable variable);

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
      super(typeVariable, false, site);
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

  /** Adds a subtype relation, excluding the root nullness of a local-variable target. */
  void addSubtypeConstraint(Type subtype, Type supertype, boolean localVariableType);

  /**
   * Incorporates structural bounds, propagates nullness, and independently certifies candidates.
   */
  Solution solve() throws UnsatisfiableConstraintsException;
}
