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
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;

@SuppressWarnings({"ALL", "UnusedMethod"})
public class EnsuresNonnullHandler extends BaseNoOpHandler {

  private static final String annotName = "com.uber.nullaway.qual.EnsuresNonnull";
  Map<Symbol.MethodSymbol, ClassTree> methodToClass = new HashMap<>();

  @Override
  public void onMatchMethod(
      NullAway analysis, MethodTree tree, VisitorState state, Symbol.MethodSymbol methodSymbol) {
    Preconditions.checkNotNull(methodSymbol);
    String contract = getContractFromAnnotation(methodSymbol);
    if (!(contract == null || contract.equals(""))) {
      ClassTree enclisingClass =
          ASTHelpers.findClass(ASTHelpers.enclosingClass(methodSymbol), state);
      methodToClass.put(methodSymbol, enclisingClass);
    }
    super.onMatchMethod(analysis, tree, state, methodSymbol);
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
    Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(node.getTree());
    Preconditions.checkNotNull(methodSymbol);
    String contract = getContractFromAnnotation(methodSymbol);
    if (contract == null || contract.equals("")) {
      return super.onDataflowVisitMethodInvocation(
          node, types, context, inputs, thenUpdates, elseUpdates, bothUpdates);
    }
    ClassTree classTree = methodToClass.get(methodSymbol);
    for (Tree member : classTree.getMembers()) {
      if (member.getKind().equals(Tree.Kind.VARIABLE)) {
        VariableTree vt = ((VariableTree) member);
        if (vt.getName().toString().equals(contract)) {
          AccessPath accessPath =
              AccessPath.fromFieldAccess(ASTHelpers.getSymbol(vt), node.getTarget().getReceiver());
          bothUpdates.set(accessPath, Nullness.NONNULL);
        }
      }
    }
    return super.onDataflowVisitMethodInvocation(
        node, types, context, inputs, thenUpdates, elseUpdates, bothUpdates);
  }

  /**
   * Retrieve the string value inside an @Contract annotation without statically depending on the
   * type.
   *
   * @param sym A method which has an @Contract annotation.
   * @return The string value spec inside the annotation.
   */
  private static @Nullable String getContractFromAnnotation(Symbol.MethodSymbol sym) {
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
