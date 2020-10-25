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
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;

/**
 * Abstract base class for handlers that process pre- and post-condition annotations for fields.
 * Note: all fields that are going to be processed must be the fields of the receiver. (e.g. field
 * or this.field)
 */
public abstract class AbstractFieldContractHandler extends BaseNoOpHandler {

  protected static final String THIS_NOTATION = "this.";
  /** Simple name of the annotation in {@code String} */
  protected String annotName;

  /**
   * Verifies that the processing method adheres to the annotation specifications.
   *
   * @param analysis NullAway instance.
   * @param tree Processing method tree.
   * @param state Javac {@link VisitorState}.
   * @param methodSymbol Processing method symbol.
   */
  @Override
  public void onMatchMethod(
      NullAway analysis, MethodTree tree, VisitorState state, Symbol.MethodSymbol methodSymbol) {
    Set<String> annotationContent =
        ContractUtils.getFieldNamesFromAnnotation(methodSymbol, annotName);
    boolean isAnnotated = annotationContent != null;
    boolean isValid =
        isAnnotated
            && validateAnnotationSyntax(annotationContent, analysis, tree, state, methodSymbol)
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
      fieldNames = ContractUtils.trimReceivers(annotationContent);
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
   * @param state Javac {@link VisitorState}.
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
   * conform to the following rules: 1. Cannot annotate a method with empty param set. 2. The
   * receiver of selected fields in annotation can only be the receiver of the method. 3. All
   * parameters given in the annotation must be one of the fields of the class or its super classes.
   *
   * @return Returns true, if the annotation conforms to the syntax rules.
   */
  protected boolean validateAnnotationSyntax(
      Set<String> content,
      NullAway analysis,
      MethodTree tree,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol) {
    if (content == null) {
      return true;
    } else {
      String message;
      if (content.isEmpty()) {
        // we should not allow useless annotations.
        message =
            "empty @"
                + annotName
                + " is the default precondition for every method, please remove it.";
        ContractUtils.reportMatch(
            tree, message, analysis, state, ErrorMessage.MessageTypes.ANNOTATION_VALUE_INVALID);
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
              ContractUtils.reportMatch(
                  tree,
                  message,
                  analysis,
                  state,
                  ErrorMessage.MessageTypes.ANNOTATION_VALUE_INVALID);
              return false;
            } else {
              fieldName = fieldName.substring(fieldName.lastIndexOf(".") + 1);
            }
          }
          Symbol.ClassSymbol classSymbol = ASTHelpers.enclosingClass(methodSymbol);
          Element field = getFieldFromClass(classSymbol, fieldName);
          if (field == null) {
            message =
                "cannot find field [" + fieldName + "] in class: " + classSymbol.getSimpleName();
            ContractUtils.reportMatch(
                tree, message, analysis, state, ErrorMessage.MessageTypes.ANNOTATION_VALUE_INVALID);
            return false;
          }
          if (field.getModifiers().contains(Modifier.STATIC)) {
            message =
                "cannot accept static field: ["
                    + fieldName
                    + "] as a parameter in @"
                    + annotName
                    + " annotation";
            ContractUtils.reportMatch(
                tree, message, analysis, state, ErrorMessage.MessageTypes.ANNOTATION_VALUE_INVALID);
            return false;
          }
        }
      }
    }
    return true;
  }

  /**
   * Finds a specific field of a class
   *
   * @param classSymbol A class symbol.
   * @param name Name of the field.
   * @return The class field with the given name.
   */
  public static Element getFieldFromClass(Symbol.ClassSymbol classSymbol, String name) {
    Preconditions.checkNotNull(classSymbol);
    for (Element member : classSymbol.getEnclosedElements()) {
      if (member.getKind().isField()) {
        if (member.getSimpleName().toString().equals(name)) {
          return member;
        }
      }
    }
    Symbol.ClassSymbol superClass = (Symbol.ClassSymbol) classSymbol.getSuperclass().tsym;
    if (superClass != null) {
      return getFieldFromClass(superClass, name);
    }
    return null;
  }
}
