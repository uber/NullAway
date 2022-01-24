/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.uber.nullaway.dataflow;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.VisitorState;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.Config;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.handlers.Handler;
import com.uber.nullaway.handlers.contract.ContractNullnessStoreInitializer;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.VariableElement;
import org.checkerframework.nullaway.dataflow.analysis.AnalysisResult;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodAccessNode;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.nullaway.dataflow.cfg.node.Node;

/**
 * API to our nullness dataflow analysis for access paths.
 *
 * <p>Based on code from Error Prone; see {@link NullnessAnalysis}
 */
public final class AccessPathNullnessAnalysis {

  private static final Context.Key<AccessPathNullnessAnalysis> FIELD_NULLNESS_ANALYSIS_KEY =
      new Context.Key<>();

  private final AccessPath.AccessPathContext apContext;

  private final AccessPathNullnessPropagation nullnessPropagation;

  private final DataFlow dataFlow;

  @Nullable private AccessPathNullnessPropagation contractNullnessPropagation;

  // Use #instance to instantiate
  private AccessPathNullnessAnalysis(
      Predicate<MethodInvocationNode> methodReturnsNonNull,
      VisitorState state,
      Config config,
      Handler handler) {
    apContext =
        AccessPath.AccessPathContext.builder()
            .setImmutableTypes(handler.onRegisterImmutableTypes())
            .build();
    this.nullnessPropagation =
        new AccessPathNullnessPropagation(
            Nullness.NONNULL,
            methodReturnsNonNull,
            state,
            apContext,
            config,
            handler,
            new CoreNullnessStoreInitializer());
    this.dataFlow = new DataFlow(config.assertsEnabled());

    if (config.checkContracts()) {
      this.contractNullnessPropagation =
          new AccessPathNullnessPropagation(
              Nullness.NONNULL,
              methodReturnsNonNull,
              state,
              apContext,
              config,
              handler,
              new ContractNullnessStoreInitializer());
    }
  }

  /**
   * Get the per-Javac instance of the analysis.
   *
   * @param state visitor state for the compilation
   * @param methodReturnsNonNull predicate determining whether a method is assumed to return NonNull
   *     value
   * @param config analysis config
   * @return instance of the analysis
   */
  public static AccessPathNullnessAnalysis instance(
      VisitorState state,
      Predicate<MethodInvocationNode> methodReturnsNonNull,
      Config config,
      Handler handler) {
    Context context = state.context;
    AccessPathNullnessAnalysis instance = context.get(FIELD_NULLNESS_ANALYSIS_KEY);
    if (instance == null) {
      instance = new AccessPathNullnessAnalysis(methodReturnsNonNull, state, config, handler);
      context.put(FIELD_NULLNESS_ANALYSIS_KEY, instance);
    }
    return instance;
  }

  /**
   * Get an expression's nullness info.
   *
   * @param exprPath tree path of expression
   * @param context Javac context
   * @return nullness info for expression, from dataflow
   */
  @Nullable
  public Nullness getNullness(TreePath exprPath, Context context) {
    return dataFlow.expressionDataflow(exprPath, context, nullnessPropagation);
  }

  /**
   * Gets nullness info for expression from the dataflow analysis, for the case of checking
   * contracts
   *
   * @param exprPath tree path of expression
   * @param context Javac context
   * @return nullness info for expression, from dataflow in case contract check
   */
  @Nullable
  public Nullness getNullnessForContractDataflow(TreePath exprPath, Context context) {
    return dataFlow.expressionDataflow(exprPath, context, contractNullnessPropagation);
  }

  /**
   * Get the fields that are guaranteed to be nonnull after a method or initializer block.
   *
   * @param path tree path of method, or initializer block
   * @param context Javac context
   * @return fields guaranteed to be nonnull at exit of method (or initializer block)
   */
  public Set<Element> getNonnullFieldsOfReceiverAtExit(TreePath path, Context context) {
    NullnessStore nullnessResult = dataFlow.finalResult(path, context, nullnessPropagation);
    if (nullnessResult == null) {
      // this case can occur if the method always throws an exception
      // be conservative and say nothing is initialized
      return Collections.emptySet();
    }
    return getNonnullReceiverFields(nullnessResult);
  }

