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

package com.uber.nullaway.handlers.contract.fieldcontract;

import com.google.common.base.Preconditions;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import com.uber.nullaway.handlers.AbstractFieldContractHandler;
import com.uber.nullaway.handlers.contract.ContractUtils;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;

/**
 * This Handler parses {@code @EnsuresNonNull} annotation and when the annotated method is invoked,
 * it injects the knowledge gained from the annotation to the data flow analysis. The following
 * tasks are performed when the {@code @EnsuresNonNull} annotation has observed:
 *
 * <ul>
 *   <li>It validates the syntax of the annotation.
 *   <li>It validates whether all fields specified in the annotation are guaranteed to be {@code
 *       Nonnull} at exit point of the method.
 *   <li>It validates whether the specified postcondition conforms to the overriding rules. Every
 *       methods postcondition must satisfy all postconditions of the super methods as well.
 * </ul>
 */
public class EnsuresNonNullHandler extends AbstractFieldContractHandler {

  @Override
  public void onMatchTopLevelClass(
      NullAway analysis, ClassTree tree, VisitorState state, Symbol.ClassSymbol classSymbol) {
    annotName = "EnsuresNonNull";
  }

  /**
   * Validates whether all parameters mentioned in the @EnsuresNonNull annotation are guaranteed to
   * be {@code @NonNull} at exit point of this method.
   */
  @Override
  protected boolean validateAnnotationSemantics(
      NullAway analysis, VisitorState state, MethodTree tree, Symbol.MethodSymbol methodSymbol) {
    String message;
    if (tree.getBody() == null) {
      message = "cannot annotate an abstract method with @EnsuresNonNull annotation";
      ContractUtils.reportMatch(
          tree, message, analysis, state, ErrorMessage.MessageTypes.ANNOTATION_VALUE_INVALID);
      return false;
    }
    Set<String> nonnullFieldsOfReceiverAtExit =
        analysis
            .getNullnessAnalysis(state)
            .getNonnullFieldsOfReceiverAtExit(new TreePath(state.getPath(), tree), state.context)
            .stream()
            .map(e -> e.getSimpleName().toString())
            .collect(Collectors.toSet());
    Set<String> fieldNames = ContractUtils.getFieldNamesFromAnnotation(methodSymbol, annotName);
    if (fieldNames == null) {
      fieldNames = Collections.emptySet();
    }
    fieldNames = ContractUtils.trimReceivers(fieldNames);
    boolean isValidLocalPostCondition = nonnullFieldsOfReceiverAtExit.containsAll(fieldNames);
    if (!isValidLocalPostCondition) {
      fieldNames.removeAll(nonnullFieldsOfReceiverAtExit);
      message =
          "method: "
              + methodSymbol
              + " is annotated with @EnsuresNonNull annotation, it indicates that all fields in the annotation parameter"
              + " must be guaranteed to be nonnull at exit point and it fails to do so for the fields: "
              + fieldNames;
      ContractUtils.reportMatch(
          tree, message, analysis, state, ErrorMessage.MessageTypes.POSTCONDITION_NOT_SATISFIED);
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
    Set<String> overriddenFieldNames =
        ContractUtils.getFieldNamesFromAnnotation(overriddenMethod, annotName);
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
    errorMessage.append(
        "postcondition inheritance is violated, this method must guarantee that all fields written in overridden method @EnsuresNonNull annotation are @NonNull at exit point as well. Fields [");
    Iterator<String> iterator = overriddenFieldNames.iterator();
    while (iterator.hasNext()) {
      errorMessage.append(iterator.next());
      if (iterator.hasNext()) {
        errorMessage.append(", ");
      }
    }
    errorMessage.append(
        "] must explicitly appear as parameters at this method @EnsuresNonNull annotation");
    ContractUtils.reportMatch(
        tree,
        errorMessage.toString(),
        analysis,
        state,
        ErrorMessage.MessageTypes.WRONG_OVERRIDE_POSTCONDITION);
  }

  /**
   * On every method annotated with {@link com.uber.nullaway.qual.EnsuresNonNull}, this method,
   * injects the {@code Nonnull} value for the class fields given in the {@code @EnsuresNonNull}
   * parameter to the dataflow analysis.
   */
  @Override
  public NullnessHint onDataflowVisitMethodInvocation(
      MethodInvocationNode node,
      Types types,
      Context context,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    if (node.getTree() == null) {
      return super.onDataflowVisitMethodInvocation(
          node, types, context, inputs, thenUpdates, elseUpdates, bothUpdates);
    }
    Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(node.getTree());
    Preconditions.checkNotNull(methodSymbol);
    Set<String> fieldNames = ContractUtils.getFieldNamesFromAnnotation(methodSymbol, annotName);
    if (fieldNames == null) {
      return super.onDataflowVisitMethodInvocation(
          node, types, context, inputs, thenUpdates, elseUpdates, bothUpdates);
    }
    fieldNames = ContractUtils.trimReceivers(fieldNames);
    for (String fieldName : fieldNames) {
      Element field = getFieldFromClass(ASTHelpers.enclosingClass(methodSymbol), fieldName);
      assert field != null
          : "cannot find field ["
              + fieldNames
              + "] in class: "
              + ASTHelpers.enclosingClass(methodSymbol).getSimpleName();
      List<Element> receivers = null;
      Node receiverNode = node.getTarget().getReceiver();
      if (receiverNode != null) {
        receivers = getReceiverTreeElements(receiverNode.getTree());
      }
      AccessPath accessPath = AccessPath.fromFieldAndBase(receivers, field);
      bothUpdates.set(accessPath, Nullness.NONNULL);
    }
    return super.onDataflowVisitMethodInvocation(
        node, types, context, inputs, thenUpdates, elseUpdates, bothUpdates);
  }
}
