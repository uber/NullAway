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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.VisitorState;
import com.google.errorprone.dataflow.nullnesspropagation.Nullness;
import com.google.errorprone.predicates.TypePredicate;
import com.google.errorprone.predicates.type.DescendantOf;
import com.google.errorprone.suppliers.Suppliers;
import com.google.errorprone.util.ASTHelpers;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.NullabilityUtil;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessAnalysis;
import com.uber.nullaway.dataflow.NullnessStore;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;

import org.checkerframework.dataflow.cfg.UnderlyingAST;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This Handler transfers nullability info through chains of calls to methods of io.reactivex.Observable.
 *
 * This allows the checker to know, for example, that code like the following has no NPEs:
 * observable.filter(... return o.foo() != null; ...).map(... o.foo.toString() ...)
 */
class RxNullabilityPropagator extends BaseNoOpHandler {

    private final TypePredicate SUBTYPE_OF_OBSERVABLE =
            new DescendantOf(Suppliers.typeFromString("io.reactivex.Observable"));

    // Names of all the methods of io.reactivex.Observable that behave like .filter(...) (must take exactly 1 argument)
    private final List<String> OBSERVABLE_FILTER_METHOD_NAMES = ImmutableList.of("filter");

    // Names of all the methods of io.reactivex.Observable that behave like .map(...) for the purposes of this checker
    // (must take exactly 1 argument)
    private final List<String> OBSERVABLE_MAP_METHOD_NAMES = ImmutableList.of("map", "flatMap", "flatMapSingle");

    // List of methods of io.reactivex.Observable through which we just propagate the nullability information of the
    // last call, e.g. m() in Observable.filter(...).m().map(...) means the nullability information from filter(...)
    // should still be propagated to map(...), ignoring the interleaving call to m().
    // We assume that if m() is a pass-through method for Observable, so are m(a1), m(a1,a2), etc. If that is ever not
    // the case, we can use a more complex method subsignature her.
    private final List<String> OBSERVABLE_PASSTHROUGH_METHOD_NAMES = ImmutableList.of(
            "distinct",
            "distinctUntilChanged",
            "observeOn");

    /* Terminology for this class internals:
     *
     * Assume the following observable chain:
     *
     * observable.filter(new A() {
     *      public boolean filter(T o) {
     *          ...
     *      }
     * }.map(new B() {
     *      public T apply(T o) {
     *          ...
     *      }
     * }
     *
     * We call:
     *   - A.filter - The filter method (not Observable.filter)
     *   - B.apply - The map method (not Observable.map)
     *   - observable.filter().map() is the observable call chain, and 'Observable.map' is the outer call of
     *   'Observable.filter'). In general, for observable.a().b().c(), c is the outer call of b and b the outer call
     *   of a in the chain.
     *
     * This class works by building the following maps which keep enough state outside of the standard dataflow
     * analysis for us to figure out what's going on:
     *
     * Note: If this state ever becomes a memory issue, we can discard it as soon as we exit any method at the
     * topmost scope (e.g. not a method called from an anonymous inner class inside another method or a lambda).
     */

    // Set of filter methods found thus far (e.g. A.filter, see above)
    private Set<MethodTree> filterMethodsSet = new LinkedHashSet<MethodTree>();

    // Maps each call in the observable call chain to its outer call (see above).
    private Map<MethodInvocationTree, MethodInvocationTree> observableOuterCallInChain = new
            LinkedHashMap<MethodInvocationTree, MethodInvocationTree>();

    // Maps the call in the observable call chain to the relevant functor method.
    // e.g. In the example above:
    //   observable.filter() => A.filter
    //   observable.filter().map() => B.apply
    private Map<MethodInvocationTree, MethodTree> observableCallToActualFunctorMethod = new
            LinkedHashMap<MethodInvocationTree, MethodTree>();

    // Map from map method to corresponding previous filter method (e.g. B.apply => A.filter)
    private Map<MethodTree, MethodTree> mapToFilterMap = new LinkedHashMap<MethodTree, MethodTree>();

    /*
     * Note that the above methods imply a diagram like the following:
     *
     *                              /--- observable.filter(new A() {
     *                              |      \->public boolean filter(T o) {<---\
     * [observableOuterCallInChain] |             ...                         |
     *                              |         }                               | [mapToFilterMap]
     *                              \--> }.map(new B() {                      |
     *                                     \->public T apply(T o) {        ---/
     *                                            ...
     *                                        }
     *                                   }
     */

    // Map from filter method to corresponding nullability info after the method returns true.
    // Specifically, this is the least upper bound of the "then" store on the branch of every return statement in
    // which the expression after the return can be true.
    private Map<MethodTree, NullnessStore<Nullness>> filterToNSMap = new LinkedHashMap<>();

    // Maps the method body to the corresponding method tree, used because the dataflow analysis loses the pointer
    // to the MethodTree by the time we hook into it.
    private Map<BlockTree, MethodTree> blockToMethod = new LinkedHashMap<BlockTree, MethodTree>();

