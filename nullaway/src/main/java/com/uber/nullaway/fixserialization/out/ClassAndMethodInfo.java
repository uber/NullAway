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
import com.sun.tools.javac.code.Symbol;
import javax.annotation.Nullable;

/** Container class of class and method info of a program point. */
public class ClassAndMethodInfo {
  /** Path to the element in source code. */
  public final TreePath path;

  /**
   * Finding values for these properties is costly and are not needed by default, hence, they are
   * not {@code final} and are only initialized at request.
   */
  @Nullable private MethodTree method;

  @Nullable private ClassTree clazz;

  public ClassAndMethodInfo(TreePath path) {
    this.path = path;
  }

  /** Finds the class and method where the error is reported according to {@code path}. */
  public void findValues() {
    method = ASTHelpers.findEnclosingNode(path, MethodTree.class);
    clazz =
        path.getLeaf().getKind().equals(Tree.Kind.CLASS)
            ? (ClassTree) path.getLeaf()
            : ASTHelpers.findEnclosingNode(path, ClassTree.class);
    if (clazz == null && path.getLeaf() instanceof ClassTree) {
      clazz = (ClassTree) path.getLeaf();
    }
    if (path.getLeaf() instanceof MethodTree) {
      method = (MethodTree) path.getLeaf();
    }
    if (clazz != null && method != null) {
      Symbol.ClassSymbol classSymbol = ASTHelpers.getSymbol(clazz);
      Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(method);
      if (methodIsEnclosingClass(classSymbol, methodSymbol)) {
        method = null;
      }
    }
  }

  /**
   * Returns true, if the method is enclosing the class.
   *
   * @param clazz class symbol.
   * @param method method symbol.
   * @return true, if method is enclosing class.
   */
  private static boolean methodIsEnclosingClass(
      Symbol.ClassSymbol clazz, Symbol.MethodSymbol method) {
    Symbol enclosingSymbol = method.getEnclosingElement();
    while (enclosingSymbol != null) {
      if (clazz.equals(enclosingSymbol)) {
        return false;
      }
      enclosingSymbol = enclosingSymbol.getEnclosingElement();
    }
    return true;
  }

  @Nullable
  public MethodTree getMethod() {
    return method;
  }

  @Nullable
  public ClassTree getClazz() {
    return clazz;
  }
}
