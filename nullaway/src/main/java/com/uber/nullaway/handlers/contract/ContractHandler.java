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
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import com.uber.nullaway.handlers.BaseNoOpHandler;
import javax.annotation.Nullable;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;

/**
 * This Handler parses the jetbrains @Contract annotation and honors the nullness spec defined there
 * on a best effort basis.
 *
 * <p>Currently, we can only reason about cases where the contract specifies that the return value
 * of the method depends on the nullness value of a single argument. This means we can reason about
 * rules like the following:
 *
 * <ul>
 *   <li>@Contract("null -> true")
 *   <li>@Contract("_, null, _ -> false")
 *   <li>@Contract("!null, _ -> false; null, _ -> true")
 *   <li>@Contract("!null -> !null")
 * </ul>
 *
 * In the last case, nullness will be propagated iff the nullness of the argument is already known
 * at invocation.
 *
 * <p>However, when the return depends on multiple arguments, this handler usually ignores the rule,
 * since it is not clear which of the values in question are null or not. For example,
 * for @Contract("null, null -> true") we know nothing when the method returns true (because truth
 * of the consequent doesn't imply truth of the antecedent), and if it return false, we only know
 * that at least one of the two arguments was non-null, but can't know for sure which one. NullAway
 * doesn't reason about multiple value conditional nullness constraints in any general way.
 *
 * <p>In some cases, this handler can determine that some arguments are already known to be non-null
 * and reason in terms of the remaining (under-constrained) arguments, to see if the final value of
 * this method depends on the nullness of a single argument for this callsite, even if the @Contract
 * clause is given in terms of many. This is not behavior that should be counted on, but it is
 * sound.
 */
public class ContractHandler extends BaseNoOpHandler {

  private final Config config;

  private @Nullable NullAway analysis;
  private @Nullable VisitorState state;

  public ContractHandler(Config config) {
    this.config = config;
  }

  @Override
  public void onMatchTopLevelClass(
      NullAway analysis, ClassTree tree, VisitorState state, Symbol.ClassSymbol classSymbol) {
    this.analysis = analysis;
    this.state = state;
  }

  @Override
  public NullnessHint onDataflowVisitMethodInvocation(
      MethodInvocationNode node,
      Types types,
      Context context,
      AccessPath.AccessPathContext apContext,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    Symbol.MethodSymbol callee = ASTHelpers.getSymbol(node.getTree());
    Preconditions.checkNotNull(callee);
    // Check to see if this method has an @Contract annotation
    String contractString = ContractUtils.getContractString(callee, config);
    if (contractString != null && contractString.trim().length() > 0) {
      // Found a contract, lets parse it.
      String[] clauses = contractString.split(";");
      for (String clause : clauses) {

        String[] antecedent =
            getAntecedent(
                clause, node.getTree(), analysis, state, callee, node.getArguments().size());
        String consequent = getConsequent(clause, node.getTree(), analysis, state, callee);

        // Find a single value constraint that is not already known. If more than one arguments with
        // unknown
        // nullness affect the method's result, then ignore this clause.
        int argIdx = -1;
        Nullness argAntecedentNullness = null;
        boolean supported =
            true; // Set to false if the rule is detected to be one we don't yet support

        for (int i = 0; i < antecedent.length; ++i) {
          String valueConstraint = antecedent[i].trim();
          if (valueConstraint.equals("_")) {
            continue;
          } else if (valueConstraint.equals("false") || valueConstraint.equals("true")) {
            supported = false;
            break;
          } else if (valueConstraint.equals("!null")
              && inputs.valueOfSubNode(node.getArgument(i)).equals(Nullness.NONNULL)) {
            // We already know this argument can't be null, so we can treat it as not part of the
            // clause
            // for the purpose of deciding the non-nullness of the other arguments.
            continue;
          } else if (valueConstraint.equals("null") || valueConstraint.equals("!null")) {
            if (argIdx != -1) {
              // More than one argument involved in the antecedent, ignore this rule
              supported = false;
              break;
            }
            argIdx = i;
            argAntecedentNullness =
                valueConstraint.equals("null") ? Nullness.NULLABLE : Nullness.NONNULL;
          } else {
            Preconditions.checkNotNull(state);
            Preconditions.checkNotNull(analysis);
            String errorMessage =
                "Invalid @Contract annotation detected for method "
                    + callee
                    + ". It contains the following uparseable clause: "
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
                        node.getTree(),
                        analysis.buildDescription(node.getTree()),
                        state));
            supported = false;
            break;
          }
        }
        if (!supported) {
          // Too many arguments involved, or unsupported @Contract features. On to next clause in
          // the
          // contract expression
          continue;
        }
        if (argIdx == -1) {
          // The antecedent is unconditionally true. Check for the ... -> !null case and set the
          // return nullness accordingly
          if (consequent.equals("!null")) {
            return NullnessHint.FORCE_NONNULL;
          }
          continue;
        }
        assert argAntecedentNullness != null;
        // The nullness of one argument is all that matters for the antecedent, let's negate the
        // consequent to fix the nullness of this argument.
        AccessPath accessPath =
            AccessPath.getAccessPathForNodeNoMapGet(node.getArgument(argIdx), apContext);
        if (accessPath == null) {
          continue;
        }
        if (consequent.equals("false") && argAntecedentNullness.equals(Nullness.NULLABLE)) {
          // If argIdx being null implies the return of the method being false, then the return
          // being true implies argIdx is not null and we must mark it as such in the then update.
          thenUpdates.set(accessPath, Nullness.NONNULL);
        } else if (consequent.equals("true") && argAntecedentNullness.equals(Nullness.NULLABLE)) {
          // If argIdx being null implies the return of the method being true, then the return being
          // false implies argIdx is not null and we must mark it as such in the else update.
          elseUpdates.set(accessPath, Nullness.NONNULL);
        } else if (consequent.equals("fail") && argAntecedentNullness.equals(Nullness.NULLABLE)) {
          // If argIdx being null implies the method throws an exception, then we can mark it as
          // non-null on both non-exceptional exits from the method
          bothUpdates.set(accessPath, Nullness.NONNULL);
        }
      }
    }
    return NullnessHint.UNKNOWN;
  }
}
