package com.uber.nullaway.generics;

import com.sun.tools.javac.code.Type;
import java.util.Map;
import javax.lang.model.type.TypeVariable;

public interface ConstraintSolver {

  void addSubtypeConstraint(Type subtype, Type supertype);

  Map<TypeVariable, Type> solve();
}
