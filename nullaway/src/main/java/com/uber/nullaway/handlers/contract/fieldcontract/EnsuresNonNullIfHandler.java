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
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.NullabilityUtil;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessAnalysis;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import com.uber.nullaway.dataflow.NullnessStore;
import com.uber.nullaway.handlers.AbstractFieldContractHandler;
import com.uber.nullaway.handlers.MethodAnalysisContext;
import com.uber.nullaway.handlers.contract.ContractUtils;
import java.util.Collections;
import java.util.HashMap;
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

  // Fast-access data structure to retrieve the enclosing MethodTree of a given ReturnTree
  private final Map<ReturnTree, MethodTree> returnToEnclosingMethodMap = new HashMap<>();

  // Fast-access data structure to retrieve the method symbol of a method, and vice-versa
  private final Map<MethodTree, Symbol.MethodSymbol> methodToMethodSymbol = new HashMap<>();
  private final Map<Symbol.MethodSymbol, MethodTree> methodSymbolToMethod = new HashMap<>();

  // Contains a map of all EnsuresNonNullIf methods and their list of semantic issues.
  // An empty list means that the method has no semantic issues
  private final Map<MethodTree, Set<String>> semanticValidationMap = new HashMap<>();

  public EnsuresNonNullIfHandler() {
    super("EnsuresNonNullIf");
  }

  /*
   * Clean all the maps whenever a new top-level class is declared
   */
  @Override
  public void onMatchTopLevelClass(
      NullAway analysis, ClassTree tree, VisitorState state, Symbol.ClassSymbol classSymbol) {
    returnToEnclosingMethodMap.clear();
    methodToMethodSymbol.clear();
    methodSymbolToMethod.clear();
    semanticValidationMap.clear();
  }

  /**
   * The validateAnnotationSemantics() doesn't work for this case, as we need to visit all the
   * ReturnTree objects before deciding whether or not the semantics match. Validation errors are
   * reported the first time the EnsureNonNullIf method is called.
   */
  @Override
  protected boolean validateAnnotationSemantics(
      MethodTree tree, MethodAnalysisContext methodAnalysisContext) {
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

  /*
   * Creates a map between the MethodTree and its related MethodSymbol
   */
  @Override
  public void onMatchMethod(MethodTree tree, MethodAnalysisContext methodAnalysisContext) {
    Symbol.MethodSymbol methodSymbol = methodAnalysisContext.methodSymbol();

    Set<String> annotationContent =
        NullabilityUtil.getAnnotationValueArray(methodSymbol, annotName, false);
    boolean isAnnotated = annotationContent != null;

    if (isAnnotated) {
      methodToMethodSymbol.put(tree, methodSymbol);
      methodSymbolToMethod.put(methodSymbol, tree);
    }

    super.onMatchMethod(tree, methodAnalysisContext);
  }

  /*
   * We create a map between the return tree and its enclosing method, so we can
   * later identify whether the return statement belongs to the EnsuresNonNullIf annotated method.
   */
  @Override
  public void onMatchReturn(NullAway analysis, ReturnTree tree, VisitorState state) {
    TreePath enclosingMethod =
        NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(state.getPath());
    if (enclosingMethod == null) {
      throw new RuntimeException("no enclosing method, lambda or initializer!");
    }

    // If it's not an annotated method, we don't care about analyzing this ReturnTree
    if (!methodToMethodSymbol.containsKey(enclosingMethod.getLeaf())) {
      return;
    }

    // For now, we only support returns within methods directly, no lambdas.
    if (!(enclosingMethod.getLeaf() instanceof MethodTree)) {
      throw new RuntimeException(
          "return statement outside of a method! (e.g. in a lambda or in an initializer block)");
    }

    // We match the return tree with its enclosing method
    returnToEnclosingMethodMap.put(tree, (MethodTree) enclosingMethod.getLeaf());

    // We now force the data-flow engine to run for the entire method,
    // which will allow us to analyze all of its return statements via the dataFlowReturn callback
    // We will call the data-flow engine for the same method multiple times, one of each return
    // statement, but the caching theoretically kicks in.
    MethodTree methodTree = (MethodTree) enclosingMethod.getLeaf();
    AccessPathNullnessAnalysis nullnessAnalysis = analysis.getNullnessAnalysis(state);
    nullnessAnalysis.forceRunOnMethod(new TreePath(state.getPath(), methodTree), state.context);
  }

  @Override
  public void onDataflowVisitReturn(
      ReturnTree tree, NullnessStore thenStore, NullnessStore elseStore) {
    MethodTree enclosingMethod = returnToEnclosingMethodMap.get(tree);

    // We might not have seen this return tree yet, so we stop
    // This will be called again later, once we call the engine for it
    if (enclosingMethod == null) {
      return;
    }

    // If it's not an EnsuresNonNullIf annotated method, we don't need to continue
    boolean visitingAnnotatedMethod = methodToMethodSymbol.containsKey(enclosingMethod);
    if (!visitingAnnotatedMethod) {
      return;
    }

    // If we already found a path that keeps the correct semantics of the method,
    // we don't need to keep searching for it
    if (semanticsHold(enclosingMethod)) {
      return;
    }

    Symbol.MethodSymbol visitingMethodSymbol = methodToMethodSymbol.get(enclosingMethod);
    Set<String> fieldNames = getAnnotationValueArray(visitingMethodSymbol, annotName, false);
    if (fieldNames == null) {
      throw new RuntimeException("List of field names shouldn't be null");
    }

    boolean trueIfNonNull = getTrueIfNonNullValue(visitingMethodSymbol);

    // We extract all the data-flow of the fields found by the engine in the "then" case
    // (i.e., true case) and check whether all fields in the annotation parameter are non-null
    Set<String> nonNullFieldsInPath =
        thenStore.getAccessPathsWithValue(trueIfNonNull ? Nullness.NONNULL : Nullness.NULL).stream()
            .flatMap(ap -> ap.getElements().stream())
            .map(e -> e.getJavaElement().getSimpleName().toString())
            .collect(Collectors.toSet());
    boolean allFieldsInPathAreVerified = nonNullFieldsInPath.containsAll(fieldNames);

    if (allFieldsInPathAreVerified) {
      // If it's a literal, then, it needs to return true/false,
      // depending on the trueIfNonNull flag
      if (tree.getExpression() instanceof LiteralTree) {
        LiteralTree expressionAsLiteral = (LiteralTree) tree.getExpression();
        if (expressionAsLiteral.getValue() instanceof Boolean) {
          boolean literalValueOfExpression = (boolean) expressionAsLiteral.getValue();

          // If the method returns literal 'true',
          // it means the proposed semantics are ensured
          if (literalValueOfExpression) {
            markCorrectSemantics(enclosingMethod);
          } else {
            markIncorrectSemantics(enclosingMethod, fieldNames);
          }
        }
      } else {
        // No literal boolean return that we can easily parse,
        // so we then trust on the analysis of the engine that, at this point,
        // the field is checked for null
        markCorrectSemantics(enclosingMethod);
      }
    } else {
      // Build list of missing fields for the elegant validation error message
      fieldNames.removeAll(nonNullFieldsInPath);
      markIncorrectSemantics(enclosingMethod, fieldNames);
    }
  }

  /*
   * We use this matcher to double check if the semantic of the
   * EnsureNonNullIf method is correct.
   */
  @Override
  public void onMatchMethodInvocation(
      MethodInvocationTree tree,
      Symbol.MethodSymbol methodSymbol,
      MethodAnalysisContext methodAnalysisContext) {
    Set<String> fieldNames = getAnnotationValueArray(methodSymbol, annotName, false);
    boolean callToAnEnsureNonNullIfMethod = fieldNames != null;

    if (!callToAnEnsureNonNullIfMethod) {
      return;
    }

    if (!semanticsHold(methodSymbol)) {
      notifySemanticError(methodSymbol, methodAnalysisContext);
    }
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
    boolean callToAnEnsureNonNullIfMethod = fieldNames != null;

    if (callToAnEnsureNonNullIfMethod) {
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

  // Add the list of missing fields to a given method,
  // indicating that this method doesn't hold the required semantics
  private void markIncorrectSemantics(MethodTree enclosingMethod, Set<String> missingFields) {
    semanticValidationMap.put(enclosingMethod, missingFields);
  }

  // Add a method to the list of semantic validations, without any issues
  private void markCorrectSemantics(MethodTree enclosingMethod) {
    semanticValidationMap.put(enclosingMethod, Collections.emptySet());
  }

  // Returns whether the given method has valid semantic
  // Returns false if the method doesn't have the right semantic, or is not on the map at all
  private boolean semanticsHold(MethodTree enclosingMethod) {
    Symbol.MethodSymbol methodSymbol = methodToMethodSymbol.get(enclosingMethod);
    if (methodSymbol == null) {
      return false;
    }
    return semanticsHold(methodSymbol);
  }

  // Returns whether the given MethodSymbol has valid semantic
  // Returns false if the method doesn't have the right semantic, or is not on the map at all
  private boolean semanticsHold(Symbol.MethodSymbol methodSymbol) {
    MethodTree methodTree = getMethodTree(methodSymbol);

    Set<String> missingFields = semanticValidationMap.get(methodTree);
    // It might be null in case the EnsuresNonNullIf method is completely wrong
    if (missingFields == null) {
      return false;
    }

    return missingFields.isEmpty();
  }

  // Gets the method tree of a given MethodSymbol
  private MethodTree getMethodTree(Symbol.MethodSymbol methodSymbol) {
    MethodTree methodTree = methodSymbolToMethod.get(methodSymbol);
    if (methodTree == null) {
      throw new RuntimeException("Method tree not found!");
    }
    return methodTree;
  }

  // Gets the list of missing fields of a given method
  private Set<String> getMissingFields(Symbol.MethodSymbol methodSymbol) {
    MethodTree methodTree = getMethodTree(methodSymbol);
    Set<String> missingFields = semanticValidationMap.get(methodTree);
    if (missingFields == null) {
      return Collections.emptySet();
    }

    return missingFields;
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

  // Notify about a semantic error in the given EnsuresNonNullIf method
  private void notifySemanticError(
      Symbol.MethodSymbol methodSymbol, MethodAnalysisContext methodAnalysisContext) {
    VisitorState state = methodAnalysisContext.state();
    NullAway analysis = methodAnalysisContext.analysis();

    MethodTree ensureNonNullIfMethod = getMethodTree(methodSymbol);
    Set<String> missingFields = getMissingFields(methodSymbol);

    String message;
    if (missingFields.isEmpty()) {
      message = "Method is annotated with @EnsuresNonNullIf but does not return boolean";
    } else {
      message =
          String.format(
              "Method is annotated with @EnsuresNonNullIf but does not ensure fields %s",
              missingFields);
    }

    state.reportMatch(
        analysis
            .getErrorBuilder()
            .createErrorDescription(
                new ErrorMessage(ErrorMessage.MessageTypes.POSTCONDITION_NOT_SATISFIED, message),
                ensureNonNullIfMethod,
                analysis.buildDescription(ensureNonNullIfMethod),
                state,
                null));
  }
}
