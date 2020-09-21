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

package com.uber.nullaway.handlers.contract;

import static com.uber.nullaway.handlers.contract.ContractUtils.getAntecedent;
import static com.uber.nullaway.handlers.contract.ContractUtils.getConsequent;
import static com.uber.nullaway.handlers.contract.ContractUtils.getContractFromAnnotation;
import static com.uber.nullaway.handlers.contract.ContractUtils.reportMatchForContractIssue;

import com.google.common.base.Preconditions;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.NullabilityUtil;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.handlers.BaseNoOpHandler;
import java.util.HashSet;
import java.util.Set;

/**
 * This Handler parses the jetbrains @Contract annotation and tries to check if the contract is
 * followed.
 *
 * <p>Currently, it supports the case when there is only one clause in the contract. The clause of
 * the form in which all the elements of the antecedent are either of "_", "null" or "!null", and
 * the consequent is "!null" is supported. The handler checks and warns under the conditions of the
 * antecedent if the consequent is "!null" and there is a return statement with "nullable" or "null"
 * expression.
 */
public class ContractCheckHandler extends BaseNoOpHandler {

  private Set<TreePath> supportedMethods = new HashSet<>();

  @Override
  public void onMatchMethod(
      NullAway analysis, MethodTree tree, VisitorState state, Symbol.MethodSymbol methodSymbol) {
    Symbol.MethodSymbol callee = ASTHelpers.getSymbol(tree);
    Preconditions.checkNotNull(callee);
    // Check to see if this method has an @Contract annotation
    String contractString = getContractFromAnnotation(callee);

    if (contractString != null) {
      // Found a contract, lets parse it.
      String[] clauses = contractString.split(";");
      if (clauses.length != 1) {
        return;
      }

      String clause = clauses[0];
      String[] antecedent =
          getAntecedent(clause, tree, analysis, state, callee, tree.getParameters().size());
      String consequent = getConsequent(clause, tree, analysis, state, callee);

      boolean supported = true;

      for (int i = 0; i < antecedent.length; ++i) {
        String valueConstraint = antecedent[i].trim();
        if (!(valueConstraint.equals("_")
            || valueConstraint.equals("!null")
            || valueConstraint.equals("null"))) {
          supported = false;
        }
      }

      if (!consequent.equals("!null")) {
        supported = false;
      }

      if (!supported) {
        return;
      }

      supportedMethods.add(state.getPath());
    }
  }

  @Override
  public void onMatchReturn(NullAway analysis, ReturnTree tree, VisitorState state) {
    final TreePath enclosingMethod =
        NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(state.getPath());
    if (!supportedMethods.contains(enclosingMethod)) {
      return;
    }

    final Nullness nullness =
        analysis
            .getNullnessAnalysis(state)
            .getNullnessForContractDataflow(
                new TreePath(state.getPath(), tree.getExpression()), state.context);

    if (nullness == Nullness.NULLABLE || nullness == Nullness.NULL) {
      reportMatchForContractIssue(
          tree,
          "@Contract might not be followed, as returning @Nullable from method with @NonNull return expected in contract",
          analysis,
          state);
    }
  }

  @Override
  public void onMatchTopLevelClass(
      NullAway analysis, ClassTree tree, VisitorState state, Symbol.ClassSymbol classSymbol) {
    // Clear compilation unit specific state
    this.supportedMethods.clear();
  }
}
