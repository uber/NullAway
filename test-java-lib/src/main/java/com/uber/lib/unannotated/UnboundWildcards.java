package com.uber.lib.unannotated;

/* @NullMarked */
@SuppressWarnings("DoNotCallSuggester")
public class UnboundWildcards<T /* extends @Nullable Object */> {

  public UnboundWildcards<T> self() {
    return this;
  }

  public static UnboundWildcards<? extends /* @Nullable */ Object> literalWildcard() {
    throw new RuntimeException();
  }

  public UnboundWildcards</* @Nullable */ T> typeVariable() {
    throw new RuntimeException();
  }
}
