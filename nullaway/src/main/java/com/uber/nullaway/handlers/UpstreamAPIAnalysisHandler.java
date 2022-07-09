package com.uber.nullaway.handlers;

/*
 * Copyright (c) 2022 Uber Technologies, Inc.
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

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import com.uber.nullaway.fixserialization.FixSerializationConfig;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;

/**
 * This handler is used to compute effects of making methods in upstream dependencies
 * {@code @Nullable} on downstream dependencies (current module). If enabled, NullAway will assume
 * all methods stored {@link
 * com.uber.nullaway.fixserialization.FixSerializationConfig#upstreamDependencyAPIInfoFile} file
 * returns {@code @Nullable}
 */
public class UpstreamAPIAnalysisHandler extends BaseNoOpHandler {

  private final FixSerializationConfig config;

  public UpstreamAPIAnalysisHandler(FixSerializationConfig config) {
    this.config = config;
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
    Symbol.MethodSymbol sym = ASTHelpers.getSymbol(node.getTree());
    String method = sym.toString();
    String clazz = sym.enclClass().toString();
    if (config.isUpstreamMethod(clazz, method)) {
      AccessPath path =
          AccessPath.fromBaseAndElement(node.getTarget().getReceiver(), sym, apContext);
      if (path != null) {
        bothUpdates.set(path, Nullness.NULLABLE);
      }
      return NullnessHint.HINT_NULLABLE;
    }
    return super.onDataflowVisitMethodInvocation(
        node, types, context, apContext, inputs, thenUpdates, elseUpdates, bothUpdates);
  }

  @Override
  public boolean onOverrideMayBeNullExpr(
      NullAway analysis, ExpressionTree expr, VisitorState state, boolean exprMayBeNull) {
    Symbol symbol = ASTHelpers.getSymbol(expr);
    if (expr instanceof MethodInvocationTree && symbol instanceof Symbol.MethodSymbol) {
      String method = symbol.toString();
      String clazz = symbol.enclClass().toString();
      return config.isUpstreamMethod(clazz, method);
    }
    return super.onOverrideMayBeNullExpr(analysis, expr, state, exprMayBeNull);
  }
}
