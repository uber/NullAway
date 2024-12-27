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

import static com.uber.nullaway.NullabilityUtil.castToNonNull;
import static com.uber.nullaway.NullabilityUtil.getAnnotationValueArray;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.annotations.EnsuresNonNull;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import com.uber.nullaway.handlers.AbstractFieldContractHandler;
import com.uber.nullaway.handlers.MethodAnalysisContext;
import com.uber.nullaway.handlers.contract.ContractUtils;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;

/**
 * This Handler parses {@code @EnsuresNonNull} annotation and when the annotated method is invoked,
 * it injects the knowledge gained from the annotation to the data flow analysis. The following
 * tasks are performed when the {@code @EnsuresNonNull} annotation is observed:
 *
 * <ul>
 *   <li>It validates the syntax of the annotation.
 *   <li>It validates whether all fields specified in the annotation are guaranteed to be {@code
 *       Nonnull} at exit point of the method.
 *   <li>It validates whether the specified postcondition conforms to the overriding rules. It must
 *       satisfy all postconditions of the overridden method as well.
 * </ul>
 */
public class EnsuresNonNullHandler extends AbstractFieldContractHandler {

  public EnsuresNonNullHandler() {
    super("EnsuresNonNull");
  }

  /**
   * Validates whether all parameters mentioned in the @EnsuresNonNull annotation are guaranteed to
   * be {@code @NonNull} at exit point of this method.
   */
  @Override
  protected boolean validateAnnotationSemantics(
      MethodTree tree, MethodAnalysisContext methodAnalysisContext) {
    NullAway analysis = methodAnalysisContext.analysis();
    VisitorState state = methodAnalysisContext.state();
    Symbol.MethodSymbol methodSymbol = methodAnalysisContext.methodSymbol();
    String message;
    if (tree.getBody() == null) {
      return true;
    }
    Set<String> nonnullFieldsOfReceiverAtExit =
        analysis
            .getNullnessAnalysis(state)
            .getNonnullFieldsOfReceiverAtExit(new TreePath(state.getPath(), tree), state.context)
            .stream()
            .map(e -> e.getSimpleName().toString())
            .collect(Collectors.toSet());
    Set<String> nonnullStaticFieldsAtExit =
        analysis
            .getNullnessAnalysis(state)
            .getNonnullStaticFieldsAtExit(new TreePath(state.getPath(), tree), state.context)
            .stream()
            .map(e -> e.getSimpleName().toString())
            .collect(Collectors.toSet());
    Set<String> nonnullFieldsAtExit = new HashSet<String>();
    nonnullFieldsAtExit.addAll(nonnullFieldsOfReceiverAtExit);
    nonnullFieldsAtExit.addAll(nonnullStaticFieldsAtExit);
    Set<String> fieldNames = getAnnotationValueArray(methodSymbol, annotName, false);
    if (fieldNames == null) {
      fieldNames = Collections.emptySet();
    }
    fieldNames = ContractUtils.trimReceivers(fieldNames);

    nonnullFieldsAtExit = ContractUtils.trimReceivers(nonnullFieldsAtExit);
    boolean isValidLocalPostCondition = nonnullFieldsAtExit.containsAll(fieldNames);
    if (!isValidLocalPostCondition) {
      fieldNames.removeAll(nonnullFieldsAtExit);
      message =
          String.format(
              "Method is annotated with @EnsuresNonNull but fails to ensure the following fields are non-null at exit: %s",
              fieldNames);
      state.reportMatch(
          analysis
              .getErrorBuilder()
              .createErrorDescription(
                  new ErrorMessage(ErrorMessage.MessageTypes.POSTCONDITION_NOT_SATISFIED, message),
                  tree,
                  analysis.buildDescription(tree),
                  state,
                  null));
      return false;
    }
    return true;
  }

  /**
   * All overriding methods can only strengthen the postcondition of its super method. All
   * overriding methods can only add new field names to the set of fields of its super method
   * mentioned in {@code EnsuresNonNull}.
   */
  @Override
  protected void validateOverridingRules(
      Set<String> overridingFieldNames,
      NullAway analysis,
      VisitorState state,
      MethodTree tree,
      Symbol.MethodSymbol overriddenMethod) {
    FieldContractUtils.ensureStrictPostConditionInheritance(
        annotName, overridingFieldNames, analysis, state, tree, overriddenMethod);
  }

  /**
   * On every method annotated with {@link EnsuresNonNull}, this method, injects the {@code Nonnull}
   * value for the class fields given in the {@code @EnsuresNonNull} parameter to the dataflow
   * analysis.
   */
  @Override
  public NullnessHint onDataflowVisitMethodInvocation(
      MethodInvocationNode node,
      Symbol.MethodSymbol methodSymbol,
      VisitorState state,
      AccessPath.AccessPathContext apContext,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    if (node.getTree() == null) {
      // A synthetic node might be inserted by the Checker Framework during CFG construction, it is
      // safer to do a null check here.
      return super.onDataflowVisitMethodInvocation(
          node, methodSymbol, state, apContext, inputs, thenUpdates, elseUpdates, bothUpdates);
    }
    Set<String> fieldNames = getAnnotationValueArray(methodSymbol, annotName, false);
    if (fieldNames != null) {
      fieldNames = ContractUtils.trimReceivers(fieldNames);
      for (String fieldName : fieldNames) {
        VariableElement field =
            getFieldOfClass(castToNonNull(ASTHelpers.enclosingClass(methodSymbol)), fieldName);
        if (field == null) {
          // Invalid annotation, will result in an error during validation. For now, skip field.
          continue;
        }
        AccessPath accessPath =
            field.getModifiers().contains(Modifier.STATIC)
                ? AccessPath.fromStaticField(field)
                : AccessPath.fromBaseAndElement(node.getTarget().getReceiver(), field, apContext);
        if (accessPath == null) {
          continue;
        }
        bothUpdates.set(accessPath, Nullness.NONNULL);
      }
    }
    return super.onDataflowVisitMethodInvocation(
        node, methodSymbol, state, apContext, inputs, thenUpdates, elseUpdates, bothUpdates);
  }
}