    // Maps the return statements of the filter method to the filter tree itself, similar issue as above.
    private Map<ReturnTree, MethodTree> returnToMethod = new LinkedHashMap<ReturnTree, MethodTree>();

    RxNullabilityPropagator() {
        super();
    }

    @Override
    public void onMatchTopLevelClass(
            NullAway analysis,
            ClassTree tree,
            VisitorState state,
            Symbol.ClassSymbol classSymbol) {
        // Clear compilation unit specific state
        this.filterMethodsSet.clear();
        this.observableOuterCallInChain.clear();
        this.observableCallToActualFunctorMethod.clear();
        this.mapToFilterMap.clear();
        this.filterToNSMap.clear();
        this.blockToMethod.clear();
        this.returnToMethod.clear();
    }

    @Override
    public void onMatchMethodInvocation(
            NullAway analysis,
            MethodInvocationTree tree,
            VisitorState state,
            Symbol.MethodSymbol methodSymbol) {
        Type receiverType = ASTHelpers.getReceiverType(tree);
        // Look only at invocations of methods of reactivex Observable
        if (SUBTYPE_OF_OBSERVABLE.apply(receiverType, state)) {
            String methodName = methodSymbol.getQualifiedName().toString();

            // Build observable call chain
            buildObservableCallChain(tree);

            // Dispatch to code handling specific observer methods
            if (OBSERVABLE_FILTER_METHOD_NAMES.contains(methodName) && methodSymbol.getParameters().length() == 1) {
                ExpressionTree argTree = tree.getArguments().get(0);
                if (argTree instanceof NewClassTree) {
                    ClassTree annonClassBody = ((NewClassTree) argTree).getClassBody();
                    // Ensure that this `new A() ...` has a custom class body, otherwise, we skip for now.
                    // In the future, we could look at the declared type and its inheritance chain, at least for
                    // filters.
                    if (annonClassBody != null) {
                        handleFilterAnonClass(tree, annonClassBody, state);
                    }
                }
                // This can also be a lambda, which currently cannot be used in the code we look at, but might be
                // needed by others. Add support soon.
            } else if (OBSERVABLE_MAP_METHOD_NAMES.contains(methodName) && methodSymbol.getParameters().length() == 1) {
                ExpressionTree argTree = tree.getArguments().get(0);
                if (argTree instanceof NewClassTree) {
                    ClassTree annonClassBody = ((NewClassTree) argTree).getClassBody();
                    // Ensure that this `new B() ...` has a custom class body, otherwise, we skip for now.
                    if (annonClassBody != null) {
                        handleMapAnonClass(tree, annonClassBody, state);
                    }
                }
                // This can also be a lambda, which currently cannot be used in the code we look at, but might be
                // needed by others. Add support soon.
            }
        }
    }

    private void buildObservableCallChain(MethodInvocationTree tree) {
        ExpressionTree methodSelect = tree.getMethodSelect();
        if (methodSelect instanceof MemberSelectTree) {
            ExpressionTree receiverExpression = ((MemberSelectTree) methodSelect).getExpression();
            if (receiverExpression instanceof MethodInvocationTree) {
                observableOuterCallInChain.put((MethodInvocationTree) receiverExpression, tree);
            }
            // ToDo: Eventually we want to handle more complex observer chains, but filter(...).map(...) is the
            // common case.
        } // ToDo: What else can be here? If there are other cases than MemberSelectTree, handle them.
    }

    private void handleFilterAnonClass(
            MethodInvocationTree observableDotFilter,
            ClassTree annonClassBody,
            VisitorState state) {
        for (Tree t : annonClassBody.getMembers()) {
            if (t instanceof MethodTree
                    && ((MethodTree) t).getName().toString().equals("test")) {
                filterMethodsSet.add((MethodTree) t);
                observableCallToActualFunctorMethod.put(observableDotFilter, (MethodTree) t);
                // Traverse the observable call chain out through any pass-through methods
                MethodInvocationTree outerCallInChain = observableOuterCallInChain.get(observableDotFilter);
                while (outerCallInChain != null
                        && SUBTYPE_OF_OBSERVABLE.apply(ASTHelpers.getReceiverType(outerCallInChain), state)
                        && OBSERVABLE_PASSTHROUGH_METHOD_NAMES.contains(
                                ASTHelpers.getSymbol(outerCallInChain).getQualifiedName().toString())) {
                    outerCallInChain = observableOuterCallInChain.get(outerCallInChain);
                }
                // Check for a map method
                MethodInvocationTree mapCallsite = observableOuterCallInChain.get(observableDotFilter);
                if (outerCallInChain != null
                        && observableCallToActualFunctorMethod.containsKey(outerCallInChain)
                        && SUBTYPE_OF_OBSERVABLE.apply(ASTHelpers.getReceiverType(outerCallInChain), state)
                        && OBSERVABLE_MAP_METHOD_NAMES.contains(
                                ASTHelpers.getSymbol(outerCallInChain).getQualifiedName().toString())) {
                    // Update mapToFilterMap
                    mapToFilterMap.put(observableCallToActualFunctorMethod.get(outerCallInChain),
                            (MethodTree) t);
                }
            }
        }
    }

