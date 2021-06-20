package com.uber.nullaway.handlers;

import com.sun.source.tree.MethodTree;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.autofix.AutoFixConfig;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.NullnessStore;
import java.util.List;
import javax.lang.model.element.Element;
import org.checkerframework.nullaway.dataflow.cfg.UnderlyingAST;
import org.checkerframework.nullaway.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.shaded.javacutil.TreeUtils;

public class MethodParameterNullableByIndex extends BaseNoOpHandler {

  private final AutoFixConfig config;

  public MethodParameterNullableByIndex(AutoFixConfig config) {
    this.config = config;
  }

  @Override
  public NullnessStore.Builder onDataflowInitialStore(
      UnderlyingAST underlyingAST,
      List<LocalVariableNode> parameters,
      NullnessStore.Builder result) {
    if (config.PARAM_TEST_ENABLED) {
      if (!(underlyingAST instanceof UnderlyingAST.CFGMethod)) {
        return super.onDataflowInitialStore(underlyingAST, parameters, result);
      }
      int index = (int) config.PARAM_INDEX;
      MethodTree methodTree = ((UnderlyingAST.CFGMethod) underlyingAST).getMethod();
      if (index < methodTree.getParameters().size()) {
        Element element = TreeUtils.elementFromTree(methodTree.getParameters().get(index));
        AccessPath accessPath = AccessPath.fromMethodParameter(element);
        result.setInformation(accessPath, Nullness.NULLABLE);
      }
    }
    return result;
  }
}
