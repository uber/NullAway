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
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.VisitorState;
import com.google.errorprone.predicates.TypePredicate;
import com.google.errorprone.predicates.type.DescendantOf;
import com.google.errorprone.suppliers.Suppliers;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import org.checkerframework.dataflow.cfg.UnderlyingAST;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;

/**
 * This Handler transfers nullability info through chains of calls to methods of
 * io.reactivex.Observable.
 *
 * <p>This allows the checker to know, for example, that code like the following has no NPEs:
 * observable.filter(... return o.foo() != null; ...).map(... o.foo.toString() ...)
 */
class RxNullabilityPropagator extends BaseNoOpHandler {

  private final ImmutableList<StreamTypeRecord> RX_MODELS =
      StreamModelBuilder.start()
          .addStreamType(new DescendantOf(Suppliers.typeFromString("io.reactivex.Observable")))
          // Names of all the methods of io.reactivex.Observable that behave like .filter(...)
          // (must take exactly 1 argument)
          .withFilterMethodFromSignature("filter(io.reactivex.functions.Predicate<? super T>)")
          // Names and relevant arguments of all the methods of io.reactivex.Observable that behave
          // like .map(...) for the purposes of this checker (the listed arguments are those that
          // take the potentially filtered objects from the stream)
          .withMapMethodFromSignature(
              "<R>map(io.reactivex.functions.Function<? super T,? extends R>)",
              "apply",
              ImmutableSet.of(0))
          .withMapMethodAllFromName("flatMap", "apply", ImmutableSet.of(0))
          .withMapMethodAllFromName("flatMapSingle", "apply", ImmutableSet.of(0))
          .withMapMethodFromSignature(
              "distinctUntilChanged(io.reactivex.functions.BiPredicate<? super T,? super T>)",
              "test",
              ImmutableSet.of(0, 1))
          // List of methods of io.reactivex.Observable through which we just propagate the
          // nullability information of the last call, e.g. m() in
          // Observable.filter(...).m().map(...) means the
          // nullability information from filter(...) should still be propagated to map(...),
          // ignoring the interleaving call to m().
          .withPassthroughMethodFromSignature("distinct()")
          .withPassthroughMethodFromSignature("distinctUntilChanged()")
          .withPassthroughMethodAllFromName("observeOn")
          // List of methods of io.reactivex.Observable that both use the nullability information
          // internally (like map does), but also don't change the values flowing through the stream
          // and thus propagate
          // the nullability information of the last call.
          .withUseAndPassthroughMethodAllFromName("doOnNext", "accept", ImmutableSet.of(0))
          .addStreamType(new DescendantOf(Suppliers.typeFromString("io.reactivex.Maybe")))
          .withFilterMethodFromSignature("filter(io.reactivex.functions.Predicate<? super T>)")
          .withMapMethodFromSignature(
              "<R>map(io.reactivex.functions.Function<? super T,? extends R>)",
              "apply",
              ImmutableSet.of(0))
          .withMapMethodAllFromName("flatMap", "apply", ImmutableSet.of(0))
          .withMapMethodAllFromName("flatMapSingle", "apply", ImmutableSet.of(0))
          .withPassthroughMethodAllFromName("observeOn")
          .withUseAndPassthroughMethodAllFromName("doOnNext", "accept", ImmutableSet.of(0))
          .addStreamType(new DescendantOf(Suppliers.typeFromString("io.reactivex.Single")))
          .withFilterMethodFromSignature("filter(io.reactivex.functions.Predicate<? super T>)")
          .withMapMethodFromSignature(
              "<R>map(io.reactivex.functions.Function<? super T,? extends R>)",
              "apply",
              ImmutableSet.of(0))
          .withMapMethodAllFromName("flatMap", "apply", ImmutableSet.of(0))
          .withMapMethodAllFromName("flatMapSingle", "apply", ImmutableSet.of(0))
          .withPassthroughMethodAllFromName("observeOn")
          .withUseAndPassthroughMethodAllFromName("doOnNext", "accept", ImmutableSet.of(0))
          .end();

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
  private final Set<Tree> filterMethodOrLambdaSet = new LinkedHashSet<Tree>();

  // Maps each call in the observable call chain to its outer call (see above).
  private final Map<MethodInvocationTree, MethodInvocationTree> observableOuterCallInChain =
      new LinkedHashMap<MethodInvocationTree, MethodInvocationTree>();

