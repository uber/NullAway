package com.uber.nullaway;

import com.sun.source.tree.Tree;
import java.util.HashSet;

public interface AnnotatedTypeWrapper<T1, T2> {

  public HashSet<Integer> getNullableTypeArgIndices(T2 wrapper);

  public void checkAssignmentTypeMatch(Tree tree, T1 lhs, T2 rhs);
}
