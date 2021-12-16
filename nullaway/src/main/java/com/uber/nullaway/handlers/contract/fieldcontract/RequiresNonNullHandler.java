/*
 * Copyright (c) 2017-2020 Uber Technologies, Inc.
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

import static com.uber.nullaway.NullabilityUtil.getAnnotationValueArray;

import com.google.common.base.Preconditions;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.NullabilityUtil;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.annotations.RequiresNonNull;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.NullnessStore;
import com.uber.nullaway.handlers.AbstractFieldContractHandler;
import com.uber.nullaway.handlers.contract.ContractUtils;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.VariableElement;
import org.checkerframework.nullaway.dataflow.cfg.UnderlyingAST;
import org.checkerframework.nullaway.dataflow.cfg.node.LocalVariableNode;

/**
 * This Handler parses {@code @RequiresNonNull} annotation and when the annotated method is invoked,
 * it checks whether the specified class field are {@code @Nonnull} at call site. The following
 * tasks are performed when the {@code @EnsuresNonNull} annotation has observed:
 *
 * <ul>
 *   <li>It validates the syntax of the annotation.
 *   <li>It injects the {@code Nonnull} value for the specified fields to pars the method body
 *       because of the precondition assumption.
 *   <li>It validates whether the specified precondition conforms to the overriding rules. Every
 *       methods precondition cannot be stronger than it's super methods precondition.
 * </ul>
 */
public class RequiresNonNullHandler extends AbstractFieldContractHandler {

  public RequiresNonNullHandler() {
    super("RequiresNonNull");
  }

  /** All methods can add the precondition of {@code RequiresNonNull}. */
  @Override
  protected boolean validateAnnotationSemantics(
      NullAway analysis, VisitorState state, MethodTree tree, Symbol.MethodSymbol methodSymbol) {
    return true;
  }

  /**
   * All overriding methods can only weaken the precondition of its super method. No overriding
   * methods can add new field names to the set of fields of its super method mentioned in {@code
   * RequiresNonNull} annotation.
   */
  @Override
  protected void validateOverridingRules(
      Set<String> overridingFieldNames,
      NullAway analysis,
      VisitorState state,
      MethodTree tree,
      Symbol.MethodSymbol overriddenMethod) {
    if (overridingFieldNames == null) {
      return;
    }
    Set<String> overriddenFieldNames = getAnnotationValueArray(overriddenMethod, annotName, false);
    if (overriddenFieldNames == null) {
      overriddenFieldNames = Collections.emptySet();
    }
    overriddenFieldNames = ContractUtils.trimReceivers(overriddenFieldNames);
    if (overriddenFieldNames.containsAll(overridingFieldNames)) {
      return;
    }
    overridingFieldNames.removeAll(overriddenFieldNames);
    StringBuilder errorMessage = new StringBuilder();
    errorMessage.append(
        "precondition inheritance is violated, method in child class cannot have a stricter precondition than its closest overridden method, adding @requiresNonNull for fields [");
    Iterator<String> iterator = overriddenFieldNames.iterator();
    while (iterator.hasNext()) {
      errorMessage.append(iterator.next());
      if (iterator.hasNext()) {
        errorMessage.append(", ");
      }
    }
    errorMessage.append("] makes this method precondition stricter");

    state.reportMatch(
        analysis
            .getErrorBuilder()
            .createErrorDescription(
                new ErrorMessage(
                    ErrorMessage.MessageTypes.WRONG_OVERRIDE_PRECONDITION, errorMessage.toString()),
                tree,
                analysis.buildDescription(tree),
                state));
  }

  /**
   * It checks whether all class fields given in {@code RequiresNonNull} parameter are @NonNull at
   * call site.
   */
  @Override
  public void onMatchMethodInvocation(
      NullAway analysis,
      MethodInvocationTree tree,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol) {
    Set<String> fieldNames = getAnnotationValueArray(methodSymbol, annotName, false);
    if (fieldNames == null) {
      super.onMatchMethodInvocation(analysis, tree, state, methodSymbol);
      return;
    }
    fieldNames = ContractUtils.trimReceivers(fieldNames);
    for (String fieldName : fieldNames) {
      Symbol.ClassSymbol classSymbol = ASTHelpers.enclosingClass(methodSymbol);
      Preconditions.checkNotNull(
          classSymbol, "Could not find the enclosing class for method symbol: " + methodSymbol);
      VariableElement field = getInstanceFieldOfClass(classSymbol, fieldName);
      if (field == null) {
        // we will report an error on the method declaration
        continue;
      }
      ExpressionTree methodSelectTree = tree.getMethodSelect();
      Nullness nullness =
          analysis
              .getNullnessAnalysis(state)
              .getNullnessOfFieldForReceiverTree(
                  state.getPath(), state.context, methodSelectTree, field, true);
      if (NullabilityUtil.nullnessToBool(nullness)) {
        String message = "Expected field " + fieldName + " to be non-null at call site";

        state.reportMatch(
            analysis
                .getErrorBuilder()
                .createErrorDescription(
                    new ErrorMessage(ErrorMessage.MessageTypes.PRECONDITION_NOT_SATISFIED, message),
                    tree,
                    analysis.buildDescription(tree),
                    state));
      }
    }
  }

  /**
   * On every method annotated with {@link RequiresNonNull}, this method, injects the {@code
   * Nonnull} value for the class field given in the {@code @RequiresNonNull} parameter to the
   * dataflow analysis.
   */
  @Override
  public NullnessStore.Builder onDataflowInitialStore(
      UnderlyingAST underlyingAST,
      List<LocalVariableNode> parameters,
      NullnessStore.Builder result) {
    if (!(underlyingAST instanceof UnderlyingAST.CFGMethod)) {
      return super.onDataflowInitialStore(underlyingAST, parameters, result);
    }
    MethodTree methodTree = ((UnderlyingAST.CFGMethod) underlyingAST).getMethod();
    ClassTree classTree = ((UnderlyingAST.CFGMethod) underlyingAST).getClassTree();
    Set<String> fieldNames =
        getAnnotationValueArray(ASTHelpers.getSymbol(methodTree), annotName, false);
    if (fieldNames == null) {
      return result;
    }
    fieldNames = ContractUtils.trimReceivers(fieldNames);
    for (String fieldName : fieldNames) {
      VariableElement field = getInstanceFieldOfClass(ASTHelpers.getSymbol(classTree), fieldName);
      if (field == null) {
        // Invalid annotation, will result in an error during validation. For now, skip field.
        continue;
      }
      AccessPath accessPath = AccessPath.fromFieldElement(field);
      result.setInformation(accessPath, Nullness.NONNULL);
    }
    return result;
  }
}
