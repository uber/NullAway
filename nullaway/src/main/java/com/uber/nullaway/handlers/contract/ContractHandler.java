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

import static com.uber.nullaway.NullabilityUtil.castToNonNull;
import static com.uber.nullaway.handlers.contract.ContractUtils.getAntecedent;
import static com.uber.nullaway.handlers.contract.ContractUtils.getConsequent;

import com.google.common.base.Preconditions;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import com.uber.nullaway.dataflow.cfg.NullAwayCFGBuilder;
import com.uber.nullaway.handlers.BaseNoOpHandler;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.lang.model.type.TypeMirror;
import org.checkerframework.nullaway.dataflow.cfg.node.AbstractNodeVisitor;
import org.checkerframework.nullaway.dataflow.cfg.node.BinaryOperationNode;
import org.checkerframework.nullaway.dataflow.cfg.node.EqualToNode;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.nullaway.dataflow.cfg.node.Node;
import org.checkerframework.nullaway.dataflow.cfg.node.NotEqualNode;

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

  // Cached on visiting the top-level class and used for onCFGBuildPhase1AfterVisitMethodInvocation,
  // where no VisitorState is otherwise available.
  private @Nullable VisitorState storedVisitorState;

  private @Nullable TypeMirror runtimeExceptionType;

  public ContractHandler(Config config) {
    this.config = config;
  }

  @Override
  public void onMatchTopLevelClass(
      NullAway analysis, ClassTree tree, VisitorState state, Symbol.ClassSymbol classSymbol) {
    this.analysis = analysis;
    this.storedVisitorState = state;
  }

  @Override
  public MethodInvocationNode onCFGBuildPhase1AfterVisitMethodInvocation(
      NullAwayCFGBuilder.NullAwayCFGTranslationPhaseOne phase,
      MethodInvocationTree tree,
      MethodInvocationNode originalNode) {
    Preconditions.checkNotNull(storedVisitorState);
    Preconditions.checkNotNull(analysis);
    Symbol.MethodSymbol callee = ASTHelpers.getSymbol(tree);
    Preconditions.checkNotNull(callee);
    for (String clause : ContractUtils.getContractClauses(callee, config)) {
      // This method currently handles contracts of the form `(true|false) -> fail`, other
      // contracts are handled by 'onDataflowVisitMethodInvocation' which has access to more
      // dataflow information.
      if (!"fail".equals(getConsequent(clause, tree, analysis, storedVisitorState, callee))) {
        continue;
      }
      String[] antecedent =
          getAntecedent(
              clause,
              tree,
              analysis,
              storedVisitorState,
              callee,
              originalNode.getArguments().size());
      // Find a single value constraint that is not already known. If more than one argument with
      // unknown nullness affects the method's result, then ignore this clause.
      Node arg = null;
      // Set to false if the rule is detected to be one we don't yet support
      boolean supported = true;
      boolean booleanConstraint = false;

      for (int i = 0; i < antecedent.length; ++i) {
        String valueConstraint = antecedent[i].trim();
        if ("false".equals(valueConstraint) || "true".equals(valueConstraint)) {
          if (arg != null) {
            // We don't currently support contracts depending on the boolean value of more than one
            // argument using the node-insertion method.
            supported = false;
            break;
          }
          booleanConstraint = Boolean.parseBoolean(valueConstraint);
          arg = originalNode.getArgument(i);
        } else if (!valueConstraint.equals("_")) {
          // Found an unsupported type of constraint, only true, false, and '_' (wildcard) are
          // supported.
          // No need to implement complex handling here, 'onDataflowVisitMethodInvocation' will
          // validate the contract.
          supported = false;
          break;
        }
      }
      if (arg != null && supported) {
        if (runtimeExceptionType == null) {
          runtimeExceptionType = phase.classToErrorType(RuntimeException.class);
        }
        // In practice the failure may not be RuntimeException, however the conditional
        // throw is inserted after the method invocation where we must assume that
        // any invocation is capable of throwing an unchecked throwable.
        Preconditions.checkNotNull(runtimeExceptionType);
        if (booleanConstraint) {
          phase.insertThrowOnTrue(arg, runtimeExceptionType);
        } else {
          phase.insertThrowOnFalse(arg, runtimeExceptionType);
        }
      }
    }
    return originalNode;
  }

  @Override
  public NullnessHint onDataflowVisitMethodInvocation(
      MethodInvocationNode node,
      Symbol.MethodSymbol callee,
      VisitorState state,
      AccessPath.AccessPathContext apContext,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    Preconditions.checkNotNull(analysis);
    MethodInvocationTree tree = castToNonNull(node.getTree());
    for (String clause : ContractUtils.getContractClauses(callee, config)) {

      String[] antecedent =
          getAntecedent(clause, tree, analysis, state, callee, node.getArguments().size());
      String consequent = getConsequent(clause, tree, analysis, state, callee);

      // Find a single value constraint that is not already known. If more than one argument with
      // unknown nullness affects the method's result, then ignore this clause.
      Node arg = null;
      Nullness argAntecedentNullness = null;
      // Set to false if the rule is detected to be one we don't yet support
      boolean supported = true;

      for (int i = 0; i < antecedent.length; ++i) {
        String valueConstraint = antecedent[i].trim();
        if (valueConstraint.equals("_")) {
          continue;
        } else if (valueConstraint.equals("false") || valueConstraint.equals("true")) {
          // We handle boolean constraints in the case that the boolean argument is the result
          // of a null or not-null check. For example,
          // '@Contract("true -> true") boolean func(boolean v)'
          // called with 'func(obj == null)'
          // can be interpreted as equivalent to
          // '@Contract("null -> true") boolean func(@Nullable Object v)'
          // called with 'func(obj)'
          // This path unwraps null reference equality and inequality checks
          // to pass the target (in the above example, 'obj') as arg.
          Node argument = node.getArgument(i);
          // isNullTarget is the variable side of a null check. For example, both 'e == null'
          // and 'null == e' would return the node representing 'e'.
          Optional<Node> isNullTarget = argument.accept(NullEqualityVisitor.IS_NULL, inputs);
          // notNullTarget is the variable side of a not-null check. For example, both 'e != null'
          // and 'null != e' would return the node representing 'e'.
          Optional<Node> notNullTarget = argument.accept(NullEqualityVisitor.NOT_NULL, inputs);
          // It is possible for at most one of isNullTarget and notNullTarget to be present.
          Node nullTestTarget = isNullTarget.orElse(notNullTarget.orElse(null));
          if (nullTestTarget == null) {
            supported = false;
            break;
          }
          // isNullTarget is equivalent to 'null ->' while notNullTarget is equivalent
          // to '!null ->'. However, the valueConstraint may reverse the check.
          // The following table illustrates expected antecedentNullness based on
          // null comparison direction and constraint:
          //                    | (obj == null)   | (obj != null)
          // Constraint 'true'  | NULL            | NONNULL
          // Constraint 'false' | NONNULL         | NULL
          boolean booleanConstraintValue = valueConstraint.equals("true");
          Nullness antecedentNullness =
              isNullTarget.isPresent()
                  ? (booleanConstraintValue ? Nullness.NULL : Nullness.NONNULL)
                  :
                  // !isNullTarget.isPresent()  -> notNullTarget.present() must be true
                  (booleanConstraintValue ? Nullness.NONNULL : Nullness.NULL);
          Nullness targetNullness = inputs.valueOfSubNode(nullTestTarget);
          if (antecedentNullness.equals(targetNullness)) {
            // We already know this argument is satisfied so we can treat it as part of the
            // clause for the purpose of deciding the nullness of the other arguments.
            continue;
          }
          if (arg != null) {
            // More than one argument involved in the antecedent, ignore this rule
            supported = false;
            break;
          }
          arg = nullTestTarget;
          argAntecedentNullness = antecedentNullness;
        } else if (valueConstraint.equals("!null")
            && inputs.valueOfSubNode(node.getArgument(i)).equals(Nullness.NONNULL)) {
          // We already know this argument can't be null, so we can treat it as not part of the
          // clause for the purpose of deciding the non-nullness of the other arguments.
          continue;
        } else if (valueConstraint.equals("null") || valueConstraint.equals("!null")) {
          if (arg != null) {
            // More than one argument involved in the antecedent, ignore this rule
            supported = false;
            break;
          }
          arg = node.getArgument(i);
          argAntecedentNullness = valueConstraint.equals("null") ? Nullness.NULL : Nullness.NONNULL;
        } else {
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
                      tree,
                      analysis.buildDescription(tree),
                      state,
                      null));
          supported = false;
          break;
        }
      }
      if (!supported) {
        // Too many arguments involved, or unsupported @Contract features. On to next clause in the
        // contract expression
        continue;
      }
      if (arg == null) {
        // The antecedent is unconditionally true. Check for the ... -> !null case and set the
        // return nullness accordingly
        if (consequent.equals("!null")) {
          return NullnessHint.FORCE_NONNULL;
        }
        continue;
      }
      Preconditions.checkState(
          argAntecedentNullness != null, "argAntecedentNullness should have been set");
      // The nullness of one argument is all that matters for the antecedent, let's negate the
      // consequent to fix the nullness of this argument.
      AccessPath accessPath = AccessPath.getAccessPathForNode(arg, state, apContext);
      if (accessPath == null) {
        continue;
      }
      if (consequent.equals("false") && argAntecedentNullness.equals(Nullness.NULL)) {
        // If arg being null implies the return of the method being false, then the return
        // being true implies arg is not null and we must mark it as such in the then update.
        thenUpdates.set(accessPath, Nullness.NONNULL);
      } else if (consequent.equals("true") && argAntecedentNullness.equals(Nullness.NULL)) {
        // If arg being null implies the return of the method being true, then the return being
        // false implies arg is not null and we must mark it as such in the else update.
        elseUpdates.set(accessPath, Nullness.NONNULL);
      } else if (consequent.equals("fail") && argAntecedentNullness.equals(Nullness.NULL)) {
        // If arg being null implies the method throws an exception, then we can mark it as
        // non-null on both non-exceptional exits from the method
        bothUpdates.set(accessPath, Nullness.NONNULL);
      }
    }
    return NullnessHint.UNKNOWN;
  }

  /**
   * This visitor returns an {@link Optional<Node>} representing the non-null side of a null
   * comparison check.
   *
   * <p>When the visited node is not an equality check (either {@link EqualToNode} or {@link
   * NotEqualNode} based on {@link #equals} being {@code true} or {@code false} respectively)
   * against a null value, {@link Optional#empty()} is returned. For example, visiting {@code e ==
   * null} with {@code new NullEqualityVisitor(true)} would return an {@link Optional} of node
   * {@code e}.
   */
  private static final class NullEqualityVisitor
      extends AbstractNodeVisitor<Optional<Node>, AccessPathNullnessPropagation.SubNodeValues> {

    private static final NullEqualityVisitor IS_NULL = new NullEqualityVisitor(true);
    private static final NullEqualityVisitor NOT_NULL = new NullEqualityVisitor(false);

    private final boolean equals;

    NullEqualityVisitor(boolean equals) {
      this.equals = equals;
    }

    @Override
    public Optional<Node> visitNode(Node node, AccessPathNullnessPropagation.SubNodeValues unused) {
      return Optional.empty();
    }

    @Override
    public Optional<Node> visitNotEqual(
        NotEqualNode notEqualNode, AccessPathNullnessPropagation.SubNodeValues inputs) {
      if (equals) {
        return Optional.empty();
      } else {
        return visit(notEqualNode, inputs);
      }
    }

    @Override
    public Optional<Node> visitEqualTo(
        EqualToNode equalToNode, AccessPathNullnessPropagation.SubNodeValues inputs) {
      if (equals) {
        return visit(equalToNode, inputs);
      } else {
        return Optional.empty();
      }
    }

    private Optional<Node> visit(
        BinaryOperationNode comparison, AccessPathNullnessPropagation.SubNodeValues inputs) {
      Node lhs = comparison.getLeftOperand();
      Node rhs = comparison.getRightOperand();
      Nullness lhsNullness = inputs.valueOfSubNode(lhs);
      Nullness rhsNullness = inputs.valueOfSubNode(rhs);
      if (Nullness.NULL.equals(lhsNullness)) {
        return Optional.of(rhs);
      }
      if (Nullness.NULL.equals(rhsNullness)) {
        return Optional.of(lhs);
      }
      return Optional.empty();
    }
  }
}
