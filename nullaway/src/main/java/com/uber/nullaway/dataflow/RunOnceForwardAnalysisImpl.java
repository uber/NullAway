package com.uber.nullaway.dataflow;

import org.checkerframework.nullaway.dataflow.analysis.AbstractValue;
import org.checkerframework.nullaway.dataflow.analysis.ForwardAnalysisImpl;
import org.checkerframework.nullaway.dataflow.analysis.ForwardTransferFunction;
import org.checkerframework.nullaway.dataflow.analysis.Store;
import org.checkerframework.nullaway.dataflow.cfg.ControlFlowGraph;

public class RunOnceForwardAnalysisImpl<
        V extends AbstractValue<V>, S extends Store<S>, T extends ForwardTransferFunction<V, S>>
    extends ForwardAnalysisImpl<V, S, T> {

  boolean analysisPerformed = false;

  public RunOnceForwardAnalysisImpl(T transferFunction) {
    super(transferFunction);
  }

  @Override
  public void performAnalysis(ControlFlowGraph cfg) {
    if (!analysisPerformed) {
      super.performAnalysis(cfg);
      analysisPerformed = true;
    }
  }
}
