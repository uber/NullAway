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

package com.uber.nullaway.handlers;

import com.google.common.base.Preconditions;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import java.util.Map;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;

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

  private @Nullable NullAway analysis;
  private @Nullable VisitorState state;

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
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    Symbol.MethodSymbol callee = ASTHelpers.getSymbol(node.getTree());
    Preconditions.checkNotNull(callee);
    // Check to see if this method has an @Contract annotation
    String contractString = getContractFromAnnotation(callee);
    if (contractString != null) {
      // Found a contract, lets parse it.
      String[] clauses = contractString.split(";");
      for (String clause : clauses) {
        String[] parts = clause.split("->");
        if (parts.length != 2) {
          reportMatch(
              node.getTree(),
              "Invalid @Contract annotation detected for method "
                  + callee
                  + ". It contains the following uparseable clause: "
                  + clause
                  + "(see https://www.jetbrains.com/help/idea/contract-annotations.html).");
        }
        String[] antecedent = parts[0].split(",");
        String consequent = parts[1].trim();
        // Find a single value constraint that is not already known. If more than one arguments with
        // unknown
        // nullness affect the method's result, then ignore this clause.
        int argIdx = -1;
        Nullness argAntecedentNullness = null;
        boolean supported =
            true; // Set to false if the rule is detected to be one we don't yet support
        if (antecedent.length != node.getArguments().size()) {
          reportMatch(
              node.getTree(),
              "Invalid @Contract annotation detected for method "
                  + callee
                  + ". It contains the following uparseable clause: "
                  + clause
                  + " (incorrect number of arguments in the clause's antecedent ["
                  + antecedent.length
                  + "], should be the same as the number of "
                  + "arguments in for the method ["
                  + node.getArguments().size()
                  + "]).");
        }
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
            reportMatch(
                node.getTree(),
                "Invalid @Contract annotation detected for method "
                    + callee
                    + ". It contains the following uparseable clause: "
                    + clause
                    + " (unknown value constraint: "
                    + valueConstraint
                    + ", see https://www.jetbrains.com/help/idea/contract-annotations.html).");
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
        // consequent to
        // fix the nullness of this argument.
        AccessPath accessPath = AccessPath.getAccessPathForNodeNoMapGet(node.getArgument(argIdx));
        if (accessPath == null) {
          continue;
        }
        if (consequent.equals("false") && argAntecedentNullness.equals(Nullness.NULLABLE)) {
          // If argIdx being null implies the return of the method being false, then the return
          // being true
          // implies argIdx is not null and we must mark it as such in the then update.
          thenUpdates.set(accessPath, Nullness.NONNULL);
        } else if (consequent.equals("true") && argAntecedentNullness.equals(Nullness.NULLABLE)) {
          // If argIdx being null implies the return of the method being true, then the return being
          // false
          // implies argIdx is not null and we must mark it as such in the else update.
          elseUpdates.set(accessPath, Nullness.NONNULL);
        } else if (consequent.equals("fail") && argAntecedentNullness.equals(Nullness.NULLABLE)) {
          // If argIdx being null implies the method throws an exception, then we can mark it as
          // non-null on
          // both non-exceptional exits from the method
          bothUpdates.set(accessPath, Nullness.NONNULL);
        }
      }
    }
    return NullnessHint.UNKNOWN;
  }

  private void reportMatch(Tree errorLocTree, String message) {
    assert this.analysis != null && this.state != null;
    if (this.analysis != null && this.state != null) {
      this.state.reportMatch(
          this.analysis.createErrorDescription(
              NullAway.MessageTypes.ANNOTATION_VALUE_INVALID, errorLocTree, message, errorLocTree));
    }
  }

  /**
   * Retrieve the string value inside an @Contract annotation without statically depending on the
   * type.
   *
   * @param sym A method which has an @Contract annotation.
   * @return The string value spec inside the annotation.
   */
  private static @Nullable String getContractFromAnnotation(Symbol.MethodSymbol sym) {
    for (AnnotationMirror annotation : sym.getAnnotationMirrors()) {
      Element element = annotation.getAnnotationType().asElement();
      assert element.getKind().equals(ElementKind.ANNOTATION_TYPE);
      if (((TypeElement) element)
          .getQualifiedName()
          .contentEquals("org.jetbrains.annotations.Contract")) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e :
            annotation.getElementValues().entrySet()) {
          if (e.getKey().getSimpleName().contentEquals("value")) {
            String value = e.getValue().toString();
            if (value.startsWith("\"") && value.endsWith("\"")) {
              value = value.substring(1, value.length() - 1);
            }
            return value;
          }
        }
      }
    }
    return null;
  }
}
