package com.uber.nullaway;

import com.sun.source.tree.ParameterizedTypeTree;
import java.util.List;
import java.util.Set;

public interface AnnotatedTypeWrapper {
  ParameterizedTypeTree tree = null;

  Set<Integer> getNullableArgumentIndices();

  List<AnnotatedTypeWrapper> getTypeArgumentWrappers();

  void checkSameTypeArgNullability(
      AnnotatedTypeWrapper lhsWrapper, AnnotatedTypeWrapper rhsWrapper);
}
