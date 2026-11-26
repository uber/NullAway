package com.uber.nullaway.generics;

import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Type;
import java.util.List;
import java.util.Map;
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
   * <p>Register each site once per inference problem. Constraint generation visits each call or
   * method-reference tree once; separate inference problems use separate solvers.
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

    /**
     * The type variable with an explicitly nullable bound that was passed for {@code ? extends}
     * {@link #typeVariable} and so constrains {@link #typeVariable} to be {@code @Nullable}; {@code
     * null} where another constraint caused the conflict.
     */
    private final @Nullable Type nullableBoundTypeVariable;

    /**
     * The type variable whose declared bound makes the bound of {@link #nullableBoundTypeVariable}
     * explicitly nullable: that type variable itself, or one its bound reaches through other type
     * variables; {@code null} exactly where {@link #nullableBoundTypeVariable} is.
     */
    private final @Nullable Element typeVariableWithNullableBound;

    public UnsatisfiableConstraintsException(Element typeVariable) {
      this(typeVariable, false);
    }

    public UnsatisfiableConstraintsException(
        Element typeVariable, boolean causedByNonNullUpperBound) {
      this(typeVariable, causedByNonNullUpperBound, null, null);
    }

    /**
     * Creates an exception for a {@code typeVariable} whose bound excludes null, where {@code
     * nullableBoundTypeVariable}, a type variable with an explicitly nullable bound, was passed for
     * {@code ? extends typeVariable}, and the declared bound of {@code
     * typeVariableWithNullableBound} makes that bound explicitly nullable.
     */
    public UnsatisfiableConstraintsException(
        Element typeVariable,
        Type nullableBoundTypeVariable,
        Element typeVariableWithNullableBound) {
      this(typeVariable, true, nullableBoundTypeVariable, typeVariableWithNullableBound);
    }

    private UnsatisfiableConstraintsException(
        Element typeVariable,
        boolean causedByNonNullUpperBound,
        @Nullable Type nullableBoundTypeVariable,
        @Nullable Element typeVariableWithNullableBound) {
      this.typeVariable = typeVariable;
      this.causedByNonNullUpperBound = causedByNonNullUpperBound;
      this.nullableBoundTypeVariable = nullableBoundTypeVariable;
      this.typeVariableWithNullableBound = typeVariableWithNullableBound;
    }

    public Element getTypeVariable() {
      return typeVariable;
    }

    public boolean isCausedByNonNullUpperBound() {
      return causedByNonNullUpperBound;
    }

    public @Nullable Type getNullableBoundTypeVariable() {
      return nullableBoundTypeVariable;
    }

    public @Nullable Element getTypeVariableWithNullableBound() {
      return typeVariableWithNullableBound;
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

  /** The nullness the solver found for an inference variable. */
  enum InferredNullability {
    /**
     * A constraint fixed the variable to be non-null, or no constraint fixed it and no unannotated
     * type variable that is not under inference and whose bound admits null flowed into it, so
     * non-null is the least solution. The solver tracks such a flow only where {@link
     * com.uber.nullaway.Config#isNullableBoundInferenceEnabled} holds; otherwise every variable
     * that no constraint fixed is {@code NONNULL}.
     */
    NONNULL,
    /** A constraint fixed the variable to be nullable. */
    NULLABLE,
    /**
     * No constraint fixed the variable, a type variable {@code T} whose bound is explicitly
     * nullable flowed into it, and the variable's own bound is explicitly nullable, as {@link
     * GenericsUtils#typeVariableWithNullableBound} decides it. Where javac inferred a type with
     * parametric nullness ({@link GenericsUtils#hasParametricNullness}), a capture included, the
     * variable is that type with no synthetic annotation; where javac inferred an intersection
     * type, it carries no annotation; where javac inferred any other type, such as the least upper
     * bound of {@code T} and {@code String}, or a type variable {@code R} whose bound excludes null
     * for {@code T extends @Nullable R}, the variable is {@code @Nullable}, the least type that
     * holds a null {@code T}.
     */
    TYPE_VARIABLE_OR_NULLABLE,
    /**
     * No constraint fixed the variable and an unannotated type variable that is not under inference
     * and whose bound admits null flowed into it, but {@link #TYPE_VARIABLE_OR_NULLABLE} does not
     * apply: no such type variable has an explicitly nullable bound, or the variable's own bound is
     * not explicitly nullable. Where javac inferred a type with parametric nullness ({@link
     * GenericsUtils#hasParametricNullness}), a capture included, the variable is that type with no
     * synthetic annotation; where javac inferred an intersection type, it carries no annotation;
     * where javac inferred any other type, the variable is {@code @NonNull}, as if no type variable
     * had flowed into it.
     */
    TYPE_VARIABLE_OR_NONNULL;
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
