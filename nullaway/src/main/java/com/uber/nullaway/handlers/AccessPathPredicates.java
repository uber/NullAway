package com.uber.nullaway.handlers;

import com.google.errorprone.VisitorState;
import com.sun.source.util.TreePath;
import com.uber.nullaway.dataflow.AccessPath;
import java.util.function.Predicate;

/**
 * {@link java.util.function.Predicate}s over {@link com.uber.nullaway.dataflow.AccessPath}s useful
 * in defining handlers.
 */
public class AccessPathPredicates {

  /**
   * An AccessPath predicate that always returns false. Used to optimize {@link
   * CompositeHandler#getAccessPathPredicateForNestedMethod(TreePath, VisitorState)}
   */
  static final Predicate<AccessPath> FALSE_AP_PREDICATE = ap -> false;

  /**
   * An AccessPath predicate that always returns true. Used to optimize {@link
   * CompositeHandler#getAccessPathPredicateForNestedMethod(TreePath, VisitorState)}
   */
  static final Predicate<AccessPath> TRUE_AP_PREDICATE = ap -> true;
}
