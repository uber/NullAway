package com.uber.nullaway;

import com.sun.tools.javac.code.Type;
import java.util.HashSet;
import java.util.List;

public interface AnnotatedTypeWrapper {
  public Type getWrapped();

  public HashSet<Integer> getNullableTypeArgIndices();

  public List<AnnotatedTypeWrapper> getWrappersForNestedTypes();

  public boolean isParameterizedTypedWrapper();

  public boolean isGenericTypedWrapper();
}
