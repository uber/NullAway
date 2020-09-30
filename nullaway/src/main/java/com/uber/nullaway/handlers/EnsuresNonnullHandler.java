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

import static com.google.errorprone.BugCheckerInfo.buildDescriptionFromChecker;

import com.google.common.base.Preconditions;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;

public class EnsuresNonnullHandler extends BaseNoOpHandler {

  private static final String annotName = "com.uber.nullaway.qual.EnsuresNonnull";
  private static final String thisNotation = "this.";

  private @Nullable NullAway analysis;
  private @Nullable VisitorState state;

  @Override
  public void onMatchTopLevelClass(
      NullAway analysis, ClassTree tree, VisitorState state, Symbol.ClassSymbol classSymbol) {
    this.analysis = analysis;
    this.state = state;
  }

  @Override
  public void onMatchMethod(
      NullAway analysis, MethodTree tree, VisitorState state, Symbol.MethodSymbol methodSymbol) {
    Preconditions.checkNotNull(methodSymbol);
    String fieldName = getFieldNameFromAnnotation(methodSymbol);
    boolean supported = true;
    if (fieldName == null) {
      supported = false;
    } else {
      if (fieldName.equals("")) {
        // we should not allow useless ensuresNonnull annotations.
        reportMatch(
            tree,
            "empty ensuresNonnull is the default precondition for every method, please remove it.");
        supported = false;
      }
      if (fieldName.contains(".")) {
        if (!fieldName.startsWith(thisNotation)) {
          reportMatch(
              tree, "currently @EnsuresNonnull supports only class fields of the method receiver.");
          supported = false;
        } else {
          fieldName = fieldName.substring(thisNotation.length());
        }
      }
    }
    if (!supported) {
      super.onMatchMethod(analysis, tree, state, methodSymbol);
      return;
    }
    Set<Element> elements =
        analysis
            .getNullnessAnalysis(state)
            .getNonnullFieldsOfReceiverAtExit(new TreePath(state.getPath(), tree), state.context);
    boolean isValidPostCondition = false;
    for (Element element : elements) {
      if (element.getKind().isField() && element.getSimpleName().toString().equals(fieldName)) {
        isValidPostCondition = true;
      }
    }
    if (!isValidPostCondition) {
      reportMatch(
          tree,
          "field ["
              + fieldName
              + "] is not guaranteed to be nonnull at exit point of method: "
              + methodSymbol);
    }
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
    if (node.getTree() == null) {
      return super.onDataflowVisitMethodInvocation(
          node, types, context, inputs, thenUpdates, elseUpdates, bothUpdates);
    }
    Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(node.getTree());
    Preconditions.checkNotNull(methodSymbol);
    String fieldName = getFieldNameFromAnnotation(methodSymbol);
    if (fieldName == null) {
      return super.onDataflowVisitMethodInvocation(
          node, types, context, inputs, thenUpdates, elseUpdates, bothUpdates);
    }
    if (fieldName.startsWith(thisNotation)) {
      fieldName = fieldName.substring(thisNotation.length());
    }

    ClassTree classTree = findClassTree(ASTHelpers.enclosingClass(methodSymbol), context);
    VariableTree field = getFieldFromClass(classTree, fieldName);
    AccessPath accessPath =
        AccessPath.fromFieldAccessNode(ASTHelpers.getSymbol(field), node.getTarget().getReceiver());
    bothUpdates.set(accessPath, Nullness.NONNULL);
    return super.onDataflowVisitMethodInvocation(
        node, types, context, inputs, thenUpdates, elseUpdates, bothUpdates);
  }

  private void reportMatch(Tree errorLocTree, String message) {
    assert this.analysis != null && this.state != null;
    this.state.reportMatch(
        analysis
            .getErrorBuilder()
            .createErrorDescription(
                new ErrorMessage(ErrorMessage.MessageTypes.ANNOTATION_VALUE_INVALID, message),
                errorLocTree,
                buildDescriptionFromChecker(errorLocTree, analysis),
                this.state));
  }

  /**
   * Finds the corresponding {@link ClassTree} to a {@link Symbol.ClassSymbol}
   *
   * @param classSymbol A {@link Symbol.ClassSymbol}.
   * @param context Javac {@link Context}.
   * @return The corresponding {@link ClassTree}.
   */
  private ClassTree findClassTree(Symbol.ClassSymbol classSymbol, Context context) {
    return JavacTrees.instance(context).getTree(classSymbol);
  }

  /**
   * Finds a specific field of a class
   *
   * @param classTree A classTree.
   * @param name Name of the field.
   * @return The class field with the given name.
   */
  private static VariableTree getFieldFromClass(ClassTree classTree, String name) {
    Preconditions.checkNotNull(classTree);
    for (Tree member : classTree.getMembers()) {
      if (member.getKind().equals(Tree.Kind.VARIABLE)) {
        VariableTree field = (VariableTree) member;
        if (field.getName().toString().equals(name)) {
          return field;
        }
      }
    }
    throw new AssertionError(
        "cannot find field [" + name + "] in class: " + classTree.getSimpleName());
  }

  /**
   * Retrieve the string value inside an @EnsuresNonnull annotation without statically depending on
   * the type.
   *
   * @param sym A method which has an @EnsuresNonnull annotation.
   * @return The string value spec inside the annotation.
   */
  private static @Nullable String getFieldNameFromAnnotation(Symbol.MethodSymbol sym) {
    for (AnnotationMirror annotation : sym.getAnnotationMirrors()) {
      Element element = annotation.getAnnotationType().asElement();
      assert element.getKind().equals(ElementKind.ANNOTATION_TYPE);
      if (((TypeElement) element).getQualifiedName().contentEquals(annotName)) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e :
            annotation.getElementValues().entrySet()) {
          if (e.getKey().getSimpleName().contentEquals("value")) {
            String value = e.getValue().toString();
            if (value.startsWith("\"") && value.endsWith("\"")) {
              value = value.substring(1, value.length() - 1);
            }
            return value;
          }
        }
      }
    }
    return null;
  }
}
