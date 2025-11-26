package com.uber.nullaway.dataflow;

import org.checkerframework.nullaway.dataflow.analysis.AbstractValue;
import org.checkerframework.nullaway.dataflow.analysis.ForwardAnalysisImpl;
import org.checkerframework.nullaway.dataflow.analysis.ForwardTransferFunction;
import org.checkerframework.nullaway.dataflow.analysis.Store;
import org.checkerframework.nullaway.dataflow.cfg.ControlFlowGraph;

/**
 * A ForwardAnalysis implementation that overrides {@link #performAnalysis(ControlFlowGraph)} to
 * perform the analysis at most once.
 */
class RunOnceForwardAnalysisImpl<
        V extends AbstractValue<V>, S extends Store<S>, T extends ForwardTransferFunction<V, S>>
    extends ForwardAnalysisImpl<V, S, T> {

  private boolean analysisPerformed = false;

  public RunOnceForwardAnalysisImpl(T transferFunction) {
    super(transferFunction);
  }

  /**
   * Performs the analysis on the given CFG if it hasn't been performed yet.
   *
   * @param cfg the control flow graph to analyze
   */
  @Override
  public void performAnalysis(ControlFlowGraph cfg) {
    if (!analysisPerformed) {
      super.performAnalysis(cfg);
      analysisPerformed = true;
    }
  }
}
