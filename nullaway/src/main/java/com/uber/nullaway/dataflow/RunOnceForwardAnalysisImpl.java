package com.uber.nullaway.dataflow;

import com.google.common.annotations.VisibleForTesting;
import java.util.function.Consumer;
import org.checkerframework.nullaway.dataflow.analysis.AbstractValue;
import org.checkerframework.nullaway.dataflow.analysis.ForwardAnalysisImpl;
import org.checkerframework.nullaway.dataflow.analysis.ForwardTransferFunction;
import org.checkerframework.nullaway.dataflow.analysis.Store;
import org.checkerframework.nullaway.dataflow.cfg.ControlFlowGraph;
import org.checkerframework.nullaway.dataflow.cfg.UnderlyingAST;
import org.jspecify.annotations.Nullable;

/**
 * A ForwardAnalysis implementation that overrides {@link #performAnalysis(ControlFlowGraph)} to
 * perform the analysis at most once.
 */
class RunOnceForwardAnalysisImpl<
        V extends AbstractValue<V>, S extends Store<S>, T extends ForwardTransferFunction<V, S>>
    extends ForwardAnalysisImpl<V, S, T> {

  /**
   * Receives the code of every analysis that runs, and not of a call that finds the analysis
   * already performed. Tests set it to count the runs; it is {@code null} otherwise.
   */
  @VisibleForTesting static volatile @Nullable Consumer<UnderlyingAST> runListener;

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
      Consumer<UnderlyingAST> listener = runListener;
      if (listener != null) {
        listener.accept(cfg.getUnderlyingAST());
      }
      super.performAnalysis(cfg);
      analysisPerformed = true;
    }
  }
}
