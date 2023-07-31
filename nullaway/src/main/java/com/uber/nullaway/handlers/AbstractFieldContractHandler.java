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

package com.uber.nullaway.handlers;

import static com.uber.nullaway.ASTHelpersBackports.getEnclosedElements;
import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.common.base.Preconditions;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.NullabilityUtil;
import com.uber.nullaway.handlers.contract.ContractUtils;
import java.util.Collections;
import java.util.Set;
import javax.annotation.Nullable;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;

/**
 * Abstract base class for handlers that process pre- and post-condition annotations for fields.
 * Note: all fields that are going to be processed must be the fields of the receiver. (e.g. field
 * or this.field)
 */
public abstract class AbstractFieldContractHandler extends BaseNoOpHandler {

  protected static final String THIS_NOTATION = "this.";

  /** Simple name of the annotation in {@code String} */
  protected final String annotName;

  protected AbstractFieldContractHandler(String annotName) {
    this.annotName = annotName;
  }

  /**
   * Verifies that the method being processed adheres to the annotation specifications.
   *
   * @param analysis NullAway instance.
   * @param tree Method tree under processing.
   * @param state Error Prone {@link VisitorState}.
   * @param methodSymbol Processing method symbol.
   */
  @Override
  public void onMatchMethod(
      NullAway analysis, MethodTree tree, VisitorState state, Symbol.MethodSymbol methodSymbol) {
    Set<String> annotationContent =
        NullabilityUtil.getAnnotationValueArray(methodSymbol, annotName, false);
    boolean isAnnotated = annotationContent != null;
    boolean isValid =
        isAnnotated
            && validateAnnotationSyntax(
                castToNonNull(annotationContent), analysis, tree, state, methodSymbol)
            && validateAnnotationSemantics(analysis, state, tree, methodSymbol);
    if (isAnnotated && !isValid) {
      return;
    }
    Symbol.MethodSymbol closestOverriddenMethod =
        NullabilityUtil.getClosestOverriddenMethod(methodSymbol, state.getTypes());
    if (closestOverriddenMethod == null) {
      return;
    }
    Set<String> fieldNames;
    if (isAnnotated) {
      fieldNames = ContractUtils.trimReceivers(castToNonNull(annotationContent));
    } else {
      fieldNames = Collections.emptySet();
    }
    validateOverridingRules(fieldNames, analysis, state, tree, closestOverriddenMethod);
    super.onMatchMethod(analysis, tree, state, methodSymbol);
  }

  /**
   * This method validates whether the input method in parameter conforms to the inheritance rules.
   * Regardless of whether an annotation is present, every method cannot have a stricter
   * precondition than its super method and must satisfy all postcondition of its super method.
   *
   * @param fieldNames The set of filed names that are given as parameter in the annotation, empty
   *     if the annotation is not present.
   * @param analysis NullAway instance.
   * @param tree Processing method tree.
   * @param state Error Prone's {@link VisitorState}.
   * @param overriddenMethod Processing method symbol.
   */
  protected abstract void validateOverridingRules(
      Set<String> fieldNames,
      NullAway analysis,
      VisitorState state,
      MethodTree tree,
      Symbol.MethodSymbol overriddenMethod);

  /**
   * Validates that a method implementation matches the semantics of the annotation.
   *
   * @return Returns true, if the annotation conforms to the semantic rules.
   */
  protected abstract boolean validateAnnotationSemantics(
      NullAway analysis, VisitorState state, MethodTree tree, Symbol.MethodSymbol methodSymbol);

  /**
   * Validates whether the parameter inside annotation conforms to the syntax rules. Parameters must
   * conform to the following rules:
   *
   * <p>
   *
   * <ul>
   *   <li>Cannot annotate a method with empty param set.
   *   <li>The receiver of selected fields in annotation can only be the receiver of the method.
   *   <li>All parameters given in the annotation must be one of the fields of the class or its
   *       super classes.
   * </ul>
   *
   * <p>
   *
   * @return Returns true, if the annotation conforms to the syntax rules.
   */
  protected boolean validateAnnotationSyntax(
      Set<String> content,
      NullAway analysis,
      MethodTree tree,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol) {
    String message;
    if (content.isEmpty()) {
      // we should not allow useless annotations.
      message =
          "empty @"
              + annotName
              + " is the default precondition for every method, please remove it.";
      state.reportMatch(
          analysis
              .getErrorBuilder()
              .createErrorDescription(
                  new ErrorMessage(ErrorMessage.MessageTypes.ANNOTATION_VALUE_INVALID, message),
                  tree,
                  analysis.buildDescription(tree),
                  state,
                  null));
      return false;
    } else {
      for (String fieldName : content) {
        if (fieldName.contains(".")) {
          if (!fieldName.startsWith(THIS_NOTATION)) {
            message =
                "currently @"
                    + annotName
                    + " supports only class fields of the method receiver: "
                    + fieldName
                    + " is not supported";

            state.reportMatch(
                analysis
                    .getErrorBuilder()
                    .createErrorDescription(
                        new ErrorMessage(
                            ErrorMessage.MessageTypes.ANNOTATION_VALUE_INVALID, message),
                        tree,
                        analysis.buildDescription(tree),
                        state,
                        null));
            return false;
          } else {
            fieldName = fieldName.substring(fieldName.lastIndexOf(".") + 1);
          }
        }
        Symbol.ClassSymbol classSymbol = castToNonNull(ASTHelpers.enclosingClass(methodSymbol));
        VariableElement field = getInstanceFieldOfClass(classSymbol, fieldName);
        if (field == null) {
          message =
              "For @"
                  + annotName
                  + " annotation, cannot find instance field "
                  + fieldName
                  + " in class "
                  + classSymbol.getSimpleName();
          state.reportMatch(
              analysis
                  .getErrorBuilder()
                  .createErrorDescription(
                      new ErrorMessage(ErrorMessage.MessageTypes.ANNOTATION_VALUE_INVALID, message),
                      tree,
                      analysis.buildDescription(tree),
                      state,
                      null));
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Finds a specific instance field of a class or its superclasses
   *
   * @param classSymbol A class symbol.
   * @param name Name of the field.
   * @return The field with the given name, or {@code null} if the field cannot be found
   */
  public static @Nullable VariableElement getInstanceFieldOfClass(
      Symbol.ClassSymbol classSymbol, String name) {
    Preconditions.checkNotNull(classSymbol);
    for (Element member : getEnclosedElements(classSymbol)) {
      if (member.getKind().isField() && !member.getModifiers().contains(Modifier.STATIC)) {
        if (member.getSimpleName().toString().equals(name)) {
          return (VariableElement) member;
        }
      }
    }
    Symbol.ClassSymbol superClass = (Symbol.ClassSymbol) classSymbol.getSuperclass().tsym;
    if (superClass != null) {
      return getInstanceFieldOfClass(superClass, name);
    }
    return null;
  }
}
