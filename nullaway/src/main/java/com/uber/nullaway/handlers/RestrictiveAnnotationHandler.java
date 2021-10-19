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
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.NullabilityUtil;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import java.util.HashSet;
import java.util.List;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;

public class RestrictiveAnnotationHandler extends BaseNoOpHandler {

  private final Config config;

  RestrictiveAnnotationHandler(Config config) {
    this.config = config;
  }

  @Override
  public ImmutableSet<Integer> onUnannotatedInvocationGetNonNullPositions(
      NullAway analysis,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol,
      List<? extends ExpressionTree> actualParams,
      ImmutableSet<Integer> nonNullPositions) {
    HashSet<Integer> positions = new HashSet<Integer>();
    positions.addAll(nonNullPositions);
    for (int i = 0; i < methodSymbol.getParameters().size(); ++i) {
      if (Nullness.paramHasNonNullAnnotation(methodSymbol, i, config)) {
        positions.add(i);
      }
    }
    return ImmutableSet.copyOf(positions);
  }

  @Override
  public boolean onOverrideMayBeNullExpr(
      NullAway analysis, ExpressionTree expr, VisitorState state, boolean exprMayBeNull) {
    if (expr.getKind().equals(Tree.Kind.METHOD_INVOCATION)) {
      Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol((MethodInvocationTree) expr);
      if (NullabilityUtil.isUnannotated(methodSymbol, config)) {
        // with the generated-as-unannotated option enabled, we want to ignore
        // annotations in generated code
        if (config.treatGeneratedAsUnannotated() && NullabilityUtil.isGenerated(methodSymbol)) {
          return exprMayBeNull;
        } else {
          return Nullness.hasNullableAnnotation(methodSymbol, config) || exprMayBeNull;
        }
      } else {
        return exprMayBeNull;
      }
    }
    return exprMayBeNull;
  }

  @Override
  public ImmutableSet<Integer> onUnannotatedInvocationGetExplicitlyNullablePositions(
      Context context,
      Symbol.MethodSymbol methodSymbol,
      ImmutableSet<Integer> explicitlyNullablePositions) {
    HashSet<Integer> positions = new HashSet<Integer>();
    positions.addAll(explicitlyNullablePositions);
    for (int i = 0; i < methodSymbol.getParameters().size(); ++i) {
      if (Nullness.paramHasNullableAnnotation(methodSymbol, i, config)) {
        positions.add(i);
      }
    }
    return ImmutableSet.copyOf(positions);
  }

  @Override
  public boolean onUnannotatedInvocationGetExplicitlyNonNullReturn(
      Symbol.MethodSymbol methodSymbol, boolean explicitlyNonNullReturn) {
    return Nullness.hasNonNullAnnotation(methodSymbol, config) || explicitlyNonNullReturn;
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
    Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(node.getTree());
    if (NullabilityUtil.isUnannotated(methodSymbol, config)
        && Nullness.hasNullableAnnotation(methodSymbol, config)) {
      return NullnessHint.HINT_NULLABLE;
    }
    return NullnessHint.UNKNOWN;
  }
}
