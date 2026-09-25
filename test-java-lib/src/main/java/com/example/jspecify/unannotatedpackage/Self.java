package com.example.jspecify.unannotatedpackage;

import org.jspecify.annotations.NullMarked;

/** A self-referential generic type used to test wildcard bounds read from bytecode. */
public class Self<S extends Self<? extends S>> {

  @NullMarked
  public static int consume(Self<?> value) {
    return 1;
  }
}