  private Set<Element> getNonnullReceiverFields(NullnessStore nullnessResult) {
    Set<AccessPath> nonnullAccessPaths = nullnessResult.getAccessPathsWithValue(Nullness.NONNULL);
    Set<Element> result = new LinkedHashSet<>();
    for (AccessPath ap : nonnullAccessPaths) {
      if (ap.getRoot().isReceiver()) {
        ImmutableList<AccessPathElement> elements = ap.getElements();
        if (elements.size() == 1) {
          Element elem = elements.get(0).getJavaElement();
          if (elem.getKind().equals(ElementKind.FIELD)) {
            result.add(elem);
          }
        }
      }
    }
    return result;
  }

  /**
   * Get the instance fields that are guaranteed to be nonnull before the current expression.
   *
   * @param path tree path of some expression
   * @param context Javac context
   * @return fields of receiver guaranteed to be nonnull before expression is evaluated
   */
  public Set<Element> getNonnullFieldsOfReceiverBefore(TreePath path, Context context) {
    NullnessStore store = dataFlow.resultBeforeExpr(path, context, nullnessPropagation);
    if (store == null) {
      return Collections.emptySet();
    }
    return getNonnullReceiverFields(store);
  }

  /**
   * Get the static fields that are guaranteed to be nonnull before the current expression.
   *
   * @param path tree path of some expression
   * @param context Javac context
   * @return static fields guaranteed to be nonnull before expression is evaluated
   */
  public Set<Element> getNonnullStaticFieldsBefore(TreePath path, Context context) {
    NullnessStore store = dataFlow.resultBeforeExpr(path, context, nullnessPropagation);
    if (store == null) {
      return Collections.emptySet();
    }
    return getNonnullStaticFields(store);
  }

  /**
   * Get nullness info for local variables before some node
   *
   * @param path tree path to some AST node within a method / lambda / initializer
   * @param state visitor state
   * @return nullness info for local variables just before the node
   */
  public NullnessStore getNullnessInfoBeforeNewContext(
      TreePath path, VisitorState state, Handler handler) {
    NullnessStore store = dataFlow.resultBefore(path, state.context, nullnessPropagation);
    if (store == null) {
      return NullnessStore.empty();
    }
    return store.filterAccessPaths(
        (ap) -> {
          if (ap.getElements().size() == 0) {
            AccessPath.Root root = ap.getRoot();
            if (!root.isReceiver()) {
              Element e = root.getVarElement();
              return e.getKind().equals(ElementKind.PARAMETER)
                  || e.getKind().equals(ElementKind.LOCAL_VARIABLE);
            }
          }

          return handler.includeApInfoInSavedContext(ap, state);
        });
  }

  /**
   * Get the {@link Nullness} value of an access path ending in a field at some program point.
   *
   * @param path Tree path to the specific program point.
   * @param context Javac context.
   * @param baseExpr The base expression {@code expr} for the access path {@code expr . f}
   * @param field The field {@code f} for the access path {@code expr . f}
   * @param trimReceiver if {@code true}, {@code baseExpr} will be trimmed to extract only the
   *     receiver if the node associated to {@code baseExpr} is of type {@link MethodAccessNode}.
   *     (e.g. {@code t.f()} will be converted to {@code t})
   * @return The {@link Nullness} value of the access path at the program point. If the baseExpr and
   *     field cannot be represented as an {@link AccessPath}, or if the dataflow analysis has no
   *     result for the program point before {@code path}, conservatively returns {@link
   *     Nullness#NULLABLE}
   */
  public Nullness getNullnessOfFieldForReceiverTree(
      TreePath path, Context context, Tree baseExpr, VariableElement field, boolean trimReceiver) {
    Preconditions.checkArgument(field.getKind().equals(ElementKind.FIELD));
    AnalysisResult<Nullness, NullnessStore> result =
        dataFlow.resultForExpr(path, context, nullnessPropagation);
    if (result == null) {
      return Nullness.NULLABLE;
    }
    NullnessStore store = result.getStoreBefore(path.getLeaf());
    // used set of nodes, a tree can have multiple nodes.
    Set<Node> baseNodes = result.getNodesForTree(baseExpr);
    if (store == null || baseNodes == null) {
      return Nullness.NULLABLE;
    }
    // look for all possible access paths might exist in store.
    for (Node baseNode : baseNodes) {
      // it trims the baseExpr to process only the receiver. (e.g. a.f() is trimmed to a)
      if (trimReceiver && baseNode instanceof MethodAccessNode) {
        baseNode = ((MethodAccessNode) baseNode).getReceiver();
      }
      AccessPath accessPath = AccessPath.fromBaseAndElement(baseNode, field, apContext);
      if (accessPath == null) {
        continue;
      }
      Nullness nullness = store.getNullnessOfAccessPath(accessPath);
      // field is non-null if at least one access path referring to it exists with non-null
      // nullness.
      if (!nullness.equals(Nullness.NULLABLE)) {
        return nullness;
      }
    }
    return Nullness.NULLABLE;
  }

