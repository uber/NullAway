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

import com.google.common.base.Preconditions;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.TargetType;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.JCDiagnostic;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;

/** Helpful utility methods for nullability analysis. */
public class NullabilityUtil {

  private NullabilityUtil() {}

  /**
   * finds the corresponding functional interface method for a lambda expression or method reference
   *
   * @param tree the lambda expression or method reference
   * @return the functional interface method
   */
  public static Symbol.MethodSymbol getFunctionalInterfaceMethod(ExpressionTree tree, Types types) {
    Preconditions.checkArgument(
        (tree instanceof LambdaExpressionTree) || (tree instanceof MemberReferenceTree));
    Type funcInterfaceType = ((JCTree.JCFunctionalExpression) tree).type;
    return (Symbol.MethodSymbol) types.findDescriptorSymbol(funcInterfaceType.tsym);
  }

  /**
   * determines whether a lambda parameter is missing an explicit type declaration
   *
   * @param lambdaParameter the parameter
   * @return true if there is no type declaration, false otherwise
   */
  public static boolean lambdaParamIsImplicitlyTyped(VariableTree lambdaParameter) {
    // kind of a hack; the "preferred position" seems to be the position
    // of the variable name.  if this differs from the start position, it
    // means there is an explicit type declaration
    JCDiagnostic.DiagnosticPosition diagnosticPosition =
        (JCDiagnostic.DiagnosticPosition) lambdaParameter;
    return diagnosticPosition.getStartPosition() == diagnosticPosition.getPreferredPosition();
  }

  /**
   * finds the symbol for the top-level class containing the given symbol
   *
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
   * find the enclosing method, lambda expression or initializer block for the leaf of some tree
   * path
   *
   * @param path the tree path
   * @return the closest enclosing method / lambda
   */
  @Nullable
  public static TreePath findEnclosingMethodOrLambdaOrInitializer(TreePath path) {
    while (path != null) {
      if (path.getLeaf() instanceof MethodTree || path.getLeaf() instanceof LambdaExpressionTree) {
        return path;
      }
      TreePath parent = path.getParentPath();
      if (parent != null && parent.getLeaf() instanceof ClassTree) {
        if (path.getLeaf() instanceof BlockTree) {
          // found initializer block
          return path;
        }
        if (path.getLeaf() instanceof VariableTree
            && ((VariableTree) path.getLeaf()).getInitializer() != null) {
          // found field with an inline initializer
          return path;
        }
      }
      path = parent;
    }
    return null;
  }

  /**
   * @param element the element
   * @return all annotations on the element and on the type of the element
   */
  public static Stream<? extends AnnotationMirror> getAllAnnotations(Element element) {
    // for methods, we care about annotations on the return type, not on the method type itself
    Stream<? extends AnnotationMirror> typeUseAnnotations = getTypeUseAnnotations((Symbol) element);
    return Stream.concat(element.getAnnotationMirrors().stream(), typeUseAnnotations);
  }

  public static Stream<? extends AnnotationMirror> getAllAnnotationsForParameter(
      Symbol.MethodSymbol symbol, int paramInd) {
    Symbol.VarSymbol varSymbol = symbol.getParameters().get(paramInd);
    return Stream.concat(
        varSymbol.getAnnotationMirrors().stream(),
        symbol
            .getRawTypeAttributes()
            .stream()
            .filter(
                t ->
                    t.position.type.equals(TargetType.METHOD_FORMAL_PARAMETER)
                        && t.position.parameter_index == paramInd));
  }

  private static Stream<? extends AnnotationMirror> getTypeUseAnnotations(Symbol symbol) {
    Stream<Attribute.TypeCompound> rawTypeAttributes = symbol.getRawTypeAttributes().stream();
    if (symbol instanceof Symbol.MethodSymbol) {
      return rawTypeAttributes.filter((t) -> t.position.type.equals(TargetType.METHOD_RETURN));
    }
    return rawTypeAttributes;
  }

  /**
   * @param symbol symbol for field
   * @param config NullAway config
   * @return true if based on the type, package, and name of the field, the analysis should assume
   *     the field might be null; false otherwise
   */
  public static boolean mayBeNullFieldFromType(@Nullable Symbol symbol, Config config) {
    if (symbol == null) {
      return true;
    }
    return !(symbol.getSimpleName().toString().equals("class")
            || symbol.isEnum()
            || isUnannotated(symbol, config))
        && Nullness.hasNullableAnnotation(symbol);
  }

  /**
   * @param symbol symbol for entity
   * @param config NullAway config
   * @return true if symbol represents an entity from a class that is unannotated; false otherwise
   */
  public static boolean isUnannotated(Symbol symbol, Config config) {
    Symbol.ClassSymbol outermostClassSymbol = getOutermostClassSymbol(symbol);
    return !config.fromAnnotatedPackage(outermostClassSymbol)
        || config.isUnannotatedClass(outermostClassSymbol);
  }
}
