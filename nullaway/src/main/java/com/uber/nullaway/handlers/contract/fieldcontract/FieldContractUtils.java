/*
 * Copyright (c) 2024 Uber Technologies, Inc.
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
package com.uber.nullaway.handlers.contract.fieldcontract;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;
import static com.uber.nullaway.NullabilityUtil.getAnnotationValueArray;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import java.util.Collections;
import java.util.Set;

public class FieldContractUtils {

  /**
   * Checks that the fields mentioned in the annotation of the overridden method are also mentioned
   * in the annotation of the overriding method. If not, reports an error.
   *
   * @param annotName name of the annotation
   * @param overridingFieldNames set of fields mentioned in the overriding method's annotation
   * @param analysis NullAway instance
   * @param state the visitor state
   * @param tree tree for the overriding method
   * @param overriddenMethod the method that is being overridden
   */
  public static void ensureStrictPostConditionInheritance(
      String annotName,
      Set<String> overridingFieldNames,
      NullAway analysis,
      VisitorState state,
      MethodTree tree,
      Symbol.MethodSymbol overriddenMethod) {
    Set<String> overriddenFieldNames = getAnnotationValueArray(overriddenMethod, annotName, false);
    if (overriddenFieldNames == null) {
      return;
    }
    if (overridingFieldNames == null) {
      overridingFieldNames = Collections.emptySet();
    }
    if (overridingFieldNames.containsAll(overriddenFieldNames)) {
      return;
    }
    overriddenFieldNames.removeAll(overridingFieldNames);

    StringBuilder errorMessage = new StringBuilder();
    errorMessage
        .append(
            "postcondition inheritance is violated, this method must guarantee that all fields written in the @")
        .append(annotName)
        .append(" annotation of overridden method ")
        .append(castToNonNull(ASTHelpers.enclosingClass(overriddenMethod)).getSimpleName())
        .append(".")
        .append(overriddenMethod.getSimpleName())
        .append(" are @NonNull at exit point as well. Fields [")
        .append(String.join(", ", overriddenFieldNames))
        .append("] must explicitly appear as parameters at this method @")
        .append(annotName)
        .append(" annotation");

    state.reportMatch(
        analysis
            .getErrorBuilder()
            .createErrorDescription(
                new ErrorMessage(
                    ErrorMessage.MessageTypes.WRONG_OVERRIDE_POSTCONDITION,
                    errorMessage.toString()),
                tree,
                analysis.buildDescription(tree),
                state,
                null));
  }
}
