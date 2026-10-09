package com.uber.nullaway.generics;

import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Type;
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
   * Inferred annotation sources and their certification status. Sources for incomplete or
   * inconsistent variables are retained for ordinary compatibility diagnostics, not for caching as
   * successful inference. All collections are immutable snapshots in insertion order.
   */
  record Solution(
      Map<InferenceVariable, Type> inferredTypes,
      Set<InferenceVariable> incompleteVariables,
      Set<InferenceVariable> inconsistentVariables) {
    /** Takes immutable, insertion-ordered snapshots of the supplied inference result. */
    public Solution {
      inferredTypes = Collections.unmodifiableMap(new LinkedHashMap<>(inferredTypes));
      incompleteVariables = Collections.unmodifiableSet(new LinkedHashSet<>(incompleteVariables));
      inconsistentVariables =
          Collections.unmodifiableSet(new LinkedHashSet<>(inconsistentVariables));
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
   * @return inferred types and explicit incomplete/inconsistent variable sets
   * @throws NestedUpperBoundViolationException if a declaration-bound violation is proven but no
   *     recursively shape-compatible annotation source can preserve ordinary diagnostics
   * @throws UnsatisfiableConstraintsException if the constraints are determined to be unsatisfiable
   */
  Solution solve() throws UnsatisfiableConstraintsException;
}
