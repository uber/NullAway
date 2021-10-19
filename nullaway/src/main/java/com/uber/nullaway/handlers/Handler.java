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
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import com.uber.nullaway.dataflow.NullnessStore;
import java.util.List;
import java.util.Optional;
import org.checkerframework.nullaway.dataflow.cfg.UnderlyingAST;
import org.checkerframework.nullaway.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;

/**
 * The general interface representing a handler.
 *
 * <p>Handlers are used to model specific libraries and APIs, as opposed to general features of the
 * Java language, which are handled by the nullability checker core and dataflow packages.
 */
public interface Handler {

  /**
   * Called when NullAway matches a particular top level class.
   *
   * <p>This also means we are starting a new Compilation Unit, which allows us to clear CU-specific
   * state.
   *
   * @param analysis A reference to the running NullAway analysis.
   * @param tree The AST node for the class being matched.
   * @param state The current visitor state.
   * @param classSymbol The class symbol for the class being matched.
   */
  void onMatchTopLevelClass(
      NullAway analysis, ClassTree tree, VisitorState state, Symbol.ClassSymbol classSymbol);

  /**
   * Called when NullAway first matches a particular method node.
   *
   * @param analysis A reference to the running NullAway analysis.
   * @param tree The AST node for the method being matched.
   * @param state The current visitor state.
   * @param methodSymbol The method symbol for the method being matched.
   */
  void onMatchMethod(
      NullAway analysis, MethodTree tree, VisitorState state, Symbol.MethodSymbol methodSymbol);

  /**
   * Called when NullAway first matches a particular method call-site.
   *
   * @param analysis A reference to the running NullAway analysis.
   * @param tree The AST node for the method invocation (call-site) being matched.
   * @param state The current visitor state.
   * @param methodSymbol The method symbol for the method being called.
   */
  void onMatchMethodInvocation(
      NullAway analysis,
      MethodInvocationTree tree,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol);

  /**
   * Called when NullAway first matches a particular lambda expression.
   *
   * @param analysis A reference to the running NullAway analysis.
   * @param tree The AST node for the lambda expression being matched.
   * @param state The current visitor state.
   * @param methodSymbol The method symbol for the functional interface of the lambda being matched.
   */
  void onMatchLambdaExpression(
      NullAway analysis,
      LambdaExpressionTree tree,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol);

  /**
   * Called when NullAway first matches a particular method reference expression
   *
   * @param analysis A reference to the running NullAway analysis.
   * @param tree The AST node for the method reference expression being matched.
   * @param state The current visitor state.
   * @param methodSymbol The method symbol for the reference being matched.
   */
  void onMatchMethodReference(
      NullAway analysis,
      MemberReferenceTree tree,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol);

  /**
   * Called when NullAway first matches a return statement.
   *
   * @param analysis A reference to the running NullAway analysis.
   * @param tree The AST node for the return statement being matched.
   * @param state The current visitor state.
   */
  void onMatchReturn(NullAway analysis, ReturnTree tree, VisitorState state);

  /**
   * Called when NullAway encounters an unannotated method and asks for params explicitly
   * marked @Nullable
   *
   * <p>This is mostly used to force overriding methods to also use @Nullable, e.g. for callbacks
   * that can get passed null values.
   *
   * <p>Note that this should be only used for parameters EXPLICLTY marked as @Nullable (e.g. by
   * library models) rather than those considered @Nullable by NullAway's default optimistic
   * assumptions.
   *
   * @param methodSymbol The method symbol for the unannotated method in question.
   * @param explicitlyNullablePositions Parameter nullability computed by upstream handlers (the
   *     core analysis supplies the empty set to the first handler in the chain).
   * @return Updated parameter nullability computed by this handler.
   */
  ImmutableSet<Integer> onUnannotatedInvocationGetExplicitlyNullablePositions(
      Context context,
      Symbol.MethodSymbol methodSymbol,
      ImmutableSet<Integer> explicitlyNullablePositions);

