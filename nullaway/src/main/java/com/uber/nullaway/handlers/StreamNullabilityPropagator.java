package com.uber.nullaway.handlers;

/*
 * Copyright (c) 2019 Uber Technologies, Inc.
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

import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.NullabilityUtil;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathElement;
import com.uber.nullaway.dataflow.AccessPathNullnessAnalysis;
import com.uber.nullaway.dataflow.NullnessStore;
import com.uber.nullaway.handlers.stream.CollectLikeMethodRecord;
import com.uber.nullaway.handlers.stream.MapLikeMethodRecord;
import com.uber.nullaway.handlers.stream.MapOrCollectLikeMethodRecord;
import com.uber.nullaway.handlers.stream.MapOrCollectMethodToFilterInstanceRecord;
import com.uber.nullaway.handlers.stream.StreamTypeRecord;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import org.checkerframework.nullaway.dataflow.cfg.UnderlyingAST;
import org.checkerframework.nullaway.dataflow.cfg.node.LocalVariableNode;

/**
 * This Handler transfers nullability info through chains of calls to methods of
 * java.util.stream.Stream.
 *
 * <p>This allows the checker to know, for example, that code like the following has no NPEs:
 * observable.filter(... return o.foo() != null; ...).map(... o.foo.toString() ...)
 */
class StreamNullabilityPropagator extends BaseNoOpHandler {

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
   * We also support collect-like methods, which take a collector factory method as an argument, e.g.:
   *
   * stream.filter(...).collect(Collectors.toMap(l1, l2)) (where l1 and l2 are lambdas)
   *
   * For such scenarios, the lambdas l1 and l2 (or the named method in the equivalent anonymous class) serve
   * an equivalent role to the map methods discussed above.
   *
   * This class works by building the following maps which keep enough state outside of the standard dataflow
   * analysis for us to figure out what's going on:
   *
   * Note: If this state ever becomes a memory issue, we can discard it as soon as we exit any method at the
   * topmost scope (e.g. not a method called from an anonymous inner class inside another method or a lambda).
   */

  // Set of filter methods found thus far (e.g. A.filter, see above)
  private final Set<Tree> filterMethodOrLambdaSet = new LinkedHashSet<>();

  // Maps each call in the observable call chain to its outer call (see above).
  private final Map<MethodInvocationTree, MethodInvocationTree> observableOuterCallInChain =
      new LinkedHashMap<>();

  // Maps the call in the observable call chain to the relevant inner method or lambda.
  // e.g. In the example above:
  //   observable.filter() => A.filter
  //   observable.filter().map() => B.apply
  private final Map<MethodInvocationTree, Tree> observableCallToInnerMethodOrLambda =
      new LinkedHashMap<>();

  @AutoValue
  abstract static class CollectRecordAndInnerMethod {

    static CollectRecordAndInnerMethod create(
        CollectLikeMethodRecord collectlikeMethodRecord, Tree innerMethodOrLambda) {
      return new AutoValue_StreamNullabilityPropagator_CollectRecordAndInnerMethod(
          collectlikeMethodRecord, innerMethodOrLambda);
    }

    abstract CollectLikeMethodRecord getCollectLikeMethodRecord();

    abstract Tree getInnerMethodOrLambda();
  }

  // Maps collect calls in the observable call chain to the relevant (collect record, inner method
  // or lambda) pairs.
  // We need a Multimap here since there may be multiple relevant methods / lambdas.
  // E.g.: stream.filter(...).collect(Collectors.toMap(l1, l2)) => (record for toMap, {l1,l2})
  private final SetMultimap<MethodInvocationTree, CollectRecordAndInnerMethod>
      collectCallToRecordsAndInnerMethodsOrLambdas = LinkedHashMultimap.create();

  // Map from map or collect method (or lambda) to corresponding previous filter method (e.g.
  // B.apply => A.filter for the map example above, or l1 => A.filter and l2 => A.filter for the
  // collect example above)
  private final Map<Tree, MapOrCollectMethodToFilterInstanceRecord> mapOrCollectRecordToFilterMap =
      new LinkedHashMap<>();

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

