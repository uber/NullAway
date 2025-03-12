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
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.NullAway;
import java.util.HashSet;
import java.util.Set;

/** Scanner that finds the symbols of all identifiers in expressions. */
public class ExpressionToSymbolScanner extends AccumulatorScanner<NullAway.MayBeNullableInquiry> {

  private final VisitorState state;

  public ExpressionToSymbolScanner(VisitorState state) {
    this.state = state;
  }

  private Symbol defaultResult(ExpressionTree node) {
    Symbol symbol = ASTHelpers.getSymbol(node);
    if (symbol != null) {
      switch (symbol.getKind()) {
        case FIELD:
        case PARAMETER:
        case LOCAL_VARIABLE:
        case METHOD:
        case RESOURCE_VARIABLE:
          return symbol;
        default:
          return null;
      }
    }
    return null;
  }

  @Override
  public Set<Symbol> visitIdentifier(IdentifierTree node, NullAway.MayBeNullableInquiry inquiry) {
    boolean isNullable = inquiry.maybeNullable(node, state);
    if (!isNullable) {
      return Set.of();
    }
    Symbol symbol = defaultResult(node);
    return symbol == null ? Set.of() : Set.of(symbol);
  }

  @Override
  public Set<Symbol> visitMemberReference(
      MemberReferenceTree node, NullAway.MayBeNullableInquiry inquiry) {
    boolean isNullable = inquiry.maybeNullable(node, state);
    if (!isNullable) {
      return Set.of();
    }
    Symbol symbol = defaultResult(node);
    return symbol == null ? Set.of() : Set.of(symbol);
  }

  @Override
  public Set<Symbol> visitMemberSelect(
      MemberSelectTree node, NullAway.MayBeNullableInquiry inquiry) {
    boolean isNullable = inquiry.maybeNullable(node, state);
    if (!isNullable) {
      return Set.of();
    }
    Symbol symbol = defaultResult(node);
    return symbol == null ? Set.of() : Set.of(symbol);
  }

  @Override
  public Set<Symbol> visitConditionalExpression(
      ConditionalExpressionTree node, NullAway.MayBeNullableInquiry inquiry) {
    if (!inquiry.maybeNullable(node, state)) {
      return Set.of();
    }
    Set<Symbol> symbols = new HashSet<>();
    if (inquiry.maybeNullable(node.getTrueExpression(), state)) {
      symbols.addAll(node.getTrueExpression().accept(this, inquiry));
    }
    if (inquiry.maybeNullable(node.getFalseExpression(), state)) {
      symbols.addAll(node.getFalseExpression().accept(this, inquiry));
    }
    return symbols;
  }
}
