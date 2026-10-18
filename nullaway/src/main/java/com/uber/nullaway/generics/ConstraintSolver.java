package com.uber.nullaway.generics;

import com.sun.tools.javac.code.Type;
import java.util.Map;
import javax.lang.model.element.Element;

/**
 * An interface for solving constraints on type variables, such as subtype relationships between
 * types. This is used to determine the nullability of inferred type arguments at generic method
 * calls, constructor calls using the diamond operator, etc.
 */
public interface ConstraintSolver {

  /**
   * Registers a type variable whose nullability is being inferred by this solver. Must be called
   * before adding constraints involving that parameter; unregistered parameters are fixed types.
   *
   * <p>The solver keys a variable by its declaration, so two calls of one generic method or class
   * in the same inference problem share one variable. Each call registers its variables, and a
   * variable registered more than once is never resolved as {@link
   * InferredNullability#TYPE_VARIABLE_OR_NULLABLE}.
   */
  void registerInferenceVariable(Element typeVariable);

  /**
   * Exception thrown when the constraints added to the solver are determined to be unsatisfiable.
   *
   * <p>This is an unchecked exception since in our current solver implementation it needs to be
   * thrown from an implementation of javac's TypeVisitor interface, which does not allow checked
   * exceptions.
   */
  class UnsatisfiableConstraintsException extends RuntimeException {
    /** Type variable on which the contradiction was detected */
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

  /** The nullness the solver found for an inference variable. */
  enum InferredNullability {
    /** A constraint fixed the variable to be non-null. */
    NONNULL,
    /** A constraint fixed the variable to be nullable. */
    NULLABLE,
    /**
     * No constraint fixed the variable, a type variable {@code T} whose bound is explicitly
     * nullable flowed into it, and the variable's own bound is explicitly nullable. Where javac
     * inferred a type variable, the variable is that type variable as written; where javac inferred
     * an intersection type, it carries no annotation; where javac inferred any other type, such as
     * the least upper bound of {@code T} and {@code String}, the variable is {@code @Nullable}, the
     * least type that holds a null {@code T}.
     *
     * <p>A variable that no constraint fixed and no such type variable reached is {@link #NONNULL},
     * the least solution.
     */
    TYPE_VARIABLE_OR_NULLABLE,
    /**
     * No constraint fixed the variable and a type variable whose bound admits null flowed into it,
     * but no type variable whose bound is explicitly nullable did, the variable's own bound is not
     * explicitly nullable, or the variable is shared by several calls. Where javac inferred a type
     * variable, the variable is that type variable as written; where javac inferred an intersection
     * type, it carries no annotation; where javac inferred any other type, the variable is
     * {@code @NonNull}, as if no type variable had reached it.
     */
    TYPE_VARIABLE_OR_NONNULL
  }

  /**
   * Solve the constraints, returning a map from type variables to their inferred nullability.
   *
   * @return a map from type variables to their inferred nullability
   * @throws UnsatisfiableConstraintsException if the constraints are determined to be unsatisfiable
   */
  Map<Element, InferredNullability> solve() throws UnsatisfiableConstraintsException;
}
