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

import com.google.common.collect.Sets;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.code.Symbol;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiPredicate;
import org.jspecify.annotations.Nullable;

/**
 * Scanner that finds the symbols of all identifiers in expressions.
 *
 * <p>The scanner's parameter (the {@code P} type argument of {@link TreeScanner}) is a nullability
 * test: given the {@link VisitorState} and an expression, the {@link BiPredicate} returns {@code
 * true} if the expression may be {@code @Nullable}. Only symbols of expressions that pass this test
 * are collected.
 */
public class ExpressionToSymbolScanner
    extends TreeScanner<Set<Symbol>, BiPredicate<VisitorState, ExpressionTree>> {

  private final VisitorState state;

  public ExpressionToSymbolScanner(VisitorState state) {
    this.state = state;
  }

  /**
   * Returns a {@link VisitorState} whose path locates {@code node} within the current compilation
   * unit.
   *
   * <p>Origin tracing walks the entire enclosing method, so the nullability {@code inquiry} for a
   * visited expression must run against that expression's own path rather than the fixed diagnostic
   * path. Otherwise path-sensitive checks (dataflow, generic-return inference) either read the
   * wrong program point or fail outright (e.g. {@code mayBeNullExpr} searching for a method
   * invocation as a descendant of the dereference site). Falls back to {@code state} if the path
   * cannot be found.
   */
  static VisitorState stateForTree(VisitorState state, Tree node) {
    TreePath path = TreePath.getPath(state.getPath().getCompilationUnit(), node);
    return path == null ? state : state.withPath(path);
  }

  @Override
  public Set<Symbol> reduce(@Nullable Set<Symbol> r1, @Nullable Set<Symbol> r2) {
    if (r1 == null) {
      return r2 == null ? Set.of() : r2;
    }
    if (r2 == null) {
      return r1;
    }
    return Sets.union(r1, r2);
  }

  /**
   * Returns a singleton set with the symbol of {@code node} if {@code node} passes the nullability
   * test, or an empty set otherwise.
   */
  private Set<Symbol> symbolIfNullable(
      ExpressionTree node, BiPredicate<VisitorState, ExpressionTree> inquiry) {
    if (!inquiry.test(stateForTree(state, node), node)) {
      return Set.of();
    }
    Symbol symbol = defaultResult(node);
    return symbol == null ? Set.of() : Set.of(symbol);
  }

  private @Nullable Symbol defaultResult(ExpressionTree node) {
    Symbol symbol = ASTHelpers.getSymbol(node);
    if (symbol != null) {
      return switch (symbol.getKind()) {
        case FIELD, PARAMETER, LOCAL_VARIABLE, METHOD, RESOURCE_VARIABLE -> symbol;
        default -> null;
      };
    }
    return null;
  }

  @Override
  public Set<Symbol> visitLiteral(
      LiteralTree node, BiPredicate<VisitorState, ExpressionTree> inquiry) {
    return Set.of();
  }

  @Override
  public Set<Symbol> visitIdentifier(
      IdentifierTree node, BiPredicate<VisitorState, ExpressionTree> inquiry) {
    return symbolIfNullable(node, inquiry);
  }

  @Override
  public Set<Symbol> visitMemberReference(
      MemberReferenceTree node, BiPredicate<VisitorState, ExpressionTree> inquiry) {
    return symbolIfNullable(node, inquiry);
  }

  @Override
  public Set<Symbol> visitMemberSelect(
      MemberSelectTree node, BiPredicate<VisitorState, ExpressionTree> inquiry) {
    return symbolIfNullable(node, inquiry);
  }

  @Override
  public Set<Symbol> visitMethodInvocation(
      MethodInvocationTree node, BiPredicate<VisitorState, ExpressionTree> inquiry) {
    return symbolIfNullable(node, inquiry);
  }

  @Override
  public Set<Symbol> visitConditionalExpression(
      ConditionalExpressionTree node, BiPredicate<VisitorState, ExpressionTree> inquiry) {
    if (!inquiry.test(stateForTree(state, node), node)) {
      return Set.of();
    }
    Set<Symbol> symbols = new HashSet<>();
    if (inquiry.test(stateForTree(state, node.getTrueExpression()), node.getTrueExpression())) {
      symbols.addAll(node.getTrueExpression().accept(this, inquiry));
    }
    if (inquiry.test(stateForTree(state, node.getFalseExpression()), node.getFalseExpression())) {
      symbols.addAll(node.getFalseExpression().accept(this, inquiry));
    }
    return symbols;
  }
}
