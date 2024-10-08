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

import static com.uber.nullaway.handlers.AccessPathPredicates.FALSE_AP_PREDICATE;
import static com.uber.nullaway.handlers.AccessPathPredicates.TRUE_AP_PREDICATE;

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
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessAnalysis;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import com.uber.nullaway.dataflow.NullnessStore;
import com.uber.nullaway.dataflow.cfg.NullAwayCFGBuilder;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.checkerframework.nullaway.dataflow.cfg.UnderlyingAST;
import org.checkerframework.nullaway.dataflow.cfg.node.FieldAccessNode;
import org.checkerframework.nullaway.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;
import org.jspecify.annotations.Nullable;

/**
 * Registry of all handlers registered on our analysis.
 *
 * <p>In general, handlers are a way to specialize the behavior of our analysis on particular APIs
 * or libraries that are not part of the core Java language (e.g. our io.reactivex.* support).
 */
class CompositeHandler implements Handler {

  private final ImmutableList<Handler> handlers;

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
  public void onMatchMethod(MethodTree tree, MethodAnalysisContext methodAnalysisContext) {
    for (Handler h : handlers) {
      h.onMatchMethod(tree, methodAnalysisContext);
    }
  }

  @Override
  public void onMatchLambdaExpression(
      LambdaExpressionTree tree, MethodAnalysisContext methodAnalysisContext) {
    for (Handler h : handlers) {
      h.onMatchLambdaExpression(tree, methodAnalysisContext);
    }
  }

  @Override
  public void onMatchMethodReference(
      MemberReferenceTree tree, MethodAnalysisContext methodAnalysisContext) {
    for (Handler h : handlers) {
      h.onMatchMethodReference(tree, methodAnalysisContext);
    }
  }

  @Override
  public void onMatchMethodInvocation(
      MethodInvocationTree tree, MethodAnalysisContext methodAnalysisContext) {
    for (Handler h : handlers) {
      h.onMatchMethodInvocation(tree, methodAnalysisContext);
    }
  }

  @Override
  public void onMatchReturn(NullAway analysis, ReturnTree tree, VisitorState state) {
    for (Handler h : handlers) {
      h.onMatchReturn(analysis, tree, state);
    }
  }

  @Override
  public Nullness onOverrideMethodReturnNullability(
      Symbol.MethodSymbol methodSymbol,
      VisitorState state,
      boolean isAnnotated,
      Nullness returnNullness) {
    for (Handler h : handlers) {
      returnNullness =
          h.onOverrideMethodReturnNullability(methodSymbol, state, isAnnotated, returnNullness);
    }
    return returnNullness;
  }

  @Override
  public boolean onOverrideFieldNullability(Symbol field) {
    for (Handler h : handlers) {
      if (h.onOverrideFieldNullability(field)) {
        // If any handler determines that the field is @Nullable, we should acknowledge that and
        // treat it as such.
        return true;
      }
    }
    return false;
  }

  @Override
  public Nullness[] onOverrideMethodInvocationParametersNullability(
      Context context,
      Symbol.MethodSymbol methodSymbol,
      boolean isAnnotated,
      Nullness[] argumentPositionNullness) {
    for (Handler h : handlers) {
      argumentPositionNullness =
          h.onOverrideMethodInvocationParametersNullability(
              context, methodSymbol, isAnnotated, argumentPositionNullness);
    }
    return argumentPositionNullness;
  }

