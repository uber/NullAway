package com.uber.nullaway.generics;

import com.sun.tools.javac.code.Type;
import java.util.Map;
import javax.lang.model.type.TypeVariable;

/**
 * An interface for solving constraints on type variables, such as subtype relationships between
 * types. This is used to determine the nullability of inferred type arguments at generic method
 * calls, constructor calls using the diamond operator, etc.
 */
public interface ConstraintSolver {

  /**
   * Exception thrown when the constraints added to the solver are determined to be unsatisfiable.
   */
  class UnsatisfiableConstraintsException extends RuntimeException {
    public UnsatisfiableConstraintsException(String message) {
      super(message);
    }
  }

  /**
   * Add a subtype constraint between two types. Also constrains nested types appropriately (e.g.,
   * generic type parameters of the two types must have identical nullability).
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
   * Solve the constraints, returning a map from type variables to their inferred nullability.
   *
   * @return a map from type variables to their inferred nullability
   * @throws UnsatisfiableConstraintsException if the constraints are determined to be unsatisfiable
   */
  Map<TypeVariable, InferredNullability> solve() throws UnsatisfiableConstraintsException;
}