  // Map from filter method (or lambda) to corresponding nullability info after the function returns
  // true.
  // Specifically, this is the least upper bound of the "then" store on the branch of every return
  // statement in which the expression after the return can be true.
  private final Map<Tree, NullnessStore> filterToNSMap = new LinkedHashMap<>();

  // Maps the body of a method or lambda to the corresponding enclosing tree, used because the
  // dataflow analysis
  // loses the pointer to the tree by the time we hook into its body.
  private final Map<Tree, Tree> bodyToMethodOrLambda = new LinkedHashMap<>();

  // Maps the return statements of the filter method to the filter tree itself, similar issue as
  // above.
  private final Map<ReturnTree, Tree> returnToEnclosingMethodOrLambda = new LinkedHashMap<>();

  // Similar to above, but mapping expression-bodies to their enclosing lambdas
  private final Map<ExpressionTree, LambdaExpressionTree> expressionBodyToFilterLambda =
      new LinkedHashMap<>();
  private final ImmutableList<StreamTypeRecord> models;

  StreamNullabilityPropagator(ImmutableList<StreamTypeRecord> models) {
    super();
    this.models = models;
  }

  @Override
  public void onMatchTopLevelClass(
      NullAway analysis, ClassTree tree, VisitorState state, Symbol.ClassSymbol classSymbol) {
    // Clear compilation unit specific state
    this.filterMethodOrLambdaSet.clear();
    this.observableOuterCallInChain.clear();
    this.observableCallToInnerMethodOrLambda.clear();
    this.collectCallToRecordsAndInnerMethodsOrLambdas.clear();
    this.mapOrCollectRecordToFilterMap.clear();
    this.filterToNSMap.clear();
    this.bodyToMethodOrLambda.clear();
    this.returnToEnclosingMethodOrLambda.clear();
  }

  @Override
  public void onMatchMethodInvocation(
      MethodInvocationTree tree, MethodAnalysisContext methodAnalysisContext) {

    Symbol.MethodSymbol methodSymbol = methodAnalysisContext.methodSymbol();
    VisitorState state = methodAnalysisContext.state();
    Type receiverType = ASTHelpers.getReceiverType(tree);
    for (StreamTypeRecord streamType : models) {
      if (!streamType.matchesType(receiverType, state)) {
        continue;
      }
      // Build observable call chain
      buildObservableCallChain(tree);
      if (methodSymbol.getParameters().length() != 1) {
        continue;
      }

      // Dispatch to code handling specific observer methods
      if (streamType.isFilterMethod(methodSymbol)) {
        handleFilterMethod(tree, streamType, state);
      } else if (streamType.isMapMethod(methodSymbol)) {
        handleMapMethod(tree, streamType, methodSymbol);
      } else {
        handleCollectMethod(tree, streamType, methodSymbol);
      }
    }
  }

  private void handleCollectMethod(
      MethodInvocationTree tree, StreamTypeRecord streamType, Symbol.MethodSymbol methodSymbol) {
    // We can have multiple CollectLikeMethodRecords for a single collect method, reflecting
    // the different possible collector factory methods whose result may be passed to a
    // collect call.  At a single collect call site, at most one of these records will be
    // relevant. So, we loop through them all, but break out of the loop as soon as we find
    // one that matches.
    for (CollectLikeMethodRecord collectlikeMethodRecord :
        streamType.getCollectlikeMethodRecords(methodSymbol)) {
      boolean handled = handleCollectCall(tree, collectlikeMethodRecord);
      if (handled) {
        break;
      }
    }
  }

  private void handleFilterMethod(
      MethodInvocationTree tree, StreamTypeRecord streamType, VisitorState state) {
    ExpressionTree argTree = tree.getArguments().get(0);
    if (argTree instanceof NewClassTree) {
      ClassTree anonClassBody = ((NewClassTree) argTree).getClassBody();
      // Ensure that this `new A() ...` has a custom class body, otherwise, we skip for now.
      // In the future, we could look at the declared type and its inheritance chain, at least
      // for
      // filters.
      if (anonClassBody != null) {
        handleFilterAnonClass(streamType, tree, anonClassBody, state);
      }
    } else if (argTree instanceof LambdaExpressionTree) {
      LambdaExpressionTree lambdaTree = (LambdaExpressionTree) argTree;
      handleFilterLambda(streamType, tree, lambdaTree, state);
    }
  }

