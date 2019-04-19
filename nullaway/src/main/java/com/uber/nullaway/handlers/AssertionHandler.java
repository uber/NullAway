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
import com.sun.tools.javac.util.Name;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;

/** This Handler deals with assertions which ensure that their arguments cannot be null. */
public class AssertionHandler extends BaseNoOpHandler {
  public AssertionHandler() {
    super();
  }

  @Override
  public NullnessHint onDataflowVisitMethodInvocation(
      MethodInvocationNode node,
      Types types,
      Context context,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    Symbol.MethodSymbol callee = ASTHelpers.getSymbol(node.getTree());
    if (callee == null) {
      return NullnessHint.UNKNOWN;
    }

    if (!areMethodNamesInitialized()) {
      initializeMethodNames(callee);
    }

    /**
     * Look for statements of the form: assertThat(A).isNotNull()
     *
     * <p>A will not be NULL after this statement.
     */
    if (isMethodIsNotNull(callee)) {
      Node receiver = node.getTarget().getReceiver();
      if (receiver instanceof MethodInvocationNode) {
        MethodInvocationNode receiver_method = (MethodInvocationNode) receiver;
        Symbol.MethodSymbol receiver_symbol = ASTHelpers.getSymbol(receiver_method.getTree());
        if (isMethodAssertThat(receiver_symbol)) {
          Node arg = receiver_method.getArgument(0);
          AccessPath ap = AccessPath.getAccessPathForNodeNoMapGet(arg);
          if (ap != null) {
            bothUpdates.set(ap, NONNULL);
          }
        }
      }
    }
    return NullnessHint.UNKNOWN;
  }

  private boolean isMethodIsNotNull(Symbol.MethodSymbol methodSymbol) {
    return matchesMethod(methodSymbol, isNotNull, isNotNullOwner);
  }

  private boolean isMethodAssertThat(Symbol.MethodSymbol methodSymbol) {
    return matchesMethod(methodSymbol, assertThat, assertThatOwner);
  }

  private boolean matchesMethod(
      Symbol.MethodSymbol methodSymbol, Name toMatchMethodName, Name toMatchOwnerName) {
    return methodSymbol.name.equals(toMatchMethodName)
        && methodSymbol.owner.getQualifiedName().equals(toMatchOwnerName);
  }

  private boolean areMethodNamesInitialized() {
    return isNotNull != null;
  }

  private synchronized void initializeMethodNames(Symbol.MethodSymbol methodSymbol) {
    isNotNull = methodSymbol.name.table.fromString(IS_NOT_NULL_METHOD);
    isNotNullOwner = methodSymbol.name.table.fromString(IS_NOT_NULL_OWNER);
    assertThat = methodSymbol.name.table.fromString(ASSERT_THAT_METHOD);
    assertThatOwner = methodSymbol.name.table.fromString(ASSERT_THAT_OWNER);
  }

  /**
   * Strings corresponding to the names of the methods (and their owners) used to identify
   * assertions in this handler.
   */
  private static final String IS_NOT_NULL_METHOD = "isNotNull";

  private static final String IS_NOT_NULL_OWNER = "com.google.common.truth.Subject";
  private static final String ASSERT_THAT_METHOD = "assertThat";
  private static final String ASSERT_THAT_OWNER = "com.google.common.truth.Truth";

  /**
   * Names of the methods (and their owners) used to identify assertions in this handler. Name used
   * here refers to com.sun.tools.javac.util.Name. Comparing methods using Names is faster than
   * comparing using strings.
   */
  private Name isNotNull;

  private Name isNotNullOwner;
  private Name assertThat;
  private Name assertThatOwner;
}
