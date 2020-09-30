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
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.NullnessStore;
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

public class RequiresNonnullHandler extends BaseNoOpHandler {

  private static final String annotName = "com.uber.nullaway.qual.RequiresNonnull";

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
    String fieldName = getFieldNameFromAnnotation(methodSymbol);
    if (fieldName != null) {
      if (fieldName.equals("")) {
        // we should not allow useless requiresNonnull annotations.
        reportMatch(
            tree,
            "empty requiresNonnull is the default precondition for every method, please remove it.");
      }
      Symbol.ClassSymbol classSymbol = ASTHelpers.enclosingClass(methodSymbol);
      ClassTree classTree = ASTHelpers.findClass(classSymbol, state);
      assert classTree != null
          : "can not find the enclosing class for method symbol: " + methodSymbol;
      if (!classContainsFieldWithName(classTree, fieldName)) {
        reportMatch(tree, "cannot find field [" + fieldName + "] in class: " + classSymbol.name);
      }
    }
    super.onMatchMethod(analysis, tree, state, methodSymbol);
  }

  @SuppressWarnings("UnusedVariable")
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
    Symbol.ClassSymbol classSymbol = ASTHelpers.enclosingClass(methodSymbol);
    ClassTree classTree = ASTHelpers.findClass(classSymbol, state);
    assert classTree != null
        : "can not find the enclosing class for method symbol: " + methodSymbol;
    MemberSelectTree receiver = null; // null receiver means (this) is the receiver.
    if (tree.getMethodSelect() instanceof MemberSelectTree) {
      receiver = (MemberSelectTree) tree.getMethodSelect();
    }
    VariableTree variableTree = getFieldFromClass(classTree, fieldName);
    AccessPath accessPath =
        AccessPath.fromFieldAccessTree(ASTHelpers.getSymbol(variableTree), receiver);
    Nullness nullness =
        analysis
            .getNullnessAnalysis(state)
            .getNullnessOfAccessPath(
                new TreePath(state.getPath(), tree), state.context, accessPath);
    if (nullnessToBool(nullness)) {
      reportMatch(tree, "expected field [" + fieldName + "] is not non-null at call site.");
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

  @Override
  public NullnessStore.Builder onDataflowInitialStore(
      UnderlyingAST underlyingAST,
      List<LocalVariableNode> parameters,
      NullnessStore.Builder result) {
    if (!(underlyingAST instanceof UnderlyingAST.CFGMethod)) {
      return super.onDataflowInitialStore(underlyingAST, parameters, result);
    }
    MethodTree methodTree = ((UnderlyingAST.CFGMethod) underlyingAST).getMethod();
    Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(methodTree);
    String fieldName = getFieldNameFromAnnotation(methodSymbol);
    if (fieldName == null) {
      return super.onDataflowInitialStore(underlyingAST, parameters, result);
    }
    ClassTree classTree = ((UnderlyingAST.CFGMethod) underlyingAST).getClassTree();
    VariableTree variableTree = getFieldFromClass(classTree, fieldName);
    AccessPath accessPath =
        AccessPath.fromFieldAccessNode(ASTHelpers.getSymbol(variableTree), null);
    result.setInformation(accessPath, Nullness.NONNULL);
    return result;
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
   * Finds all fields of a class
   *
   * @param classTree A classTree.
   * @return The set of classes fields.
   */
  private static Set<VariableTree> getFieldsOfClass(ClassTree classTree) {
    Preconditions.checkNotNull(classTree);
    Set<VariableTree> fields = new HashSet<>();
    for (Tree t : classTree.getMembers()) {
      if (t.getKind().equals(Tree.Kind.VARIABLE)) {
        fields.add(((VariableTree) t));
      }
    }
    return fields;
  }

  /**
   * Finds a specific field of a class
   *
   * @param classTree A classTree.
   * @param name Name of the field.
   * @return The class field with the given name.
   */
  private static VariableTree getFieldFromClass(ClassTree classTree, String name) {
    Set<VariableTree> fields = getFieldsOfClass(classTree);
    for (VariableTree field : fields) {
      if (field.getName().toString().equals(name)) {
        return field;
      }
    }
    throw new AssertionError(
        "cannot find field [" + name + "] in class: " + classTree.getSimpleName());
  }

  /**
   * @param classTree A classTree.
   * @param name Name of the field.
   * @return true, if the class contains a field with the given name.
   */
  private static boolean classContainsFieldWithName(ClassTree classTree, String name) {
    Set<VariableTree> fields = getFieldsOfClass(classTree);
    for (VariableTree field : fields) {
      if (field.getName().toString().equals(name)) {
        return true;
      }
    }
    return false;
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
