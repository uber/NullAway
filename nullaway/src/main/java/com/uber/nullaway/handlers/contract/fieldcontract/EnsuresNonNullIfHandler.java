/*
 * Copyright (c) 2024 Uber Technologies, Inc.
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

package com.uber.nullaway.handlers.contract.fieldcontract;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;
import static com.uber.nullaway.NullabilityUtil.getAnnotationValueArray;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.NullabilityUtil;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import com.uber.nullaway.dataflow.NullnessStore;
import com.uber.nullaway.handlers.AbstractFieldContractHandler;
import com.uber.nullaway.handlers.MethodAnalysisContext;
import com.uber.nullaway.handlers.contract.ContractUtils;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;

/**
 * This Handler parses {@code @EnsuresNonNullIf} annotation and when the annotated method is
 * invoked, it marks the annotated fields as not-null in the following flows.
 */
public class EnsuresNonNullIfHandler extends AbstractFieldContractHandler {

  // List of return trees in the method under analysis
  // This list is built in a way that return trees inside lambdas
  // or anonymous classes aren't included.
  private final Set<ReturnTree> returnTreesInMethodUnderAnalysis = new HashSet<>();

  // The MethodTree and MethodAnalysisContext of the EnsureNonNullIf method
  // under current semantic validation
  // They are set to null when no methods are being validated.
  @Nullable private MethodTree methodTreeUnderAnalysis;
  @Nullable private MethodAnalysisContext methodAnalysisContextUnderAnalysis;

  public EnsuresNonNullIfHandler() {
    super("EnsuresNonNullIf");
  }

  /**
   * Validates whether all parameters mentioned in the @EnsuresNonNullIf annotation are guaranteed
   * to be {@code @NonNull} at exit point of this method.
   */
  @Override
  protected boolean validateAnnotationSemantics(
      MethodTree tree, MethodAnalysisContext methodAnalysisContext) {
    if (tree.getBody() == null) {
      return false;
    }

    // clean up state variables, as we are visiting a new annotated method
    methodTreeUnderAnalysis = tree;
    methodAnalysisContextUnderAnalysis = methodAnalysisContext;
    returnTreesInMethodUnderAnalysis.clear();

    VisitorState state = methodAnalysisContext.state();
    NullAway analysis = methodAnalysisContext.analysis();

    // we visit the tree of the method, just so we can build a map between
    // return statements and their enclosing methods
    buildUpReturnToEnclosingMethodMap(state);

    // if no returns, then, this method doesn't return boolean, and it's wrong
    if (returnTreesInMethodUnderAnalysis.isEmpty()) {
      raiseError(
          tree, state, "Method is annotated with @EnsuresNonNullIf but does not return boolean");
      return false;
    }

    // We force the nullness analysis of the method under validation
    // The semantic validation will happen at each ReturnTree visit of the data-flow engine
    analysis
        .getNullnessAnalysis(state)
        .forceRunOnMethod(new TreePath(state.getPath(), tree), state.context);

    // Clean up state
    methodTreeUnderAnalysis = null;
    methodAnalysisContextUnderAnalysis = null;
    returnTreesInMethodUnderAnalysis.clear();

    return true;
  }

