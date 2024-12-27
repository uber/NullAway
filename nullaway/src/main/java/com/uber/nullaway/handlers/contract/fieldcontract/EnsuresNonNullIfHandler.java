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
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;
import org.jspecify.annotations.Nullable;

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
  private @Nullable MethodTree methodTreeUnderAnalysis;
  private @Nullable MethodAnalysisContext methodAnalysisContextUnderAnalysis;

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
      return true;
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
        if (enclosingMethod.getLeaf().equals(methodTreeUnderAnalysis)) {
          returnTreesInMethodUnderAnalysis.add(node);
        }
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

    // We extract all the fields that are considered non-null by the data-flow engine
    // We pick the "thenStore" case in case result is set to true
    // or "elseStore" in case result is set to false
    // and check whether the non-full fields match the ones in the annotation parameter
    NullnessStore chosenStore = trueIfNonNull ? thenStore : elseStore;
    Set<String> nonNullFieldsInPath =
        chosenStore.getNonNullReceiverFields().stream()
            .map(e -> e.getSimpleName().toString())
            .collect(Collectors.toSet());
    Set<String> nonNullStaticFieldsInPath =
        chosenStore.getNonNullStaticFields().stream()
            .map(e -> e.getSimpleName().toString())
            .collect(Collectors.toSet());
    nonNullFieldsInPath.addAll(nonNullStaticFieldsInPath);
    boolean allFieldsAreNonNull = nonNullFieldsInPath.containsAll(fieldNames);

    // Whether the return true expression evaluates to a boolean literal or not.  If null, then not
    // a boolean literal.
    Boolean expressionAsBoolean = null;
    if (returnTree.getExpression() instanceof LiteralTree) {
      LiteralTree expressionAsLiteral = (LiteralTree) returnTree.getExpression();
      if (expressionAsLiteral.getValue() instanceof Boolean) {
        expressionAsBoolean = (Boolean) expressionAsLiteral.getValue();
      }
    }

    /*
     * Identify whether the expression is a boolean literal and whether
     * it evaluates to the correct literal.
     * - If result param in annotation is set to true, then expression should return true.
     * - If result param in annotation is set to false, then expression should return false.
     */
    boolean isBooleanLiteral = expressionAsBoolean != null;
    boolean evaluatesToNonNullLiteral =
        expressionAsBoolean != null && (trueIfNonNull == expressionAsBoolean);

    /*
     * Decide whether the semantics of this ReturnTree are correct.
     * The decision is as follows:
     *
     * If all fields in the path are verified:
     * - Semantics are valid
     *
     * If fields in path aren't verified:
     * - If the literal boolean is the opposite of the configured non-null boolean, semantics
     * are then correct, as the method correctly returns in case the semantics don't hold.
     * - Otherwise, semantics are wrong, as the method incorrectly returns.
     * - If the expression isn't a literal boolean, then semantics are wrong, as we
     * assume it is possible that the configured non-null boolean can be returned.
     */
    if (!allFieldsAreNonNull) {
      if (evaluatesToNonNullLiteral || !isBooleanLiteral) {
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

    // Default value of the 'result' field is true
    return true;
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
      fieldNames = ContractUtils.trimReceivers(fieldNames);
      boolean trueIfNonNull = getResultValueFromAnnotation(methodSymbol);
      // chosenUpdates is set to the thenUpdates or elseUpdates appropriately given the annotation's
      // result value
      AccessPathNullnessPropagation.Updates chosenUpdates =
          trueIfNonNull ? thenUpdates : elseUpdates;
      for (String fieldName : fieldNames) {
        VariableElement field =
            getFieldOfClass(castToNonNull(ASTHelpers.enclosingClass(methodSymbol)), fieldName);
        if (field == null) {
          // Invalid annotation, will result in an error during validation.
          continue;
        }
        AccessPath accessPath =
            field.getModifiers().contains(Modifier.STATIC)
                ? AccessPath.fromStaticField(field)
                : AccessPath.fromFieldElement(field);

        if (accessPath == null) {
          // Also likely to be an invalid annotation, will result in an error during validation.
          continue;
        }

        // The call to the EnsuresNonNullIf method ensures that the field is not null in the chosen
        // updates.
        // In here, we assume that the annotated method is already validated.
        chosenUpdates.set(accessPath, Nullness.NONNULL);
      }
    }

    return super.onDataflowVisitMethodInvocation(
        node, methodSymbol, state, apContext, inputs, thenUpdates, elseUpdates, bothUpdates);
  }
}