  /**
   * Get the static fields that are guaranteed to be nonnull after a method or initializer block.
   *
   * @param path tree path of static method, or initializer block
   * @param context Javac context
   * @return fields guaranteed to be nonnull at exit of static method (or initializer block)
   */
  public Set<Element> getNonnullStaticFieldsAtExit(TreePath path, Context context) {
    NullnessStore nullnessResult = dataFlow.finalResult(path, context, nullnessPropagation);
    if (nullnessResult == null) {
      // this case can occur if the method always throws an exception
      // be conservative and say nothing is initialized
      return Collections.emptySet();
    }
    return getNonnullStaticFields(nullnessResult);
  }

  private Set<Element> getNonnullStaticFields(NullnessStore nullnessResult) {
    Set<AccessPath> nonnullAccessPaths = nullnessResult.getAccessPathsWithValue(Nullness.NONNULL);
    Set<Element> result = new LinkedHashSet<>();
    for (AccessPath ap : nonnullAccessPaths) {
      assert !ap.getRoot().isReceiver();
      Element varElement = ap.getRoot().getVarElement();
      if (varElement.getKind().equals(ElementKind.FIELD)) {
        result.add(varElement);
      }
    }
    return result;
  }

  /**
   * Forces a run of the access path nullness analysis on the method (or lambda) at the given
   * TreePath.
   *
   * <p>Because of caching, if the analysis has run in the past for this particular path, it might
   * not be re-run. The intended usage of this method is to force an analysis pass and thus the
   * execution of any Handler hooks into the dataflow analysis (such as `onDataflowInitialStore` or
   * `onDataflowVisitX`).
   *
   * @param methodPath tree path of the method (or lambda) to analyze.
   * @param context Javac context
   * @return the final NullnessStore on exit from the method.
   */
  public NullnessStore forceRunOnMethod(TreePath methodPath, Context context) {
    return dataFlow.finalResult(methodPath, context, nullnessPropagation);
  }

  /**
   * Nullness of the variable element field of the expression is checked in the store.
   *
   * @param exprPath tree path of the expression
   * @param context Javac context
   * @param variableElement variable element for which the nullness is evaluated
   * @return nullness info of variable element field of the expression
   */
  public Nullness getNullnessOfExpressionNamedField(
      TreePath exprPath, Context context, VariableElement variableElement) {
    NullnessStore store = dataFlow.resultBeforeExpr(exprPath, context, nullnessPropagation);

    // We use the CFG to get the Node corresponding to the expression
    Set<Node> exprNodes =
        dataFlow
            .getControlFlowGraph(exprPath, context, nullnessPropagation)
            .getNodesCorrespondingToTree(exprPath.getLeaf());

    if (exprNodes.size() != 1) {
      // Since the expression must have a single corresponding node
      // NULLABLE is our default assumption
      return Nullness.NULLABLE;
    }

    AccessPath ap =
        AccessPath.fromBaseAndElement(exprNodes.iterator().next(), variableElement, apContext);

    if (store != null && ap != null) {
      if (store
          .getAccessPathsWithValue(Nullness.NONNULL)
          .stream()
          .anyMatch(accessPath -> accessPath.equals(ap))) {
        return Nullness.NONNULL;
      }
    }
    return Nullness.NULLABLE;
  }

  /** invalidate all caches */
  public void invalidateCaches() {
    dataFlow.invalidateCaches();
  }
}
