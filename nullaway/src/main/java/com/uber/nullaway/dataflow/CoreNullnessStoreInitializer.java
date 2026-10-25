package com.uber.nullaway.dataflow;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.Config;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.ParameterNullness;
import com.uber.nullaway.generics.GenericsChecks;
import com.uber.nullaway.handlers.Handler;
import java.util.List;
import java.util.Objects;
import org.checkerframework.nullaway.dataflow.cfg.UnderlyingAST;
import org.checkerframework.nullaway.dataflow.cfg.node.LocalVariableNode;

class CoreNullnessStoreInitializer extends NullnessStoreInitializer {

  private final GenericsChecks genericsChecks;

  public CoreNullnessStoreInitializer(GenericsChecks genericsChecks) {
    this.genericsChecks = genericsChecks;
  }

  @Override
  public NullnessStore getInitialStore(
      UnderlyingAST underlyingAST,
      List<LocalVariableNode> parameters,
      Handler handler,
      Context context,
      Types types,
      Config config) {
    if (underlyingAST.getKind().equals(UnderlyingAST.Kind.ARBITRARY_CODE)) {
      // not a method or a lambda; an initializer expression or block
      UnderlyingAST.CFGStatement ast = (UnderlyingAST.CFGStatement) underlyingAST;
      return getEnvNullnessStoreForClass(ast.getClassTree(), context);
    }
    boolean isLambda = underlyingAST.getKind().equals(UnderlyingAST.Kind.LAMBDA);
    if (isLambda) {
      return lambdaInitialStore(
          (UnderlyingAST.CFGLambda) underlyingAST, parameters, handler, context, types, config);
    } else {
      return methodInitialStore(
          (UnderlyingAST.CFGMethod) underlyingAST, parameters, handler, context, config);
    }
  }

  private static NullnessStore methodInitialStore(
      UnderlyingAST.CFGMethod underlyingAST,
      List<LocalVariableNode> parameters,
      Handler handler,
      Context context,
      Config config) {
    ClassTree classTree = underlyingAST.getClassTree();
    NullnessStore envStore = getEnvNullnessStoreForClass(classTree, context);
    NullnessStore.Builder result = envStore.toBuilder();
    for (LocalVariableNode param : parameters) {
      Symbol paramSymbol = (Symbol) param.getElement();
      result.setInformation(
          AccessPath.fromLocal(param),
          ParameterNullness.getMethodParameterNullness(paramSymbol, config));
    }
    result = handler.onDataflowInitialStore(underlyingAST, parameters, result);
    return result.build();
  }

  private NullnessStore lambdaInitialStore(
      UnderlyingAST.CFGLambda underlyingAST,
      List<LocalVariableNode> parameters,
      Handler handler,
      Context context,
      Types types,
      Config config) {
    // include nullness info for locals from enclosing environment
    EnclosingEnvironmentNullness environmentNullness =
        EnclosingEnvironmentNullness.instance(context);
    NullnessStore environmentMapping =
        Objects.requireNonNull(
            environmentNullness.getEnvironmentMapping(underlyingAST.getLambdaTree()),
            "no environment stored for lambda");
    NullnessStore.Builder result = environmentMapping.toBuilder();
    LambdaExpressionTree code = underlyingAST.getLambdaTree();
    List<Nullness> parameterNullness =
        ParameterNullness.getLambdaParameterNullness(
            code, genericsChecks, handler, context, types, config);
    for (int i = 0; i < parameters.size(); i++) {
      result.setInformation(AccessPath.fromLocal(parameters.get(i)), parameterNullness.get(i));
    }
    result = handler.onDataflowInitialStore(underlyingAST, parameters, result);
    return result.build();
  }
}