  // Maps the call in the observable call chain to the relevant inner method or lambda.
  // e.g. In the example above:
  //   observable.filter() => A.filter
  //   observable.filter().map() => B.apply
  private final Map<MethodInvocationTree, Tree> observableCallToInnerMethodOrLambda =
      new LinkedHashMap<MethodInvocationTree, Tree>();

  // Map from map method (or lambda) to corresponding previous filter method (e.g. B.apply =>
  // A.filter)
  private final Map<Tree, MaplikeToFilterInstanceRecord> mapToFilterMap =
      new LinkedHashMap<Tree, MaplikeToFilterInstanceRecord>();

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
  private final Map<Tree, NullnessStore> filterToNSMap = new LinkedHashMap<Tree, NullnessStore>();

  // Maps the body of a method or lambda to the corresponding enclosing tree, used because the
  // dataflow analysis
  // loses the pointer to the tree by the time we hook into its body.
  private final Map<Tree, Tree> bodyToMethodOrLambda = new LinkedHashMap<Tree, Tree>();

  // Maps the return statements of the filter method to the filter tree itself, similar issue as
  // above.
  private final Map<ReturnTree, Tree> returnToEnclosingMethodOrLambda =
      new LinkedHashMap<ReturnTree, Tree>();

  // Similar to above, but mapping expression-bodies to their enclosing lambdas
  private final Map<ExpressionTree, LambdaExpressionTree> expressionBodyToFilterLambda =
      new LinkedHashMap<ExpressionTree, LambdaExpressionTree>();

  RxNullabilityPropagator() {
    super();
  }

  @Override
  public void onMatchTopLevelClass(
      NullAway analysis, ClassTree tree, VisitorState state, Symbol.ClassSymbol classSymbol) {
    // Clear compilation unit specific state
    this.filterMethodOrLambdaSet.clear();
    this.observableOuterCallInChain.clear();
    this.observableCallToInnerMethodOrLambda.clear();
    this.mapToFilterMap.clear();
    this.filterToNSMap.clear();
    this.bodyToMethodOrLambda.clear();
    this.returnToEnclosingMethodOrLambda.clear();
  }

