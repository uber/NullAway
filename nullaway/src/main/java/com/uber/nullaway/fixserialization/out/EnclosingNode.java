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

import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;

/** Nodes with this type, are surrounded in a method in source code. */
public abstract class EnclosingNode {

  /** Path to the Node in source code. */
  public final TreePath path;

  /**
   * Finding values for these properties is costly and are not needed by default, hence, they are
   * not {@code final} and are only initialized at request.
   */
  protected MethodTree enclosingMethod;

  protected ClassTree enclosingClass;

  public EnclosingNode(TreePath path) {
    this.path = path;
  }

  /** Finds the enclosing class and enclosing method of this node. */
  public void findEnclosing() {
    enclosingMethod = ASTHelpers.findEnclosingNode(path, MethodTree.class);
    enclosingClass = ASTHelpers.findEnclosingNode(path, ClassTree.class);
    if (enclosingClass == null && path.getLeaf() instanceof ClassTree) {
      enclosingClass = (ClassTree) path.getLeaf();
    }
    if (enclosingMethod == null) {
      Tree methodTree = path.getLeaf();
      if (methodTree instanceof MethodTree) {
        enclosingMethod = (MethodTree) methodTree;
      }
    }
  }
}
