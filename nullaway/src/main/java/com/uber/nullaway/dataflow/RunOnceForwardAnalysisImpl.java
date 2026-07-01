package com.uber.nullaway.dataflow;

import org.checkerframework.nullaway.dataflow.analysis.AbstractValue;
import org.checkerframework.nullaway.dataflow.analysis.ForwardAnalysisImpl;
import org.checkerframework.nullaway.dataflow.analysis.ForwardTransferFunction;
import org.checkerframework.nullaway.dataflow.analysis.Store;
import org.checkerframework.nullaway.dataflow.cfg.ControlFlowGraph;
import org.checkerframework.nullaway.dataflow.cfg.node.Node;
import org.jspecify.annotations.Nullable;

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

  /**
   * Override as a workaround for <a
   * href="https://github.com/typetools/checker-framework/issues/7726">Checker Framework issue
   * 7726</a>. This version returns the current value for {@code n} even if we have a running
   * analysis and {@code n} is not a (transitive) operand of the current node of the analysis.
   * Otherwise, its implementation is identical to that of {@code
   * org.checkerframework.nullaway.dataflow.analysis.AbstractAnalysis#getValue(Node).}
   *
   * <p>We should remove this method if / when CF issue 7726 is fixed in a suitable manner.
   */
  @Override
  @SuppressWarnings("ReferenceEquality") // intentional reference comparison
  public @Nullable V getValue(Node n) {
    if (isRunning) {
      if (currentNode == null
          || currentNode == n
          || (currentTree != null && currentTree == n.getTree())) {
        return null;
      }
      assert !n.isLValue() : "Did not expect an lvalue, but got " + n;
    }
    return nodeValues.get(n);
  }
}
