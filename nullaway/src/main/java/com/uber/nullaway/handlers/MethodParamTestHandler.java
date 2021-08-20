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
    if (config.PARAM_INDEX > parameters.size()
        || !(underlyingAST instanceof UnderlyingAST.CFGMethod)) {
      return super.onDataflowInitialStore(underlyingAST, parameters, result);
    }
    for (LocalVariableNode node : parameters) {
      result.setInformation(AccessPath.fromLocal(node), Nullness.NULLABLE);
    }
    return result;
  }
}
