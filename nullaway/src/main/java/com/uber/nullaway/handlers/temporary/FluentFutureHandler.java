package com.uber.nullaway.handlers.temporary;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.NullabilityUtil;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.handlers.BaseNoOpHandler;
import java.util.Arrays;

/**
 * A temporary workaround due to our lack of support for generics. We special case
 * com.google.common.base.Function when used within com.google.common.util.concurrent.FluentFuture
 * to assume a {@code Function<@Nullable T>}
 */
public class FluentFutureHandler extends BaseNoOpHandler {

  private static final String GUAVA_FUNCTION_CLASS_NAME = "com.google.common.base.Function";
  private static final String FLUENT_FUTURE_CLASS_NAME =
      "com.google.common.util.concurrent.FluentFuture";
  private static final String FUNCTION_APPLY_METHOD_NAME = "apply";
  private static final String[] FLUENT_FUTURE_INCLUDE_LIST_METHODS = {
    "catching", "catchingAsync", "transform", "transformAsync"
  };

  private boolean isGuavaFunctionDotAppy(Symbol.MethodSymbol methodSymbol) {
    return methodSymbol.enclClass().flatName().contentEquals(GUAVA_FUNCTION_CLASS_NAME)
        && methodSymbol.name.contentEquals(FUNCTION_APPLY_METHOD_NAME);
  }

  private boolean isFluentFutureIncludeListMethod(Symbol.MethodSymbol methodSymbol) {
    return methodSymbol.enclClass().flatName().contentEquals(FLUENT_FUTURE_CLASS_NAME)
        && Arrays.stream(FLUENT_FUTURE_INCLUDE_LIST_METHODS)
            .anyMatch(s -> methodSymbol.name.contentEquals(s));
  }

  @Override
  public Nullness onOverrideMethodReturnNullability(
      Symbol.MethodSymbol methodSymbol,
      VisitorState state,
      boolean isAnnotated,
      Nullness returnNullness) {
    // We only care about lambda's implementing Guava's Function
    if (!isGuavaFunctionDotAppy(methodSymbol)) {
      return returnNullness;
    }
    // Check if we are inside a lambda inside a call to a FluentFuture method:
    LambdaExpressionTree enclosingLambda =
        ASTHelpers.findEnclosingNode(state.getPath(), LambdaExpressionTree.class);
    if (enclosingLambda == null
        && NullabilityUtil.getFunctionalInterfaceMethod(enclosingLambda, state.getTypes())
            .equals(methodSymbol)) {
      return returnNullness;
    }
    MethodInvocationTree methodInvocation =
        ASTHelpers.findEnclosingNode(state.getPath(), MethodInvocationTree.class);
    if (methodInvocation == null || !methodInvocation.getArguments().contains(enclosingLambda)) {
      return returnNullness;
    }
    Symbol.MethodSymbol lambdaConsumerMethodSymbol = ASTHelpers.getSymbol(methodInvocation);
    if (!isFluentFutureIncludeListMethod(lambdaConsumerMethodSymbol)) {
      return returnNullness;
    }
    return Nullness.NULLABLE;
  }
}
