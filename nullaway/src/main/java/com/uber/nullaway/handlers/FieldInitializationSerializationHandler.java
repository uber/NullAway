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
 * This handler is used to serialize information regarding methods that initialize a class field. If
 * a method guarantee to leave the initialized class field in the method body to be {@code @NonNull}
 * at exit point, this handler will serialize information regarding the initializer method and the
 * class field. These information helps to detect initializer methods in classes.
 */
public class FieldInitializationSerializationHandler extends BaseNoOpHandler {

  private final FixSerializationConfig config;

  FieldInitializationSerializationHandler(FixSerializationConfig config) {
    this.config = config;
  }

  /**
   * If the method guarantees to leave the initialized class field to be {@code @Nonnull} at exit
   * point, this method will serialize information regarding the initializer method and the class
   * field. Since traversing AST is costly, we do it only inside the handler when the feature is
   * enabled and, this method accesses the initializer method through the leaf node in state
   * parameter.
   */
  @Override
  public void onNonNullFieldAssignment(
      Symbol field, AccessPathNullnessAnalysis analysis, VisitorState state) {
    TreePath pathToMethod =
        NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(state.getPath());
    if (pathToMethod == null || pathToMethod.getLeaf().getKind() != Tree.Kind.METHOD) {
      return;
    }
    Symbol.MethodSymbol symbol = (Symbol.MethodSymbol) ASTHelpers.getSymbol(pathToMethod.getLeaf());
    // We are only looking for non-constructor methods that initializes a class field.
    if (symbol.getKind() == ElementKind.CONSTRUCTOR) {
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
    config
        .getSerializer()
        .serializeFieldInitializationInfo(new FieldInitializationInfo(symbol, field));
  }
}
