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

package com.uber.nullaway.handlers.condition;

import com.google.common.base.Preconditions;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import com.uber.nullaway.handlers.ConditionHandler;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;

public class EnsuresNonNullHandler extends ConditionHandler {

  @Override
  public void onMatchTopLevelClass(
      NullAway analysis, ClassTree tree, VisitorState state, Symbol.ClassSymbol classSymbol) {
    ANNOT_NAME = "EnsuresNonNull";
  }

  /**
   * Validates whether all parameters mentioned in the @EnsuresNonNull annotation are guaranteed to
   * be {@code @NonNull} at exit point of this method.
   */
  @Override
  protected boolean validateAnnotationSemantics(
      NullAway analysis, VisitorState state, MethodTree tree, Symbol.MethodSymbol methodSymbol) {
    // skip abstract methods
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
    Set<String> fieldNames = getFieldNamesFromAnnotation(methodSymbol);
    Preconditions.checkNotNull(fieldNames);
    fieldNames =
        fieldNames.stream().map(EnsuresNonNullHandler::trimFieldName).collect(Collectors.toSet());
    boolean isValidLocalPostCondition = nonnullFieldsOfReceiverAtExit.containsAll(fieldNames);
    if (!isValidLocalPostCondition) {
      reportMatch(
          analysis,
          state,
          tree,
          "method: "
              + methodSymbol
              + " is annotated with @EnsuresNonNull annotation, it indicates that all fields "
              + fieldNames
              + " must be guaranteed to be nonnull at exit point and it does not");
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
    Set<String> overriddenFieldNames = getFieldNamesFromAnnotation(overriddenMethod);
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
    reportMatch(analysis, state, tree, errorMessage.toString());
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
    Set<String> fieldNames = getFieldNamesFromAnnotation(methodSymbol);
    if (fieldNames == null) {
      return super.onDataflowVisitMethodInvocation(
          node, types, context, inputs, thenUpdates, elseUpdates, bothUpdates);
    }
    fieldNames =
        fieldNames.stream().map(EnsuresNonNullHandler::trimFieldName).collect(Collectors.toSet());
    for (String fieldName : fieldNames) {
      Element field = getFieldFromClass(ASTHelpers.enclosingClass(methodSymbol), fieldName);
      assert field != null
          : "cannot find field ["
              + fieldNames
              + "] in class: "
              + ASTHelpers.enclosingClass(methodSymbol).getSimpleName();
      Element receiver = null;
      Node receiverNode = node.getTarget().getReceiver();
      if (receiverNode != null) {
        receiver = ASTHelpers.getSymbol(receiverNode.getTree());
      }
      AccessPath accessPath = AccessPath.fromFieldAccess(field, receiver);
      bothUpdates.set(accessPath, Nullness.NONNULL);
    }
    return super.onDataflowVisitMethodInvocation(
        node, types, context, inputs, thenUpdates, elseUpdates, bothUpdates);
  }
}
