/*
 * Copyright (c) 2017-2020 Uber Technologies, Inc.
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
import com.sun.source.util.TreePath;
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
import javax.lang.model.element.VariableElement;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;

/**
 * This Handler parses {@code @EnsuresNonNullIf} annotation and when the annotated method is
 * invoked, it marks the annotated fields as not-null in the following flows.
 */
public class EnsuresNonNullIfHandler extends AbstractFieldContractHandler {

  // Field is set to true when the EnsuresNonNullIf method ensures that all listed fields are
  // checked for non-nullness
  private boolean currentEnsuresNonNullIfMethodChecksNullnessOfAllFields;
  // List of fields missing in the current EnsuresNonNullIf method so we can build proper error
  // message
  private Set<String> missingFieldNames;

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
    // If no body in the method, return false right away
    // TODO: Do we need proper error message here?
    if (tree.getBody() == null) {
      return false;
    }

    // clean up state variables, as we are visiting a new annotated method
    currentEnsuresNonNullIfMethodChecksNullnessOfAllFields = false;
    missingFieldNames = null;

    // We force the nullness analysis of the method
    NullAway analysis = methodAnalysisContext.analysis();
    VisitorState state = methodAnalysisContext.state();
    analysis
        .getNullnessAnalysis(state)
        .forceRunOnMethod(new TreePath(state.getPath(), tree), state.context);

    // If listed fields aren't checked for their nullness, return error
    if (!currentEnsuresNonNullIfMethodChecksNullnessOfAllFields) {
      String message;
      if (missingFieldNames == null) {
        message = "Method is annotated with @EnsuresNonNullIf but does not return boolean";
      } else {
        message =
            String.format(
                "Method is annotated with @EnsuresNonNullIf but does not ensure fields %s",
                missingFieldNames);
      }
      state.reportMatch(
          analysis
              .getErrorBuilder()
              .createErrorDescription(
                  new ErrorMessage(ErrorMessage.MessageTypes.POSTCONDITION_NOT_SATISFIED, message),
                  tree,
                  analysis.buildDescription(tree),
                  state,
                  null));
    }

    return true;
  }

  /*
   * Sub-classes can only strengthen the post-condition. We check if the list in the child classes
   * is at least the same as in the parent class.
   */
  @Override
  protected void validateOverridingRules(
      Set<String> overridingFieldNames,
      NullAway analysis,
      VisitorState state,
      MethodTree tree,
      Symbol.MethodSymbol overriddenMethod) {
    FieldContractUtils.validateOverridingRules(
        annotName, overridingFieldNames, analysis, state, tree, overriddenMethod);
  }

  @Override
  public void onDataflowVisitReturn(
      ReturnTree tree, NullnessStore thenStore, NullnessStore elseStore) {
    if (visitingAnnotatedMethod) {
      // We might have already found a flow that ensures the non-nullness, so we don't keep going
      // deep.
      if (currentEnsuresNonNullIfMethodChecksNullnessOfAllFields) {
        return;
      }

      Set<String> fieldNames = getAnnotationValueArray(visitingMethodSymbol, annotName, false);
      boolean trueIfNonNull = getTrueIfNonNullValue(visitingMethodSymbol);

      // We extract all the data-flow of the fields found by the engine in the "then" case (i.e.,
      // true case)
      // and check whether all fields in the annotation parameter are non-null
      Set<String> nonNullFieldsInPath =
          thenStore
              .getAccessPathsWithValue(trueIfNonNull ? Nullness.NONNULL : Nullness.NULL)
              .stream()
              .flatMap(ap -> ap.getElements().stream())
              .map(e -> e.getJavaElement().getSimpleName().toString())
              .collect(Collectors.toSet());
      boolean allFieldsInPathAreVerified = nonNullFieldsInPath.containsAll(fieldNames);

      if (allFieldsInPathAreVerified) {
        // If it's a literal, then, it needs to return true/false, depending on the trueIfNonNull
        // flag
        if (tree.getExpression() instanceof LiteralTree) {
          LiteralTree expressionAsLiteral = (LiteralTree) tree.getExpression();
          if (expressionAsLiteral.getValue() instanceof Boolean) {
            boolean literalValueOfExpression = (boolean) expressionAsLiteral.getValue();
            this.currentEnsuresNonNullIfMethodChecksNullnessOfAllFields = literalValueOfExpression;
          }
        } else {
          // We then trust on the analysis of the engine that, at this point, the field is checked
          // for null
          this.currentEnsuresNonNullIfMethodChecksNullnessOfAllFields = true;
        }
      } else {
        // Build list of missing fields for the elegant validation error message
        fieldNames.removeAll(nonNullFieldsInPath);
        this.missingFieldNames = new HashSet<>(fieldNames);
      }
    }
  }

  private boolean getTrueIfNonNullValue(Symbol.MethodSymbol methodSymbol) {
    AnnotationMirror annot = NullabilityUtil.findAnnotation(methodSymbol, annotName, false);
    if (annot == null) {
      throw new RuntimeException("Annotation should not be null at this point");
    }

    Map<? extends ExecutableElement, ? extends AnnotationValue> elementValues =
        annot.getElementValues();
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
        elementValues.entrySet()) {
      ExecutableElement elem = entry.getKey();
      if (elem.getSimpleName().contentEquals("trueIfNonNull")) {
        return (boolean) entry.getValue().getValue();
      }
    }

    // Not explicitly declared in the annotation, so we default to true
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
      boolean trueIfNonNull = getTrueIfNonNullValue(methodSymbol);
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
        // (or null, depending on the trueIfNotNUll flag) at this point.
        // In here, we assume that the annotated method is already validated.
        thenUpdates.set(accessPath, trueIfNonNull ? Nullness.NONNULL : Nullness.NULL);
        elseUpdates.set(accessPath, trueIfNonNull ? Nullness.NULL : Nullness.NONNULL);
      }
    }

    return super.onDataflowVisitMethodInvocation(
        node, methodSymbol, state, apContext, inputs, thenUpdates, elseUpdates, bothUpdates);
  }
}
