package com.uber.nullaway.generics;

import com.sun.tools.javac.code.Type;
import java.util.Map;
import javax.lang.model.type.TypeVariable;

public interface ConstraintSolver {

  class UnsatConstraintsException extends Exception {
    public UnsatConstraintsException(String message) {
      super(message);
    }
  }

  void addSubtypeConstraint(Type subtype, Type supertype) throws UnsatConstraintsException;

  /**
   * Solve the constraints, returning a map from type variables to booleans indicating whether the
   * type variable is {@code @Nullable} or not. Throws an exception if the constraints are
   * unsatisfiable.
   *
   * @return a map from type variables to booleans indicating whether the type variable is
   *     {@code @Nullable} or not. If the boolean is {@code true}, the type variable is
   *     {@code @Nullable}; if it is {@code false}, the type variable is {@code @NonNull}.
   */
  Map<TypeVariable, Boolean> solve() throws UnsatConstraintsException;
}
