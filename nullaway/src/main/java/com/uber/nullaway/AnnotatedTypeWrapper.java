package com.uber.nullaway;

import com.sun.tools.javac.code.Type;
import java.util.List;
import java.util.Set;

public interface AnnotatedTypeWrapper {
  Type getWrapped();

  Set<Integer> getNullableTypeArgIndices();

  List<AnnotatedTypeWrapper> getWrappersForNestedTypes();

  /**
   * This method should return a new AnnotatedTypeWrapper that is a view of this as some supertype.
   * Critically, the nullable type arg indices should still be correct.
   */
  AnnotatedTypeWrapper asSupertype(Type.ClassType superType);
}
