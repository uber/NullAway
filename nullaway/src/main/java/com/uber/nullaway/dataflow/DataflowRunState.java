package com.uber.nullaway.dataflow;

/**
 * Tracks whether any NullAway dataflow analysis is running on the current thread. Results read from
 * a running analysis are provisional, so callers can use this to avoid memoizing values that depend
 * on them.
 */
public final class DataflowRunState {

  private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

  private DataflowRunState() {}

  static void enter() {
    DEPTH.get()[0]++;
  }

  static void exit() {
    DEPTH.get()[0]--;
  }

  /** Returns true if a dataflow analysis is running on the current thread. */
  public static boolean isAnyAnalysisRunning() {
    return DEPTH.get()[0] > 0;
  }
}