  private void handleMapMethod(
      MethodInvocationTree tree, StreamTypeRecord streamType, Symbol.MethodSymbol methodSymbol) {
    ExpressionTree argTree = tree.getArguments().get(0);
    if (argTree instanceof NewClassTree) {
      ClassTree annonClassBody = ((NewClassTree) argTree).getClassBody();
      // Ensure that this `new B() ...` has a custom class body, otherwise, we skip for now.
      if (annonClassBody != null) {
        MapLikeMethodRecord methodRecord = streamType.getMaplikeMethodRecord(methodSymbol);
        handleMapOrCollectAnonClassBody(
            methodRecord, annonClassBody, t -> observableCallToInnerMethodOrLambda.put(tree, t));
      }
    } else if (argTree instanceof LambdaExpressionTree) {
      observableCallToInnerMethodOrLambda.put(tree, argTree);
    } else if (argTree instanceof MemberReferenceTree) {
      observableCallToInnerMethodOrLambda.put(tree, argTree);
    }
  }

  /**
   * Handles a call to a collect-like method. If the argument to the method is supported, updates
   * the {@link #collectCallToRecordsAndInnerMethodsOrLambdas} map appropriately.
   *
   * @param collectInvocationTree The MethodInvocationTree representing the call to the collect-like
   *     method.
   * @param collectlikeMethodRecord The record representing the collect-like method.
   * @return true if the argument to the collect method was a call to the factory method in the
   *     record, false otherwise.
   */
  private boolean handleCollectCall(
      MethodInvocationTree collectInvocationTree, CollectLikeMethodRecord collectlikeMethodRecord) {
    ExpressionTree argTree = collectInvocationTree.getArguments().get(0);
    if (argTree instanceof MethodInvocationTree) {
      // the argument passed to the collect method.  We check if this is a call to the collector
      // factory method from the record
      MethodInvocationTree collectInvokeArg = (MethodInvocationTree) argTree;
      Symbol.MethodSymbol collectInvokeArgSymbol = ASTHelpers.getSymbol(collectInvokeArg);
      if (collectInvokeArgSymbol
              .owner
              .getQualifiedName()
              .contentEquals(collectlikeMethodRecord.collectorFactoryMethodClass())
          && collectInvokeArgSymbol
              .toString()
              .equals(collectlikeMethodRecord.collectorFactoryMethodSignature())) {
        List<? extends ExpressionTree> arguments = collectInvokeArg.getArguments();
        for (int ind : collectlikeMethodRecord.argsToCollectorFactoryMethod()) {
          ExpressionTree factoryMethodArg = arguments.get(ind);
          // TODO eventually, support method references, though this is likely only useful in
          // JSpecify mode with generics checking
          if (factoryMethodArg instanceof NewClassTree) {
            ClassTree anonClassBody = ((NewClassTree) factoryMethodArg).getClassBody();
            // Ensure that this `new B() ...` has a custom class body, otherwise, we skip for now.
            if (anonClassBody != null) {
              handleMapOrCollectAnonClassBody(
                  collectlikeMethodRecord,
                  anonClassBody,
                  t ->
                      collectCallToRecordsAndInnerMethodsOrLambdas.put(
                          collectInvocationTree,
                          CollectRecordAndInnerMethod.create(collectlikeMethodRecord, t)));
            }
          } else if (factoryMethodArg instanceof LambdaExpressionTree) {
            collectCallToRecordsAndInnerMethodsOrLambdas.put(
                collectInvocationTree,
                CollectRecordAndInnerMethod.create(collectlikeMethodRecord, factoryMethodArg));
          }
        }
        return true;
      }
    }
    return false;
  }

