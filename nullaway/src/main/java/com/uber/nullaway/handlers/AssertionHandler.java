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

import static com.uber.nullaway.Nullness.NONNULL;

import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import java.util.List;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.nullaway.dataflow.cfg.node.Node;

/** This Handler deals with assertions which ensure that their arguments cannot be null. */
public class AssertionHandler extends BaseNoOpHandler {

  private final MethodNameUtil methodNameUtil;

  AssertionHandler(MethodNameUtil methodNameUtil) {
    this.methodNameUtil = methodNameUtil;
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
    Symbol.MethodSymbol callee = ASTHelpers.getSymbol(node.getTree());
    if (callee == null) {
      return NullnessHint.UNKNOWN;
    }

    if (!methodNameUtil.isUtilInitialized()) {
      methodNameUtil.initializeMethodNames(callee.name.table);
    }

    // Look for statements of the form: assertThat(A).isNotNull()
    // A will not be NULL after this statement.
    if (methodNameUtil.isMethodIsNotNull(callee)) {
      Node receiver = node.getTarget().getReceiver();
      if (receiver instanceof MethodInvocationNode) {
        MethodInvocationNode receiver_method = (MethodInvocationNode) receiver;
        Symbol.MethodSymbol receiver_symbol = ASTHelpers.getSymbol(receiver_method.getTree());
        if (methodNameUtil.isMethodAssertThat(receiver_symbol)) {
          Node arg = receiver_method.getArgument(0);
          AccessPath ap = AccessPath.getAccessPathForNodeNoMapGet(arg, apContext);
          if (ap != null) {
            bothUpdates.set(ap, NONNULL);
          }
        }
      }
    }

    // Look for statements of the form:
    //    * assertThat(A, is(not(nullValue())))
    //    * assertThat(A, is(notNullValue()))
    if (methodNameUtil.isMethodHamcrestAssertThat(callee)
        || methodNameUtil.isMethodJunitAssertThat(callee)) {
      List<Node> args = node.getArguments();
      if (args.size() == 2 && methodNameUtil.isMatcherIsNotNull(args.get(1))) {
        AccessPath ap = AccessPath.getAccessPathForNodeNoMapGet(args.get(0), apContext);
        if (ap != null) {
          bothUpdates.set(ap, NONNULL);
        }
      }
    }

    return NullnessHint.UNKNOWN;
  }
}
