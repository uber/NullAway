/*
 * MIT License
 *
 * Copyright (c) 2025 Nima Karimipour
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

package com.uber.nullaway.fixserialization.scanners;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.NullAway;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import org.jspecify.annotations.Nullable;

/**
 * Scanner that finds the origin of a variable. An origin is a variable that is either a field, a
 * parameter or a return result of a method invocation that contributes to the value of the
 * variable.
 */
public class OriginScanner extends AccumulatorScanner<Symbol> {

  private final NullAway.MayBeNullableInquiry inquiry;
  private final VisitorState state;

  public OriginScanner(NullAway.MayBeNullableInquiry inquiry, VisitorState state) {
    this.inquiry = inquiry;
    this.state = state;
  }

  @Override
  public Set<Symbol> visitAssignment(AssignmentTree node, Symbol target) {
    Tree variable = node.getVariable();
    Symbol symbol = ASTHelpers.getSymbol(variable);
    if (symbol != null && symbol.equals(target)) {
      ExpressionTree expr = node.getExpression();
      if (!inquiry.maybeNullable(expr, state)) {
        return Set.of();
      }
      return expr.accept(new ExpressionToSymbolScanner(state), inquiry);
    }
    return Set.of();
  }

  @Override
  public Set<Symbol> visitVariable(VariableTree node, Symbol target) {
    Symbol symbol = ASTHelpers.getSymbol(node);
    if (symbol != null && symbol.equals(target)) {
      if (node.getInitializer() == null) {
        return Set.of();
      }
      ExpressionTree initializer = node.getInitializer();
      if (!inquiry.maybeNullable(initializer, state)) {
        return Set.of();
      }
      return initializer.accept(new ExpressionToSymbolScanner(state), inquiry);
    }
    return Set.of();
  }

  @Override
  public Set<Symbol> visitEnhancedForLoop(EnhancedForLoopTree node, Symbol target) {
    Symbol variable = ASTHelpers.getSymbol(node.getVariable());
    if (variable != null && variable.equals(target)) {
      ExpressionTree expr = node.getExpression();
      if (!inquiry.maybeNullable(expr, state)) {
        return Set.of();
      }
      return expr.accept(new ExpressionToSymbolScanner(state), inquiry);
    }
    return Set.of();
  }

  /**
   * Retrieve the origins of a variable in a method.
   *
   * @param tree the enclosing method.
   * @param target the variable to find the origins of.
   * @return a set of symbols that are the origins of the variable.
   */
  public Set<Symbol> retrieveOrigins(@Nullable MethodTree tree, Symbol target) {
    if (tree == null) {
      return Set.of();
    }
    if (isOriginal(target)) {
      return Set.of(target);
    }
    Set<Symbol> result = new HashSet<>();
    Queue<Symbol> queue = new ArrayDeque<>();
    queue.add(target);
    Set<Symbol> visited = new HashSet<>();
    while (!queue.isEmpty()) {
      Symbol current = queue.poll();
      if (visited.contains(current)) {
        continue;
      }
      visited.add(current);
      Set<Symbol> involvedSymbols = tree.accept(this, current);
      involvedSymbols.forEach(
          symbol -> {
            if (isOriginal(symbol)) {
              result.add(symbol);
            } else {
              if (!visited.contains(symbol)) {
                queue.add(symbol);
              }
            }
          });
    }
    return result;
  }

  /**
   * Check if the symbol is an original symbol. An original symbol is a field, a parameter or a
   * method return call.
   *
   * @param symbol the symbol to check
   * @return true if the symbol is an original symbol, false otherwise
   */
  private static boolean isOriginal(Symbol symbol) {
    // An exception parameter is not an original symbol but we are not interested in their origins.
    return !symbol.getKind().equals(ElementKind.LOCAL_VARIABLE)
        && !symbol.getKind().equals(ElementKind.RESOURCE_VARIABLE);
  }
}