  /**
   * Called when NullAway encounters an unannotated method and asks if return value is explicitly
   * marked @Nullable
   *
   * <p>Note that this should be only used for return values EXPLICLTY marked as @NonNull (e.g. by
   * library models) rather than those considered @Nullable by NullAway's default optimistic
   * assumptions.
   *
   * @param methodSymbol The method symbol for the unannotated method in question.
   * @param explicitlyNonNullReturn return nullability computed by upstream handlers.
   * @return Updated return nullability computed by this handler.
   */
  boolean onUnannotatedInvocationGetExplicitlyNonNullReturn(
      Symbol.MethodSymbol methodSymbol, boolean explicitlyNonNullReturn);

  /**
   * Called when NullAway encounters an unannotated method and asks for params default nullability
   *
   * @param analysis A reference to the running NullAway analysis.
   * @param state The current visitor state.
   * @param methodSymbol The method symbol for the unannotated method in question.
   * @param actualParams The actual parameters from the invocation node
   * @param nonNullPositions Parameter nullability computed by upstream handlers (the core analysis
   *     supplies the empty set to the first handler in the chain).
   * @return Updated parameter nullability computed by this handler.
   */
  ImmutableSet<Integer> onUnannotatedInvocationGetNonNullPositions(
      NullAway analysis,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol,
      List<? extends ExpressionTree> actualParams,
      ImmutableSet<Integer> nonNullPositions);

  /**
   * Called after the analysis determines if a expression can be null or not, allowing handlers to
   * override.
   *
   * @param analysis A reference to the running NullAway analysis.
   * @param expr The expression in question.
   * @param state The current visitor state.
   * @param exprMayBeNull Whether or not the expression may be null according to the base analysis
   *     or upstream handlers.
   * @return Whether or not the expression may be null, as updated by this handler.
   */
  boolean onOverrideMayBeNullExpr(
      NullAway analysis, ExpressionTree expr, VisitorState state, boolean exprMayBeNull);

  /**
   * Called when the Dataflow analysis generates the initial NullnessStore for a method or lambda.
   *
   * @param underlyingAST The AST node for the method's (or lambda's) body, using the checkers
   *     framework UnderlyingAST class.
   * @param parameters The formal parameters of the method.
   * @param result The state of the initial NullnessStore for the method (or lambda) at the point
   *     this hook is called, represented as a builder.
   * @return The desired state of the initial NullnessStore for the method (or lambda), after this
   *     hook is called, represented as a builder. Usually, implementors of this hook will either
   *     take {@code result} and call {@code setInformation(...)} on it to add additional nullness
   *     facts, or replace it with a new builder altogether.
   */
  NullnessStore.Builder onDataflowInitialStore(
      UnderlyingAST underlyingAST,
      List<LocalVariableNode> parameters,
      NullnessStore.Builder result);

  /**
   * Called when the Dataflow analysis visits each method invocation.
   *
   * @param node The AST node for the method callsite.
   * @param types {@link Types} for the current compilation
   * @param apContext the current access path context information (see {@link
   *     AccessPath.AccessPathContext}).
   * @param inputs NullnessStore information known before the method invocation.
   * @param thenUpdates NullnessStore updates to be added along the then path, handlers can add via
   *     the set() method.
   * @param elseUpdates NullnessStore updates to be added along the else path, handlers can add via
   *     the set() method.
   * @param bothUpdates NullnessStore updates to be added along both paths, handlers can add via the
   *     set() method.
   * @return The Nullness information for this method's return computed by this handler. See
   *     NullnessHint and CompositeHandler for more information about how this values get merged
   *     into a final Nullness value.
   */
  NullnessHint onDataflowVisitMethodInvocation(
      MethodInvocationNode node,
      Types types,
      Context context,
      AccessPath.AccessPathContext apContext,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates);

