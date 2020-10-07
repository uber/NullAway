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
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import java.util.HashSet;
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

public abstract class ConditionHandler extends BaseNoOpHandler {

  protected static final String THIS_NOTATION = "this.";
  protected String ANNOT_NAME;

  /**
   * This method verifies that the method adheres to any corresponding
   * (@EnsuresNonNull/@RequiresNonNull) annotation.
   */
  @Override
  public void onMatchMethod(
      NullAway analysis, MethodTree tree, VisitorState state, Symbol.MethodSymbol methodSymbol) {
    Set<String> annotationContent = getFieldNamesFromAnnotation(methodSymbol);
    boolean isValidAnnotation =
        validateAnnotationSyntax(annotationContent, analysis, tree, state, methodSymbol);
    if (annotationContent != null && isValidAnnotation) {
      isValidAnnotation = validateAnnotationSemantics(analysis, state, tree, methodSymbol);
    }
    if (isValidAnnotation) {
      Set<String> fieldNames = null;
      Symbol.MethodSymbol closestOverriddenMethod =
          getClosestOverriddenMethod(methodSymbol, state.getTypes());
      if (closestOverriddenMethod == null) {
        return;
      }
      if (annotationContent != null) {
        fieldNames =
            annotationContent
                .stream()
                .map(ConditionHandler::trimFieldName)
                .collect(Collectors.toSet());
      }
      validateOverridingRules(fieldNames, analysis, state, tree, closestOverriddenMethod);
    }
    super.onMatchMethod(analysis, tree, state, methodSymbol);
  }

  protected abstract void validateOverridingRules(
      Set<String> fieldNames,
      NullAway analysis,
      VisitorState state,
      MethodTree tree,
      Symbol.MethodSymbol closestOverriddenMethod);

  protected abstract boolean validateAnnotationSemantics(
      NullAway analysis, VisitorState state, MethodTree tree, Symbol.MethodSymbol methodSymbol);

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
        // we should not allow useless ensuresNonnull annotations.
        reportMatch(
            analysis,
            state,
            tree,
            "empty @ensuresNonnull is the default precondition for every method, please remove it.");
        return false;
      } else {
        for (String fieldName : content) {
          if (fieldName.contains(".")) {
            if (!fieldName.startsWith(THIS_NOTATION)) {
              reportMatch(
                  analysis,
                  state,
                  tree,
                  "currently @EnsuresNonnull supports only class fields of the method receiver: "
                      + fieldName
                      + " is not supported");
              return false;
            } else {
              fieldName = trimFieldName(fieldName);
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

  protected static String trimFieldName(String fieldName) {
    if (fieldName.startsWith(THIS_NOTATION)) {
      return fieldName.substring(THIS_NOTATION.length());
    }
    return fieldName;
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

  /**
   * find the closest ancestor method in a superclass or superinterface that method overrides
   *
   * @param method the subclass method
   * @param types the types data structure from javac
   * @return closest overridden ancestor method, or <code>null</code> if method does not override
   *     anything
   */
  @Nullable
  protected static Symbol.MethodSymbol getClosestOverriddenMethod(
      Symbol.MethodSymbol method, Types types) {
    // taken from Error Prone MethodOverrides check
    Symbol.ClassSymbol owner = method.enclClass();
    for (Type s : types.closure(owner.type)) {
      if (types.isSameType(s, owner.type)) {
        continue;
      }
      for (Symbol m : s.tsym.members().getSymbolsByName(method.name)) {
        if (!(m instanceof Symbol.MethodSymbol)) {
          continue;
        }
        Symbol.MethodSymbol msym = (Symbol.MethodSymbol) m;
        if (msym.isStatic()) {
          continue;
        }
        if (method.overrides(msym, owner, types, /*checkReturn*/ false)) {
          return msym;
        }
      }
    }
    return null;
  }

  protected static void reportMatch(
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
   * Retrieve the string value inside an @EnsuresNonnull annotation without statically depending on
   * the type.
   *
   * @param sym A method which has an @EnsuresNonnull annotation.
   * @return The string value spec inside the annotation.
   */
  protected @Nullable Set<String> getFieldNamesFromAnnotation(Symbol.MethodSymbol sym) {
    for (AnnotationMirror annotation : sym.getAnnotationMirrors()) {
      Element element = annotation.getAnnotationType().asElement();
      assert element.getKind().equals(ElementKind.ANNOTATION_TYPE);
      if (((TypeElement) element).getQualifiedName().toString().endsWith(ANNOT_NAME)) {
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

  protected static boolean nullnessToBool(Nullness nullness) {
    switch (nullness) {
      case BOTTOM:
      case NONNULL:
        return false;
      case NULL:
      case NULLABLE:
        return true;
      default:
        throw new AssertionError("Impossible: " + nullness);
    }
  }
}
