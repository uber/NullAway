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
import com.uber.nullaway.annotations.Initializer;
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
  private static final String IS_OWNER_TRUTH_SUBJECT = "com.google.common.truth.Subject";
  private static final String IS_OWNER_ASSERTJ_ABSTRACT_ASSERT =
      "org.assertj.core.api.AbstractAssert";
  private static final String IS_INSTANCE_OF_METHOD = "isInstanceOf";
  private static final String IS_INSTANCE_OF_ANY_METHOD = "isInstanceOfAny";
  private static final String IS_TRUE_METHOD = "isTrue";
  private static final String IS_FALSE_METHOD = "isFalse";
  private static final String IS_TRUE_OWNER_TRUTH = "com.google.common.truth.BooleanSubject";
  private static final String IS_TRUE_OWNER_ASSERTJ = "org.assertj.core.api.AbstractBooleanAssert";
  private static final String BOOLEAN_VALUE_OF_METHOD = "valueOf";
  private static final String BOOLEAN_VALUE_OF_OWNER = "java.lang.Boolean";
  private static final String IS_PRESENT_METHOD = "isPresent";
  private static final String IS_NOT_EMPTY_METHOD = "isNotEmpty";
  private static final String IS_PRESENT_OWNER_ASSERTJ =
      "org.assertj.core.api.AbstractOptionalAssert";
  private static final String ASSERT_THAT_METHOD = "assertThat";
  private static final String ASSERT_THAT_OWNER_TRUTH = "com.google.common.truth.Truth";
  private static final String ASSERT_THAT_OWNER_ASSERTJ = "org.assertj.core.api.Assertions";

  private static final String HAMCREST_ASSERT_CLASS = "org.hamcrest.MatcherAssert";
  private static final String JUNIT_ASSERT_CLASS = "org.junit.Assert";
  private static final String JUNIT5_ASSERTION_CLASS = "org.junit.jupiter.api.Assertions";

  private static final String ASSERT_TRUE_METHOD = "assertTrue";
  private static final String ASSERT_FALSE_METHOD = "assertFalse";

  private static final String MATCHERS_CLASS = "org.hamcrest.Matchers";
  private static final String CORE_MATCHERS_CLASS = "org.hamcrest.CoreMatchers";
  private static final String CORE_IS_NULL_CLASS = "org.hamcrest.core.IsNull";
  private static final String IS_MATCHER = "is";
  private static final String IS_A_MATCHER = "isA";
  private static final String NOT_MATCHER = "not";
  private static final String NOT_NULL_VALUE_MATCHER = "notNullValue";
  private static final String NULL_VALUE_MATCHER = "nullValue";
  private static final String INSTANCE_OF_MATCHER = "instanceOf";

  // Names of the methods (and their owners) used to identify assertions in this handler. Name used
  // here refers to com.sun.tools.javac.util.Name. Comparing methods using Names is faster than
  // comparing using strings.
  private Name isNotNull;

  private Name isInstanceOf;
  private Name isInstanceOfAny;
  private Name isOwnerTruthSubject;
  private Name isOwnerAssertJAbstractAssert;

  private Name isTrue;
  private Name isFalse;
  private Name isTrueOwnerTruth;
  private Name isTrueOwnerAssertJ;
  private Name isPresent;
  private Name isNotEmpty;
  private Name isPresentOwnerAssertJ;

  private Name isBooleanValueOfMethod;
  private Name isBooleanValueOfOwner;

  private Name assertThat;
  private Name assertThatOwnerTruth;
  private Name assertThatOwnerAssertJ;

  // Names for junit assertion libraries.
  private Name hamcrestAssertClass;
  private Name junitAssertClass;
  private Name junit5AssertionClass;

  private Name assertTrue;
  private Name assertFalse;

  // Names for hamcrest matchers.
  private Name matchersClass;
  private Name coreMatchersClass;
  private Name coreIsNullClass;
  private Name isMatcher;
  private Name isAMatcher;
  private Name notMatcher;
  private Name notNullValueMatcher;
  private Name nullValueMatcher;
  private Name instanceOfMatcher;

  @Initializer
  void initializeMethodNames(Name.Table table) {
    isNotNull = table.fromString(IS_NOT_NULL_METHOD);
    isOwnerTruthSubject = table.fromString(IS_OWNER_TRUTH_SUBJECT);
    isOwnerAssertJAbstractAssert = table.fromString(IS_OWNER_ASSERTJ_ABSTRACT_ASSERT);

    isInstanceOf = table.fromString(IS_INSTANCE_OF_METHOD);
    isInstanceOfAny = table.fromString(IS_INSTANCE_OF_ANY_METHOD);

    isTrue = table.fromString(IS_TRUE_METHOD);
    isFalse = table.fromString(IS_FALSE_METHOD);
    isTrueOwnerTruth = table.fromString(IS_TRUE_OWNER_TRUTH);
    isTrueOwnerAssertJ = table.fromString(IS_TRUE_OWNER_ASSERTJ);

    isBooleanValueOfMethod = table.fromString(BOOLEAN_VALUE_OF_METHOD);
    isBooleanValueOfOwner = table.fromString(BOOLEAN_VALUE_OF_OWNER);

    assertThat = table.fromString(ASSERT_THAT_METHOD);
    assertThatOwnerTruth = table.fromString(ASSERT_THAT_OWNER_TRUTH);
    assertThatOwnerAssertJ = table.fromString(ASSERT_THAT_OWNER_ASSERTJ);

    isPresent = table.fromString(IS_PRESENT_METHOD);
    isNotEmpty = table.fromString(IS_NOT_EMPTY_METHOD);
    isPresentOwnerAssertJ = table.fromString(IS_PRESENT_OWNER_ASSERTJ);

    hamcrestAssertClass = table.fromString(HAMCREST_ASSERT_CLASS);
    junitAssertClass = table.fromString(JUNIT_ASSERT_CLASS);
    junit5AssertionClass = table.fromString(JUNIT5_ASSERTION_CLASS);

    assertTrue = table.fromString(ASSERT_TRUE_METHOD);
    assertFalse = table.fromString(ASSERT_FALSE_METHOD);

    matchersClass = table.fromString(MATCHERS_CLASS);
    coreMatchersClass = table.fromString(CORE_MATCHERS_CLASS);
    coreIsNullClass = table.fromString(CORE_IS_NULL_CLASS);
    isMatcher = table.fromString(IS_MATCHER);
    isAMatcher = table.fromString(IS_A_MATCHER);
    notMatcher = table.fromString(NOT_MATCHER);
    notNullValueMatcher = table.fromString(NOT_NULL_VALUE_MATCHER);
    nullValueMatcher = table.fromString(NULL_VALUE_MATCHER);
    instanceOfMatcher = table.fromString(INSTANCE_OF_MATCHER);
  }

  boolean isMethodIsNotNull(Symbol.MethodSymbol methodSymbol) {
    return matchesMethod(methodSymbol, isNotNull, isOwnerTruthSubject)
        || matchesMethod(methodSymbol, isNotNull, isOwnerAssertJAbstractAssert);
  }

  boolean isMethodIsInstanceOf(Symbol.MethodSymbol methodSymbol) {
    return matchesMethod(methodSymbol, isInstanceOf, isOwnerTruthSubject)
        || matchesMethod(methodSymbol, isInstanceOf, isOwnerAssertJAbstractAssert)
        // Truth doesn't seem to have isInstanceOfAny
        || matchesMethod(methodSymbol, isInstanceOfAny, isOwnerAssertJAbstractAssert);
  }

  boolean isMethodAssertTrue(Symbol.MethodSymbol methodSymbol) {
    return matchesMethod(methodSymbol, assertTrue, junitAssertClass)
        || matchesMethod(methodSymbol, assertTrue, junit5AssertionClass);
  }

  boolean isMethodAssertFalse(Symbol.MethodSymbol methodSymbol) {
    return matchesMethod(methodSymbol, assertFalse, junitAssertClass)
        || matchesMethod(methodSymbol, assertFalse, junit5AssertionClass);
  }

  boolean isMethodThatEnsuresOptionalPresent(Symbol.MethodSymbol methodSymbol) {
    // same owner
    return matchesMethod(methodSymbol, isPresent, isPresentOwnerAssertJ)
        || matchesMethod(methodSymbol, isNotEmpty, isPresentOwnerAssertJ);
  }

  boolean isMethodIsTrue(Symbol.MethodSymbol methodSymbol) {
    return matchesMethod(methodSymbol, isTrue, isTrueOwnerTruth)
        || matchesMethod(methodSymbol, isTrue, isTrueOwnerAssertJ);
  }

  boolean isMethodIsFalse(Symbol.MethodSymbol methodSymbol) {
    // same owners as isTrue
    return matchesMethod(methodSymbol, isFalse, isTrueOwnerTruth)
        || matchesMethod(methodSymbol, isFalse, isTrueOwnerAssertJ);
  }

  boolean isMethodBooleanValueOf(Symbol.MethodSymbol methodSymbol) {
    return matchesMethod(methodSymbol, isBooleanValueOfMethod, isBooleanValueOfOwner);
  }

  boolean isMethodAssertThat(Symbol.MethodSymbol methodSymbol) {
    return matchesMethod(methodSymbol, assertThat, assertThatOwnerTruth)
        || matchesMethod(methodSymbol, assertThat, assertThatOwnerAssertJ);
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

  boolean isMatcherIsInstanceOf(Node node) {
    // Matches with
    //   * is(instanceOf(Some.class))
    //   * isA(Some.class)
    if (matchesMatcherMethod(node, isMatcher, matchersClass)
        || matchesMatcherMethod(node, isMatcher, coreMatchersClass)) {
      // All overloads of `is` method have exactly one argument.
      Node inner = ((MethodInvocationNode) node).getArgument(0);
      return matchesMatcherMethod(inner, instanceOfMatcher, matchersClass)
          || matchesMatcherMethod(inner, instanceOfMatcher, coreMatchersClass);
    }
    return (matchesMatcherMethod(node, isAMatcher, matchersClass)
        || matchesMatcherMethod(node, isAMatcher, coreMatchersClass));
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
