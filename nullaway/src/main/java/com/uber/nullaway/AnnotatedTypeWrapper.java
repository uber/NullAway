package com.uber.nullaway;

import java.util.List;
import java.util.Set;

public interface AnnotatedTypeWrapper {
  Set<Integer> getNullableArgumentIndices();

  List<AnnotatedTypeWrapper> getTypeArgumentWrappers();

  void checkSameTypeArgNullability(
      AnnotatedTypeWrapper lhsWrapper, AnnotatedTypeWrapper rhsWrapper);
}
