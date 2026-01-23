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
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPathNullnessAnalysis;
import com.uber.nullaway.handlers.Handler;
import com.uber.nullaway.handlers.MethodAnalysisContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * This Handler parses @Contract-style annotations (JetBrains or any annotation with simple name
 * "Contract", plus any configured custom annotations) and tries to check if the contract is
 * followed.
 *
 * <p>Currently, it supports the case when there is only one clause in the contract. The clause of
 * the form in which all the elements of the antecedent are either of "_", "null" or "!null", and
 * the consequent is "!null" is supported. The handler checks and warns under the conditions of the
 * antecedent if the consequent is "!null" and there is a return statement with "nullable" or "null"
 * expression.
 */
public class ContractCheckHandler implements Handler {

  private final Config config;

  /** A set of value constraints in the antecedent which we can check for now. */
  private final Set<String> checkableValueConstraints = Set.of("_", "null", "!null");

  /** All known valid value constraints */
  private final Set<String> allValidValueConstraints =
      Set.of("_", "null", "!null", "true", "false");

  public ContractCheckHandler(Config config) {
    this.config = config;
  }

  /**
   * Perform checks on any {@code @Contract} annotations on the method. By default, we check for
   * syntactic well-formedness of the annotation. If {@code config.checkContracts()} is true, we
   * also check that the method body is consistent with contracts whose value constraints are one of
   * "_", "null", or "!null" in the antecedent and "!null" in the consequent.
   *
   * @param tree The AST node for the method being matched.
   * @param methodAnalysisContext The MethodAnalysisContext object
   */
  @Override
  public void onMatchMethod(MethodTree tree, MethodAnalysisContext methodAnalysisContext) {
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
      NullAway analysis = methodAnalysisContext.analysis();
      VisitorState state = methodAnalysisContext.state();
      String[] antecedent =
          getAntecedent(clause, tree, analysis, state, callee, tree.getParameters().size());
      String consequent = getConsequent(clause, tree, analysis, state, callee);

      boolean checkMethodBody = config.checkContracts();

      for (int i = 0; i < antecedent.length; ++i) {
        String valueConstraint = antecedent[i].trim();
        if (!allValidValueConstraints.contains(valueConstraint)) {
          String errorMessage =
              "Invalid @Contract annotation detected for method "
                  + callee
                  + ". It contains the following unparseable clause: "
                  + clause
                  + " (unknown value constraint: "
                  + valueConstraint
                  + ", see https://www.jetbrains.com/help/idea/contract-annotations.html).";
          state.reportMatch(
              analysis
                  .getErrorBuilder()
                  .createErrorDescription(
                      new ErrorMessage(
                          ErrorMessage.MessageTypes.ANNOTATION_VALUE_INVALID, errorMessage),
                      tree,
                      analysis.buildDescription(tree),
                      state,
                      null));
          checkMethodBody = false;
        } else if (!checkableValueConstraints.contains(valueConstraint)) {
          checkMethodBody = false;
        }
      }

      if (!consequent.equals("!null")) {
        checkMethodBody = false;
      }

      if (!checkMethodBody) {
        return;
      }

      // we scan the method tree for the return nodes and check the contract
      new TreePathScanner<@Nullable Void, @Nullable Void>() {

        @Override
        public @Nullable Void visitLambdaExpression(
            LambdaExpressionTree node, @Nullable Void unused) {
          // do not scan into lambdas
          return null;
        }

        @Override
        public @Nullable Void visitClass(ClassTree node, @Nullable Void unused) {
          // do not scan into local/anonymous classes
          return null;
        }

        @Override
        public @Nullable Void visitReturn(ReturnTree returnTree, @Nullable Void unused) {

          ExpressionTree returnExpression = returnTree.getExpression();
          if (returnExpression == null) {
            // this should only be possible with an invalid @Contract on a void-returning method
            return null;
          }
          TreePath returnExpressionPath = new TreePath(getCurrentPath(), returnExpression);
          AccessPathNullnessAnalysis nullnessAnalysis = analysis.getNullnessAnalysis(state);
          List<TreePath> allPossiblyReturnedExpressions = new ArrayList<>();
          collectNestedReturnedExpressions(returnExpressionPath, allPossiblyReturnedExpressions);

          boolean contractViolated = false;
          for (TreePath expressionPath : allPossiblyReturnedExpressions) {
            Nullness nullness =
                nullnessAnalysis.getNullnessForContractDataflow(expressionPath, state.context);
            if (nullness == Nullness.NULLABLE || nullness == Nullness.NULL) {
              if (nullnessAnalysis.hasBottomAccessPathForContractDataflow(
                  expressionPath, state.context)) {
                // if any access path is mapped to bottom, this branch is unreachable
                continue;
              }
              contractViolated = true;
              break;
            }
          }

          if (contractViolated) {
            String errorMessage =
                getErrorMessageForViolatedContract(antecedent, callee, contractString, tree);

            state.reportMatch(
                analysis
                    .getErrorBuilder()
                    .createErrorDescription(
                        new ErrorMessage(
                            ErrorMessage.MessageTypes.ANNOTATION_VALUE_INVALID, errorMessage),
                        returnTree,
                        analysis.buildDescription(returnTree),
                        state,
                        null));
          }
          return super.visitReturn(returnTree, null);
        }
      }.scan(state.getPath(), null);
    }
  }

  private static String getErrorMessageForViolatedContract(
      String[] antecedent, Symbol.MethodSymbol callee, String contractString, MethodTree tree) {
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
    return errorMessage;
  }

  /**
   * Collect {@code TreePath}s to all nested expressions that may be returned, recursing through
   * parenthesized expressions and conditional expressions.
   *
   * @param expressionPath the TreePath to an expression being returned
   * @param output output parameter list to collect nested returned expression TreePaths
   */
  private static void collectNestedReturnedExpressions(
      TreePath expressionPath, List<TreePath> output) {
    ExpressionTree expression = (ExpressionTree) expressionPath.getLeaf();
    while (expression instanceof ParenthesizedTree) {
      ExpressionTree nestedExpression = ((ParenthesizedTree) expression).getExpression();
      expressionPath = new TreePath(expressionPath, nestedExpression);
      expression = nestedExpression;
    }
    if (expression instanceof ConditionalExpressionTree) {
      ConditionalExpressionTree conditionalExpression = (ConditionalExpressionTree) expression;
      TreePath truePath = new TreePath(expressionPath, conditionalExpression.getTrueExpression());
      TreePath falsePath = new TreePath(expressionPath, conditionalExpression.getFalseExpression());
      collectNestedReturnedExpressions(truePath, output);
      collectNestedReturnedExpressions(falsePath, output);
      return;
    }
    output.add(expressionPath);
  }
}