  @Override
  public void onMatchMethodInvocation(
      NullAway analysis,
      MethodInvocationTree tree,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol) {
    Type receiverType = ASTHelpers.getReceiverType(tree);
    for (StreamTypeRecord streamType : RX_MODELS) {
      if (streamType.matchesType(receiverType, state)) {
        // Build observable call chain
        buildObservableCallChain(tree);

        // Dispatch to code handling specific observer methods
        if (streamType.isFilterMethod(methodSymbol) && methodSymbol.getParameters().length() == 1) {
          ExpressionTree argTree = tree.getArguments().get(0);
          if (argTree instanceof NewClassTree) {
            ClassTree annonClassBody = ((NewClassTree) argTree).getClassBody();
            // Ensure that this `new A() ...` has a custom class body, otherwise, we skip for now.
            // In the future, we could look at the declared type and its inheritance chain, at least
            // for
            // filters.
            if (annonClassBody != null) {
              handleFilterAnonClass(streamType, tree, annonClassBody, state);
            }
          } else if (argTree instanceof LambdaExpressionTree) {
            LambdaExpressionTree lambdaTree = (LambdaExpressionTree) argTree;
            handleFilterLambda(streamType, tree, lambdaTree, state);
          }
        } else if (streamType.isMapMethod(methodSymbol)
            && methodSymbol.getParameters().length() == 1) {
          ExpressionTree argTree = tree.getArguments().get(0);
          if (argTree instanceof NewClassTree) {
            ClassTree annonClassBody = ((NewClassTree) argTree).getClassBody();
            // Ensure that this `new B() ...` has a custom class body, otherwise, we skip for now.
            if (annonClassBody != null) {
              MaplikeMethodRecord methodRecord = streamType.getMaplikeMethodRecord(methodSymbol);
              handleMapAnonClass(methodRecord, tree, annonClassBody);
            }
          } else if (argTree instanceof LambdaExpressionTree) {
            observableCallToInnerMethodOrLambda.put(tree, (LambdaExpressionTree) argTree);
          } else if (argTree instanceof MemberReferenceTree) {
            observableCallToInnerMethodOrLambda.put(tree, (MemberReferenceTree) argTree);
          }
        }
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
        // Update mapToFilterMap
        Symbol.MethodSymbol mapMethod = ASTHelpers.getSymbol(outerCallInChain);
        if (streamType.isMapMethod(mapMethod)) {
          MaplikeToFilterInstanceRecord record =
              new MaplikeToFilterInstanceRecord(
                  streamType.getMaplikeMethodRecord(mapMethod), filterMethodOrLambda);
          mapToFilterMap.put(observableCallToInnerMethodOrLambda.get(outerCallInChain), record);
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
        observableCallToInnerMethodOrLambda.put(observableDotFilter, (MethodTree) t);
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

  private void handleMapAnonClass(
      MaplikeMethodRecord methodRecord,
      MethodInvocationTree observableDotMap,
      ClassTree annonClassBody) {
    for (Tree t : annonClassBody.getMembers()) {
      if (t instanceof MethodTree
          && ((MethodTree) t).getName().toString().equals(methodRecord.getInnerMethodName())) {
        observableCallToInnerMethodOrLambda.put(observableDotMap, (MethodTree) t);
      }
    }
  }

  @Override
  public void onMatchMethod(
      NullAway analysis, MethodTree tree, VisitorState state, Symbol.MethodSymbol methodSymbol) {
    if (mapToFilterMap.containsKey(tree)) {
      bodyToMethodOrLambda.put(tree.getBody(), tree);
    }
  }

  @Override
  public void onMatchLambdaExpression(
      NullAway analysis,
      LambdaExpressionTree tree,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol) {
    if (filterMethodOrLambdaSet.contains(tree)
        && tree.getBodyKind().equals(LambdaExpressionTree.BodyKind.EXPRESSION)) {
      expressionBodyToFilterLambda.put((ExpressionTree) tree.getBody(), tree);
      // Single expression lambda, onMatchReturn will not be triggered, force the dataflow analysis
      // here
      AccessPathNullnessAnalysis nullnessAnalysis = analysis.getNullnessAnalysis(state);
      nullnessAnalysis.forceRunOnMethod(state.getPath(), state.context);
    }
    if (mapToFilterMap.containsKey(tree)) {
      bodyToMethodOrLambda.put(tree.getBody(), tree);
    }
  }

  @Override
  public void onMatchMethodReference(
      NullAway analysis,
      MemberReferenceTree tree,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol) {
    if (mapToFilterMap.containsKey(tree) && ((JCTree.JCMemberReference) tree).kind.isUnbound()) {
      // Unbound method reference, check if we know the corresponding path to be NonNull from the
      // previous filter.
      MaplikeToFilterInstanceRecord callInstanceRecord = mapToFilterMap.get(tree);
      Tree filterTree = callInstanceRecord.getFilter();
      assert (filterTree instanceof MethodTree || filterTree instanceof LambdaExpressionTree);
      NullnessStore filterNullnessStore = filterToNSMap.get(filterTree);
      assert filterNullnessStore != null;
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
          if (!element.getSimpleName().equals(methodSymbol.getSimpleName())) {
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
          analysis.setComputedNullness(tree, Nullness.NONNULL);
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
    if (mapToFilterMap.containsKey(tree)) {
      // Plug Nullness info from filter method into entry to map method.
      MaplikeToFilterInstanceRecord callInstanceRecord = mapToFilterMap.get(tree);
      Tree filterTree = callInstanceRecord.getFilter();
      assert (filterTree instanceof MethodTree || filterTree instanceof LambdaExpressionTree);
      MaplikeMethodRecord mapMR = callInstanceRecord.getMaplikeMethodRecord();
      for (int argIdx : mapMR.getArgsFromStream()) {
        LocalVariableNode filterLocalName = null;
        LocalVariableNode mapLocalName = null;
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
        assert filterNullnessStore != null;
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
      ReturnTree tree, NullnessStore thenStore, NullnessStore elseStore) {
    if (returnToEnclosingMethodOrLambda.containsKey(tree)) {
      Tree filterTree = returnToEnclosingMethodOrLambda.get(tree);
      assert (filterTree instanceof MethodTree || filterTree instanceof LambdaExpressionTree);
      ExpressionTree retExpression = tree.getExpression();
      if (canBooleanExpressionEvalToTrue(retExpression)) {
        if (filterToNSMap.containsKey(filterTree)) {
          filterToNSMap.put(filterTree, filterToNSMap.get(filterTree).leastUpperBound(thenStore));
        } else {
          filterToNSMap.put(filterTree, thenStore);
        }
      }
    }
  }

  @Override
  public void onDataflowVisitLambdaResultExpression(
      ExpressionTree tree, NullnessStore thenStore, NullnessStore elseStore) {
    if (expressionBodyToFilterLambda.containsKey(tree)) {
      LambdaExpressionTree filterTree = expressionBodyToFilterLambda.get(tree);
      if (canBooleanExpressionEvalToTrue(tree)) {
        filterToNSMap.put(filterTree, thenStore);
      }
    }
  }

  /**
   * Used to produce a new list of StreamTypeRecord models, where each model represents a class from
   * a stream-based API such as RxJava.
   *
   * <p>This class should be used as:
   *
   * <p>[...] models = StreamModelBuilder.start() // Start the builder .addStreamType(...) // Add a
   * type filter matching a stream type .withX(...) // Model the type methods ... .end();
   */
  private static class StreamModelBuilder {

    private final List<StreamTypeRecord> typeRecords = new ArrayList<StreamTypeRecord>();
    private TypePredicate tp = null;
    private ImmutableSet.Builder<String> filterMethodSigs;
    private ImmutableSet.Builder<String> filterMethodSimpleNames;
    private ImmutableMap.Builder<String, MaplikeMethodRecord> mapMethodSigToRecord;
    private ImmutableMap.Builder<String, MaplikeMethodRecord> mapMethodSimpleNameToRecord;
    private ImmutableSet.Builder<String> passthroughMethodSigs;
    private ImmutableSet.Builder<String> passthroughMethodSimpleNames;

    private StreamModelBuilder() {}

    /**
     * Get an empty StreamModelBuilder.
     *
     * @return An empty StreamModelBuilder.
     */
    public static StreamModelBuilder start() {
      return new StreamModelBuilder();
    }

    private void finalizeOpenStreamTypeRecord() {
      if (this.tp != null) {
        typeRecords.add(
            new StreamTypeRecord(
                this.tp,
                filterMethodSigs.build(),
                filterMethodSimpleNames.build(),
                mapMethodSigToRecord.build(),
                mapMethodSimpleNameToRecord.build(),
                passthroughMethodSigs.build(),
                passthroughMethodSimpleNames.build()));
      }
    }

    /**
     * Add a stream type to our models.
     *
     * @param tp A type predicate matching the class/interface of the type in our stream-based API.
     * @return This builder (for chaining).
     */
    public StreamModelBuilder addStreamType(TypePredicate tp) {
      finalizeOpenStreamTypeRecord();
      this.tp = tp;
      this.filterMethodSigs = ImmutableSet.builder();
      this.filterMethodSimpleNames = ImmutableSet.builder();
      this.mapMethodSigToRecord = ImmutableMap.builder();
      this.mapMethodSimpleNameToRecord = ImmutableMap.builder();
      this.passthroughMethodSigs = ImmutableSet.builder();
      this.passthroughMethodSimpleNames = ImmutableSet.builder();
      return this;
    }

    /**
     * Add a filter method to the last added stream type.
     *
     * @param filterMethodSig The full sub-signature (everything except the receiver type) of the
     *     filter method.
     * @return This builder (for chaining).
     */
    public StreamModelBuilder withFilterMethodFromSignature(String filterMethodSig) {
      this.filterMethodSigs.add(filterMethodSig);
      return this;
    }

    /**
     * Add all methods of the last stream type with the given simple name as filter methods.
     *
     * @param methodSimpleName The method's simple name.
     * @return This builder (for chaining).
     */
    public StreamModelBuilder withFilterMethodAllFromName(String methodSimpleName) {
      this.filterMethodSimpleNames.add(methodSimpleName);
      return this;
    }

    /**
     * Add a model for a map method to the last added stream type.
     *
     * @param methodSig The full sub-signature (everything except the receiver type) of the method.
     * @param innerMethodName The name of the inner "apply" method of the callback or functional
     *     interface that must be passed to this method.
     * @param argsFromStream The indexes (starting at 0, not counting the receiver) of all the
     *     arguments to this method that receive objects from the stream.
     * @return This builder (for chaining).
     */
    public StreamModelBuilder withMapMethodFromSignature(
        String methodSig, String innerMethodName, ImmutableSet<Integer> argsFromStream) {
      this.mapMethodSigToRecord.put(
          methodSig, new MaplikeMethodRecord(innerMethodName, argsFromStream));
      return this;
    }

    /**
     * Add all methods of the last stream type with the given simple name as map methods.
     *
     * @param methodSimpleName The method's simple name.
     * @param innerMethodName The name of the inner "apply" method of the callback or functional
     *     interface that must be passed to this method.
     * @param argsFromStream The indexes (starting at 0, not counting the receiver) of all the
     *     arguments to this method that receive objects from the stream. Must be the same for all
     *     methods with this name (else use withMapMethodFromSignature).
     * @return This builder (for chaining).
     */
    public StreamModelBuilder withMapMethodAllFromName(
        String methodSimpleName, String innerMethodName, ImmutableSet<Integer> argsFromStream) {
      this.mapMethodSimpleNameToRecord.put(
          methodSimpleName, new MaplikeMethodRecord(innerMethodName, argsFromStream));
      return this;
    }

    /**
     * Add a passthrough method to the last added stream type.
     *
     * <p>A passthrough method is a method that affects the stream but doesn't change the
     * nullability information of the elements inside the stream (e.g. in
     * o.filter(...).sync().map(...), sync() is a passthrough method if the exact same objects that
     * are added to the stream at the end of filter are those that will be consumed by map(...).
     *
     * @param passthroughMethodSig The full sub-signature (everything except the receiver type) of
     *     the method.
     * @return This builder (for chaining).
     */
    public StreamModelBuilder withPassthroughMethodFromSignature(String passthroughMethodSig) {
      this.passthroughMethodSigs.add(passthroughMethodSig);
      return this;
    }

    /**
     * Add all methods of the last stream type with the given simple name as passthrough methods.
     *
     * @param methodSimpleName The method's simple name.
     * @return This builder (for chaining).
     */
    public StreamModelBuilder withPassthroughMethodAllFromName(String methodSimpleName) {
      this.passthroughMethodSimpleNames.add(methodSimpleName);
      return this;
    }

    /**
     * Add a passthrough method that uses the value to the last added stream type.
     *
     * <p>Like a normal passthrough method, but it takes a callback which inspects but doesn't
     * change the elements flowing through the stream.
     *
     * @param passthroughMethodSig The full sub-signature (everything except the receiver type) of
     *     the method.
     * @param innerMethodName The name of the inner method of the callback or functional interface
     *     that must be passed to this method.
     * @param argsFromStream The indexes (starting at 0, not counting the receiver) of all the
     *     arguments to this method that receive objects from the stream.
     * @return This builder (for chaining).
     */
    public StreamModelBuilder withUseAndPassthroughMethodFromSignature(
        String passthroughMethodSig, String innerMethodName, ImmutableSet<Integer> argsFromStream) {
      this.mapMethodSigToRecord.put(
          passthroughMethodSig, new MaplikeMethodRecord(innerMethodName, argsFromStream));
      this.passthroughMethodSigs.add(passthroughMethodSig);
      return this;
    }

    /**
     * Add all methods of the last stream type with the given simple name as use-and-passthrough
     * methods.
     *
     * @param methodSimpleName The method's simple name.
     * @param innerMethodName The name of the inner method of the callback or functional interface
     *     that must be passed to this method.
     * @param argsFromStream The indexes (starting at 0, not counting the receiver) of all the
     *     arguments to this method that receive objects from the stream. Must be the same for all
     *     methods with this name (else use withUseAndPassthroughMethodFromSignature).
     * @return This builder (for chaining).
     */
    public StreamModelBuilder withUseAndPassthroughMethodAllFromName(
        String methodSimpleName, String innerMethodName, ImmutableSet<Integer> argsFromStream) {
      this.mapMethodSimpleNameToRecord.put(
          methodSimpleName, new MaplikeMethodRecord(innerMethodName, argsFromStream));
      this.passthroughMethodSimpleNames.add(methodSimpleName);
      return this;
    }

    /**
     * Turn the models added to this builder into a list of StreamTypeRecord objects.
     *
     * @return The finalized (immutable) models.
     */
    public ImmutableList<StreamTypeRecord> end() {
      finalizeOpenStreamTypeRecord();
      return ImmutableList.copyOf(typeRecords);
    }
  }

  /** An immutable model describing a class from a stream-based API such as RxJava. */
  private static class StreamTypeRecord {

    private final TypePredicate typePredicate;

    // Names of all the methods of this type that behave like .filter(...) (must take exactly 1
    // argument)
    private final ImmutableSet<String> filterMethodSigs;
    private final ImmutableSet<String> filterMethodSimpleNames;

    // Names and relevant arguments of all the methods of this type that behave like .map(...) for
    // the purposes of this checker (the listed arguments are those that take the potentially
    // filtered objects from the stream)
    private final ImmutableMap<String, MaplikeMethodRecord> mapMethodSigToRecord;
    private final ImmutableMap<String, MaplikeMethodRecord> mapMethodSimpleNameToRecord;

    // List of methods of io.reactivex.Observable through which we just propagate the nullability
    // information of the last call, e.g. m() in Observable.filter(...).m().map(...) means the
    // nullability information from filter(...) should still be propagated to map(...), ignoring the
    // interleaving call to m().
    // We assume that if m() is a pass-through method for Observable, so are m(a1), m(a1,a2), etc.
    // If that is ever not the case, we can use a more complex method subsignature her.
    private final ImmutableSet<String> passthroughMethodSigs;
    private final ImmutableSet<String> passthroughMethodSimpleNames;

    public StreamTypeRecord(
        TypePredicate typePredicate,
        ImmutableSet<String> filterMethodSigs,
        ImmutableSet<String> filterMethodSimpleNames,
        ImmutableMap<String, MaplikeMethodRecord> mapMethodSigToRecord,
        ImmutableMap<String, MaplikeMethodRecord> mapMethodSimpleNameToRecord,
        ImmutableSet<String> passthroughMethodSigs,
        ImmutableSet<String> passthroughMethodSimpleNames) {
      this.typePredicate = typePredicate;
      this.filterMethodSigs = filterMethodSigs;
      this.filterMethodSimpleNames = filterMethodSimpleNames;
      this.mapMethodSigToRecord = mapMethodSigToRecord;
      this.mapMethodSimpleNameToRecord = mapMethodSimpleNameToRecord;
      this.passthroughMethodSigs = passthroughMethodSigs;
      this.passthroughMethodSimpleNames = passthroughMethodSimpleNames;
    }

    public boolean matchesType(Type type, VisitorState state) {
      return typePredicate.apply(type, state);
    }

    public boolean isFilterMethod(Symbol.MethodSymbol methodSymbol) {
      return filterMethodSigs.contains(methodSymbol.toString())
          || filterMethodSimpleNames.contains(methodSymbol.getQualifiedName().toString());
    }

    public boolean isMapMethod(Symbol.MethodSymbol methodSymbol) {
      return mapMethodSigToRecord.containsKey(methodSymbol.toString())
          || mapMethodSimpleNameToRecord.containsKey(methodSymbol.getQualifiedName().toString());
    }

    public MaplikeMethodRecord getMaplikeMethodRecord(Symbol.MethodSymbol methodSymbol) {
      MaplikeMethodRecord record = mapMethodSigToRecord.get(methodSymbol.toString());
      if (record == null) {
        record = mapMethodSimpleNameToRecord.get(methodSymbol.getQualifiedName().toString());
      }
      return record;
    }

    public boolean isPassthroughMethod(Symbol.MethodSymbol methodSymbol) {
      return passthroughMethodSigs.contains(methodSymbol.toString())
          || passthroughMethodSimpleNames.contains(methodSymbol.getQualifiedName().toString());
    }
  }

  /** An immutable model describing a map-like method from a stream-based API such as RxJava. */
  private static class MaplikeMethodRecord {

    private final String innerMethodName;

    public String getInnerMethodName() {
      return innerMethodName;
    }

    private final Set<Integer> argsFromStream;

    public Set<Integer> getArgsFromStream() {
      return argsFromStream;
    }

    public MaplikeMethodRecord(String innerMethodName, Set<Integer> argsFromStream) {
      this.innerMethodName = innerMethodName;
      this.argsFromStream = argsFromStream;
    }
  }

  /**
   * Internal bookeeping record that keeps track of the model of a map-like method and the previous
   * filter method's inner method tree. See RxNullabilityPropagator documentation and diagram.
   */
  private static class MaplikeToFilterInstanceRecord {

    private final MaplikeMethodRecord mapMR;

    public MaplikeMethodRecord getMaplikeMethodRecord() {
      return mapMR;
    }

    private final Tree filter;

    public Tree getFilter() {
      return filter;
    }

    public MaplikeToFilterInstanceRecord(MaplikeMethodRecord mapMR, Tree filter) {
      assert (filter instanceof MethodTree || filter instanceof LambdaExpressionTree);
      this.mapMR = mapMR;
      this.filter = filter;
    }
  }
}
