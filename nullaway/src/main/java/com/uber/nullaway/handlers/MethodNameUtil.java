package com.uber.nullaway.handlers;

/*
 * Copyright (c) 2019 Uber Technologies, Inc.
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

import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.util.Name;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.nullaway.dataflow.cfg.node.Node;

/**
 * A utility class that holds the names from the Table. Currently, {@link
 * com.uber.nullaway.handlers.AssertionHandler} requires it, while {@link
 * com.uber.nullaway.handlers.OptionalEmptinessHandler} uses it only when AssertionHandler is
 * enabled.
 */
class MethodNameUtil {

  // Strings corresponding to the names of the methods (and their owners) used to identify
  // assertions in this handler.
  private static final String IS_NOT_NULL_METHOD = "isNotNull";
  private static final String IS_NOT_NULL_OWNER = "com.google.common.truth.Subject";
  private static final String IS_TRUE_METHOD = "isTrue";
  private static final String IS_TRUE_OWNER = "com.google.common.truth.BooleanSubject";
  private static final String ASSERT_THAT_METHOD = "assertThat";
  private static final String ASSERT_THAT_OWNER = "com.google.common.truth.Truth";

  private static final String HAMCREST_ASSERT_CLASS = "org.hamcrest.MatcherAssert";
  private static final String JUNIT_ASSERT_CLASS = "org.junit.Assert";

  private static final String MATCHERS_CLASS = "org.hamcrest.Matchers";
  private static final String CORE_MATCHERS_CLASS = "org.hamcrest.CoreMatchers";
  private static final String CORE_IS_NULL_CLASS = "org.hamcrest.core.IsNull";
  private static final String IS_MATCHER = "is";
  private static final String NOT_MATCHER = "not";
  private static final String NOT_NULL_VALUE_MATCHER = "notNullValue";
  private static final String NULL_VALUE_MATCHER = "nullValue";

  // Names of the methods (and their owners) used to identify assertions in this handler. Name used
  // here refers to com.sun.tools.javac.util.Name. Comparing methods using Names is faster than
  // comparing using strings.
  private Name isNotNull;
  private Name isNotNullOwner;

  private Name isTrue;
  private Name isTrueOwner;

  private Name assertThat;
  private Name assertThatOwner;

  // Names for junit assertion libraries.
  private Name hamcrestAssertClass;
  private Name junitAssertClass;

  // Names for hamcrest matchers.
  private Name matchersClass;
  private Name coreMatchersClass;
  private Name coreIsNullClass;
  private Name isMatcher;
  private Name notMatcher;
  private Name notNullValueMatcher;
  private Name nullValueMatcher;

  void initializeMethodNames(Name.Table table) {
    isNotNull = table.fromString(IS_NOT_NULL_METHOD);
    isNotNullOwner = table.fromString(IS_NOT_NULL_OWNER);

    isTrue = table.fromString(IS_TRUE_METHOD);
    isTrueOwner = table.fromString(IS_TRUE_OWNER);

    assertThat = table.fromString(ASSERT_THAT_METHOD);
    assertThatOwner = table.fromString(ASSERT_THAT_OWNER);

    hamcrestAssertClass = table.fromString(HAMCREST_ASSERT_CLASS);
    junitAssertClass = table.fromString(JUNIT_ASSERT_CLASS);

    matchersClass = table.fromString(MATCHERS_CLASS);
    coreMatchersClass = table.fromString(CORE_MATCHERS_CLASS);
    coreIsNullClass = table.fromString(CORE_IS_NULL_CLASS);
    isMatcher = table.fromString(IS_MATCHER);
    notMatcher = table.fromString(NOT_MATCHER);
    notNullValueMatcher = table.fromString(NOT_NULL_VALUE_MATCHER);
    nullValueMatcher = table.fromString(NULL_VALUE_MATCHER);
  }

  boolean isMethodIsNotNull(Symbol.MethodSymbol methodSymbol) {
    return matchesMethod(methodSymbol, isNotNull, isNotNullOwner);
  }

  boolean isMethodIsTrue(Symbol.MethodSymbol methodSymbol) {
    return matchesMethod(methodSymbol, isTrue, isTrueOwner);
  }

  boolean isMethodAssertThat(Symbol.MethodSymbol methodSymbol) {
    return matchesMethod(methodSymbol, assertThat, assertThatOwner);
  }

  boolean isMethodHamcrestAssertThat(Symbol.MethodSymbol methodSymbol) {
    return matchesMethod(methodSymbol, assertThat, hamcrestAssertClass);
  }

  boolean isMethodJunitAssertThat(Symbol.MethodSymbol methodSymbol) {
    return matchesMethod(methodSymbol, assertThat, junitAssertClass);
  }

  boolean isMatcherIsNotNull(Node node) {
    // Matches with
    //   * is(not(nullValue()))
    //   * is(notNullValue())
    if (matchesMatcherMethod(node, isMatcher, matchersClass)
        || matchesMatcherMethod(node, isMatcher, coreMatchersClass)) {
      // All overloads of `is` method have exactly one argument.
      return isMatcherNotNull(((MethodInvocationNode) node).getArgument(0));
    }
    return false;
  }

  private boolean isMatcherNotNull(Node node) {
    // Matches with
    //   * not(nullValue())
    //   * notNullValue()
    if (matchesMatcherMethod(node, notMatcher, matchersClass)
        || matchesMatcherMethod(node, notMatcher, coreMatchersClass)) {
      // All overloads of `not` method have exactly one argument.
      return isMatcherNull(((MethodInvocationNode) node).getArgument(0));
    }
    return matchesMatcherMethod(node, notNullValueMatcher, matchersClass)
        || matchesMatcherMethod(node, notNullValueMatcher, coreMatchersClass)
        || matchesMatcherMethod(node, notNullValueMatcher, coreIsNullClass);
  }

  private boolean isMatcherNull(Node node) {
    // Matches with nullValue()
    return matchesMatcherMethod(node, nullValueMatcher, matchersClass)
        || matchesMatcherMethod(node, nullValueMatcher, coreMatchersClass)
        || matchesMatcherMethod(node, nullValueMatcher, coreIsNullClass);
  }

  private boolean matchesMatcherMethod(Node node, Name matcherName, Name matcherClass) {
    if (node instanceof MethodInvocationNode) {
      MethodInvocationNode methodInvocationNode = (MethodInvocationNode) node;
      Symbol.MethodSymbol callee = ASTHelpers.getSymbol(methodInvocationNode.getTree());
      return matchesMethod(callee, matcherName, matcherClass);
    }
    return false;
  }

  private boolean matchesMethod(
      Symbol.MethodSymbol methodSymbol, Name toMatchMethodName, Name toMatchOwnerName) {
    return methodSymbol.name.equals(toMatchMethodName)
        && methodSymbol.owner.getQualifiedName().equals(toMatchOwnerName);
  }

  boolean isUtilInitialized() {
    return isNotNull != null;
  }
}
