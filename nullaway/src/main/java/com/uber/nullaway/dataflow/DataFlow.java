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

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.NullabilityUtil;

import org.checkerframework.dataflow.analysis.AbstractValue;
import org.checkerframework.dataflow.analysis.Analysis;
import org.checkerframework.dataflow.analysis.Store;
import org.checkerframework.dataflow.analysis.TransferFunction;
import org.checkerframework.dataflow.cfg.CFGBuilder;
import org.checkerframework.dataflow.cfg.ControlFlowGraph;
import org.checkerframework.dataflow.cfg.UnderlyingAST;

import javax.annotation.Nullable;
import javax.annotation.processing.ProcessingEnvironment;

/**
 * Provides a wrapper around {@link org.checkerframework.dataflow.analysis.Analysis}.
 *
 * Modified from Error Prone code for more aggressive caching, and to avoid static state.
 * See {@link com.google.errorprone.dataflow.DataFlow}
 *
 */
public final class DataFlow {

    /*
     * We cache both the control flow graph and the analyses that are run on it.
     *
     * Unlike in Error Prone's core analyses, sometimes we do not complete all analyses on a CFG
     * before moving on to the next one.  So, here we set a reasonable maximum size to avoid leaks,
     * and also expose an API method to clear the caches.
     */
    private static final int MAX_CACHE_SIZE = 50;

    private final LoadingCache<AnalysisParams, Analysis<?, ?, ?>> analysisCache =
            CacheBuilder.newBuilder()
                    .maximumSize(MAX_CACHE_SIZE)
                    .build(
                            new CacheLoader<AnalysisParams, Analysis<?, ?, ?>>() {
                                @Override
                                public Analysis<?, ?, ?> load(AnalysisParams key) {
                                    final ProcessingEnvironment env = key.environment();
                                    final ControlFlowGraph cfg = key.cfg();
                                    final TransferFunction<?, ?> transfer = key.transferFunction();

                                    @SuppressWarnings({"unchecked", "rawtypes"})
                                    final Analysis<?, ?, ?> analysis = new Analysis(env, transfer);
                                    analysis.performAnalysis(cfg);
                                    return analysis;
                                }
                            });

    private final LoadingCache<CfgParams, ControlFlowGraph> cfgCache =
            CacheBuilder.newBuilder()
                    .maximumSize(MAX_CACHE_SIZE)
                    .build(
                            new CacheLoader<CfgParams, ControlFlowGraph>() {
                                @Override
                                public ControlFlowGraph load(CfgParams key) {
                                    final TreePath methodPath = key.methodPath();
                                    final TreePath bodyPath;
                                    final UnderlyingAST ast;
                                    final ProcessingEnvironment env = key.environment();
                                    if (methodPath.getLeaf() instanceof LambdaExpressionTree) {
                                        LambdaExpressionTree lambdaExpressionTree =
                                                (LambdaExpressionTree) methodPath.getLeaf();
                                        ast = new UnderlyingAST.CFGLambda(lambdaExpressionTree);
                                        bodyPath = new TreePath(methodPath, lambdaExpressionTree.getBody());
                                    } else {
                                        // must be a method per findEnclosingMethodOrLambda
                                        MethodTree method = (MethodTree) methodPath.getLeaf();
                                        ast = new UnderlyingAST.CFGMethod(method, /*classTree*/ null);
                                        bodyPath = new TreePath(methodPath, method.getBody());
                                    }
                                    return CFGBuilder.build(bodyPath, env, ast, false, false);

                                }
                            });

    /**
     * Run the {@code transfer} dataflow analysis over the method or lambda which is the leaf of the
     * {@code methodPath}.
     *
     * <p>For caching, we make the following assumptions:
     * - if two paths to methods are {@code equal}, their control flow graph is the same.
     * - if two transfer functions are {@code equal}, and are run over the same control flow graph,
     *   the analysis result is the same.
     * - for all contexts, the analysis result is the same.
     */
    private <A extends AbstractValue<A>, S extends Store<S>, T extends TransferFunction<A, S>>
    Result<A, S, T> methodDataflow(TreePath methodPath, Context context, T transfer) {
        final ProcessingEnvironment env = JavacProcessingEnvironment.instance(context);
        final ControlFlowGraph cfg = cfgCache.getUnchecked(CfgParams.create(methodPath, env));
        final AnalysisParams aparams = AnalysisParams.create(transfer, cfg, env);
        @SuppressWarnings("unchecked")
        final Analysis<A, S, T> analysis = (Analysis<A, S, T>) analysisCache.getUnchecked(aparams);

        return new Result<A, S, T>() {
            @Override
            public Analysis<A, S, T> getAnalysis() {
                return analysis;
            }

            @Override
            public ControlFlowGraph getControlFlowGraph() {
                return cfg;
            }
        };
    }