  private void buildUpReturnToEnclosingMethodMap(VisitorState methodState) {
    returnTreesInMethodUnderAnalysis.clear();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitReturn(ReturnTree node, Void unused) {
        TreePath enclosingMethod =
            NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(getCurrentPath());
        if (enclosingMethod == null) {
          throw new RuntimeException("no enclosing method, lambda or initializer!");
        }

        // We only add returns that are directly in the method under analysis
        if (!enclosingMethod.getLeaf().equals(methodTreeUnderAnalysis)) {
          return super.visitReturn(node, null);
        }

        // We add the current return tree to the set as it's in the method
        returnTreesInMethodUnderAnalysis.add(node);
        return super.visitReturn(node, null);
      }
    }.scan(methodState.getPath(), null);
  }

  /*
   * Sub-classes can only strengthen the post-condition.
   * We check if the list in the child classes is at least the same as in the parent class.
   */
  @Override
  protected void validateOverridingRules(
      Set<String> overridingFieldNames,
      NullAway analysis,
      VisitorState state,
      MethodTree tree,
      Symbol.MethodSymbol overriddenMethod) {
    FieldContractUtils.ensureStrictPostConditionInheritance(
        annotName, overridingFieldNames, analysis, state, tree, overriddenMethod);
  }

  @Override
  public void onDataflowVisitReturn(
      ReturnTree returnTree, VisitorState state, NullnessStore thenStore, NullnessStore elseStore) {
    // We only explore return statements that is inside
    // the method under validation
    if (!returnTreesInMethodUnderAnalysis.contains(returnTree)) {
      return;
    }

    // Get the declared configuration of the EnsureNonNullIf method under analysis
    Symbol.MethodSymbol methodSymbolUnderAnalysis =
        NullabilityUtil.castToNonNull(methodAnalysisContextUnderAnalysis).methodSymbol();

    Set<String> fieldNames = getAnnotationValueArray(methodSymbolUnderAnalysis, annotName, false);
    if (fieldNames == null) {
      throw new RuntimeException("List of field names shouldn't be null");
    }

    boolean trueIfNonNull = getResultValueFromAnnotation(methodSymbolUnderAnalysis);

    // We extract all the data-flow of the fields found by the
    // engine in the "then" case (i.e., true case)
    // and check whether all fields in the annotation parameter are non-null
    Set<String> nonNullFieldsInPath =
        thenStore.getReceiverFields(trueIfNonNull ? Nullness.NONNULL : Nullness.NULL).stream()
            .map(e -> e.getSimpleName().toString())
            .collect(Collectors.toSet());
    boolean allFieldsInPathAreVerified = nonNullFieldsInPath.containsAll(fieldNames);

    // Whether the return true expression evaluates to a boolean literal or not.
    Optional<Boolean> expressionAsBoolean = Optional.empty();
    if (returnTree.getExpression() instanceof LiteralTree) {
      LiteralTree expressionAsLiteral = (LiteralTree) returnTree.getExpression();
      if (expressionAsLiteral.getValue() instanceof Boolean) {
        expressionAsBoolean = Optional.of((boolean) expressionAsLiteral.getValue());
      }
    }

    boolean evaluatesToLiteral = expressionAsBoolean.isPresent();
    boolean evaluatesToFalse = expressionAsBoolean.isPresent() && !expressionAsBoolean.get();
    boolean evaluatesToTrue = expressionAsBoolean.isPresent() && expressionAsBoolean.get();

    /*
     * Decide whether the semantics of this ReturnTree are correct.
     * The decision is as follows:
     *
     * If all fields in the path are verified:
     * - If the literal boolean evaluates to true, semantics are correct, as the method
     * does return true in case the semantics hold.
     * - If the literal boolean evaluates to false, semantics are wrong, as the method
     * incorrect return false when it should have returned true.
     * - If the expression isn't a literal boolean, but something more complex,
     * we assume semantics are correct as we trust the data-flow engine.
     *
     * If fields in path aren't verified:
     * - If the literal boolean evaluates to false, semantics are correct, as the method
     * correctly returns false in case the semantics don't hold.
     * - If the literal boolean evaluates to true, semantics are wrong, as the method
     * incorrectly returns true when it should have returned false.
     * - If the expression isn't a literal boolean, then semantics are wrong, as we
     * assume the data-flow engine is correct.
     *
     * The implementation below is an optimized version of the decision table above.
     */
    if (allFieldsInPathAreVerified) {
      if (evaluatesToLiteral && evaluatesToFalse) {
        String message =
            String.format(
                "The method ensures the %s of the fields, but doesn't return true",
                (trueIfNonNull ? "non-nullability" : "nullability"));
        raiseError(returnTree, state, message);
      }
    } else {
      if (evaluatesToTrue || !evaluatesToLiteral) {
        fieldNames.removeAll(nonNullFieldsInPath);
        String message =
            String.format(
                "Method is annotated with @EnsuresNonNullIf but does not ensure fields %s",
                fieldNames);
        raiseError(returnTree, state, message);
      }
    }
  }

  private void raiseError(Tree returnTree, VisitorState state, String message) {
    NullAway analysis =
        NullabilityUtil.castToNonNull(methodAnalysisContextUnderAnalysis).analysis();

    state.reportMatch(
        analysis
            .getErrorBuilder()
            .createErrorDescription(
                new ErrorMessage(ErrorMessage.MessageTypes.POSTCONDITION_NOT_SATISFIED, message),
                returnTree,
                analysis.buildDescription(returnTree),
                state,
                null));
  }

  private boolean getResultValueFromAnnotation(Symbol.MethodSymbol methodSymbol) {
    AnnotationMirror annot = NullabilityUtil.findAnnotation(methodSymbol, annotName, false);
    if (annot == null) {
      throw new RuntimeException("Annotation should not be null at this point");
    }

    Map<? extends ExecutableElement, ? extends AnnotationValue> elementValues =
        annot.getElementValues();
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
        elementValues.entrySet()) {
      ExecutableElement elem = entry.getKey();
      if (elem.getSimpleName().contentEquals("result")) {
        return (boolean) entry.getValue().getValue();
      }
    }

    // Not explicitly declared in the annotation, so we default to true
    // (This should never happen as the compiler would have caught it before)
    throw new RuntimeException(
        "EnsureNonNullIf requires explicit 'return' value of the method under which the postcondition holds.");
  }

  /**
   * On every method annotated with {@link EnsuresNonNullIf}, this method injects the {@code
   * Nonnull} value for the class fields given in the {@code @EnsuresNonNullIf} parameter to the
   * dataflow analysis.
   */
  @Override
  public NullnessHint onDataflowVisitMethodInvocation(
      MethodInvocationNode node,
      Symbol.MethodSymbol methodSymbol,
      VisitorState state,
      AccessPath.AccessPathContext apContext,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    if (node.getTree() == null) {
      return super.onDataflowVisitMethodInvocation(
          node, methodSymbol, state, apContext, inputs, thenUpdates, elseUpdates, bothUpdates);
    }

    Set<String> fieldNames = getAnnotationValueArray(methodSymbol, annotName, false);
    if (fieldNames != null) {
      boolean trueIfNonNull = getResultValueFromAnnotation(methodSymbol);
      fieldNames = ContractUtils.trimReceivers(fieldNames);
      for (String fieldName : fieldNames) {
        VariableElement field =
            getInstanceFieldOfClass(
                castToNonNull(ASTHelpers.enclosingClass(methodSymbol)), fieldName);
        if (field == null) {
          // Invalid annotation, will result in an error during validation.
          continue;
        }
        AccessPath accessPath =
            AccessPath.fromBaseAndElement(node.getTarget().getReceiver(), field, apContext);
        if (accessPath == null) {
          // Also likely to be an invalid annotation, will result in an error during validation.
          continue;
        }

        // The call to the EnsuresNonNullIf method ensures that the field is then not null
        // (or null, depending on the result flag) at this point.
        // In here, we assume that the annotated method is already validated.
        thenUpdates.set(accessPath, trueIfNonNull ? Nullness.NONNULL : Nullness.NULL);
        elseUpdates.set(accessPath, trueIfNonNull ? Nullness.NULL : Nullness.NONNULL);
      }
    }

    return super.onDataflowVisitMethodInvocation(
        node, methodSymbol, state, apContext, inputs, thenUpdates, elseUpdates, bothUpdates);
  }
}
