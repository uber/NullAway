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
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import java.util.HashSet;
import java.util.Iterator;
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
import org.checkerframework.dataflow.cfg.node.Node;

public class EnsuresNonNullHandler extends BaseNoOpHandler {

  private static final String ANNOT_NAME = "EnsuresNonNull";
  private static final String THIS_NOTATION = "this.";

  /** This method verifies that the method adheres to any @EnsuresNonNull annotation. */
  @Override
  public void onMatchMethod(
      NullAway analysis, MethodTree tree, VisitorState state, Symbol.MethodSymbol methodSymbol) {
    Preconditions.checkNotNull(methodSymbol);
    boolean isValidAnnotation = checkAnnotationValidation(analysis, tree, state, methodSymbol);
    if (tree.getBody() != null) {
      Set<Element> elements =
          analysis
              .getNullnessAnalysis(state)
              .getNonnullFieldsOfReceiverAtExit(new TreePath(state.getPath(), tree), state.context);
      if (isValidAnnotation) {
        String fieldName = getFieldNameFromAnnotation(methodSymbol);
        // skip abstract methods
        boolean isValidLocalPostCondition = false;
        for (Element element : elements) {
          if (element.getSimpleName().toString().equals(fieldName)) {
            isValidLocalPostCondition = true;
            break;
          }
        }
        if (!isValidLocalPostCondition) {
          reportMatch(
              analysis,
              state,
              tree,
              "method: "
                  + methodSymbol
                  + " is annotated with @EnsuresNonNull annotation, it indicates that  field ["
                  + fieldName
                  + "] must be guaranteed to be nonnull at exit point and it does not");
        }
      }
      checkOverridingConditions(analysis, tree, methodSymbol, state, elements);
    }
  }

  private boolean checkAnnotationValidation(
      NullAway analysis, MethodTree tree, VisitorState state, Symbol.MethodSymbol methodSymbol) {
    String fieldName = getFieldNameFromAnnotation(methodSymbol);
    boolean supported = true;
    if (fieldName == null) {
      supported = false;
    } else {
      if (fieldName.equals("")) {
        // we should not allow useless ensuresNonnull annotations.
        reportMatch(
            analysis,
            state,
            tree,
            "empty ensuresNonnull is the default precondition for every method, please remove it.");
        supported = false;
      }
      if (fieldName.contains(".") && !fieldName.startsWith(THIS_NOTATION)) {
        reportMatch(
            analysis,
            state,
            tree,
            "currently @EnsuresNonnull supports only class fields of the method receiver.");
        supported = false;
      }
    }
    return supported;
  }

  private void checkOverridingConditions(
      NullAway analysis,
      MethodTree tree,
      Symbol.MethodSymbol methodSymbol,
      VisitorState state,
      Set<Element> elements) {
    Set<String> fields = getSuperMethodEnsuresNonNullFields(methodSymbol, state);
    for (Element element : elements) {
      fields.remove(element.getSimpleName().toString());
    }
    if (fields.size() == 0) {
      return;
    }
    StringBuilder errorMessage = new StringBuilder("Fields [");
    Iterator<String> iterator = fields.iterator();
    while (iterator.hasNext()) {
      errorMessage.append(iterator.next());
      if (iterator.hasNext()) {
        errorMessage.append(", ");
      }
    }
    errorMessage.append(
        "] are not guaranteed to be Nonnull at exit point and it violates the overriding rule since super methods are annotated with @EnsuresNonNull annotation.");
    reportMatch(analysis, state, tree, errorMessage.toString());
  }

  /**
   * On every method annotated with {@link com.uber.nullaway.qual.EnsuresNonNull}, this method,
   * injects the {@code Nonnull} value for the class field given in the {@code @EnsuresNonNull}
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
    String fieldName = getFieldNameFromAnnotation(methodSymbol);
    if (fieldName == null) {
      return super.onDataflowVisitMethodInvocation(
          node, types, context, inputs, thenUpdates, elseUpdates, bothUpdates);
    }
    if (fieldName.startsWith(THIS_NOTATION)) {
      fieldName = fieldName.substring(THIS_NOTATION.length());
    }
    Element field = getFieldFromClass(ASTHelpers.enclosingClass(methodSymbol), fieldName);
    Element receiver = null;
    Node receiverNode = node.getTarget().getReceiver();
    if (receiverNode != null) {
      receiver = ASTHelpers.getSymbol(receiverNode.getTree());
    }
    AccessPath accessPath = AccessPath.fromFieldAccess(field, receiver);
    bothUpdates.set(accessPath, Nullness.NONNULL);
    return super.onDataflowVisitMethodInvocation(
        node, types, context, inputs, thenUpdates, elseUpdates, bothUpdates);
  }

  private void reportMatch(
      NullAway analysis, VisitorState state, Tree errorLocTree, String message) {
    assert analysis != null && state != null;
    state.reportMatch(
        analysis
            .getErrorBuilder()
            .createErrorDescription(
                new ErrorMessage(ErrorMessage.MessageTypes.ANNOTATION_VALUE_INVALID, message),
                errorLocTree,
                buildDescriptionFromChecker(errorLocTree, analysis),
                state));
  }

  /**
   * Finds a specific field of a class
   *
   * @param classSymbol A class symbol.
   * @param name Name of the field.
   * @return The class field with the given name.
   */
  private static Element getFieldFromClass(Symbol.ClassSymbol classSymbol, String name) {
    Preconditions.checkNotNull(classSymbol);
    for (Element member : classSymbol.getEnclosedElements()) {
      if (member.getKind().isField()) {
        if (member.getSimpleName().toString().equals(name)) {
          return member;
        }
      }
    }
    throw new AssertionError(
        "cannot find field [" + name + "] in class: " + classSymbol.getSimpleName());
  }

  /**
   * Finds the set of fields that are given as param in {@link
   * com.uber.nullaway.qual.EnsuresNonNull} annotation through all super methods.
   *
   * @param methodSymbol A method symbol.
   * @param state Javac Visoitor State.
   * @return Set of fields name in {@code String}.
   */
  private static Set<String> getSuperMethodEnsuresNonNullFields(
      Symbol.MethodSymbol methodSymbol, VisitorState state) {
    Preconditions.checkNotNull(methodSymbol);
    Set<Symbol.MethodSymbol> superMethods =
        ASTHelpers.findSuperMethods(methodSymbol, state.getTypes());
    Set<String> fieldNames = new HashSet<>();
    for (Symbol.MethodSymbol superMethodSymbol : superMethods) {
      String field = getFieldNameFromAnnotation(superMethodSymbol);
      if (field != null) {
        fieldNames.add(field);
      }
    }
    return fieldNames;
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
      if (((TypeElement) element).getQualifiedName().toString().endsWith(ANNOT_NAME)) {
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