  private void buildObservableCallChain(MethodInvocationTree tree) {
    ExpressionTree methodSelect = tree.getMethodSelect();
    if (methodSelect instanceof MemberSelectTree) {
      ExpressionTree receiverExpression = ((MemberSelectTree) methodSelect).getExpression();
      if (receiverExpression instanceof MethodInvocationTree) {
        observableOuterCallInChain.put((MethodInvocationTree) receiverExpression, tree);
      }
    } // ToDo: What else can be here? If there are other cases than MemberSelectTree, handle them.
  }

  private void handleChainFromFilter(
      StreamTypeRecord streamType,
      MethodInvocationTree observableDotFilter,
      Tree filterMethodOrLambda,
      VisitorState state) {
    MethodInvocationTree outerCallInChain = observableDotFilter;
    if (outerCallInChain == null) {
      return;
    }
    // Traverse the observable call chain out through any pass-through methods
    do {
      outerCallInChain = observableOuterCallInChain.get(outerCallInChain);
      // Check for a map method (which might be a pass-through method or the first method after a
      // pass-through chain)
      if (observableCallToInnerMethodOrLambda.containsKey(outerCallInChain)) {
        // Update mapOrCollectRecordToFilterMap
        Symbol.MethodSymbol mapMethod = ASTHelpers.getSymbol(outerCallInChain);
        if (streamType.isMapMethod(mapMethod)) {
          MapOrCollectMethodToFilterInstanceRecord record =
              new MapOrCollectMethodToFilterInstanceRecord(
                  streamType.getMaplikeMethodRecord(mapMethod), filterMethodOrLambda);
          mapOrCollectRecordToFilterMap.put(
              observableCallToInnerMethodOrLambda.get(outerCallInChain), record);
        }
      } else if (collectCallToRecordsAndInnerMethodsOrLambdas.containsKey(outerCallInChain)) {
        // Update mapOrCollectRecordToFilterMap for all relevant methods / lambdas
        for (CollectRecordAndInnerMethod collectRecordAndInnerMethod :
            collectCallToRecordsAndInnerMethodsOrLambdas.get(castToNonNull(outerCallInChain))) {
          MapOrCollectMethodToFilterInstanceRecord record =
              new MapOrCollectMethodToFilterInstanceRecord(
                  collectRecordAndInnerMethod.getCollectLikeMethodRecord(), filterMethodOrLambda);
          mapOrCollectRecordToFilterMap.put(
              collectRecordAndInnerMethod.getInnerMethodOrLambda(), record);
        }
      }
    } while (outerCallInChain != null
        && streamType.matchesType(ASTHelpers.getReceiverType(outerCallInChain), state)
        && streamType.isPassthroughMethod(ASTHelpers.getSymbol(outerCallInChain)));
  }

  private void handleFilterAnonClass(
      StreamTypeRecord streamType,
      MethodInvocationTree observableDotFilter,
      ClassTree annonClassBody,
      VisitorState state) {
    for (Tree t : annonClassBody.getMembers()) {
      if (t instanceof MethodTree && ((MethodTree) t).getName().toString().equals("test")) {
        filterMethodOrLambdaSet.add(t);
        observableCallToInnerMethodOrLambda.put(observableDotFilter, t);
        handleChainFromFilter(streamType, observableDotFilter, t, state);
      }
    }
  }

  private void handleFilterLambda(
      StreamTypeRecord streamType,
      MethodInvocationTree observableDotFilter,
      LambdaExpressionTree lambdaTree,
      VisitorState state) {
    filterMethodOrLambdaSet.add(lambdaTree);
    observableCallToInnerMethodOrLambda.put(observableDotFilter, lambdaTree);
    handleChainFromFilter(streamType, observableDotFilter, lambdaTree, state);
  }

  /**
   * If the relevant inner method from the method record is found in the class body, the consumer is
   * called with the corresponding MethodTree.
   */
  private void handleMapOrCollectAnonClassBody(
      MapOrCollectLikeMethodRecord methodRecord, ClassTree anonClassBody, Consumer<Tree> consumer) {
    for (Tree t : anonClassBody.getMembers()) {
      if (t instanceof MethodTree
          && ((MethodTree) t).getName().toString().equals(methodRecord.innerMethodName())) {
        consumer.accept(t);
      }
    }
  }

