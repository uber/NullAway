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

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.VisitorState;
import com.google.errorprone.dataflow.nullnesspropagation.Nullness;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import com.uber.nullaway.dataflow.NullnessStore;

import org.checkerframework.dataflow.cfg.UnderlyingAST;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;

import java.util.List;

/**
 * The general interface representing a handler.
 *
 * Handlers are used to model specific libraries and APIs, as opposed to general features of the Java language, which
 * are handled by the nullability checker core and dataflow packages.
 */
public interface Handler {

    /**
     * Called when NullAway matches a particular top level class.
     *
     * This also means we are starting a new Compilation Unit, which allows us to clear CU-specific state.
     *
     * @param analysis A reference to the running NullAway analysis.
     * @param tree The AST node for the class being matched.
     * @param state The current visitor state.
     * @param classSymbol The class symbol for the class being matched.
     */
    void onMatchTopLevelClass(
            NullAway analysis,
            ClassTree tree,
            VisitorState state,
            Symbol.ClassSymbol classSymbol);

    /**
     * Called when NullAway first matches a particular method node.
     *
     * @param analysis A reference to the running NullAway analysis.
     * @param tree The AST node for the method being matched.
     * @param state The current visitor state.
     * @param methodSymbol The method symbol for the method being matched.
     */
    void onMatchMethod(
            NullAway analysis,
            MethodTree tree,
            VisitorState state,
            Symbol.MethodSymbol methodSymbol);

    /**
     * Called when NullAway first matches a particular method call-site.
     *
     * @param analysis A reference to the running NullAway analysis.
     * @param tree The AST node for method invocation (call-site) being matched.
     * @param state The current visitor state.
     * @param methodSymbol The method symbol for the method being called.
     */
    void onMatchMethodInvocation(
            NullAway analysis,
            MethodInvocationTree tree,
            VisitorState state,
            Symbol.MethodSymbol methodSymbol);

    /**
     * Called when NullAway first matches a return statement.
     *
     * @param analysis A reference to the running NullAway analysis.
     * @param tree The AST node for the return statement being matched.
     * @param state The current visitor state.
     */
    void onMatchReturn(
            NullAway analysis,
            ReturnTree tree,
            VisitorState state);

    /**
     * Called when NullAway encounters an unannotated method and asks for params default nullability
     *
     * @param analysis A reference to the running NullAway analysis.
     * @param state The current visitor state.
     * @param methodSymbol The method symbol for the unannotated method in question.
     * @param actualParams The actual parameters from the invocation node
     * @param nonNullPositions Parameter nullability computed by upstream handlers (the core analysis suplies the
     * empty set to the first handler in the chain).
     * @return
     */
    ImmutableSet<Integer> onUnannotatedInvocationGetNonNullPositions(
            NullAway analysis,
            VisitorState state,
            Symbol.MethodSymbol methodSymbol,
            List<? extends ExpressionTree> actualParams,
            ImmutableSet<Integer> nonNullPositions);

    /**
     * Called after the analysis determines if a expression can be null or not, allowing handlers to override.
     *
     * @param analysis A reference to the running NullAway analysis.
     * @param expr The expression in question.
     * @param state The current visitor state.
     * @param exprMayBeNull Whether or not the expression may be null according to the base analysis.
     * @return
     */
    boolean onOverrideMayBeNullExpr(
            NullAway analysis,
            ExpressionTree expr,
            VisitorState state,
            boolean exprMayBeNull);

    /**
     * Called when the Dataflow analysis generates the initial NullnessStore for a method.
     *
     * @param underlyingAST The AST node for the method's body, using the checkers framework UnderlyingAST class.
     * @param parameters The formal parameters of the method.
     * @param result The state of the initial NullnessStore for the method at the point this hook is called,
     * represented as a builder.
     * @return The desired state of the initial NullnessStore for the method, after this hook is called, represented
     * as a builder. Usually, implementors of this hook will either take {@code result} and call {@code
     * setInformation(...)} on it to add additional nullness facts, or replace it with a new builder altogether.
     */
    NullnessStore.Builder<Nullness> onDataflowMethodInitialStore(
            UnderlyingAST underlyingAST,
            List<LocalVariableNode> parameters,
            NullnessStore.Builder<Nullness> result);

    /**
     * Called when the Dataflow analysis visits each method invocation.
     *
     * @param node The AST node for the method callsite.
     * @param types The types of the method's arguments.
     * @param thenUpdates NullnessStore updates to be added along the then path, handlers can add via the set() method.
     * @param elseUpdates NullnessStore updates to be added along the else path, handlers can add via the set() method.
     * @param bothUpdates NullnessStore updates to be added along both paths, handlers can add via the set() method.
     * @return The Nullness information for this method's return computed by this handler. If any handler in a handler
     * chain (see CompositeHandler) returns NULLABLE, then the final return should be NULLABLE.
     */
    Nullness onDataflowVisitMethodInvocation(
            MethodInvocationNode node,
            Types types,
            AccessPathNullnessPropagation.Updates thenUpdates,
            AccessPathNullnessPropagation.Updates elseUpdates,
            AccessPathNullnessPropagation.Updates bothUpdates);

    /**
     * Called when the Dataflow analysis visits a return statement.
     *
     * @param tree The AST node for the return statement being matched.
     * @param thenStore The NullnessStore for the true case of the expression inside the return statement.
     * @param elseStore The NullnessStore for the false case of the expression inside the return statement.
     */
    void onDataflowVisitReturn(ReturnTree tree, NullnessStore<Nullness> thenStore, NullnessStore<Nullness> elseStore);
}
