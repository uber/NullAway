/*
 * Copyright (c) 2022 Uber Technologies, Inc.
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

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.NullabilityUtil;
import com.uber.nullaway.dataflow.AccessPathNullnessAnalysis;
import com.uber.nullaway.fixserialization.FixSerializationConfig;
import com.uber.nullaway.fixserialization.out.FieldInitializationInfo;
import javax.lang.model.element.ElementKind;

/**
 * This handler is used to serialize information regarding methods that initialize a class field.
 *
 * <p>It uses the following heuristic: if a method assigns a {@code @NonNull} value to a field, and
 * furthermore guarantees that said field is {@code @NonNull} on return from the method, then we
 * serialize information for this method as a potential initializer for that field. This information
 * can be used by an inference tool to detect initializer methods in classes.
 *
 * <p>Note that the above two conditions are both needed to eliminate the cases below:
 *
 * <ul>
 *   <li>Methods that initialize a field conditionally through only some of their execution paths
 *   <li>Methods which only check that the field is already initialized and terminate exceptionally
 *       otherwise (these methods guarantee the field is initialized on return, but are rarely what
 *       we are looking for when we look for candidates for {@code @Initializer})
 * </ul>
 */
public class FieldInitializationSerializationHandler extends BaseNoOpHandler {

  private final FixSerializationConfig config;

  FieldInitializationSerializationHandler(FixSerializationConfig config) {
    this.config = config;
  }

  @Override
  public void onNonNullFieldAssignment(
      Symbol field, AccessPathNullnessAnalysis analysis, VisitorState state) {
    // Traversing AST is costly, therefore we access the initializer method through the leaf node in
    // state parameter
    TreePath pathToMethod =
        NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(state.getPath());
    if (pathToMethod == null || pathToMethod.getLeaf().getKind() != Tree.Kind.METHOD) {
      return;
    }
    Symbol symbol = ASTHelpers.getSymbol(pathToMethod.getLeaf());
    if (!(symbol instanceof Symbol.MethodSymbol)) {
      return;
    }
    Symbol.MethodSymbol methodSymbol = (Symbol.MethodSymbol) symbol;
    // Check if the method and the field are in the same class.
    if (!field.enclClass().equals(methodSymbol.enclClass())) {
      // We don't want m in A.m() to be a candidate initializer for B.f unless A == B
      return;
    }
    // We are only looking for non-constructor methods that initialize a class field.
    if (methodSymbol.getKind() == ElementKind.CONSTRUCTOR) {
      return;
    }
    final String fieldName = field.getSimpleName().toString();
    boolean leavesNonNullAtExitPoint =
        analysis.getNonnullFieldsOfReceiverAtExit(pathToMethod, state.context).stream()
            .anyMatch(element -> element.getSimpleName().toString().equals(fieldName));
    if (!leavesNonNullAtExitPoint) {
      // Method does not keep the field @NonNull at exit point and fails the post condition to be an
      // Initializer.
      return;
    }
    NullabilityUtil.castToNonNull(config.getSerializer())
        .serializeFieldInitializationInfo(new FieldInitializationInfo(methodSymbol, field));
  }
}