  @Override
  public void onMatchMethod(MethodTree tree, MethodAnalysisContext methodAnalysisContext) {
    if (mapOrCollectRecordToFilterMap.containsKey(tree)) {
      bodyToMethodOrLambda.put(tree.getBody(), tree);
    }
  }

  @Override
  public void onMatchLambdaExpression(
      LambdaExpressionTree tree, MethodAnalysisContext methodAnalysisContext) {
    VisitorState state = methodAnalysisContext.state();
    if (filterMethodOrLambdaSet.contains(tree)
        && tree.getBodyKind().equals(LambdaExpressionTree.BodyKind.EXPRESSION)) {
      expressionBodyToFilterLambda.put((ExpressionTree) tree.getBody(), tree);
      // Single expression lambda, onMatchReturn will not be triggered, force the dataflow analysis
      // here
      AccessPathNullnessAnalysis nullnessAnalysis =
          methodAnalysisContext.analysis().getNullnessAnalysis(state);
      nullnessAnalysis.forceRunOnMethod(state.getPath(), state.context);
    }
    if (mapOrCollectRecordToFilterMap.containsKey(tree)) {
      bodyToMethodOrLambda.put(tree.getBody(), tree);
    }
  }

  @Override
  public void onMatchMethodReference(
      MemberReferenceTree tree, MethodAnalysisContext methodAnalysisContext) {
    VisitorState state = methodAnalysisContext.state();
    MapOrCollectMethodToFilterInstanceRecord callInstanceRecord =
        mapOrCollectRecordToFilterMap.get(tree);
    if (callInstanceRecord != null && ((JCTree.JCMemberReference) tree).kind.isUnbound()) {
      // Unbound method reference, check if we know the corresponding path to be NonNull from the
      // previous filter.
      Tree filterTree = callInstanceRecord.getFilter();
      if (!(filterTree instanceof MethodTree || filterTree instanceof LambdaExpressionTree)) {
        throw new IllegalStateException(
            "unexpected filterTree type "
                + filterTree.getClass()
                + " "
                + state.getSourceForNode(filterTree));
      }
      NullnessStore filterNullnessStore = filterToNSMap.get(filterTree);
      if (filterNullnessStore == null) {
        throw new IllegalStateException(
            "null filterNullStore for tree " + state.getSourceForNode(filterTree));
      }
      for (AccessPath ap : filterNullnessStore.getAccessPathsWithValue(Nullness.NONNULL)) {
        // Find the access path corresponding to the current unbound method reference after binding
        ImmutableList<AccessPathElement> elements = ap.getElements();
        if (elements.size() == 1) {
          // We only care for single method call chains (e.g. this.foo(), not this.f.bar())
          Element element = elements.get(0).getJavaElement();
          if (!element.getKind().equals(ElementKind.METHOD)) {
            // We are only looking for method APs
            continue;
          }
          if (!element
              .getSimpleName()
              .equals(methodAnalysisContext.methodSymbol().getSimpleName())) {
            // Check for the name match
            continue;
          }
          if (((ExecutableElement) element).getParameters().size() != 0) {
            // Methods that take parameters might have return values that don't depend only on this
            // and the AP
            continue;
          }
          // We found our method, and it was non-null when called inside the filter, so we mark the
          // return of the
          // method reference as non-null here
          methodAnalysisContext.analysis().setComputedNullness(tree, Nullness.NONNULL);
        }
      }
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
    // We are fairly conservative, anything other than 'return false;' is assumed to potentially be
    // true.
    // No SAT-solving or any other funny business.
    return true;
  }

  @Override
  public void onMatchReturn(NullAway analysis, ReturnTree tree, VisitorState state) {
    // Figure out the enclosing method node
    TreePath enclosingMethodOrLambda =
        NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(state.getPath());
    if (enclosingMethodOrLambda == null) {
      throw new RuntimeException("no enclosing method, lambda or initializer!");
    }
    if (!(enclosingMethodOrLambda.getLeaf() instanceof MethodTree
        || enclosingMethodOrLambda.getLeaf() instanceof LambdaExpressionTree)) {
      throw new RuntimeException(
          "return statement outside of a method or lambda! (e.g. in an initializer block)");
    }
    Tree leaf = enclosingMethodOrLambda.getLeaf();
    if (filterMethodOrLambdaSet.contains(leaf)) {
      returnToEnclosingMethodOrLambda.put(tree, leaf);
      // We need to manually trigger the dataflow analysis to run on the filter method,
      // this ensures onDataflowVisitReturn(...) gets called for all return statements in this
      // method before
      // onDataflowInitialStore(...) is called for all successor methods in the observable chain.
      // Caching should prevent us from re-analyzing any given method.
      AccessPathNullnessAnalysis nullnessAnalysis = analysis.getNullnessAnalysis(state);
      nullnessAnalysis.forceRunOnMethod(new TreePath(state.getPath(), leaf), state.context);
    }
  }

  @Override
  public NullnessStore.Builder onDataflowInitialStore(
      UnderlyingAST underlyingAST,
      List<LocalVariableNode> parameters,
      NullnessStore.Builder nullnessBuilder) {
    Tree tree = bodyToMethodOrLambda.get(underlyingAST.getCode());
    if (tree == null) {
      return nullnessBuilder;
    }
    assert (tree instanceof MethodTree || tree instanceof LambdaExpressionTree);
    MapOrCollectMethodToFilterInstanceRecord callInstanceRecord =
        mapOrCollectRecordToFilterMap.get(tree);
    if (callInstanceRecord != null) {
      // Plug Nullness info from filter method into entry to map method.
      Tree filterTree = callInstanceRecord.getFilter();
      assert (filterTree instanceof MethodTree || filterTree instanceof LambdaExpressionTree);
      MapOrCollectLikeMethodRecord methodRecord =
          callInstanceRecord.getMapOrCollectLikeMethodRecord();
      for (int argIdx : methodRecord.argsFromStream()) {
        LocalVariableNode filterLocalName;
        LocalVariableNode mapLocalName;
        if (filterTree instanceof MethodTree) {
          filterLocalName = new LocalVariableNode(((MethodTree) filterTree).getParameters().get(0));
        } else {
          filterLocalName =
              new LocalVariableNode(((LambdaExpressionTree) filterTree).getParameters().get(0));
        }
        if (tree instanceof MethodTree) {
          mapLocalName = new LocalVariableNode(((MethodTree) tree).getParameters().get(argIdx));
        } else {
          mapLocalName =
              new LocalVariableNode(((LambdaExpressionTree) tree).getParameters().get(argIdx));
        }
        NullnessStore filterNullnessStore = filterToNSMap.get(filterTree);
        if (filterNullnessStore == null) {
          throw new IllegalStateException("null filterNullStore for tree");
        }
        NullnessStore renamedRootsNullnessStore =
            filterNullnessStore.uprootAccessPaths(ImmutableMap.of(filterLocalName, mapLocalName));
        for (AccessPath ap : renamedRootsNullnessStore.getAccessPathsWithValue(Nullness.NONNULL)) {
          nullnessBuilder.setInformation(ap, Nullness.NONNULL);
        }
      }
    }
    return nullnessBuilder;
  }

  @Override
  public void onDataflowVisitReturn(
      ReturnTree tree, VisitorState state, NullnessStore thenStore, NullnessStore elseStore) {
    Tree filterTree = returnToEnclosingMethodOrLambda.get(tree);
    if (filterTree != null) {
      assert (filterTree instanceof MethodTree || filterTree instanceof LambdaExpressionTree);
      ExpressionTree retExpression = tree.getExpression();
      if (canBooleanExpressionEvalToTrue(retExpression)) {
        filterToNSMap.compute(
            filterTree,
            (key, value) -> value == null ? thenStore : value.leastUpperBound(thenStore));
      }
    }
  }

  @Override
  public void onDataflowVisitLambdaResultExpression(
      ExpressionTree tree, NullnessStore thenStore, NullnessStore elseStore) {
    LambdaExpressionTree filterTree = expressionBodyToFilterLambda.get(tree);
    if (filterTree != null) {
      if (canBooleanExpressionEvalToTrue(tree)) {
        filterToNSMap.put(filterTree, thenStore);
      }
    }
  }
}
