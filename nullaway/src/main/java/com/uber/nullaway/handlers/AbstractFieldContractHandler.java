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
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.NullabilityUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Abstract base class for any annotation which aims processing class fields to satisfy pre/post
 * conditions. Note: all fields that are going to be processed must be the fields of the receiver.
 * (e.g. field or this.field)
 */
public abstract class AbstractFieldContractHandler extends BaseNoOpHandler {

  protected static final String THIS_NOTATION = "this.";
  /** Simple name of the annotation in {@code String} */
  protected String annotName;

  /**
   * This method verifies that the method adheres to any corresponding
   * (@EnsuresNonNull/@RequiresNonNull) annotation.
   */
  @Override
  public void onMatchMethod(
      NullAway analysis, MethodTree tree, VisitorState state, Symbol.MethodSymbol methodSymbol) {
    Set<String> annotationContent = getFieldNamesFromAnnotation(methodSymbol);
    boolean isValid =
        validateAnnotationSyntax(annotationContent, analysis, tree, state, methodSymbol);
    isValid =
        (isValid && annotationContent != null)
            ? validateAnnotationSemantics(analysis, state, tree, methodSymbol)
            : isValid;
    if (isValid) {
      Set<String> fieldNames = null;
      Symbol.MethodSymbol closestOverriddenMethod =
          NullabilityUtil.getClosestOverriddenMethod(methodSymbol, state.getTypes());
      if (closestOverriddenMethod == null) {
        return;
      }
      if (annotationContent != null) {
        fieldNames =
            annotationContent
                .stream()
                .map(AbstractFieldContractHandler::trimReceiver)
                .collect(Collectors.toSet());
      }
      validateOverridingRules(fieldNames, analysis, state, tree, closestOverriddenMethod);
    }
    super.onMatchMethod(analysis, tree, state, methodSymbol);
  }

  /** Validates whether the annotation conforms to the inheritance rules. */
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
      if (content.isEmpty()) {
        // we should not allow useless annotations.
        reportMatch(
            analysis,
            state,
            tree,
            "empty @"
                + annotName
                + " is the default precondition for every method, please remove it.");
        return false;
      } else {
        for (String fieldName : content) {
          if (fieldName.contains(".")) {
            if (!fieldName.startsWith(THIS_NOTATION)) {
              reportMatch(
                  analysis,
                  state,
                  tree,
                  "currently @"
                      + annotName
                      + " supports only class fields of the method receiver: "
                      + fieldName
                      + " is not supported");
              return false;
            } else {
              fieldName = trimReceiver(fieldName);
            }
          }
          Symbol.ClassSymbol classSymbol = ASTHelpers.enclosingClass(methodSymbol);
          Element field = getFieldFromClass(classSymbol, fieldName);
          if (field == null) {
            reportMatch(
                analysis,
                state,
                tree,
                "cannot find field [" + fieldName + "] in class: " + classSymbol.getSimpleName());
            return false;
          }
        }
      }
    }
    return true;
  }

  /**
   * Returns the field name excluding its receiver (e.g. "this.a" will be "a")
   *
   * @param fieldName A class symbol.
   * @return The class field name.
   */
  protected static String trimReceiver(String fieldName) {
    return fieldName.substring(fieldName.lastIndexOf(".") + 1);
  }

  /**
   * Finds a specific field of a class
   *
   * @param classSymbol A class symbol.
   * @param name Name of the field.
   * @return The class field with the given name.
   */
  protected static Element getFieldFromClass(Symbol.ClassSymbol classSymbol, String name) {
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

  protected static List<Element> getReceiverTreeElements(Tree receiver) {
    List<Element> elements = new ArrayList<>();
    if (receiver != null) {
      elements.add(0, ASTHelpers.getSymbol(receiver));
      while (receiver instanceof MemberSelectTree) {
        ExpressionTree expression = ((MemberSelectTree) receiver).getExpression();
        elements.add(0, ASTHelpers.getSymbol(expression));
        receiver = expression;
      }
    }
    return elements;
  }

  /**
   * Retrieve the string value inside the corresponding (@EnsuresNonNull/@RequiresNonNull depending
   * on the value of {@code ANNOT_NAME}) annotation without statically depending on the type.
   *
   * @param sym A method.
   * @return The set of all values inside the annotation.
   */
  protected @Nullable Set<String> getFieldNamesFromAnnotation(Symbol.MethodSymbol sym) {
    for (AnnotationMirror annotation : sym.getAnnotationMirrors()) {
      Element element = annotation.getAnnotationType().asElement();
      assert element.getKind().equals(ElementKind.ANNOTATION_TYPE);
      if (((TypeElement) element).getQualifiedName().toString().endsWith(annotName)) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e :
            annotation.getElementValues().entrySet()) {
          if (e.getKey().getSimpleName().contentEquals("value")) {
            String value = e.getValue().toString();
            if (value.startsWith("{") && value.endsWith("}")) {
              value = value.substring(1, value.length() - 1);
            }
            String[] rawFieldNamesArray = value.split(",");
            Set<String> ans = new HashSet<>();
            for (String s : rawFieldNamesArray) {
              ans.add(s.trim().replaceAll("\"", ""));
            }
            return ans;
          }
        }
      }
    }
    return null;
  }
}
