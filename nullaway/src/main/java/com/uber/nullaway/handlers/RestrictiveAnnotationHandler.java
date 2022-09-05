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

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.ClassAnnotationInfo;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import javax.annotation.Nullable;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;

public class RestrictiveAnnotationHandler extends BaseNoOpHandler {

  private final Config config;

  RestrictiveAnnotationHandler(Config config) {
    this.config = config;
  }

  @Override
  public boolean onOverrideMayBeNullExpr(
      NullAway analysis, ExpressionTree expr, VisitorState state, boolean exprMayBeNull) {
    if (expr.getKind().equals(Tree.Kind.METHOD_INVOCATION)) {
      Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol((MethodInvocationTree) expr);
      ClassAnnotationInfo classAnnotationInfo = getClassAnnotationInfo(state.context);
      if (classAnnotationInfo.isSymbolUnannotated(methodSymbol, config)) {
        // with the generated-as-unannotated option enabled, we want to ignore
        // annotations in generated code
        if (config.treatGeneratedAsUnannotated()
            && classAnnotationInfo.isGenerated(methodSymbol, config)) {
          return exprMayBeNull;
        } else {
          return Nullness.hasNullableAnnotation(methodSymbol, config) || exprMayBeNull;
        }
      }
    }
    return exprMayBeNull;
  }

  @Nullable private ClassAnnotationInfo classAnnotationInfo;

  private ClassAnnotationInfo getClassAnnotationInfo(Context context) {
    if (classAnnotationInfo == null) {
      classAnnotationInfo = ClassAnnotationInfo.instance(context);
    }
    return classAnnotationInfo;
  }

  @Override
  public Nullness[] onOverrideMethodInvocationParametersNullability(
      Context context,
      Symbol.MethodSymbol methodSymbol,
      boolean isAnnotated,
      Nullness[] argumentPositionNullness) {
    if (isAnnotated) {
      // We ignore isAnnotated code here, since annotations in code considered isAnnotated are
      // already handled
      // by NullAway's core algorithm.
      return argumentPositionNullness;
    }
    for (int i = 0; i < methodSymbol.getParameters().size(); ++i) {
      if (Nullness.paramHasNonNullAnnotation(methodSymbol, i, config)) {
        argumentPositionNullness[i] = Nullness.NONNULL;
      } else if (Nullness.paramHasNullableAnnotation(methodSymbol, i, config)) {
        argumentPositionNullness[i] = Nullness.NULLABLE;
      }
    }
    return argumentPositionNullness;
  }

  @Override
  public Nullness onOverrideMethodInvocationReturnNullability(
      Symbol.MethodSymbol methodSymbol,
      VisitorState state,
      boolean isAnnotated,
      Nullness returnNullness) {
    // Note that, for the purposes of overriding/subtyping, either @Nullable or @NonNull
    // can be considered restrictive annotations, depending on whether the unannotated method
    // is overriding or being overridden.
    if (isAnnotated) {
      return returnNullness;
    }
    if (Nullness.hasNullableAnnotation(methodSymbol, config)) {
      return Nullness.NULLABLE;
    } else if (Nullness.hasNonNullAnnotation(methodSymbol, config)) {
      return Nullness.NONNULL;
    }
    return returnNullness;
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
    ClassAnnotationInfo classAnnotationInfo = getClassAnnotationInfo(context);
    // with the generated-as-unannotated option enabled, we want to ignore
    // annotations in generated code
    if (config.treatGeneratedAsUnannotated()
        && classAnnotationInfo.isGenerated(methodSymbol, config)) {
      return NullnessHint.UNKNOWN;
    }
    if (classAnnotationInfo.isSymbolUnannotated(methodSymbol, config)
        && Nullness.hasNullableAnnotation(methodSymbol, config)) {
      return NullnessHint.HINT_NULLABLE;
    }
    return NullnessHint.UNKNOWN;
  }
}
