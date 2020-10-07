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

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.NullnessStore;
import com.uber.nullaway.handlers.ConditionHandler;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import org.checkerframework.dataflow.cfg.UnderlyingAST;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;

public class RequiresNonNullHandler extends ConditionHandler {

  @Override
  public void onMatchTopLevelClass(
      NullAway analysis, ClassTree tree, VisitorState state, Symbol.ClassSymbol classSymbol) {
    ANNOT_NAME = "RequiresNonNull";
  }

  /** All methods can add the precondition of {@code RequiresNonNull}. */
  @Override
  protected boolean validateAnnotationSemantics(
      NullAway analysis, VisitorState state, MethodTree tree, Symbol.MethodSymbol methodSymbol) {
    return true;
  }

  /**
   * All overriding methods can only weaken the precondition of its super method. All overriding
   * methods cannot add new field names to the set of fields of its super method mentioned in {@code
   * EnsuresNonNull}.
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
    Set<String> overriddenFieldNames = getFieldNamesFromAnnotation(overriddenMethod);
    if (overriddenFieldNames == null) {
      overriddenFieldNames = Collections.emptySet();
    }
    overriddenFieldNames =
        overriddenFieldNames
            .stream()
            .map(RequiresNonNullHandler::trimFieldName)
            .collect(Collectors.toSet());
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
    reportMatch(analysis, state, tree, errorMessage.toString());
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
    Set<String> fieldNames = getFieldNamesFromAnnotation(methodSymbol);
    if (fieldNames == null) {
      super.onMatchMethodInvocation(analysis, tree, state, methodSymbol);
      return;
    }
    fieldNames =
        fieldNames.stream().map(RequiresNonNullHandler::trimFieldName).collect(Collectors.toSet());
    for (String fieldName : fieldNames) {
      Symbol.ClassSymbol classSymbol = ASTHelpers.enclosingClass(methodSymbol);
      assert classSymbol != null
          : "can not find the enclosing class for method symbol: " + methodSymbol;
      Element receiver = null; // null receiver means (this) is the receiver.
      if (tree.getMethodSelect() instanceof MemberSelectTree) {
        MemberSelectTree memberTree = (MemberSelectTree) tree.getMethodSelect();
        if (memberTree != null) {
          receiver = ASTHelpers.getSymbol(memberTree.getExpression());
        }
      }
      Element field = getFieldFromClass(classSymbol, fieldName);
      assert field != null
          : "Could not find field: [" + fieldName + "]" + "for class: " + classSymbol;
      AccessPath accessPath = AccessPath.fromFieldAccess(field, receiver);
      Nullness nullness =
          analysis
              .getNullnessAnalysis(state)
              .getNullnessOfAccessPath(
                  new TreePath(state.getPath(), tree), state.context, accessPath);
      if (nullnessToBool(nullness)) {
        reportMatch(
            analysis,
            state,
            tree,
            "expected field [" + fieldName + "] is not non-null at call site.");
      }
    }
  }

  /**
   * On every method annotated with {@link com.uber.nullaway.qual.RequiresNonNull}, this method,
   * injects the {@code Nonnull} value for the class field given in the {@code @RequiresNonNull}
   * parameter to the dataflow analysis.
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
    Set<String> fieldNames = getFieldNamesFromAnnotation(ASTHelpers.getSymbol(methodTree));
    if (fieldNames == null) {
      return result;
    }
    fieldNames =
        fieldNames.stream().map(RequiresNonNullHandler::trimFieldName).collect(Collectors.toSet());
    for (String fieldName : fieldNames) {
      Element field = getFieldFromClass(ASTHelpers.getSymbol(classTree), fieldName);
      assert field != null
          : "Could not find field: [" + fieldName + "]" + "for class: " + classTree.getSimpleName();
      AccessPath accessPath = AccessPath.fromFieldAccess(field, null);
      result.setInformation(accessPath, Nullness.NONNULL);
    }
    return result;
  }
}
