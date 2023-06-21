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
import javax.lang.model.element.Name;

/**
 * This handler provides a temporary workaround due to our lack of support for generics, which
 * allows natural usage of Futures/FluentFuture Guava APIs. It can potentially introduce false
 * negatives, however, and should be deprecated as soon as full generic support is available.
 *
 * <p>This works by special casing the return nullability of {@link com.google.common.base.Function}
 * and {@link com.google.common.util.concurrent.AsyncFunction} to be e.g. {@code Function<@Nullable
 * T>} whenever these functional interfaces are implemented as a lambda expression passed to a list
 * of specific methods of {@link com.google.common.util.concurrent.FluentFuture} or {@link
 * com.google.common.util.concurrent.Futures}. We cannot currently check that {@code T} for {@code
 * FluentFuture<T>} is a {@code @Nullable} type, so this is unsound. However, we have found many
 * cases in practice where these lambdas include {@code null} returns, which were already being
 * ignored (due to a bug) before PR #765. This handler offers the best possible support for these
 * cases, at least until our generics support is mature enough to handle them.
 *
 * <p>Note: Package {@code com.uber.nullaway.handlers.temporary} is meant for this sort of temporary
 * workaround handler, to be removed as future NullAway features make them unnecessary. This is a
 * hack, but the best of a bunch of bad options.
 */
public class FluentFutureHandler extends BaseNoOpHandler {

  private static final String GUAVA_FUNCTION_CLASS_NAME = "com.google.common.base.Function";
  private static final String GUAVA_ASYNC_FUNCTION_CLASS_NAME =
      "com.google.common.util.concurrent.AsyncFunction";
  private static final String FLUENT_FUTURE_CLASS_NAME =
      "com.google.common.util.concurrent.FluentFuture";
  private static final String FUTURES_CLASS_NAME = "com.google.common.util.concurrent.Futures";
  private static final String FUNCTION_APPLY_METHOD_NAME = "apply";
  private static final String[] FLUENT_FUTURE_INCLUDE_LIST_METHODS = {
    "catching", "catchingAsync", "transform", "transformAsync"
  };

  private static boolean isGuavaFunctionDotApply(Symbol.MethodSymbol methodSymbol) {
    Name className = methodSymbol.enclClass().flatName();
    return (className.contentEquals(GUAVA_FUNCTION_CLASS_NAME)
            || className.contentEquals(GUAVA_ASYNC_FUNCTION_CLASS_NAME))
        && methodSymbol.name.contentEquals(FUNCTION_APPLY_METHOD_NAME);
  }

  private static boolean isFluentFutureIncludeListMethod(Symbol.MethodSymbol methodSymbol) {
    Name className = methodSymbol.enclClass().flatName();
    return (className.contentEquals(FLUENT_FUTURE_CLASS_NAME)
            || className.contentEquals(FUTURES_CLASS_NAME))
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
    if (!isGuavaFunctionDotApply(methodSymbol)) {
      return returnNullness;
    }
    // Check if we are inside a lambda passed as an argument to a method call:
    LambdaExpressionTree enclosingLambda =
        ASTHelpers.findEnclosingNode(state.getPath(), LambdaExpressionTree.class);
    if (enclosingLambda == null
        || !NullabilityUtil.getFunctionalInterfaceMethod(enclosingLambda, state.getTypes())
            .equals(methodSymbol)) {
      return returnNullness;
    }
    MethodInvocationTree methodInvocation =
        ASTHelpers.findEnclosingNode(state.getPath(), MethodInvocationTree.class);
    if (methodInvocation == null || !methodInvocation.getArguments().contains(enclosingLambda)) {
      return returnNullness;
    }
    // Check if that method call is one of the FluentFuture APIs we care about
    Symbol.MethodSymbol lambdaConsumerMethodSymbol = ASTHelpers.getSymbol(methodInvocation);
    if (!isFluentFutureIncludeListMethod(lambdaConsumerMethodSymbol)) {
      return returnNullness;
    }
    return Nullness.NULLABLE;
  }
}
