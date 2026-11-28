package com.uber.nullaway.generics;

import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.Config;
import com.uber.nullaway.Nullness;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;

/**
 * An interface for solving constraints on type variables, such as subtype relationships between
 * types. This is used to determine the nullability of inferred type arguments at generic method
 * calls, constructor calls using the diamond operator, etc., including nested nullability when a
 * full substitution is available.
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
   * <p>Javac's inferred Java types supply the structure of full substitutions; nullness annotations
   * come from the collected bounds. Pass an empty map when no inferred structure is available.
   *
   * <p>Re-registering a site with the same declarations returns its existing fresh variables and
   * retains all constraints. Passing different declarations fails verification. Supplied javac
   * instantiations replace earlier entries; omitted entries leave earlier entries unchanged.
   * Separate inference problems use separate solvers.
   *
   * @param site the tree whose type arguments are inferred
   * @param typeVariables the declared type variables inferred at {@code site}
   * @param javacInstantiations javac's inferred type arguments, keyed by declared variable; any
   *     variable may be omitted and then receives at most a {@link TopLevelOnly} result unless an
   *     earlier registration supplied its instantiation. These types supply Java structure only;
   *     their nullness annotations are not used to generate inference constraints.
   * @return a map from each declared type variable to its fresh type variable for {@code site}
   */
  Map<Element, Type.TypeVar> registerInferenceVariables(
      Tree site, List<? extends Element> typeVariables, Map<Element, Type> javacInstantiations);

  /**
   * The inferred type argument for one variable at a call site. A {@link Substitution} includes
   * nested nullness, such as {@code List<@Nullable String>}; a {@link TopLevelOnly} records just
   * the top-level nullness when a full substitution is unavailable.
   */
  sealed interface InferredType permits Substitution, TopLevelOnly {
    /** Returns the established top-level qualifier for either kind of result. */
    Nullness topLevelNullness(Config config);
  }

  /**
   * An inferred replacement type, including nested nullness and containing no inference variables.
   */
  record Substitution(Type type) implements InferredType {
    @Override
    public Nullness topLevelNullness(Config config) {
      return GenericsUtils.isAnnotatedAsNullable(type, config)
          ? Nullness.NULLABLE
          : Nullness.NONNULL;
    }
  }

  /**
   * An inferred top-level nullness qualifier. Consumers retain the attributed type's nested
   * structure, including annotations restored by {@link NestedTypeVarSubstitutionRepairVisitor}.
   */
  record TopLevelOnly(Nullness nullness) implements InferredType {
    /**
     * Only NONNULL and NULLABLE are type qualifiers; other states describe dataflow values.
     *
     * @throws IllegalArgumentException if nullness is neither NONNULL nor NULLABLE
     */
    public TopLevelOnly {
      if (nullness != Nullness.NONNULL && nullness != Nullness.NULLABLE) {
        throw new IllegalArgumentException("Expected an inferred top-level nullness qualifier");
      }
    }

    @Override
    public Nullness topLevelNullness(Config config) {
      return nullness;
    }
  }

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

    public UnsatisfiableConstraintsException(Element typeVariable) {
      this(typeVariable, false);
    }

    public UnsatisfiableConstraintsException(
        Element typeVariable, boolean causedByNonNullUpperBound) {
      this.typeVariable = typeVariable;
      this.causedByNonNullUpperBound = causedByNonNullUpperBound;
    }

    public Element getTypeVariable() {
      return typeVariable;
    }

    public boolean isCausedByNonNullUpperBound() {
      return causedByNonNullUpperBound;
    }
  }

  /**
   * Add a subtype constraint between two types. Also constrains nested types appropriately (e.g.,
   * generic type parameters of the two types must have identical nullability). Inference variables
   * must appear in the types as the fresh type variables returned by {@link
   * #registerInferenceVariables(Tree, List, Map)}.
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
   * Solves nullness constraints and returns an immutable map of inferred type arguments. Each
   * result contains either a full substitution or just the top-level nullness when nested inference
   * is unsupported or its bounds disagree. Variables with neither top-level constraints nor
   * supported type bounds are omitted.
   *
   * <p>A full substitution requires javac's instantiation to be a non-raw class or array type,
   * including enclosing types, without wildcards, captures, or fixed type variables. A dependency
   * cycle or disagreement at an invariant position, such as lower bounds {@code List<String>} and
   * {@code List<@Nullable String>}, leaves only top-level nullability available. See {@link
   * FullTypeBounds} for the resolution rules.
   *
   * @return inferred type arguments keyed by call-specific inference variables
   * @throws UnsatisfiableConstraintsException if top-level constraints are unsatisfiable
   */
  Map<InferenceVariable, InferredType> solve() throws UnsatisfiableConstraintsException;
}
