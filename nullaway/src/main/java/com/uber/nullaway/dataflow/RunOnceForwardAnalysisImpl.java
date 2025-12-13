package com.uber.nullaway.dataflow;

import com.google.common.base.Verify;
import com.sun.source.tree.Tree;
import java.util.Set;
import org.checkerframework.nullaway.dataflow.analysis.AbstractValue;
import org.checkerframework.nullaway.dataflow.analysis.AnalysisResult;
import org.checkerframework.nullaway.dataflow.analysis.ForwardAnalysisImpl;
import org.checkerframework.nullaway.dataflow.analysis.ForwardTransferFunction;
import org.checkerframework.nullaway.dataflow.analysis.Store;
import org.checkerframework.nullaway.dataflow.analysis.TransferInput;
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
   * Gets the store before the given tree for a currently-running analysis. If the analysis has
   * completed running, use {@code getResult()}.
   *
   * @param tree the tree
   * @return the store before the given tree, or {@code null} if the tree is not in the CFG
   */
  public @Nullable S getStoreBefore(Tree tree) {
    Verify.verify(isRunning());
    Set<Node> nodes = getNodesForTree(tree);
    if (nodes != null) {
      return getStoreBefore(nodes);
    } else {
      return null;
    }
  }

  private @Nullable S getStoreBefore(Set<Node> nodes) {
    S merge = null;
    for (Node aNode : nodes) {
      S s = getStoreBefore(aNode);
      if (merge == null) {
        merge = s;
      } else if (s != null) {
        merge = merge.leastUpperBound(s);
      }
    }
    return merge;
  }

  private @Nullable S getStoreBefore(Node node) {
    TransferInput<V, S> prevStore = getInput(node.getBlock());
    if (prevStore == null) {
      return null;
    }
    return AnalysisResult.runAnalysisFor(
        node, BeforeOrAfter.BEFORE, prevStore, getNodeValues(), null);
  }
}
