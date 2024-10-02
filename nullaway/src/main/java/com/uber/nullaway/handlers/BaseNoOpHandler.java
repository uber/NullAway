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
 * Provides a default (No-Op) implementation of every method defined by the Handler interface.
 *
 * <p>Useful Handler classes can subclass this class and implement only the methods for the hooks
 * they actually need to take action on, rather than having to implement the entire Handler
 * interface. Additionally, we can add extensibility points without breaking existing handlers, as
 * long as we define the corresponding No-Op behavior here.
 */
public abstract class BaseNoOpHandler implements Handler {

  protected BaseNoOpHandler() {
    // We don't allow creating useless handlers, subclass to add real behavior.
  }

  @Override
  public void onMatchTopLevelClass(
      NullAway analysis, ClassTree tree, VisitorState state, Symbol.ClassSymbol classSymbol) {
    // NoOp
  }

  @Override
  public void onMatchMethod(MethodTree tree, MethodAnalysisContext methodAnalysisContext) {
    // NoOp
  }

  @Override
  public void onMatchMethodInvocation(
      MethodInvocationTree tree, MethodAnalysisContext methodAnalysisContext) {
    // NoOp
  }

  @Override
  public void onMatchLambdaExpression(
      LambdaExpressionTree tree, MethodAnalysisContext methodAnalysisContext) {
    // NoOp
  }

  @Override
  public void onMatchMethodReference(
      MemberReferenceTree tree, MethodAnalysisContext methodAnalysisContext) {
    // NoOp
  }

  @Override
  public void onMatchReturn(NullAway analysis, ReturnTree tree, VisitorState state) {
    // NoOp
  }

  @Override
  public Nullness onOverrideMethodReturnNullability(
      Symbol.MethodSymbol methodSymbol,
      VisitorState state,
      boolean isAnnotated,
      Nullness returnNullness) {
    // NoOp
    return returnNullness;
  }

  @Override
  public boolean onOverrideFieldNullability(Symbol field) {
    // NoOp
    return false;
  }

  @Override
  public Nullness[] onOverrideMethodInvocationParametersNullability(
      Context context,
      Symbol.MethodSymbol methodSymbol,
      boolean isAnnotated,
      Nullness[] argumentPositionNullness) {
    // NoOp
    return argumentPositionNullness;
  }

  @Override
  public boolean onOverrideMayBeNullExpr(
      NullAway analysis,
      ExpressionTree expr,
      @Nullable Symbol exprSymbol,
      VisitorState state,
      boolean exprMayBeNull) {
    // NoOp
    return exprMayBeNull;
  }

  @Override
  public NullnessStore.Builder onDataflowInitialStore(
      UnderlyingAST underlyingAST,
      List<LocalVariableNode> parameters,
      NullnessStore.Builder result) {
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
    // NoOp
    return NullnessHint.UNKNOWN;
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
    // NoOp
    return NullnessHint.UNKNOWN;
  }

  @Override
  public void onDataflowVisitReturn(
      ReturnTree tree, NullnessStore thenStore, NullnessStore elseStore) {
    // NoOp
  }

  @Override
  public void onDataflowVisitLambdaResultExpression(
      ExpressionTree tree, NullnessStore thenStore, NullnessStore elseStore) {
    // NoOp
  }

  @Override
  public Optional<ErrorMessage> onExpressionDereference(
      ExpressionTree expr, ExpressionTree baseExpr, VisitorState state) {
    return Optional.empty();
  }

  @Override
  public Predicate<AccessPath> getAccessPathPredicateForNestedMethod(
      TreePath path, VisitorState state) {
    return AccessPathPredicates.FALSE_AP_PREDICATE;
  }

  @Override
  public ImmutableSet<String> onRegisterImmutableTypes() {
    return ImmutableSet.of();
  }

  @Override
  public void onNonNullFieldAssignment(
      Symbol field, AccessPathNullnessAnalysis analysis, VisitorState state) {
    // NoOp
  }

  @Override
  public boolean onOverrideTypeParameterUpperBound(String className, int index) {
    return false;
  }

  @Override
  public boolean onOverrideNullMarkedClasses(String className) {
    return false;
  }

  @Override
  public MethodInvocationNode onCFGBuildPhase1AfterVisitMethodInvocation(
      NullAwayCFGBuilder.NullAwayCFGTranslationPhaseOne phase,
      MethodInvocationTree tree,
      MethodInvocationNode originalNode) {
    return originalNode;
  }

  @Override
  public @Nullable Integer castToNonNullArgumentPositionsForMethod(
      List<? extends ExpressionTree> actualParams,
      @Nullable Integer previousArgumentPosition,
      MethodAnalysisContext methodAnalysisContext) {
    // NoOp
    return previousArgumentPosition;
  }
}