    /**
     *
     * Run the {@code transfer} dataflow analysis to compute the abstract value of the expression
     * which is the leaf of {@code exprPath}.
     * @param exprPath expression
     * @param context Javac context
     * @param transfer transfer functions
     * @param <A> values in abstraction
     * @param <S> store type
     * @param <T> transfer function type
     * @return dataflow value for expression
     */
    @Nullable
    public <A extends AbstractValue<A>, S extends Store<S>,
            T extends TransferFunction<A, S>> A
    expressionDataflow(TreePath exprPath, Context context, T transfer) {
        final Tree leaf = exprPath.getLeaf();
        Preconditions.checkArgument(
                leaf instanceof ExpressionTree,
                "Leaf of exprPath must be of type ExpressionTree, but was %s",
                leaf.getClass().getName());

        final ExpressionTree expr = (ExpressionTree) leaf;
        final TreePath enclosingMethodPath = NullabilityUtil.findEnclosingMethodOrLambda(exprPath);
        if (enclosingMethodPath == null) {
            // This can happen in field initialization.
            // Currently not supported because it only happens in ~2% of cases.
            return null;
        }

        final Tree method = enclosingMethodPath.getLeaf();
        if (method instanceof MethodTree && ((MethodTree) method).getBody() == null) {
            // expressions can occur in abstract methods, for example {@code Map.Entry} in:
            //
            //   abstract Set<Map.Entry<K, V>> entries();
            return null;
        }
        // Calling getValue() on the AnalysisResult (as opposed to calling it on the Analysis itself)
        // ensures we get the result for expr
        // *before* any unboxing operations (like invoking intValue() on an Integer).  This is important,
        // e.g., for actually checking that the unboxing operation is legal.
        return methodDataflow(enclosingMethodPath, context, transfer).getAnalysis().getResult().getValue(expr);
    }

    /**
     *
     * @param methodPath path to method
     * @param context Javac context
     * @param transfer transfer functions
     * @param <A> values in abstraction
     * @param <S> store type
     * @param <T> transfer function type
     * @return dataflow result at exit of method
     */
    public <A extends AbstractValue<A>, S extends Store<S>,
            T extends TransferFunction<A, S>> S
    finalResultForMethod(TreePath methodPath, Context context, T transfer) {
        final Tree leaf = methodPath.getLeaf();
        Preconditions.checkArgument(
                leaf instanceof MethodTree,
                "Leaf of exprPath must be of type ExpressionTree, but was %s",
                leaf.getClass().getName());

        return methodDataflow(methodPath, context, transfer).getAnalysis().getRegularExitStore();
    }

    /**
     * clear the CFG and analysis caches
     */
    public void invalidateCaches() {
        cfgCache.invalidateAll();
        analysisCache.invalidateAll();
    }

    @AutoValue
    abstract static class CfgParams {
        // Should not be used for hashCode or equals
        private ProcessingEnvironment environment;

        private static CfgParams create(TreePath methodPath, ProcessingEnvironment environment) {
            CfgParams cp = new AutoValue_DataFlow_CfgParams(methodPath);
            cp.environment = environment;
            return cp;
        }

        ProcessingEnvironment environment() {
            return environment;
        }

        abstract TreePath methodPath();

    }

    @AutoValue
    abstract static class AnalysisParams {

        // Should not be used for hashCode or equals
        private ProcessingEnvironment environment;

        private static AnalysisParams create(
                TransferFunction<?, ?> transferFunction,
                ControlFlowGraph cfg,
                ProcessingEnvironment environment) {
            AnalysisParams ap = new AutoValue_DataFlow_AnalysisParams(transferFunction, cfg);
            ap.environment = environment;
            return ap;
        }

        ProcessingEnvironment environment() {
            return environment;
        }

        abstract TransferFunction<?, ?> transferFunction();

        abstract ControlFlowGraph cfg();

    }

    /**
     * A pair of Analysis and ControlFlowGraph.
     */
    private interface Result<A extends AbstractValue<A>, S extends Store<S>,
            T extends TransferFunction<A, S>> {
        Analysis<A, S, T> getAnalysis();
        ControlFlowGraph getControlFlowGraph();
    }

}
