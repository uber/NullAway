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
import java.util.List;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;

/** This Handler deals with assertions which ensure that their arguments cannot be null. */
public class AssertionHandler extends BaseNoOpHandler {

  // Strings corresponding to the names of the methods (and their owners) used to identify
  // assertions in this handler.
  private static final String IS_NOT_NULL_METHOD = "isNotNull";
  private static final String IS_NOT_NULL_OWNER = "com.google.common.truth.Subject";
  private static final String ASSERT_THAT_METHOD = "assertThat";
  private static final String ASSERT_THAT_OWNER = "com.google.common.truth.Truth";

  private static final String HAMCREST_ASSERT_CLASS = "org.hamcrest.MatcherAssert";
  private static final String JUNIT_ASSERT_CLASS = "org.junit.Assert";

  private static final String MATCHERS_CLASS = "org.hamcrest.Matchers";
  private static final String IS_MATCHER = "is";
  private static final String NOT_MATCHER = "not";
  private static final String NOT_NULL_VALUE_MATCHER = "notNullValue";
  private static final String NULL_VALUE_MATCHER = "nullValue";

  // Names of the methods (and their owners) used to identify assertions in this handler. Name used
  // here refers to com.sun.tools.javac.util.Name. Comparing methods using Names is faster than
  // comparing using strings.
  private Name isNotNull;
  private Name isNotNullOwner;
  private Name assertThat;
  private Name assertThatOwner;

  // Names for junit assertion libraries.
  private Name hamcrestAssertClass;
  private Name junitAssertClass;

  // Names for matchers.
  private Name matchersClass;
  private Name isMatcher;
  private Name notMatcher;
  private Name notNullValueMatcher;
  private Name nullValueMatcher;

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
      initializeMethodNames(callee.name.table);
    }

    // Look for statements of the form: assertThat(A).isNotNull()
    // A will not be NULL after this statement.
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

    // Look for statements of the form:
    //    * assertThat(A, is(not(nullValue())))
    //    * assertThat(A, is(notNullValue()))
    if (isMethodHamcrestAssertThat(callee) || isMethodJunitAssertThat(callee)) {
      List<Node> args = node.getArguments();
      if (args.size() == 2 && isMatcherIsNotNull(args.get(1))) {
        AccessPath ap = AccessPath.getAccessPathForNodeNoMapGet(args.get(0));
        if (ap != null) {
          bothUpdates.set(ap, NONNULL);
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

  private boolean isMethodHamcrestAssertThat(Symbol.MethodSymbol methodSymbol) {
    return matchesMethod(methodSymbol, assertThat, hamcrestAssertClass);
  }

  private boolean isMethodJunitAssertThat(Symbol.MethodSymbol methodSymbol) {
    return matchesMethod(methodSymbol, assertThat, junitAssertClass);
  }

  private boolean isMatcherIsNotNull(Node node) {
    // Matches with
    //   * is(not(nullValue()))
    //   * is(notNullValue())
    if (matchesMatcherMethod(node, isMatcher)) {
      // All overloads of `is` method have exactly one argument.
      return isMatcherNotNull(((MethodInvocationNode) node).getArgument(0));
    }
    return false;
  }

  private boolean isMatcherNotNull(Node node) {
    // Matches with
    //   * not(nullValue())
    //   * notNullValue()
    if (matchesMatcherMethod(node, notMatcher)) {
      // All overloads of `not` method have exactly one argument.
      return isMatcherNull(((MethodInvocationNode) node).getArgument(0));
    }
    return matchesMatcherMethod(node, notNullValueMatcher);
  }

  private boolean isMatcherNull(Node node) {
    // Matches with nullValue()
    return matchesMatcherMethod(node, nullValueMatcher);
  }

  private boolean matchesMatcherMethod(Node node, Name matcherName) {
    if (node instanceof MethodInvocationNode) {
      MethodInvocationNode methodInvocationNode = (MethodInvocationNode) node;
      Symbol.MethodSymbol callee = ASTHelpers.getSymbol(methodInvocationNode.getTree());
      return matchesMethod(callee, matcherName, matchersClass);
    }
    return false;
  }

  private boolean matchesMethod(
      Symbol.MethodSymbol methodSymbol, Name toMatchMethodName, Name toMatchOwnerName) {
    return methodSymbol.name.equals(toMatchMethodName)
        && methodSymbol.owner.getQualifiedName().equals(toMatchOwnerName);
  }

  private boolean areMethodNamesInitialized() {
    return isNotNull != null;
  }

  private void initializeMethodNames(Name.Table table) {
    isNotNull = table.fromString(IS_NOT_NULL_METHOD);
    isNotNullOwner = table.fromString(IS_NOT_NULL_OWNER);
    assertThat = table.fromString(ASSERT_THAT_METHOD);
    assertThatOwner = table.fromString(ASSERT_THAT_OWNER);

    hamcrestAssertClass = table.fromString(HAMCREST_ASSERT_CLASS);
    junitAssertClass = table.fromString(JUNIT_ASSERT_CLASS);

    matchersClass = table.fromString(MATCHERS_CLASS);
    isMatcher = table.fromString(IS_MATCHER);
    notMatcher = table.fromString(NOT_MATCHER);
    notNullValueMatcher = table.fromString(NOT_NULL_VALUE_MATCHER);
    nullValueMatcher = table.fromString(NULL_VALUE_MATCHER);
  }
}
