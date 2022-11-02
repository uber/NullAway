package com.uber.nullaway.dataflow;

import javax.annotation.Generated;
import org.checkerframework.nullaway.dataflow.analysis.ForwardTransferFunction;
import org.checkerframework.nullaway.dataflow.cfg.ControlFlowGraph;

@Generated("com.google.auto.value.processor.AutoValueProcessor")
final class AutoValue_DataFlow_AnalysisParams extends DataFlow.AnalysisParams {

  private final ForwardTransferFunction<?, ?> transferFunction;

  private final ControlFlowGraph cfg;

  AutoValue_DataFlow_AnalysisParams(
      ForwardTransferFunction<?, ?> transferFunction, ControlFlowGraph cfg) {
    if (transferFunction == null) {
      throw new NullPointerException("Null transferFunction");
    }
    this.transferFunction = transferFunction;
    if (cfg == null) {
      throw new NullPointerException("Null cfg");
    }
    this.cfg = cfg;
  }

  @Override
  ForwardTransferFunction<?, ?> transferFunction() {
    return transferFunction;
  }

  @Override
  ControlFlowGraph cfg() {
    return cfg;
  }

  @Override
  public String toString() {
    return "AnalysisParams{" + "transferFunction=" + transferFunction + ", " + "cfg=" + cfg + "}";
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (o instanceof DataFlow.AnalysisParams) {
      DataFlow.AnalysisParams that = (DataFlow.AnalysisParams) o;
      return this.transferFunction.equals(that.transferFunction()) && this.cfg.equals(that.cfg());
    }
    return false;
  }

  @Override
  public int hashCode() {
    int h$ = 1;
    h$ *= 1000003;
    h$ ^= transferFunction.hashCode();
    h$ *= 1000003;
    h$ ^= cfg.hashCode();
    return h$;
  }
}