  @Override
  public boolean onOverrideMayBeNullExpr(
      NullAway analysis,
      ExpressionTree expr,
      @Nullable Symbol exprSymbol,
      VisitorState state,
      boolean exprMayBeNull) {
    for (Handler h : handlers) {
      exprMayBeNull = h.onOverrideMayBeNullExpr(analysis, expr, exprSymbol, state, exprMayBeNull);
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
      Symbol.MethodSymbol symbol,
      VisitorState state,
      AccessPath.AccessPathContext apContext,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    NullnessHint nullnessHint = NullnessHint.UNKNOWN;
    for (Handler h : handlers) {
      NullnessHint n =
          h.onDataflowVisitMethodInvocation(
              node, symbol, state, apContext, inputs, thenUpdates, elseUpdates, bothUpdates);
      nullnessHint = nullnessHint.merge(n);
    }
    return nullnessHint;
  }

  @Override
  public NullnessHint onDataflowVisitFieldAccess(
      FieldAccessNode node,
      Symbol symbol,
      Types types,
      Context context,
      AccessPath.AccessPathContext apContext,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates updates) {
    NullnessHint nullnessHint = NullnessHint.UNKNOWN;
    for (Handler h : handlers) {
      NullnessHint n =
          h.onDataflowVisitFieldAccess(node, symbol, types, context, apContext, inputs, updates);
      nullnessHint = nullnessHint.merge(n);
    }
    return nullnessHint;
  }

  @Override
  public void onDataflowVisitReturn(
      ReturnTree tree, VisitorState state, NullnessStore thenStore, NullnessStore elseStore) {
    for (Handler h : handlers) {
      h.onDataflowVisitReturn(tree, state, thenStore, elseStore);
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
  public Predicate<AccessPath> getAccessPathPredicateForNestedMethod(
      TreePath path, VisitorState state) {
    Predicate<AccessPath> filter = FALSE_AP_PREDICATE;
    for (Handler h : handlers) {
      Predicate<AccessPath> curFilter = h.getAccessPathPredicateForNestedMethod(path, state);
      // here we do some optimization, to try to avoid unnecessarily returning a deeply nested
      // Predicate object (which would be more costly to test)
      if (curFilter != FALSE_AP_PREDICATE) {
        if (curFilter == TRUE_AP_PREDICATE) {
          return curFilter;
        } else if (filter == FALSE_AP_PREDICATE) {
          filter = curFilter;
        } else {
          filter = filter.or(curFilter);
        }
      }
    }
    return filter;
  }

  @Override
  public ImmutableSet<String> onRegisterImmutableTypes() {
    ImmutableSet.Builder<String> builder = ImmutableSet.<String>builder();
    for (Handler h : handlers) {
      builder.addAll(h.onRegisterImmutableTypes());
    }
    return builder.build();
  }

  @Override
  public void onNonNullFieldAssignment(
      Symbol field, AccessPathNullnessAnalysis analysis, VisitorState state) {
    for (Handler h : handlers) {
      h.onNonNullFieldAssignment(field, analysis, state);
    }
  }

  @Override
  public MethodInvocationNode onCFGBuildPhase1AfterVisitMethodInvocation(
      NullAwayCFGBuilder.NullAwayCFGTranslationPhaseOne phase,
      MethodInvocationTree tree,
      MethodInvocationNode originalNode) {
    MethodInvocationNode currentNode = originalNode;
    for (Handler h : handlers) {
      currentNode = h.onCFGBuildPhase1AfterVisitMethodInvocation(phase, tree, currentNode);
    }
    return currentNode;
  }

  @Override
  public @Nullable Integer castToNonNullArgumentPositionsForMethod(
      List<? extends ExpressionTree> actualParams,
      @Nullable Integer previousArgumentPosition,
      MethodAnalysisContext methodAnalysisContext) {
    for (Handler h : handlers) {
      previousArgumentPosition =
          h.castToNonNullArgumentPositionsForMethod(
              actualParams, previousArgumentPosition, methodAnalysisContext);
    }
    return previousArgumentPosition;
  }

  /** Returns true if any handler returns true. */
  @Override
  public boolean onOverrideTypeParameterUpperBound(String className, int index) {
    boolean result = false;
    for (Handler h : handlers) {
      result = h.onOverrideTypeParameterUpperBound(className, index);
      if (result) {
        break;
      }
    }
    return result;
  }

  /** Returns true if any handler returns true. */
  @Override
  public boolean onOverrideNullMarkedClasses(String className) {
    boolean result = false;
    for (Handler h : handlers) {
      result = h.onOverrideNullMarkedClasses(className);
      if (result) {
        break;
      }
    }
    return result;
  }
}
