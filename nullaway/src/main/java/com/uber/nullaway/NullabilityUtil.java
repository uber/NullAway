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

package com.uber.nullaway;

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.JCDiagnostic;

import java.util.List;

import javax.annotation.Nullable;

/**
 * Helpful utility methods for nullability analysis.
 */
public class NullabilityUtil {

    static final ImmutableSet<String> OBJECT_METHOD_NAMES = ImmutableSet.of(
            "equals",
            "hashCode",
            "toString",
            "finalize",
            "clone",
            "notify",
            "notifyAll",
            "wait"
    );

    private NullabilityUtil() { }

    /**
     * finds the corresponding functional interface method for a lambda expression
     * @param tree the lambda expression
     * @return the functional interface method
     */
    public static Symbol.MethodSymbol getFunctionalInterfaceMethod(LambdaExpressionTree tree) {
        Type funcInterfaceType = ((JCTree.JCLambda) tree).type;
        // we want the method symbol for the single function inside the interface...hrm
        List<Symbol> enclosedElements = funcInterfaceType.tsym.getEnclosedElements();

        Symbol.MethodSymbol result = null;
        for (Symbol s: enclosedElements) {
            Symbol.MethodSymbol elem = (Symbol.MethodSymbol) s;
            if (elem.isDefault() || elem.isStatic()) {
                continue;
            }
            String name = elem.getSimpleName().toString();
            // any methods overridding java.lang.Object methods don't count;
            // see https://docs.oracle.com/javase/8/docs/api/java/lang/FunctionalInterface.html
            // we should really be checking method signatures here; hack for now
            if (OBJECT_METHOD_NAMES.contains(name)) {
                continue;
            }
            if (result != null) {
                throw new RuntimeException("already found an answer! " + result + " " + elem + " " + enclosedElements);
            }
            result = elem;
        }
        if (result == null) {
            throw new RuntimeException("could not find functional interface method in " + enclosedElements);
        }
        return result;
    }

    /**
     * determines whether a lambda parameter has an explicit type declaration
     * @param lambdaParameter the parameter
     * @return true if there is a type declaration, false otherwise
     */
    public static boolean lambdaParamIsExplicitlyTyped(VariableTree lambdaParameter) {
        // kind of a hack; the "preferred position" seems to be the position
        // of the variable name.  if this differs from the start position, it
        // means there is an explicit type declaration
        JCDiagnostic.DiagnosticPosition diagnosticPosition = (JCDiagnostic.DiagnosticPosition) lambdaParameter;
        return diagnosticPosition.getStartPosition() != diagnosticPosition.getPreferredPosition();
    }

    /**
     * finds the symbol for the top-level class containing the given symbol
     * @param symbol the given symbol
     * @return symbol for the non-nested enclosing class
     */
    public static Symbol.ClassSymbol getOutermostClassSymbol(Symbol symbol) {
        // get the symbol for the outermost enclosing class.  this handles
        // the case of anonymous classes
        Symbol.ClassSymbol outermostClassSymbol = ASTHelpers.enclosingClass(symbol);
        while (outermostClassSymbol.getNestingKind().isNested()) {
            Symbol.ClassSymbol enclosingSymbol = ASTHelpers.enclosingClass(outermostClassSymbol.owner);
            if (enclosingSymbol != null) {
                outermostClassSymbol = enclosingSymbol;
            } else {
                // enclosingSymbol can be null in weird cases like for array methods
                break;
            }
        }
        return outermostClassSymbol;
    }

    /**
     * find the enclosing method or lambda expression for the leaf of some tree path
     * @param path the tree path
     * @return the closest enclosing method / lambda
     */
    @Nullable
    public static TreePath findEnclosingMethodOrLambda(TreePath path) {
        while (path != null) {
            if (path.getLeaf() instanceof MethodTree || path.getLeaf() instanceof LambdaExpressionTree) {
                return path;
            }
            path = path.getParentPath();
        }
        return null;
    }
}
