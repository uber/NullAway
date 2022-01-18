/*
 * Copyright (c) 2017 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.nullaway.handlers;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.VisitorState;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import com.uber.nullaway.dataflow.NullnessStore;
import java.util.List;
import java.util.Optional;
import org.checkerframework.nullaway.dataflow.cfg.UnderlyingAST;
import org.checkerframework.nullaway.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;

/**
 * Registry of all handlers registered on our analysis.
 *
 * <p>In general, handlers are a way to specialize the behavior of our analysis on particular APIs
 * or libraries that are not part of the core Java language (e.g. our io.reactivex.* support).
 */
class CompositeHandler implements Handler {

  private final List<Handler> handlers;

  CompositeHandler(ImmutableList<Handler> handlers) {
    // Attach default handlers
    this.handlers = handlers;
  }

  @Override
  public void onMatchTopLevelClass(
      NullAway analysis, ClassTree tree, VisitorState state, Symbol.ClassSymbol classSymbol) {
    for (Handler h : handlers) {
      h.onMatchTopLevelClass(analysis, tree, state, classSymbol);
    }
  }

  @Override
  public void onMatchMethod(
      NullAway analysis, MethodTree tree, VisitorState state, Symbol.MethodSymbol methodSymbol) {
    for (Handler h : handlers) {
      h.onMatchMethod(analysis, tree, state, methodSymbol);
    }
  }

  @Override
  public void onMatchLambdaExpression(
      NullAway analysis,
      LambdaExpressionTree tree,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol) {
    for (Handler h : handlers) {
      h.onMatchLambdaExpression(analysis, tree, state, methodSymbol);
    }
  }

  @Override
  public void onMatchMethodReference(
      NullAway analysis,
      MemberReferenceTree tree,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol) {
    for (Handler h : handlers) {
      h.onMatchMethodReference(analysis, tree, state, methodSymbol);
    }
  }

  @Override
  public void onMatchMethodInvocation(
      NullAway analysis,
      MethodInvocationTree tree,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol) {
    for (Handler h : handlers) {
      h.onMatchMethodInvocation(analysis, tree, state, methodSymbol);
    }
  }

  @Override
  public void onMatchReturn(NullAway analysis, ReturnTree tree, VisitorState state) {
    for (Handler h : handlers) {
      h.onMatchReturn(analysis, tree, state);
    }
  }

  @Override
  public ImmutableSet<Integer> onUnannotatedInvocationGetExplicitlyNullablePositions(
      Context context,
      Symbol.MethodSymbol methodSymbol,
      ImmutableSet<Integer> explicitlyNullablePositions) {
    for (Handler h : handlers) {
      explicitlyNullablePositions =
          h.onUnannotatedInvocationGetExplicitlyNullablePositions(
              context, methodSymbol, explicitlyNullablePositions);
    }
    return explicitlyNullablePositions;
  }

  @Override
  public boolean onUnannotatedInvocationGetExplicitlyNonNullReturn(
      Symbol.MethodSymbol methodSymbol, boolean explicitlyNonNullReturn) {
    for (Handler h : handlers) {
      explicitlyNonNullReturn =
          h.onUnannotatedInvocationGetExplicitlyNonNullReturn(
              methodSymbol, explicitlyNonNullReturn);
    }
    return explicitlyNonNullReturn;
  }

  @Override
  public ImmutableSet<Integer> onUnannotatedInvocationGetNonNullPositions(
      NullAway analysis,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol,
      List<? extends ExpressionTree> actualParams,
      ImmutableSet<Integer> nonNullPositions) {
    for (Handler h : handlers) {
      nonNullPositions =
          h.onUnannotatedInvocationGetNonNullPositions(
              analysis, state, methodSymbol, actualParams, nonNullPositions);
    }
    return nonNullPositions;
  }

  @Override
  public boolean onOverrideMayBeNullExpr(
      NullAway analysis, ExpressionTree expr, VisitorState state, boolean exprMayBeNull) {
    for (Handler h : handlers) {
      exprMayBeNull = h.onOverrideMayBeNullExpr(analysis, expr, state, exprMayBeNull);
    }
    return exprMayBeNull;
  }

  @Override
  public NullnessStore.Builder onDataflowInitialStore(
      UnderlyingAST underlyingAST,
      List<LocalVariableNode> parameters,
      NullnessStore.Builder result) {
    for (Handler h : handlers) {
      result = h.onDataflowInitialStore(underlyingAST, parameters, result);
    }
    return result;
  }

  @Override
  public NullnessHint onDataflowVisitMethodInvocation(
      MethodInvocationNode node,
      Types types,
      Context context,
      AccessPath.AccessPathContext apContext,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    NullnessHint nullnessHint = NullnessHint.UNKNOWN;
    for (Handler h : handlers) {
      NullnessHint n =
          h.onDataflowVisitMethodInvocation(
              node, types, context, apContext, inputs, thenUpdates, elseUpdates, bothUpdates);
      nullnessHint = nullnessHint.merge(n);
    }
    return nullnessHint;
  }

  @Override
  public void onDataflowVisitReturn(
      ReturnTree tree, NullnessStore thenStore, NullnessStore elseStore) {
    for (Handler h : handlers) {
      h.onDataflowVisitReturn(tree, thenStore, elseStore);
    }
  }

  @Override
  public void onDataflowVisitLambdaResultExpression(
      ExpressionTree tree, NullnessStore thenStore, NullnessStore elseStore) {
    for (Handler h : handlers) {
      h.onDataflowVisitLambdaResultExpression(tree, thenStore, elseStore);
    }
  }

  @Override
  public Optional<ErrorMessage> onExpressionDereference(
      ExpressionTree expr, ExpressionTree baseExpr, VisitorState state) {
    Optional<ErrorMessage> optionalErrorMessage;
    for (Handler h : handlers) {
      optionalErrorMessage = h.onExpressionDereference(expr, baseExpr, state);
      if (optionalErrorMessage.isPresent()) {
        return optionalErrorMessage;
      }
    }
    return Optional.empty();
  }

  @Override
  public boolean includeApInfoInSavedContext(AccessPath accessPath, VisitorState state) {
    boolean shouldFilter = false;
    for (Handler h : handlers) {
      shouldFilter |= h.includeApInfoInSavedContext(accessPath, state);
    }
    return shouldFilter;
  }

  @Override
  public ImmutableSet<String> onRegisterImmutableTypes() {
    ImmutableSet.Builder<String> builder = ImmutableSet.<String>builder();
    for (Handler h : handlers) {
      builder.addAll(h.onRegisterImmutableTypes());
    }
    return builder.build();
  }
}
