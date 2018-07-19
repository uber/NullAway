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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.TargetType;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import java.util.HashSet;
import java.util.List;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;

public class RestrictiveAnnotationHandler extends BaseNoOpHandler {

  @Override
  public ImmutableSet<Integer> onUnannotatedInvocationGetNonNullPositions(
      NullAway analysis,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol,
      List<? extends ExpressionTree> actualParams,
      ImmutableSet<Integer> nonNullPositions) {
    HashSet<Integer> positions = new HashSet<Integer>();
    positions.addAll(nonNullPositions);
    for (Attribute.TypeCompound tc : methodSymbol.getRawTypeAttributes()) {
      if (tc.position.type.equals(TargetType.METHOD_FORMAL_PARAMETER)
          && tc.getAnnotationType().asElement().getSimpleName().contentEquals("NonNull")) {
        positions.add(tc.position.parameter_index);
      }
    }
    return ImmutableSet.copyOf(positions);
  }

  @Override
  public boolean onOverrideMayBeNullExpr(
      NullAway analysis, ExpressionTree expr, VisitorState state, boolean exprMayBeNull) {
    if (expr.getKind().equals(Tree.Kind.METHOD_INVOCATION)) {
      Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol((MethodInvocationTree) expr);
      return isMethodSymbolAnnotatedNullable(methodSymbol);
    }
    return exprMayBeNull;
  }

  @Override
  public NullnessHint onDataflowVisitMethodInvocation(
      MethodInvocationNode node,
      Types types,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(node.getTree());
    return (isMethodSymbolAnnotatedNullable(methodSymbol)
        ? NullnessHint.HINT_NULLABLE
        : NullnessHint.UNKNOWN);
  }

  private boolean isMethodSymbolAnnotatedNullable(Symbol.MethodSymbol methodSymbol) {
    Preconditions.checkNotNull(methodSymbol);
    for (Attribute.TypeCompound tc : methodSymbol.getRawTypeAttributes()) {
      if (tc.position.type.equals(TargetType.METHOD_RETURN)
          && tc.getAnnotationType().asElement().getSimpleName().contentEquals("Nullable")) {
        return true;
      }
    }
    return false;
  }
}
