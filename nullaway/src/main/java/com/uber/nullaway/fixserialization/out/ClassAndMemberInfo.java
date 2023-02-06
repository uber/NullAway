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

import com.google.common.base.Preconditions;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import javax.annotation.Nullable;
import javax.lang.model.element.ElementKind;

/** Class and member corresponding to a program point at which an error / fix was reported. */
public class ClassAndMemberInfo {
  /** Path to the program point of the reported error / fix */
  @Nullable public TreePath path;

  // Finding values for these properties is costly and are not needed by default, hence, they are
  // not final and are only initialized at request.
  @Nullable private Symbol member;

  @Nullable private Symbol.ClassSymbol clazz;

  public ClassAndMemberInfo(TreePath path) {
    Preconditions.checkNotNull(path);
    this.path = path;
  }

  public ClassAndMemberInfo(Tree regionTree) {
    // regionTree should either represent a field or a method
    Symbol symbol = ASTHelpers.getSymbol(regionTree);
    if (!(regionTree instanceof MethodTree
        || (regionTree instanceof VariableTree
            && symbol != null
            && symbol.getKind().equals(ElementKind.FIELD)))) {
      throw new RuntimeException(
          "not expecting a region tree " + regionTree + " of type " + regionTree.getClass());
    }
    this.member = symbol;
    this.clazz = ASTHelpers.enclosingClass(this.member);
  }

  /** Finds the class and member where the error / fix is reported according to {@code path}. */
  public void findValues() {
    if (this.member != null || path == null) {
      // Values are already computed.
      return;
    }
    MethodTree enclosingMethod;
    // If the error is reported on a method, that method itself is the relevant program point.
    // Otherwise, use the enclosing method (if present).
    enclosingMethod =
        path.getLeaf() instanceof MethodTree
            ? (MethodTree) path.getLeaf()
            : ASTHelpers.findEnclosingNode(path, MethodTree.class);
    // If the error is reported on a class, that class itself is the relevant program point.
    // Otherwise, use the enclosing class.
    ClassTree classTree =
        path.getLeaf() instanceof ClassTree
            ? (ClassTree) path.getLeaf()
            : ASTHelpers.findEnclosingNode(path, ClassTree.class);
    if (classTree != null) {
      clazz = ASTHelpers.getSymbol(classTree);
      if (enclosingMethod != null) {
        // It is possible that the computed method is not enclosed by the computed class, e.g., for
        // the following case:
        //  class C {
        //    void foo() {
        //      class Local {
        //        Object f = null; // error
        //      }
        //    }
        //  }
        // Here the above code will compute clazz to be Local and method as foo().  In such cases,
        // set method to null, we always want the corresponding method to be nested in the
        // corresponding class.
        Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(enclosingMethod);
        if (!methodSymbol.isEnclosedBy(clazz)) {
          enclosingMethod = null;
        }
      }
      if (enclosingMethod != null) {
        member = ASTHelpers.getSymbol(enclosingMethod);
      } else {
        // Node is not enclosed by any method, can be a field declaration or enclosed by it.
        Symbol sym = ASTHelpers.getSymbol(path.getLeaf());
        Symbol.VarSymbol fieldSymbol = null;
        if (sym != null && sym.getKind().isField() && sym.isEnclosedBy(clazz)) {
          // Directly on a field declaration.
          fieldSymbol = (Symbol.VarSymbol) sym;
        } else {
          // Can be enclosed by a field declaration tree.
          VariableTree fieldDeclTree = ASTHelpers.findEnclosingNode(path, VariableTree.class);
          if (fieldDeclTree != null) {
            fieldSymbol = ASTHelpers.getSymbol(fieldDeclTree);
          }
        }
        if (fieldSymbol != null && fieldSymbol.isEnclosedBy(clazz)) {
          member = fieldSymbol;
        }
      }
    }
  }

  @Nullable
  public Symbol getMember() {
    return member;
  }

  @Nullable
  public Symbol.ClassSymbol getClazz() {
    return clazz;
  }
}