    private void handleMapAnonClass(
            MethodInvocationTree observableDotMap,
            ClassTree annonClassBody,
            VisitorState state) {
        for (Tree t : annonClassBody.getMembers()) {
            if (t instanceof MethodTree
                    && ((MethodTree) t).getName().toString().equals("apply")) {
                observableCallToActualFunctorMethod.put(observableDotMap, (MethodTree) t);
            }
        }
    }

    @Override
    public void onMatchMethod(
            NullAway analysis,
            MethodTree tree,
            VisitorState state,
            Symbol.MethodSymbol methodSymbol) {
        if (mapToFilterMap.containsKey(tree)) {
            blockToMethod.put(tree.getBody(), tree);
        }
    }

    private boolean canBooleanExpressionEvalToTrue(ExpressionTree expressionTree) {
        if (expressionTree instanceof LiteralTree) {
            LiteralTree expressionAsLiteral = (LiteralTree) expressionTree;
            if (expressionAsLiteral.getValue() instanceof Boolean) {
                return (boolean) expressionAsLiteral.getValue();
            } else {
                throw new RuntimeException("not a boolean expression!");
            }
        }
        // We are fairly conservative, anything other than 'return false;' is assumed to potentially be true.
        // No SAT-solving or any other funny business.
        return true;
    }

    @Override
    public void onMatchReturn(NullAway analysis, ReturnTree tree, VisitorState state) {
        // Figure out the enclosing method node
        TreePath enclosingMethodOrLambda = NullabilityUtil.findEnclosingMethodOrLambda(state.getPath());
        if (enclosingMethodOrLambda == null) {
            throw new RuntimeException("no enclosing method!");
        }
        Tree leaf = enclosingMethodOrLambda.getLeaf();
        if (leaf instanceof MethodTree) {
            MethodTree enclosingMethod = (MethodTree) leaf;
            if (filterMethodsSet.contains(enclosingMethod)) {
                returnToMethod.put(tree, enclosingMethod);
                // We need to manually trigger the dataflow analysis to run on the filter method,
                // this ensures onDataflowVisitReturn(...) gets called for all return statements in this method before
                // onDataflowMethodInitialStore(...) is called for all successor methods in the observable
                // chain.
                // Caching should prevent us from re-analyzing any given method.
                AccessPathNullnessAnalysis nullnessAnalysis = analysis.getNullnessAnalysis(state);
                nullnessAnalysis.forceRunOnMethod(new TreePath(state.getPath(), enclosingMethod), state.context);
            }
        }
        // This can also be a lambda, which currently cannot be used in the code we look at, but might be needed by
        // others. Add support soon.

    }

    @Override
    public NullnessStore.Builder<Nullness> onDataflowMethodInitialStore(
            UnderlyingAST underlyingAST,
            List<LocalVariableNode> parameters,
            NullnessStore.Builder<Nullness> nullnessBuilder) {
        MethodTree tree = blockToMethod.get((BlockTree) underlyingAST.getCode());
        if (mapToFilterMap.containsKey(tree)) {
            // Plug Nullness info from filter method into entry to map method.
            MethodTree filterMethodTree = mapToFilterMap.get(tree);
            LocalVariableNode filterLocalName = new LocalVariableNode(filterMethodTree.getParameters().get(0));
            LocalVariableNode mapLocalName = new LocalVariableNode(tree.getParameters().get(0));
            NullnessStore<Nullness> filterNullnessStore = filterToNSMap.get(mapToFilterMap.get(tree));
            NullnessStore<Nullness> renamedRootsNullnessStore =
                    filterNullnessStore.uprootAccessPaths(ImmutableMap.of(filterLocalName, mapLocalName));
            for (AccessPath ap : renamedRootsNullnessStore.getAccessPathsWithValue(Nullness.NONNULL)) {
                nullnessBuilder.setInformation(ap, Nullness.NONNULL);
            }
        }
        return nullnessBuilder;
    }

    @Override
    public void onDataflowVisitReturn(
            ReturnTree tree,
            NullnessStore<Nullness> thenStore,
            NullnessStore<Nullness> elseStore) {
        if (returnToMethod.containsKey(tree)) {
            MethodTree filterMethod = returnToMethod.get(tree);
            ExpressionTree retExpression = tree.getExpression();
            if (canBooleanExpressionEvalToTrue(retExpression)) {
                if (filterToNSMap.containsKey(filterMethod)) {
                    filterToNSMap.put(filterMethod, filterToNSMap.get(filterMethod).leastUpperBound(thenStore));
                } else {
                    filterToNSMap.put(filterMethod, thenStore);
                }
            }
        }
    }
}
