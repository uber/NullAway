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

import com.google.common.base.Preconditions;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.handlers.BaseNoOpHandler;

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

  private final Config config;

  public ContractCheckHandler(Config config) {
    this.config = config;
  }

  @Override
  public void onMatchMethod(
      NullAway analysis, MethodTree tree, VisitorState state, Symbol.MethodSymbol methodSymbol) {
    Symbol.MethodSymbol callee = ASTHelpers.getSymbol(tree);
    Preconditions.checkNotNull(callee);
    // Check to see if this method has an @Contract annotation
    String contractString = ContractUtils.getContractString(callee, config);
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

      // we scan the method tree for the return nodes and check the contract
      new TreePathScanner<Void, Void>() {
        @Override
        public Void visitReturn(ReturnTree returnTree, Void unused) {

          final VisitorState returnState = state.withPath(getCurrentPath());
          final Nullness nullness =
              analysis
                  .getNullnessAnalysis(returnState)
                  .getNullnessForContractDataflow(
                      new TreePath(returnState.getPath(), returnTree.getExpression()),
                      returnState.context);

          if (nullness == Nullness.NULLABLE || nullness == Nullness.NULL) {

            String errorMessage;

            // used for error message
            int nonNullAntecedentCount = 0;
            int nonNullAntecedentPosition = -1;

            for (int i = 0; i < antecedent.length; ++i) {
              String valueConstraint = antecedent[i].trim();

              if (valueConstraint.equals("!null")) {
                nonNullAntecedentCount += 1;
                nonNullAntecedentPosition = i;
              }
            }

            if (nonNullAntecedentCount == 1) {

              errorMessage =
                  "Method "
                      + callee.name
                      + " has @Contract("
                      + contractString
                      + "), but this appears to be violated, as a @Nullable value may be returned when parameter "
                      + tree.getParameters().get(nonNullAntecedentPosition).getName()
                      + " is non-null.";
            } else {
              errorMessage =
                  "Method "
                      + callee.name
                      + " has @Contract("
                      + contractString
                      + "), but this appears to be violated, as a @Nullable value may be returned "
                      + "when the contract preconditions are true.";
            }

            returnState.reportMatch(
                analysis
                    .getErrorBuilder()
                    .createErrorDescription(
                        new ErrorMessage(
                            ErrorMessage.MessageTypes.ANNOTATION_VALUE_INVALID, errorMessage),
                        returnTree,
                        analysis.buildDescription(returnTree),
                        returnState));
          }
          return super.visitReturn(returnTree, unused);
        }
      }.scan(state.getPath(), null);
    }
  }
}
