package com.uber.nullaway.generics;

import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Type;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;

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
   * @return a map from each declared type variable to its fresh type variable for {@code site}
   */
  Map<Element, Type.TypeVar> registerInferenceVariables(
      Tree site, List<? extends Element> typeVariables);

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
   * #registerInferenceVariables(Tree, List)}.
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

  enum InferredNullability {
    NONNULL,
    NULLABLE
  }

  /**
   * Solve the constraints, returning a map from inference variables to their inferred nullability.
   * The map only contains inference variables that appear in constraints.
   *
   * @return a map from inference variables to their inferred nullability
   * @throws UnsatisfiableConstraintsException if the constraints are determined to be unsatisfiable
   */
  Map<InferenceVariable, InferredNullability> solve() throws UnsatisfiableConstraintsException;
}
