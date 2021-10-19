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
  public void onMatchMethod(
      NullAway analysis, MethodTree tree, VisitorState state, Symbol.MethodSymbol methodSymbol) {
    // NoOp
  }

  @Override
  public void onMatchMethodInvocation(
      NullAway analysis,
      MethodInvocationTree tree,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol) {
    // NoOp
  }

  @Override
  public void onMatchLambdaExpression(
      NullAway analysis,
      LambdaExpressionTree tree,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol) {
    // NoOp
  }

  @Override
  public void onMatchMethodReference(
      NullAway analysis,
      MemberReferenceTree tree,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol) {
    // NoOp
  }

  @Override
  public void onMatchReturn(NullAway analysis, ReturnTree tree, VisitorState state) {
    // NoOp
  }

  @Override
  public ImmutableSet<Integer> onUnannotatedInvocationGetExplicitlyNullablePositions(
      Context context,
      Symbol.MethodSymbol methodSymbol,
      ImmutableSet<Integer> explicitlyNullablePositions) {
    // NoOp
    return explicitlyNullablePositions;
  }

  @Override
  public boolean onUnannotatedInvocationGetExplicitlyNonNullReturn(
      Symbol.MethodSymbol methodSymbol, boolean explicitlyNonNullReturn) {
    // NoOp
    return explicitlyNonNullReturn;
  }

  @Override
  public ImmutableSet<Integer> onUnannotatedInvocationGetNonNullPositions(
      NullAway analysis,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol,
      List<? extends ExpressionTree> actualParams,
      ImmutableSet<Integer> nonNullPositions) {
    // NoOp
    return nonNullPositions;
  }

  @Override
  public boolean onOverrideMayBeNullExpr(
      NullAway analysis, ExpressionTree expr, VisitorState state, boolean exprMayBeNull) {
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
      Types types,
      Context context,
      AccessPath.AccessPathContext apContext,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
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
  public boolean includeApInfoInSavedContext(AccessPath accessPath, VisitorState state) {
    return false;
  }

  @Override
  public ImmutableSet<String> onRegisterImmutableTypes() {
    return ImmutableSet.of();
  }
}
