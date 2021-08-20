package com.uber.nullaway.handlers;

import com.uber.nullaway.Config;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.autofix.AutoFixConfig;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.NullnessStore;
import java.util.List;
import org.checkerframework.nullaway.dataflow.cfg.UnderlyingAST;
import org.checkerframework.nullaway.dataflow.cfg.node.LocalVariableNode;

public class MethodParamTestHandler extends BaseNoOpHandler {

  private final AutoFixConfig config;

  public MethodParamTestHandler(Config config) {
    this.config = config.getAutoFixConfig();
  }

  @Override
  public NullnessStore.Builder onDataflowInitialStore(
      UnderlyingAST underlyingAST,
      List<LocalVariableNode> parameters,
      NullnessStore.Builder result) {
    int index = Math.toIntExact(config.PARAM_INDEX);
    if (index >= parameters.size() || !(underlyingAST instanceof UnderlyingAST.CFGMethod)) {
      return super.onDataflowInitialStore(underlyingAST, parameters, result);
    }
    result.setInformation(AccessPath.fromLocal(parameters.get(index)), Nullness.NULLABLE);
    return result;
  }
}
