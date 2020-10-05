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
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.NullnessStore;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import org.checkerframework.dataflow.cfg.UnderlyingAST;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;

public class RequiresNonNullHandler extends BaseNoOpHandler {

  private static final String ANNOT_NAME = "RequiresNonNull";
  private static final String THIS_NOTATION = "this.";
  private static final Map<MethodTree, Set<String>> METHOD_FIELDS_MAP = new HashMap<>();

  /** This method verifies that the method adheres to any @RequireNonNull annotation. */
  @Override
  public void onMatchMethod(
      NullAway analysis, MethodTree tree, VisitorState state, Symbol.MethodSymbol methodSymbol) {
    Set<String> fields = new HashSet<>();
    String fieldName = getFieldNameFromAnnotation(methodSymbol);
    if (fieldName != null) {
      if (fieldName.equals("")) {
        // we should not allow useless requiresNonnull annotations.
        reportMatch(
            analysis,
            state,
            tree,
            "empty requiresNonnull is the default precondition for every method, please remove it.");
      }
      if (fieldName.contains(".")) {
        if (!fieldName.startsWith(THIS_NOTATION)) {
          reportMatch(
              analysis,
              state,
              tree,
              "currently @RequiresNonnull supports only class fields of the method receiver.");
        } else {
          fieldName = fieldName.substring(THIS_NOTATION.length());
        }
      }
      Symbol.ClassSymbol classSymbol = ASTHelpers.enclosingClass(methodSymbol);
      assert classSymbol != null
          : "can not find the enclosing class for method symbol: " + methodSymbol;
      if (getFieldFromClassAndSuperClasses(classSymbol, fieldName) == null) {
        reportMatch(
            analysis,
            state,
            tree,
            "cannot find field [" + fieldName + "] in class: " + classSymbol.name);
      }
      fields.add(fieldName);
    }
    fields.addAll(getSuperMethodRequiresNonNullFields(methodSymbol, state));
    if (fields.size() > 0) {
      METHOD_FIELDS_MAP.put(tree, fields);
    }
    super.onMatchMethod(analysis, tree, state, methodSymbol);
  }

  @Override
  public void onMatchMethodInvocation(
      NullAway analysis,
      MethodInvocationTree tree,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol) {
    String fieldName = getFieldNameFromAnnotation(methodSymbol);
    if (fieldName == null || fieldName.equals("")) {
      super.onMatchMethodInvocation(analysis, tree, state, methodSymbol);
      return;
    }
    if (fieldName.startsWith(THIS_NOTATION)) {
      fieldName = fieldName.substring(THIS_NOTATION.length());
    }
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
    Element field = getFieldFromClassAndSuperClasses(classSymbol, fieldName);
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

  private static boolean nullnessToBool(Nullness nullness) {
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
    Set<String> fields = METHOD_FIELDS_MAP.get(methodTree);
    if (fields == null) {
      return result;
    }
    for (String fieldName : fields) {
      if (fieldName.startsWith(THIS_NOTATION)) {
        fieldName = fieldName.substring(THIS_NOTATION.length());
      }
      Element field = getFieldFromClassAndSuperClasses(ASTHelpers.getSymbol(classTree), fieldName);
      assert field != null
          : "Could not find field: [" + fieldName + "]" + "for class: " + classTree.getSimpleName();
      AccessPath accessPath = AccessPath.fromFieldAccess(field, null);
      result.setInformation(accessPath, Nullness.NONNULL);
    }
    return result;
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
   * Finds all declared fields of a class
   *
   * @param classSymbol A class symbol.
   * @return The set of classes fields.
   */
  private static Set<Element> getFieldsOfClass(Symbol.ClassSymbol classSymbol) {
    Preconditions.checkNotNull(classSymbol);
    Set<Element> fields = new HashSet<>();
    for (Element t : classSymbol.getEnclosedElements()) {
      if (t.getKind().isField()) {
        fields.add(t);
      }
    }
    return fields;
  }

  /**
   * Finds a specific field of a class considering it's super classes
   *
   * @param classSymbol A class symbol.
   * @param name Name of the field.
   * @return The closest class field element with the given name.
   */
  private static Element getFieldFromClassAndSuperClasses(
      Symbol.ClassSymbol classSymbol, String name) {
    Set<Element> fields = getFieldsOfClass(classSymbol);
    for (Element field : fields) {
      if (field.getSimpleName().toString().equals(name)) {
        return field;
      }
    }
    Symbol.ClassSymbol superClass = (Symbol.ClassSymbol) classSymbol.getSuperclass().tsym;
    if (superClass != null) {
      return getFieldFromClassAndSuperClasses(superClass, name);
    }
    return null;
  }

  /**
   * Finds the set of fields that are given as param in {@link
   * com.uber.nullaway.qual.RequiresNonNull} annotation through all super methods.
   *
   * @param methodSymbol A method symbol.
   * @param state Javac Visoitor State.
   * @return Set of fields name in {@code String}.
   */
  private static Set<String> getSuperMethodRequiresNonNullFields(
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
   * Retrieve the string value inside an @RequiresNonnull annotation without statically depending on
   * the type.
   *
   * @param sym A method which has an @RequiresNonnull annotation.
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
