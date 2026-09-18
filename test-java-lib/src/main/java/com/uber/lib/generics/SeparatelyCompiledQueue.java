package com.uber.lib.generics;

import java.util.AbstractQueue;

/** A minimal generic type used to test wildcard bounds read from bytecode. */
public abstract class SeparatelyCompiledQueue<E> extends AbstractQueue<E> {

  /** Returns a dummy producer index. */
  public static long producerIndex(SeparatelyCompiledQueue<?> queue) {
    return 0L;
  }
}
