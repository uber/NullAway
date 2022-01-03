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

package com.uber.nullaway.fixserialization.out;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.uber.nullaway.ErrorMessage;

/** Nodes with this type, are surrounded in a method in source code. */
public abstract class EnclosingNode {

  /**
   * Finding values for these properties is costly and are not needed by default, hence, they are
   * not {@code final} and are only initialized at request.
   */
  protected MethodTree enclosingMethod;

  protected ClassTree enclosingClass;

  public void findEnclosing(VisitorState state, ErrorMessage errorMessage) {
    enclosingMethod = ASTHelpers.findEnclosingNode(state.getPath(), MethodTree.class);
    enclosingClass = ASTHelpers.findEnclosingNode(state.getPath(), ClassTree.class);
    if (enclosingClass == null && state.getPath().getLeaf() instanceof ClassTree) {
      ErrorMessage.MessageTypes messageTypes = errorMessage.getMessageType();
      if (messageTypes.equals(ErrorMessage.MessageTypes.ASSIGN_FIELD_NULLABLE)
          || messageTypes.equals(ErrorMessage.MessageTypes.FIELD_NO_INIT)
          || messageTypes.equals(ErrorMessage.MessageTypes.METHOD_NO_INIT)) {
        enclosingClass = (ClassTree) state.getPath().getLeaf();
      }
    }
    if (enclosingMethod == null
        && errorMessage.getMessageType().equals(ErrorMessage.MessageTypes.WRONG_OVERRIDE_RETURN)) {
      Tree methodTree = state.getPath().getLeaf();
      if (methodTree instanceof MethodTree) {
        enclosingMethod = (MethodTree) methodTree;
      }
    }
  }
}