  /**
   * Called when the Dataflow analysis visits a return statement.
   *
   * @param tree The AST node for the return statement being matched.
   * @param thenStore The NullnessStore for the true case of the expression inside the return
   *     statement.
   * @param elseStore The NullnessStore for the false case of the expression inside the return
   *     statement.
   */
  void onDataflowVisitReturn(ReturnTree tree, NullnessStore thenStore, NullnessStore elseStore);

  /**
   * Called when the Dataflow analysis visits the result expression inside the body of lambda.
   *
   * <p>This is only called for lambda expressions with a single expression as their body. For
   * lambdas with a block of code as their body, onDataflowVisitReturn will be called instead, one
   * or more times.
   *
   * <p>It is not expected to be called for anything other than boolean expressions, which are the
   * only ones for which providing separate then/else stores makes sense. For simply getting the
   * final exit store of the lambda, see Dataflow.finalResult or
   * AccessPathNullnessAnalysis.forceRunOnMethod.
   *
   * @param tree The AST node for the expression being matched.
   * @param thenStore The NullnessStore for the true case of the expression inside the return
   *     statement.
   * @param elseStore The NullnessStore for the false case of the expression inside the return
   *     statement.
   */
  void onDataflowVisitLambdaResultExpression(
      ExpressionTree tree, NullnessStore thenStore, NullnessStore elseStore);

  /**
   * It should return an error wrapped in Optional if any of the handlers detect an error in
   * dereference.
   *
   * @param expr The AST node for the expression being matched.
   * @param baseExpr The AST node for the base of dereference expression being matched.
   * @param state The current visitor state.
   * @return {@link ErrorMessage} wrapped in {@link Optional} if dereference causes some error,
   *     otherwise returns empty Optional
   */
  Optional<ErrorMessage> onExpressionDereference(
      ExpressionTree expr, ExpressionTree baseExpr, VisitorState state);

  /**
   * Called when the store access paths are filtered for local variable information before an
   * expression.
   *
   * @param accessPath The access path that needs to be checked if filtered.
   * @param state The current visitor state.
   * @return true if the nullability information for this accesspath should be treated as part of
   *     the surrounding context when processing a lambda expression or anonymous class declaration.
   */
  boolean includeApInfoInSavedContext(AccessPath accessPath, VisitorState state);

  /**
   * Called during dataflow analysis initialization to register structurally immutable types.
   *
   * <p>Handlers declare structurally immutable types, requesting that they be treated as constants
   * when they appear as arguments of method inside an AccessPath. Whenever a static final field of
   * one of these types appears as an argument to a method in an access path (e.g. get(Foo.f) where
   * Foo.f is a static final field of an immutable type T returned by this method), it is treated
   * the same as a String or primitive type compile-time constant for the purposes of tracking the
   * nullability of that access path.
   *
   * @return A set of fully qualified immutable type names.
   */
  ImmutableSet<String> onRegisterImmutableTypes();

  /**
   * A three value enum for handlers implementing onDataflowVisitMethodInvocation to communicate
   * their knowledge of the method return nullability to the the rest of NullAway.
   */
  public enum NullnessHint {
    /**
     * No new information about return nullability, defer to the core algorithm and other handlers.
     */
    UNKNOWN(0, Nullness.NONNULL),
    /**
     * The method is nullable in general (e.g. due to a library model or a return type annotation).
     * Override core behavior and mark this method as Nullable, unless a handler returns
     * FORCE_NONNULL.
     */
    HINT_NULLABLE(1, Nullness.NULLABLE),
    /**
     * Handler asserts this method is guaranteed to be NonNull for the current invocation (e.g. used
     * by ContractHandler when all preconditions for a "-> !null" clause are known to hold).
     */
    FORCE_NONNULL(2, Nullness.NONNULL);

    private final int priority;
    private final Nullness nullness;

    NullnessHint(int priority, Nullness nullness) {
      this.priority = priority;
      this.nullness = nullness;
    }

    public Nullness toNullness() {
      return nullness;
    }

    public NullnessHint merge(NullnessHint other) {
      if (other.priority > this.priority) {
        return other;
      } else {
        return this;
      }
    }
  }
}
