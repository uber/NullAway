package com.uber.nullaway.generics;

import static com.google.common.base.Verify.verify;
import static com.uber.nullaway.NullabilityUtil.castToNonNull;
import static com.uber.nullaway.NullabilityUtil.pathWithLeaf;
import static com.uber.nullaway.generics.TypeMetadataBuilder.TYPE_METADATA_BUILDER;
import static java.util.stream.Collectors.joining;

import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.errorprone.VisitorState;
import com.google.errorprone.suppliers.Supplier;
import com.google.errorprone.suppliers.Suppliers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import com.uber.nullaway.CodeAnnotationInfo;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorBuilder;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.InvocationArguments;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.NullabilityUtil;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPathNullnessAnalysis;
import com.uber.nullaway.dataflow.EnclosingEnvironmentNullness;
import com.uber.nullaway.dataflow.NullnessStore;
import com.uber.nullaway.generics.ConstraintSolver.BoundEvidence;
import com.uber.nullaway.generics.ConstraintSolver.LowerBoundConflict;
import com.uber.nullaway.generics.ConstraintSolver.NestedUpperBoundViolationException;
import com.uber.nullaway.generics.ConstraintSolver.Solution;
import com.uber.nullaway.generics.ConstraintSolver.SourceOrigin;
import com.uber.nullaway.generics.ConstraintSolver.UnsatisfiableConstraintsException;
import com.uber.nullaway.generics.GenericsUtils.MethodRefTypeRelationKind;
import com.uber.nullaway.handlers.Handler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.NullType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import org.jspecify.annotations.Nullable;

/** Methods for performing checks related to generic types and nullability. */
public final class GenericsChecks {

  private static final Supplier<Type> MAP_TYPE_SUPPLIER = Suppliers.typeFromString("java.util.Map");

  /** Types resolved for a method reference using its ground target type. */
  public record ResolvedMethodReference(Type.MethodType methodType, @Nullable Type qualifierType) {}

  /** Marker interface for results of attempting to infer nullability of type variables at a call */
  private interface CallInferenceResult {}

  /** Complete solutions and diagnostic fallback evidence share only their substitution view. */
  private interface InferenceWithTypes extends CallInferenceResult {
    Map<Tree, Map<Element, Type>> typesBySite();

    /** Selects substitutions belonging to one call or reference, never a sibling's variables. */
    default Map<Element, Type> inferredTypesForSite(Tree site) {
      return typesBySite().getOrDefault(site, Map.of());
    }
  }

  /** A completed, consistent solution eligible for publication subject to lifecycle guards. */
  private record InferenceSuccess(Solution solution, Map<Tree, Map<Element, Type>> typesBySite)
      implements InferenceWithTypes {
    InferenceSuccess(Solution solution) {
      this(solution, indexInferredTypesBySite(solution));
    }
  }

  /** Uncertified or contradictory evidence used for ordinary checking, never success caching. */
  private record InferencePartial(
      Solution solution, Map<Tree, Map<Element, Type>> typesBySite, Map<Tree, InferenceCall> calls)
      implements InferenceWithTypes {
    InferencePartial(Solution solution) {
      this(solution, Map.of());
    }

    /** Retains the exact resolved signatures that produced the uncertified outcome. */
    InferencePartial(Solution solution, Map<Tree, InferenceCall> calls) {
      this(solution, indexInferredTypesBySite(solution), Map.copyOf(calls));
    }
  }

  /** Indexes immutable site-scoped substitution views once, without changing certification. */
  private static Map<Tree, Map<Element, Type>> indexInferredTypesBySite(Solution solution) {
    Map<Tree, Map<Element, Type>> bySite = new LinkedHashMap<>();
    solution
        .inferredTypes()
        .forEach(
            (variable, type) ->
                bySite
                    .computeIfAbsent(variable.site(), unused -> new LinkedHashMap<>())
                    .put(variable.typeVariable(), type));
    bySite.replaceAll((site, types) -> Map.copyOf(types));
    return Map.copyOf(bySite);
  }

  /**
   * Tracks which participating calls can safely share a persisted inference result. Once any call
   * is analyzed with provisional lambda parameter types, no result from that inference problem is
   * persisted, since all participating calls can be connected through the shared constraint graph.
   */
  private static final class InferenceCacheState {
    final Set<Tree> cacheableCalls = new LinkedHashSet<>();
    final Set<MemberReferenceTree> methodReferences = new LinkedHashSet<>();
    final Map<Tree, InferenceCall> calls = new LinkedHashMap<>();

    boolean provisionalLambdaTypesSeen = false;

    /**
     * Records a participating call, permanently disabling persistence for the inference problem if
     * the call depends on provisional lambda parameter types.
     *
     * @param call the participating generic call
     * @param usesProvisionalLambdaTypes whether provisional lambda parameter types are active
     */
    void recordCall(Tree call, boolean usesProvisionalLambdaTypes) {
      if (usesProvisionalLambdaTypes) {
        provisionalLambdaTypesSeen = true;
        cacheableCalls.clear();
      } else if (!provisionalLambdaTypesSeen) {
        cacheableCalls.add(call);
      }
    }
  }

  /** Indicates failed inference of nullability of type variables at a call */
  private record InferenceFailure(@SuppressWarnings("UnusedVariable") @Nullable String errorMessage)
      implements CallInferenceResult {
    private InferenceFailure(@Nullable String errorMessage) {
      this.errorMessage = errorMessage;
    }
  }

  /**
   * Maps a Tree representing a call to a generic method or constructor to the result of inferring
   * its type argument nullability. The call must not have any explicit type arguments. If a tree is
   * not present as a key in this map, it means inference has not yet been attempted for that call.
   */
  private final Map<Tree, CallInferenceResult> inferredTypeVarNullabilityForGenericCalls =
      new LinkedHashMap<>();

  /** Final root targets retained when provisional lambda participation prevents result caching. */
  private final Map<Tree, CallAndContext> completedCallContexts = new LinkedHashMap<>();

  /**
   * Completed enclosing or independent inference results indexed by generic method references. This
   * site-scoped cache remains valid when reusable call caching is disabled because provisional
   * lambda parameter types participated: the enclosing problem has reached a complete solution
   * before entries are published.
   */
  private final Map<MemberReferenceTree, InferenceSuccess>
      inferredResultsForGenericMethodReferences = new LinkedHashMap<>();

  /** References currently being solved independently, guarding declaration-type resolution. */
  private final Set<MemberReferenceTree> methodReferenceInferenceInProgress = new LinkedHashSet<>();

  /** Calls or references for which a generic inference failure has already been reported. */
  private final Set<Tree> callsWithReportedInferenceFailures = new LinkedHashSet<>();

  /** Sites for which a nested declaration-upper-bound violation has already been reported. */
  private final Set<Tree> reportedNestedUpperBoundViolations = new LinkedHashSet<>();

  /**
   * Completed uncertified outcomes, distinct from reusable inference and diagnostic deduplication.
   */
  private final Map<Tree, InferencePartial> diagnosticInferenceResults = new LinkedHashMap<>();

  /** Reporting history has no role in determining which type positions remain checkable. */
  private final Set<ConstraintSolver.InferenceVariable> reportedLowerConflicts =
      new LinkedHashSet<>();

  /**
   * Maps poly expressions for which we have computed a context-derived type to that type, if
   * inference succeeded.
   */
  private final Map<Tree, Type> inferredPolyExpressionTypes = new LinkedHashMap<>();

  /**
   * Types of implicit lambda parameters to use while generating inference constraints. These types
   * can contain unsolved inference variables and hence are scoped to the lambda body. Needed since
   * falling back on javac types for these parameters can miss nullability information, see
   * https://github.com/uber/NullAway/issues/1919
   */
  private Map<Symbol, Type> lambdaParameterTypesForInference = Map.of();

  /** Ground lambda targets scoped to constraint generation, including parameterless lambdas. */
  private Map<LambdaExpressionTree, Type> lambdaTargetTypesForInference = Map.of();

  /** Symbolic var-local types owned by the solver generating the current lambda's constraints. */
  private record LambdaVarLocalInference(
      ConstraintSolver solver,
      InferenceCacheState cacheState,
      Map<Symbol, Type> types,
      Map<Symbol, TreePath> declarations,
      boolean calledFromDataflow) {}

  /** Never published: these local types may contain unsolved variables of the owning solver. */
  private @Nullable LambdaVarLocalInference lambdaVarLocalInference = null;

  /** Cached syntax recipes; receiver-dependent types are resolved anew in their typing context. */
  private final Map<ExpressionTree, CallSite> callSites = new LinkedHashMap<>();

  /** An annotated executable and its result template before site-scoped inference substitution. */
  private record InferenceCall(CallSite site, Type.MethodType signature, Type resultTemplate) {

    /** Applies signature models without changing the result of an allocation into void. */
    InferenceCall withSignature(Type.MethodType modeledSignature) {
      return new InferenceCall(
          site,
          modeledSignature,
          site.returnsAllocation() ? resultTemplate : modeledSignature.getReturnType());
    }
  }

  /** One stable syntax recipe for a regular invocation, constructor chain, or allocation. */
  private final class CallSite {
    final ExpressionTree tree;
    final Symbol.MethodSymbol symbol;
    final List<Symbol.TypeVariableSymbol> variables;
    final Type attributedResult;
    final boolean fixedResult;
    final @Nullable Type allocatedType;
    final List<? extends Tree> explicitArguments;

    /**
     * Resolves the declaration once and selects exactly the type arguments omitted at this site.
     */
    CallSite(ExpressionTree tree) {
      this.tree = tree;
      List<Symbol.TypeVariableSymbol> inferred = new ArrayList<>();
      if (tree instanceof NewClassTree construction) {
        symbol = resolveConstructorSymbol(construction);
        allocatedType = getConstructedTypeAtCallSite(construction);
        attributedResult = allocatedType;
        boolean diamond = TreeInfo.isDiamond((JCTree) construction);
        fixedResult = !diamond;
        explicitArguments = construction.getTypeArguments();
        if (diamond) {
          inferred.addAll(allocatedType.tsym.getTypeParameters());
        }
        if (explicitArguments.isEmpty()) {
          inferred.addAll(symbol.getTypeParameters());
        }
      } else {
        Verify.verify(tree instanceof MethodInvocationTree);
        symbol = (Symbol.MethodSymbol) castToNonNull(ASTHelpers.getSymbol(tree));
        allocatedType = null;
        attributedResult = castToNonNull(ASTHelpers.getType(tree));
        fixedResult = false;
        explicitArguments = ((MethodInvocationTree) tree).getTypeArguments();
        if (explicitArguments.isEmpty() && symbol.type instanceof Type.ForAll) {
          inferred.addAll(symbol.getTypeParameters());
        }
      }
      variables = List.copyOf(inferred);
    }

    /** Distinguishes an allocated result from an executable's declared return type. */
    boolean returnsAllocation() {
      return allocatedType != null;
    }

    /** Resolves annotated receiver types and witnesses without publishing provisional types. */
    InferenceCall resolve(@Nullable TreePath path, VisitorState state, boolean calledFromDataflow) {
      Type executable = symbol.type;
      Type resultTemplate;
      if (allocatedType != null) {
        NewClassTree construction = (NewClassTree) tree;
        Type template =
            fixedResult
                ? typeWithPreservedAnnotations(construction.getIdentifier())
                : allocatedType.tsym.type;
        resultTemplate =
            withEnclosingTypeFromQualifier(template, construction, state, calledFromDataflow);
        executable =
            TypeSubstitutionUtils.memberType(state.getTypes(), resultTemplate, symbol, config);
      } else {
        Type receiver =
            getEnclosingTypeForCallExpression(symbol, tree, path, state, calledFromDataflow);
        if (receiver != null) {
          executable = TypeSubstitutionUtils.memberType(state.getTypes(), receiver, symbol, config);
        }
        resultTemplate = executable.getReturnType();
      }
      if (!explicitArguments.isEmpty() && executable instanceof Type.ForAll quantified) {
        executable =
            TypeSubstitutionUtils.subst(
                state.getTypes(),
                quantified.asMethodType(),
                quantified.tvars,
                convertTreesToTypes(explicitArguments),
                config);
        if (!returnsAllocation()) {
          resultTemplate = executable.getReturnType();
        }
      }
      return new InferenceCall(this, executable.asMethodType(), resultTemplate);
    }

    /** Collects javac's independent executable and allocated-type shape evidence for this site. */
    Map<Element, Type> javacInstantiations(Type.MethodType declaration, VisitorState state) {
      Map<Element, Type> result = new LinkedHashMap<>();
      Type attributedSignature;
      if (allocatedType != null) {
        result.putAll(
            InferenceTypeShapes.inferInstantiations(
                allocatedType.tsym.type, allocatedType, variables, state, config));
        attributedSignature = ((JCTree.JCNewClass) tree).constructorType;
      } else {
        attributedSignature = ASTHelpers.getType(((MethodInvocationTree) tree).getMethodSelect());
      }
      if (attributedSignature != null) {
        InferenceTypeShapes.inferInstantiations(
                declaration, attributedSignature.asMethodType(), variables, state, config)
            .forEach(result::putIfAbsent);
      }
      return result;
    }

    /** Returns the instantiated javac signature, preserving the existing fallback source. */
    Type.MethodType attributedSignature() {
      return (allocatedType != null
              ? ((JCTree.JCNewClass) tree).constructorType
              : castToNonNull(ASTHelpers.getType(((MethodInvocationTree) tree).getMethodSelect())))
          .asMethodType();
    }
  }

  /** Returns a shared syntax recipe, never a cached context-dependent signature. */
  private CallSite callSite(ExpressionTree tree) {
    return callSites.computeIfAbsent(tree, CallSite::new);
  }

  /**
   * While generating constraints for an inference problem, maps each participating call whose
   * lambda and method reference arguments get their types published after successful inference (see
   * {@link #inferredPolyExpressionTypes}) to its context-resolved call, as computed by {@link
   * #getCallForInference}. Calls inside lambda bodies are excluded, since their executable types
   * can refer to unsolved inference variables of enclosing calls through provisional lambda
   * parameter types (see {@link #lambdaParameterTypesForInference}). {@code null} when no
   * constraints are being generated. Inference problems can nest, e.g., for a generic call in the
   * initializer of a {@code var} local inside a lambda body, so this is saved and restored around
   * each constraint generation.
   */
  private @Nullable Map<Tree, InferenceCall> callTypesForPolyArguments = null;

  /** Maps each {@code var}-declared local to its inferred NullAway type */
  private final Map<Symbol, Type> inferredVarLocalTypes = new LinkedHashMap<>();

  /** Maps each {@code var}-declared local to its declaration tree */
  private final Map<Symbol, VariableTree> varLocalDeclarations = new LinkedHashMap<>();

  /**
   * Tracks generic method invocations currently undergoing nested-nullability repair so re-entrant
   * requests for the same invocation can use the already inferred call-site method type rather than
   * recursing back through the same repair logic. See {@link
   * #getResolvedCallSignature(InferenceCall, TreePath, VisitorState, boolean)}
   */
  private final Set<MethodInvocationTree> nestedNullabilityRepairInProgress = new LinkedHashSet<>();

  public @Nullable Type getInferredPolyExpressionType(Tree tree) {
    Preconditions.checkArgument(
        tree instanceof LambdaExpressionTree || tree instanceof MemberReferenceTree,
        "Expected lambda or method reference tree but got: %s",
        tree.getKind());
    return inferredPolyExpressionTypes.get(tree);
  }

  /**
   * Handles cases where a poly expression (lambda or method reference) is passed as a parameter,
   * and the invoked function has a library model for the corresponding formal. In such cases, we
   * ensure the appropriate formal parameter type from the library model is used when inferring the
   * type of the poly expression, and cache the result. If inference has already cached a
   * context-derived type, we preserve it since inference itself applies library models.
   */
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"})
  public void maybeStoreLibraryModeledPolyExpressionType(
      Tree polyExpressionTree, VisitorState state) {
    Preconditions.checkArgument(
        polyExpressionTree instanceof LambdaExpressionTree
            || polyExpressionTree instanceof MemberReferenceTree,
        "Expected lambda or method reference tree but got: %s",
        polyExpressionTree.getKind());
    Preconditions.checkArgument(
        state.getPath().getLeaf() == polyExpressionTree,
        "Expected current path leaf to be the poly expression");
    if (inferredPolyExpressionTypes.containsKey(polyExpressionTree)) {
      return;
    }
    TreePath invocationPath = state.getPath().getParentPath();
    // Skip parentheses around the invocation argument.
    while (invocationPath != null && invocationPath.getLeaf() instanceof ParenthesizedTree) {
      invocationPath = invocationPath.getParentPath();
    }
    if (invocationPath == null
        || !(invocationPath.getLeaf() instanceof MethodInvocationTree invocationTree)) {
      return;
    }
    Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(invocationTree);
    if (methodSymbol == null) {
      return;
    }
    Type attributedMethodType = ASTHelpers.getType(invocationTree.getMethodSelect());
    if (attributedMethodType == null) {
      return;
    }
    Type.MethodType methodType = attributedMethodType.asMethodType();
    Type.MethodType modeledMethodType =
        handler.onOverrideMethodType(
            methodSymbol, methodType, state.withPath(invocationPath), invocationTree);
    if (modeledMethodType == methodType) {
      return;
    }
    Type formalParameterType =
        getFormalParameterTypeForArgument(invocationTree, modeledMethodType, polyExpressionTree);
    if (formalParameterType == null || formalParameterType.isRaw()) {
      return;
    }
    Type polyExpressionType = ASTHelpers.getType(polyExpressionTree);
    if (polyExpressionType != null) {
      Type groundTargetType =
          GenericsUtils.groundTargetType(formalParameterType, state, config, handler);
      Type modeledPolyExpressionType =
          TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
              groundTargetType, polyExpressionType, config);
      inferredPolyExpressionTypes.put(polyExpressionTree, modeledPolyExpressionType);
    }
  }

  private final NullAway analysis;
  private final Config config;
  private final Handler handler;

  public GenericsChecks(NullAway analysis, Config config, Handler handler) {
    this.analysis = analysis;
    this.config = config;
    this.handler = handler;
  }

  /* package-private */ Config getConfig() {
    return config;
  }

  /**
   * Checks that for an instantiated generic type, {@code @Nullable} types are only used for type
   * variables that have a {@code @Nullable} upper bound.
   *
   * @param tree the tree representing the instantiated type
   * @param state visitor state
   */
  public void checkInstantiationForParameterizedTypedTree(
      ParameterizedTypeTree tree, VisitorState state) {
    if (!config.isJSpecifyMode()) {
      return;
    }
    List<? extends Tree> typeArguments = tree.getTypeArguments();
    if (typeArguments.isEmpty()) {
      return;
    }
    Map<Integer, Tree> nullableTypeArguments = new HashMap<>();
    for (int i = 0; i < typeArguments.size(); i++) {
      Tree curTypeArg = typeArguments.get(i);
      if (curTypeArg instanceof AnnotatedTypeTree annotatedType) {
        // Annotations directly on wildcards are illegal in JSpecify mode and are diagnosed in
        // checkForNullnessAnnotationsOnWildcards().
        if (annotatedType.getUnderlyingType() instanceof WildcardTree) {
          continue;
        }
        for (AnnotationTree annotation : annotatedType.getAnnotations()) {
          Type annotationType = ASTHelpers.getType(annotation);
          if (annotationType != null
              && Nullness.isNullableAnnotation(annotationType.toString(), config)) {
            nullableTypeArguments.put(i, curTypeArg);
            break;
          }
        }
      }
    }
    // base type that is being instantiated
    Type baseType = ASTHelpers.getType(tree);
    if (baseType == null) {
      return;
    }
    boolean[] typeParamsWithNullableUpperBound = getTypeParamsWithNullableUpperBound(baseType);
    com.sun.tools.javac.util.List<Type> baseTypeArgs = baseType.tsym.type.getTypeArguments();
    for (int i = 0; i < baseTypeArgs.size(); i++) {
      if (nullableTypeArguments.containsKey(i) && !typeParamsWithNullableUpperBound[i]) {
        // if base type variable does not have @Nullable upper bound then the instantiation is
        // invalid
        reportInvalidInstantiationError(
            nullableTypeArguments.get(i), baseType, baseTypeArgs.get(i), state);
      }
    }
  }

  /**
   * Reports nullness annotations written directly on a wildcard, which is not a legal annotation
   * location under JSpecify.
   */
  public void checkForNullnessAnnotationsOnWildcards(
      AnnotatedTypeTree annotatedType, VisitorState state) {
    if (!(annotatedType.getUnderlyingType() instanceof WildcardTree)) {
      return;
    }
    for (AnnotationTree annotation : annotatedType.getAnnotations()) {
      Type annotationType = ASTHelpers.getType(annotation);
      if (annotationType == null) {
        continue;
      }
      String annotationName = annotationType.toString();
      if (!Nullness.isNullableAnnotation(annotationName, config)
          && !Nullness.isNonNullAnnotation(annotationName, config)) {
        continue;
      }
      ErrorBuilder errorBuilder = analysis.getErrorBuilder();
      ErrorMessage errorMessage =
          new ErrorMessage(
              ErrorMessage.MessageTypes.UNRECOGNIZED_NULLNESS_ANNOTATION_LOCATION,
              "illegal location for annotation: nullness annotations cannot be written directly on a wildcard; annotate the wildcard bound instead");
      state.reportMatch(
          errorBuilder.createErrorDescription(
              errorMessage, analysis.buildDescription(annotation), state, null));
    }
  }

  private boolean[] getTypeParamsWithNullableUpperBound(Type type) {
    Symbol.TypeSymbol tsym = type.tsym;
    com.sun.tools.javac.util.List<Type> baseTypeArgs = tsym.type.getTypeArguments();
    boolean[] result = new boolean[baseTypeArgs.size()];
    for (int i = 0; i < baseTypeArgs.size(); i++) {
      Type typeVariable = baseTypeArgs.get(i);
      Type upperBound = typeVariable.getUpperBound();
      if (TypeSubstitutionUtils.hasNullableRoot(upperBound, config)
          || handler.onOverrideClassTypeVariableUpperBound(type.tsym.toString(), i)) {
        result[i] = true;
      }
    }
    return result;
  }

  /**
   * Checks validity of type arguments at a generic method call. A {@code @Nullable} type argument
   * can only be used for a type variable that has a {@code @Nullable} upper bound.
   *
   * @param tree the tree representing the instantiated type
   * @param state visitor state
   */
  public void checkGenericMethodCallTypeArguments(Tree tree, VisitorState state) {

    List<? extends Tree> typeArguments =
        switch (tree.getKind()) {
          case METHOD_INVOCATION -> ((MethodInvocationTree) tree).getTypeArguments();
          case NEW_CLASS -> ((NewClassTree) tree).getTypeArguments();
          default -> throw new RuntimeException("Unexpected tree kind: " + tree.getKind());
        };
    if (typeArguments.isEmpty()) {
      return;
    }
    // get Nullable annotated type arguments
    Map<Integer, Tree> nullableTypeArguments = new HashMap<>();
    for (int i = 0; i < typeArguments.size(); i++) {
      Tree curTypeArg = typeArguments.get(i);
      if (curTypeArg instanceof AnnotatedTypeTree annotatedType) {
        for (AnnotationTree annotation : annotatedType.getAnnotations()) {
          Type annotationType = ASTHelpers.getType(annotation);
          if (annotationType != null
              && Nullness.isNullableAnnotation(annotationType.toString(), config)) {
            nullableTypeArguments.put(i, curTypeArg);
            break;
          }
        }
      }
    }
    Symbol.MethodSymbol methodSymbol = callSite((ExpressionTree) tree).symbol;

    // Check the restored declaration, not bounds stripped by anonymous-class attribution.
    Type baseType =
        TypeSubstitutionUtils.restoreMemberDeclarationBounds(
            state.getTypes(), methodSymbol, config);
    List<Type> baseTypeVariables = baseType.getTypeArguments();
    for (int i = 0; i < baseTypeVariables.size(); i++) {
      if (nullableTypeArguments.containsKey(i)) {
        Type typeVariable = baseTypeVariables.get(i);
        Type upperBound = typeVariable.getUpperBound();
        boolean hasNullableAnnotation =
            TypeSubstitutionUtils.hasNullableRoot(upperBound, config)
                || handler.onOverrideClassTypeVariableUpperBound(baseType.tsym.toString(), i);
        // if type variable's upper bound does not have @Nullable annotation then the instantiation
        // is invalid
        if (!hasNullableAnnotation
            && !handler.onOverrideMethodTypeVariableUpperBound(methodSymbol, i, state)) {
          reportInvalidTypeArgumentError(
              nullableTypeArguments.get(i), methodSymbol, typeVariable, state);
        }
      }
    }
  }

  private void reportInvalidTypeArgumentError(
      Tree tree, Symbol.MethodSymbol methodSymbol, Type typeVariable, VisitorState state) {
    ErrorBuilder errorBuilder = analysis.getErrorBuilder();
    ErrorMessage errorMessage =
        new ErrorMessage(
            ErrorMessage.MessageTypes.TYPE_PARAMETER_CANNOT_BE_NULLABLE,
            String.format(
                "Type argument cannot be @Nullable, as method %s's type variable %s is not @Nullable",
                methodSymbol, typeVariable.tsym.toString()));
    state.reportMatch(
        errorBuilder.createErrorDescription(
            errorMessage, analysis.buildDescription(tree), state, null));
  }

  private void reportInvalidInstantiationError(
      Tree tree, Type baseType, Type baseTypeVariable, VisitorState state) {
    ErrorBuilder errorBuilder = analysis.getErrorBuilder();
    ErrorMessage errorMessage =
        new ErrorMessage(
            ErrorMessage.MessageTypes.TYPE_PARAMETER_CANNOT_BE_NULLABLE,
            String.format(
                "Generic type parameter cannot be @Nullable, as type variable %s of type %s does not have a @Nullable upper bound",
                baseTypeVariable.tsym.toString(), baseType.tsym.toString()));
    state.reportMatch(
        errorBuilder.createErrorDescription(
            errorMessage, analysis.buildDescription(tree), state, null));
  }

  private void reportInvalidAssignmentInstantiationError(
      Tree tree, Type lhsType, Type rhsType, VisitorState state) {
    ErrorBuilder errorBuilder = analysis.getErrorBuilder();
    String msg = errorMessageForIncompatibleTypesAtPseudoAssignment(lhsType, rhsType, state);
    ErrorMessage errorMessage =
        new ErrorMessage(ErrorMessage.MessageTypes.ASSIGN_GENERIC_NULLABLE, msg);
    state.reportMatch(
        errorBuilder.createErrorDescription(
            errorMessage, analysis.buildDescription(tree), state, null));
  }

  private String errorMessageForIncompatibleTypesAtPseudoAssignment(
      Type lhsType, Type rhsType, VisitorState state) {
    String prettyRhsType = prettyTypeForError(rhsType, state);
    String prettyLhsType = prettyTypeForError(lhsType, state);
    String result =
        String.format(
            "incompatible types: %s cannot be converted to %s", prettyRhsType, prettyLhsType);
    boolean allowNonCaptureMismatch = prettyRhsType.equals(prettyLhsType);
    WildcardBoundMismatch wildcardBoundMismatch =
        firstWildcardBoundMismatch(lhsType, rhsType, state, allowNonCaptureMismatch);
    if (wildcardBoundMismatch != null) {
      result += " (" + describeWildcardBoundMismatch(wildcardBoundMismatch) + ")";
    }
    if (!ASTHelpers.isSameType(lhsType, rhsType, state)
        && lhsType.getKind() == TypeKind.DECLARED
        && rhsType.getKind() == TypeKind.DECLARED) {
      Symbol.TypeSymbol lhsSym = lhsType.asElement();
      if (lhsSym instanceof Symbol.ClassSymbol classSymbol) {
        Type asSuper =
            TypeSubstitutionUtils.asSuper(state.getTypes(), rhsType, classSymbol, config);
        if (asSuper != null) {
          result +=
              String.format(
                  " (%s is a subtype of %s)", prettyRhsType, prettyTypeForError(asSuper, state));
        }
      }
    }
    return result;
  }

  /**
   * Returns the first wildcard bound difference between {@code lhsType} and {@code rhsType}, or
   * {@code null} if no such difference can be found. Recursively traverses {@code lhsType} and
   * {@code rhsType} to find such a difference.
   *
   * <p>This is especially useful when two types pretty-print identically, but differ in the
   * effective upper bound of a wildcard nested somewhere inside the type.
   *
   * @param lhsType the lhs (target) type
   * @param rhsType the rhs (source) type
   * @param state the visitor state
   * @param allowNonCaptureMismatch whether a mismatch may be returned when neither causal wildcard
   *     is captured. Such details are useful when the full types print identically, but redundant
   *     when the full types already expose the difference.
   */
  private @Nullable WildcardBoundMismatch firstWildcardBoundMismatch(
      Type lhsType, Type rhsType, VisitorState state, boolean allowNonCaptureMismatch) {
    // base case: both wildcard types
    Type.WildcardType lhsWildcard = GenericsUtils.asWildcard(lhsType);
    Type.WildcardType rhsWildcard = GenericsUtils.asWildcard(rhsType);
    if (lhsWildcard != null && rhsWildcard != null) {
      if (!allowNonCaptureMismatch
          && !(lhsType instanceof Type.CapturedType)
          && !(rhsType instanceof Type.CapturedType)) {
        return null;
      }
      return wildcardBoundMismatch(lhsType, rhsType, lhsWildcard, rhsWildcard, state);
    }
    if (lhsType instanceof Type.ClassType lhsClassType && rhsType instanceof Type.ClassType) {
      Type rhsTypeAsSuper =
          TypeSubstitutionUtils.asSuper(
              state.getTypes(), rhsType, (Symbol.ClassSymbol) lhsType.tsym, config);
      if (!(rhsTypeAsSuper instanceof Type.ClassType rhsClassType)
          || rhsTypeAsSuper.isRaw()
          || lhsType.isRaw()) {
        return null;
      }
      List<Type> lhsTypeArguments = lhsClassType.getTypeArguments();
      List<Type> rhsTypeArguments = rhsClassType.getTypeArguments();
      if (lhsTypeArguments.size() != rhsTypeArguments.size()) {
        return null;
      }
      for (int i = 0; i < lhsTypeArguments.size(); i++) {
        WildcardBoundMismatch mismatch =
            firstWildcardBoundMismatch(
                lhsTypeArguments.get(i), rhsTypeArguments.get(i), state, allowNonCaptureMismatch);
        if (mismatch != null) {
          return mismatch;
        }
      }
      return firstWildcardBoundMismatch(
          lhsClassType.getEnclosingType(),
          rhsClassType.getEnclosingType(),
          state,
          allowNonCaptureMismatch);
    }
    if (lhsType instanceof Type.ArrayType lhsArrayType
        && rhsType instanceof Type.ArrayType rhsArrayType) {
      return firstWildcardBoundMismatch(
          lhsArrayType.elemtype, rhsArrayType.elemtype, state, allowNonCaptureMismatch);
    }
    return null;
  }

  /**
   * Creates a structured mismatch for two wildcards with visibly different effective upper bounds,
   * or returns {@code null} if those bounds pretty-print the same.
   */
  private @Nullable WildcardBoundMismatch wildcardBoundMismatch(
      Type lhsType,
      Type rhsType,
      Type.WildcardType lhsWildcard,
      Type.WildcardType rhsWildcard,
      VisitorState state) {
    Type lhsUpperBound = GenericsUtils.wildcardUpperBound(lhsWildcard, state, config, handler);
    Type rhsUpperBound = GenericsUtils.wildcardUpperBound(rhsWildcard, state, config, handler);
    String prettyLhsUpperBound = prettyTypeForError(lhsUpperBound, state);
    String prettyRhsUpperBound = prettyTypeForError(rhsUpperBound, state);
    if (!prettyLhsUpperBound.equals(prettyRhsUpperBound)) {
      return new WildcardBoundMismatch(lhsType, rhsType, prettyLhsUpperBound, prettyRhsUpperBound);
    }
    return null;
  }

  /**
   * Describes a wildcard-bound mismatch, including provenance for every captured wildcard that
   * directly participates in that mismatch.
   */
  private static String describeWildcardBoundMismatch(WildcardBoundMismatch mismatch) {
    String result =
        String.format(
            "target wildcard upper bound is %s; source wildcard upper bound is %s",
            mismatch.prettyTargetUpperBound(), mismatch.prettySourceUpperBound());
    String targetCaptureDescription =
        captureProvenanceDescription(mismatch.targetWildcardType(), "target");
    if (targetCaptureDescription != null) {
      result += "; " + targetCaptureDescription;
    }
    String sourceCaptureDescription =
        captureProvenanceDescription(mismatch.sourceWildcardType(), "source");
    if (sourceCaptureDescription != null) {
      result += "; " + sourceCaptureDescription;
    }
    return result;
  }

  /** Returns a short description of the type parameter underlying a captured type, if available. */
  private static @Nullable String captureProvenanceDescription(Type type, String assignmentRole) {
    if (!(type instanceof Type.CapturedType capturedType)) {
      return null;
    }
    Type.TypeVar formalTypeVariable = capturedType.wildcard.bound;
    if (formalTypeVariable == null
        || !(formalTypeVariable.tsym.owner instanceof Symbol.ClassSymbol owner)
        || owner.getSimpleName().isEmpty()) {
      return null;
    }
    return String.format(
        "%s wildcard is the type argument for type variable %s of %s",
        assignmentRole, formalTypeVariable.tsym.getSimpleName(), owner.getSimpleName());
  }

  /**
   * A difference between the effective upper bounds of two corresponding wildcards.
   *
   * <p>The wildcard types are retained so the diagnostic can report provenance for the captured
   * wildcard or wildcards that directly caused the mismatch, without reporting unrelated captures
   * elsewhere in the enclosing types.
   */
  private record WildcardBoundMismatch(
      Type targetWildcardType,
      Type sourceWildcardType,
      String prettyTargetUpperBound,
      String prettySourceUpperBound) {}

  private void reportInvalidReturnTypeError(
      Tree tree, Type methodType, Type returnType, VisitorState state) {
    ErrorBuilder errorBuilder = analysis.getErrorBuilder();
    ErrorMessage errorMessage =
        new ErrorMessage(
            ErrorMessage.MessageTypes.RETURN_NULLABLE_GENERIC,
            errorMessageForIncompatibleTypesAtPseudoAssignment(methodType, returnType, state));
    state.reportMatch(
        errorBuilder.createErrorDescription(
            errorMessage, analysis.buildDescription(tree), state, null));
  }

  private void reportMismatchedTypeForTernaryOperator(
      Tree tree, Type expressionType, Type subPartType, VisitorState state) {
    ErrorBuilder errorBuilder = analysis.getErrorBuilder();
    ErrorMessage errorMessage =
        new ErrorMessage(
            ErrorMessage.MessageTypes.ASSIGN_GENERIC_NULLABLE,
            String.format(
                "Conditional expression must have type "
                    + prettyTypeForError(expressionType, state)
                    + " but the sub-expression has type "
                    + prettyTypeForError(subPartType, state)
                    + ", which has mismatched nullability of type parameters"));
    state.reportMatch(
        errorBuilder.createErrorDescription(
            errorMessage, analysis.buildDescription(tree), state, null));
  }

  private void reportInvalidParametersNullabilityError(
      Type formalParameterType,
      Type actualParameterType,
      ExpressionTree paramExpression,
      VisitorState state) {
    ErrorBuilder errorBuilder = analysis.getErrorBuilder();
    ErrorMessage errorMessage =
        new ErrorMessage(
            ErrorMessage.MessageTypes.PASS_NULLABLE_GENERIC,
            errorMessageForIncompatibleTypesAtPseudoAssignment(
                formalParameterType, actualParameterType, state));
    state.reportMatch(
        errorBuilder.createErrorDescription(
            errorMessage, analysis.buildDescription(paramExpression), state, null));
  }

  private void reportInvalidMethodReferenceReturnTypeError(
      MemberReferenceTree memberReferenceTree,
      Type functionalInterfaceReturnType,
      Type referencedMethodReturnType,
      VisitorState state) {
    ErrorBuilder errorBuilder = analysis.getErrorBuilder();
    ErrorMessage errorMessage =
        new ErrorMessage(
            ErrorMessage.MessageTypes.WRONG_OVERRIDE_RETURN_GENERIC,
            String.format(
                "referenced method returns %s, but functional interface method returns %s, which"
                    + " has mismatched type parameter nullability",
                prettyTypeForError(referencedMethodReturnType, state),
                prettyTypeForError(functionalInterfaceReturnType, state)));
    state.reportMatch(
        errorBuilder.createErrorDescription(
            errorMessage, analysis.buildDescription(memberReferenceTree), state, null));
  }

  private void reportInvalidMethodReferenceParameterTypeError(
      MemberReferenceTree memberReferenceTree,
      Type functionalInterfaceParameterType,
      Type referencedMethodParameterType,
      VisitorState state) {
    ErrorBuilder errorBuilder = analysis.getErrorBuilder();
    ErrorMessage errorMessage =
        new ErrorMessage(
            ErrorMessage.MessageTypes.WRONG_OVERRIDE_PARAM_GENERIC,
            String.format(
                "parameter type of referenced method is %s, but parameter in functional interface"
                    + " method has type %s, which has mismatched type parameter nullability",
                prettyTypeForError(referencedMethodParameterType, state),
                prettyTypeForError(functionalInterfaceParameterType, state)));
    state.reportMatch(
        errorBuilder.createErrorDescription(
            errorMessage, analysis.buildDescription(memberReferenceTree), state, null));
  }

  private void reportInvalidOverridingMethodReturnTypeError(
      Tree methodTree,
      Type overriddenMethodReturnType,
      Type overridingMethodReturnType,
      VisitorState state) {
    ErrorBuilder errorBuilder = analysis.getErrorBuilder();
    ErrorMessage errorMessage =
        new ErrorMessage(
            ErrorMessage.MessageTypes.WRONG_OVERRIDE_RETURN_GENERIC,
            "Method returns "
                + prettyTypeForError(overridingMethodReturnType, state)
                + ", but overridden method returns "
                + prettyTypeForError(overriddenMethodReturnType, state)
                + ", which has mismatched type parameter nullability");
    state.reportMatch(
        errorBuilder.createErrorDescription(
            errorMessage, analysis.buildDescription(methodTree), state, null));
  }

  private void reportInvalidOverridingMethodParamTypeError(
      Tree formalParameterTree, Type typeParameterType, Type methodParamType, VisitorState state) {
    ErrorBuilder errorBuilder = analysis.getErrorBuilder();
    ErrorMessage errorMessage =
        new ErrorMessage(
            ErrorMessage.MessageTypes.WRONG_OVERRIDE_PARAM_GENERIC,
            "Parameter has type "
                + prettyTypeForError(methodParamType, state)
                + ", but overridden method has parameter type "
                + prettyTypeForError(typeParameterType, state)
                + ", which has mismatched type parameter nullability");
    state.reportMatch(
        errorBuilder.createErrorDescription(
            errorMessage, analysis.buildDescription(formalParameterTree), state, null));
  }

  /**
   * This method returns the type of the given tree, including any type use annotations.
   *
   * <p>This method is required because in some cases, the type returned by {@link
   * com.google.errorprone.util.ASTHelpers#getType(Tree)} fails to preserve type use annotations,
   * e.g., when dealing with {@link com.sun.source.tree.NewClassTree} (e.g., {@code new
   * Foo<@Nullable A>}).
   *
   * @param tree A tree for which we need the type with preserved annotations.
   * @param state the visitor state
   * @return Type of the tree with preserved annotations. Returns {@code null} for raw non-array
   *     types and other unhandled cases. Arrays with raw component types are returned since their
   *     structure and component annotations are still useful.
   */
  public @Nullable Type getTreeType(Tree tree, VisitorState state) {
    return getTreeType(tree, state, false);
  }

  /**
   * This method returns the type of the given tree, including any type use annotations.
   *
   * <p>This method is required because in some cases, the type returned by {@link
   * com.google.errorprone.util.ASTHelpers#getType(Tree)} fails to preserve type use annotations,
   * e.g., when dealing with {@link com.sun.source.tree.NewClassTree} (e.g., {@code new
   * Foo<@Nullable A>}).
   *
   * @param tree A tree for which we need the type with preserved annotations.
   * @param state the visitor state
   * @param calledFromDataflow true if the type is being computed as part of dataflow analysis
   * @return Type of the tree with preserved annotations. Returns {@code null} for raw non-array
   *     types and other unhandled cases. Arrays with raw component types are returned since their
   *     structure and component annotations are still useful.
   */
  /* package-private */ @Nullable Type getTreeType(
      Tree tree, VisitorState state, boolean calledFromDataflow) {
    if (tree instanceof ExpressionTree exprTree) {
      NullabilityUtil.ExprTreeAndState exprTreeAndState =
          NullabilityUtil.stripParensAndUpdateTreePath(exprTree, state);
      tree = exprTreeAndState.expr();
      state = exprTreeAndState.state();
    }
    if (tree instanceof LambdaExpressionTree
        || tree instanceof MemberReferenceTree
        || tree instanceof ConditionalExpressionTree) {
      Type result =
          lambdaTargetTypesForInference.isEmpty() ? inferredPolyExpressionTypes.get(tree) : null;
      if (result == null) {
        // For lambda and method ref poly expressions, their nullness-enhanced type should have
        // already been cached via an inference call from a parent tree.  In contrast, the
        // nullness-enhanced type of a conditional expression tree may not have been computed, so we
        // invoke getConditionalExpressionType here to do so
        result =
            tree instanceof ConditionalExpressionTree conditionalExpressionTree
                ? getConditionalExpressionType(conditionalExpressionTree, state, calledFromDataflow)
                : ASTHelpers.getType(tree);
      }
      return typeOrNullIfRawNonArray(result);
    }
    if (tree instanceof NewClassTree newClassTree) {
      if (isCallNeedingInference(newClassTree)) {
        TreePath currentPath = state.getPath();
        CallAndContext directContext =
            getDirectCallContextForInference(currentPath, state, calledFromDataflow);
        return inferCallType(
            state,
            newClassTree,
            currentPath,
            directContext.typeFromAssignmentContext,
            directContext.assignedToLocal,
            calledFromDataflow);
      }
      if (newClassTree.getIdentifier() instanceof ParameterizedTypeTree paramTypedTree
          && !paramTypedTree.getTypeArguments().isEmpty()) {
        Type typeFromIdentifier = typeWithPreservedAnnotations(paramTypedTree);
        return withEnclosingTypeFromQualifier(
            typeFromIdentifier, newClassTree, state, calledFromDataflow);
      }
      return typeOrNullIfRawNonArray(ASTHelpers.getType(tree));
    } else if (tree instanceof NewArrayTree
        && ((NewArrayTree) tree).getType() instanceof AnnotatedTypeTree) {
      return typeWithPreservedAnnotations(tree);
    } else {
      Type result;
      if (tree instanceof VariableTree) {
        // type on the tree itself can be missing nested annotations for arrays; get the type from
        // the symbol for the variable instead
        result = castToNonNull(ASTHelpers.getSymbol(tree)).type;
      } else if (tree instanceof IdentifierTree identifierTree) {
        // handle "this" specially, for cases where it appears inside an anonymous class
        Symbol symbol = castToNonNull(ASTHelpers.getSymbol(identifierTree));
        if (identifierTree.getName().contentEquals("this")) {
          Symbol owner = symbol.owner;
          if (owner != null) {
            Symbol.ClassSymbol enclosingClass = owner.enclClass();
            if (enclosingClass != null) {
              Type enclosingType = getTypeForSymbol(enclosingClass, state);
              if (enclosingType != null) {
                return enclosingType;
              }
            }
          }
        } else if (symbol.getKind() == ElementKind.PARAMETER) {
          // Use the derived parameter type from lambdaParameterTypesForInference during constraint
          // generation, or the inferred parameter type after inference succeeds.
          Type lambdaParameterType = getInferredLambdaParameterType(symbol, state);
          if (lambdaParameterType != null) {
            return lambdaParameterType;
          }
        }
        // If it's a local variable declared using `var`, get the inferred type
        Type inferredVarLocalType = getInferredVarLocalType(symbol, state, calledFromDataflow);
        if (inferredVarLocalType != null) {
          return inferredVarLocalType;
        }
        result = ASTHelpers.getType(tree);
        // type on the tree itself can be missing nested annotations in certain cases, so use the
        // type on the symbol instead.  for type variables, we've found that the type on the symbol
        // can be wrong (which caused https://github.com/uber/NullAway/issues/1377), so we still use
        // the tree type for type variables
        if (result != null && !(result instanceof Type.TypeVar)) {
          result = symbol.type;
        }
      } else if (tree instanceof AssignmentTree assignmentTree) {
        // The type on the tree itself can be missing nested annotations, so prefer the declared
        // type of the assigned location. For an array access, javac capture-converts the component
        // type; recover the pre-capture component type from the array expression instead.
        ExpressionTree lhs = assignmentTree.getVariable();
        Symbol lhsSymbol = ASTHelpers.getSymbol(lhs);
        if (lhs instanceof ArrayAccessTree arrayAccess) {
          ExpressionTree arrayExpression = arrayAccess.getExpression();
          TreePath arrayExpressionPath =
              pathWithLeaf(pathWithLeaf(state.getPath(), arrayAccess), arrayExpression);
          Type arrayType =
              getTreeType(arrayExpression, state.withPath(arrayExpressionPath), calledFromDataflow);
          result =
              arrayType instanceof Type.ArrayType arrayTypeWithComponent
                  ? arrayTypeWithComponent.getComponentType()
                  : ASTHelpers.getType(assignmentTree);
        } else if (lhsSymbol != null) {
          Type inferredVarLocalType = getInferredVarLocalType(lhsSymbol, state, calledFromDataflow);
          result = inferredVarLocalType != null ? inferredVarLocalType : lhsSymbol.type;
        } else {
          result = ASTHelpers.getType(assignmentTree);
        }
      } else {
        result = ASTHelpers.getType(tree);
        if (result != null) {
          if (tree instanceof MethodInvocationTree invocationTree) {
            // The type javac reports for a method invocation may omit nested nullability
            // annotations. Compute the resolved call-site method type, including substitutions
            // inferred for the method itself and for generic calls in its receiver. For example,
            // in `foo(x, y).bar().baz()`, where `foo` is generic, inference for `foo` determines
            // the receiver type of `bar()`, which can in turn affect the receiver type of `baz()`.
            // The resolved return type is used below to restore annotations on javac's invocation
            // type.
            Type.MethodType methodType =
                getInvokedMethodTypeAtCall(
                    invocationTree, state.getPath(), state, calledFromDataflow);
            // restore explicit annotations from the return type
            Type returnType = methodType.getReturnType();
            // If the return type is a wildcard, restore annotations from its upper bound
            Type annotationSource =
                config.handleWildcardGenerics()
                    ? GenericsUtils.effectiveWildcardUpperBound(returnType, state, config, handler)
                    : returnType;
            result =
                TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
                    annotationSource, result, config);
          } else if (tree instanceof MemberSelectTree memberSelectTree) {
            Symbol memberSelectSymbol = ASTHelpers.getSymbol(memberSelectTree);
            if (memberSelectSymbol != null && memberSelectSymbol.getKind().isField()) {
              // restore explicit annotations from the field's declared type
              Type fieldType = memberSelectSymbol.type;
              result =
                  TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
                      fieldType, result, config);
            }
          }
        }
      }
      return typeOrNullIfRawNonArray(result);
    }
  }

  /**
   * @param type a type to check
   * @return the given type, or {@code null} if it is a raw non-array type. Javac reports an array
   *     type as raw when its component type is raw, but the array structure and component
   *     annotations are still usable for checking.
   */
  private static @Nullable Type typeOrNullIfRawNonArray(@Nullable Type type) {
    if (type != null && type.isRaw() && !(type instanceof Type.ArrayType)) {
      return null;
    }
    return type;
  }

  /**
   * Returns the inferred/declared formal parameter type corresponding to actual parameter {@code
   * argumentTree}.
   */
  @SuppressWarnings("ReferenceEquality") // deliberate reference equality checks
  private @Nullable Type getFormalParameterTypeForArgument(
      Tree invocationTree, Type.MethodType invocationType, Tree argumentTree) {
    AtomicReference<@Nullable Type> formalParamTypeRef = new AtomicReference<>();
    new InvocationArguments(invocationTree, invocationType)
        .forEach(
            (arg, pos, formalParamType, unused) -> {
              if (ASTHelpers.stripParentheses(arg) == argumentTree) {
                formalParamTypeRef.set(formalParamType);
              }
            });
    return formalParamTypeRef.get();
  }

  /**
   * Returns the instantiated type named by a constructor call, excluding any synthetic anonymous
   * class type introduced by javac.
   *
   * <p>For an anonymous diamond call, the symbol of the synthetic constructor is owned by the
   * anonymous class, which has no type parameters. The identifier instead has the type being
   * constructed, preserving the generic declaration whose type variables require inference.
   */
  private static Type getConstructedTypeAtCallSite(NewClassTree newClassTree) {
    return castToNonNull(
        ASTHelpers.getType(
            newClassTree.getClassBody() == null ? newClassTree : newClassTree.getIdentifier()));
  }

  /** Resolves a lambda descriptor as a member of its ground functional target. */
  private Type.MethodType getLambdaDescriptorType(
      LambdaExpressionTree lambda, Type targetType, VisitorState state) {
    Type groundTarget = GenericsUtils.groundTargetType(targetType, state, config, handler);
    return TypeSubstitutionUtils.memberType(
            state.getTypes(),
            groundTarget,
            NullabilityUtil.getFunctionalInterfaceMethod(lambda, state.getTypes()),
            config)
        .asMethodType();
  }

  /**
   * Returns the lambda body's target from its owning inference problem or completed functional
   * target. Reentrant inference must not replace either with javac's annotation-erased target.
   */
  private @Nullable Type getLambdaReturnTargetType(
      LambdaExpressionTree lambda, VisitorState state) {
    Type targetType = lambdaTargetTypesForInference.get(lambda);
    if (targetType == null) {
      targetType = inferredPolyExpressionTypes.get(lambda);
    }
    if (targetType == null) {
      targetType = ASTHelpers.getType(lambda);
    }
    if (targetType == null || targetType.isRaw()) {
      return null;
    }
    Type returnType = getLambdaDescriptorType(lambda, targetType, state).getReturnType();
    // A void-compatible expression lambda discards the value of its statement expression.
    return returnType.hasTag(TypeTag.VOID) ? null : returnType;
  }

  /**
   * Gets the temporary type of a lambda parameter during constraint generation (see {@link
   * #lambdaParameterTypesForInference}), or its inferred type if the lambda was passed to a generic
   * method and inference succeeded.
   *
   * @param symbol the symbol for the parameter (possibly not of a lambda, just needs kind to be
   *     {@code ElementKind.PARAMETER})
   * @param state the visitor state
   * @return the target-derived or inferred type of the lambda parameter, or null if not found
   */
  private @Nullable Type getInferredLambdaParameterType(Symbol symbol, VisitorState state) {
    Type provisionalType = lambdaParameterTypesForInference.get(symbol);
    if (provisionalType != null) {
      return provisionalType;
    }
    // A parameter captured by a nested lambda belongs to an outer lambda, not necessarily the
    // nearest one. Match its declaration before selecting the completed functional target.
    for (TreePath path = state.getPath(); path != null; path = path.getParentPath()) {
      if (path.getLeaf() instanceof LambdaExpressionTree lambdaTree) {
        var params = lambdaTree.getParameters();
        for (int i = 0; i < params.size(); i++) {
          if (symbol.equals(ASTHelpers.getSymbol(params.get(i)))) {
            Type inferredLambdaType = inferredPolyExpressionTypes.get(lambdaTree);
            if (inferredLambdaType != null) {
              return getLambdaDescriptorType(lambdaTree, inferredLambdaType, state)
                  .getParameterTypes()
                  .get(i);
            }
            return null;
          }
        }
      }
    }
    return null;
  }

  /**
   * For a tree representing an assignment, ensures that from the perspective of type parameter
   * nullability, the type of the right-hand side is assignable to (a subtype of) the type of the
   * left-hand side. This check ensures that for every parameterized type nested in each of the
   * types, the type parameters have identical nullability.
   *
   * @param tree the tree to check, which must be either an {@link AssignmentTree} or a {@link
   *     VariableTree}
   * @param state the visitor state
   */
  public void checkTypeParameterNullnessForAssignability(Tree tree, VisitorState state) {
    Config config = analysis.getConfig();
    if (!config.isJSpecifyMode()) {
      return;
    }
    Type lhsType = getTreeType(tree, state);
    if (lhsType == null) {
      return;
    }
    ExpressionTree rhsTree;
    boolean assignedToLocal;
    if (tree instanceof VariableTree varTree) {
      rhsTree = varTree.getInitializer();
      assignedToLocal = isAssignmentToLocalVariable(varTree);
    } else if (tree instanceof AssignmentTree assignmentTree) {
      rhsTree = assignmentTree.getExpression();
      assignedToLocal = isAssignmentToLocalVariable(assignmentTree);
    } else {
      throw new RuntimeException("Unexpected tree type: " + tree.getKind());
    }
    // rhsTree can be null for a VariableTree.  Also, we don't need to do a check
    // if rhsTree is the null literal
    if (rhsTree == null || rhsTree.getKind().equals(Tree.Kind.NULL_LITERAL)) {
      return;
    }
    if (rhsTree instanceof LambdaExpressionTree || rhsTree instanceof MemberReferenceTree) {
      maybeStorePolyExpressionTypeFromTarget(rhsTree, lhsType, state);
    }
    boolean varLocalDeclaration =
        tree instanceof VariableTree varTree && isVarLocalVariableDeclaration(varTree);
    TreePath pathToRhs = new TreePath(state.getPath(), rhsTree);
    Type rhsType =
        varLocalDeclaration
            ? getInferredTypeForVarLocalDeclaration(
                (VariableTree) tree, rhsTree, pathToRhs, state, false)
            : getTypeForRhsOfAssignment(rhsTree, pathToRhs, lhsType, assignedToLocal, state, false);
    if (rhsType != null && !hasUnavailableLowerResult(rhsTree)) {
      if (varLocalDeclaration) {
        lhsType = rhsType;
      }
      boolean isAssignmentValid = subtypeParameterNullability(lhsType, rhsType, state);
      if (!isAssignmentValid) {
        reportInvalidAssignmentInstantiationError(tree, lhsType, rhsType, state);
      }
    }
  }

  private static boolean isAssignmentToLocalVariable(Tree tree) {
    return isAssignmentToKind(tree, ElementKind.LOCAL_VARIABLE)
        || isAssignmentToKind(tree, ElementKind.RESOURCE_VARIABLE);
  }

  private static boolean isVarLocalVariableDeclaration(VariableTree tree) {
    return tree instanceof JCTree.JCVariableDecl variableDecl && variableDecl.declaredUsingVar();
  }

  /**
   * Associates the {@code Symbol} for a {@code var}-declared local with the local's declaration.
   * This is used in case we observe a use of the local before we have processed the declaration
   * (e.g., due to dataflow analysis), and need to jump to the declaration to infer the variable's
   * type.
   */
  public void registerVarLocalDeclaration(VariableTree tree) {
    if (!isVarLocalVariableDeclaration(tree)) {
      return;
    }
    Symbol symbol = ASTHelpers.getSymbol(tree);
    if (symbol != null
        && (symbol.getKind().equals(ElementKind.LOCAL_VARIABLE)
            || symbol.getKind().equals(ElementKind.RESOURCE_VARIABLE))) {
      varLocalDeclarations.put(symbol, (JCTree.JCVariableDecl) tree);
    }
  }

  /**
   * Returns whether a computed type or generic-call inference result can be cached.
   *
   * <p>Results computed during dataflow may depend on incomplete analysis results. Results computed
   * while provisional lambda parameter or target types are available may depend on unsolved outer
   * inference variables. Skip caching in either context so subsequent checks can recompute the
   * result after dataflow or the enclosing generic inference completes.
   *
   * @param calledFromDataflow whether the result was computed as part of dataflow analysis
   * @return whether the inference result can be cached in the current context
   */
  private boolean okToCacheInferenceResult(boolean calledFromDataflow) {
    return !calledFromDataflow
        && lambdaParameterTypesForInference.isEmpty()
        && lambdaTargetTypesForInference.isEmpty();
  }

  /**
   * Gets the inferred type for a local variable declared with {@code var}.
   *
   * @param symbol symbol for the local
   * @param state visitor state
   * @param calledFromDataflow whether this method was called as part of dataflow analysis
   * @return the inferred type, or {@code null} if the symbol is not for a var-declared local
   */
  private @Nullable Type getInferredVarLocalType(
      Symbol symbol, VisitorState state, boolean calledFromDataflow) {
    if (lambdaVarLocalInference != null) {
      Type symbolicType = lambdaVarLocalInference.types().get(symbol);
      if (symbolicType != null) {
        return typeOrNullIfRawNonArray(symbolicType);
      }
      TreePath declarationPath = lambdaVarLocalInference.declarations().get(symbol);
      if (declarationPath != null) {
        return getSymbolicLambdaVarLocalType(
            symbol, declarationPath, state, lambdaVarLocalInference);
      }
    }
    Type cachedType = inferredVarLocalTypes.get(symbol);
    if (cachedType != null) {
      return cachedType;
    }
    VariableTree variableDecl = varLocalDeclarations.get(symbol);
    if (variableDecl == null) {
      return null;
    }
    ExpressionTree initializer = variableDecl.getInitializer();
    if (initializer == null) {
      Type enhancedForElementType =
          getEnhancedForLoopElementType(symbol, state, calledFromDataflow);
      if (enhancedForElementType != null) {
        if (okToCacheInferenceResult(calledFromDataflow)) {
          inferredVarLocalTypes.put(symbol, enhancedForElementType);
        }
        return enhancedForElementType;
      }
      return typeOrNullIfRawNonArray(symbol.type);
    }
    TreePath pathToInitializer = pathWithLeaf(state.getPath(), initializer);
    return getInferredTypeForVarLocalDeclaration(
        variableDecl, initializer, pathToInitializer, state, calledFromDataflow);
  }

  /**
   * Gets the inferred element type for a {@code var}-declared enhanced-for loop variable.
   *
   * <p>javac may drop nested type-use annotations from the loop variable's symbol, so this method
   * reconstructs the element type from the annotated type of the loop expression. For an iterable,
   * the element type is the effective upper bound of its {@code Iterable} type argument, as
   * specified by JLS 14.14.2.
   *
   * @param symbol symbol for the enhanced-for loop variable
   * @param state visitor state whose path is within the loop
   * @param calledFromDataflow whether this method was called as part of dataflow analysis
   * @return the inferred element type, or {@code null} if no matching loop or usable expression
   *     type can be found
   */
  private @Nullable Type getEnhancedForLoopElementType(
      Symbol symbol, VisitorState state, boolean calledFromDataflow) {
    TreePath currentPath = state.getPath();
    // currentPath points to some use of the loop variable inside the loop.  So, we need to traverse
    // up the path to parents until we find the EnhancedForLoopTree.  This only happens once per
    // variable so shouldn't be too costly.
    while (currentPath != null) {
      if (currentPath.getLeaf() instanceof EnhancedForLoopTree enhancedForLoop
          && symbol.equals(ASTHelpers.getSymbol(enhancedForLoop.getVariable()))) {
        ExpressionTree expression = enhancedForLoop.getExpression();
        TreePath expressionPath = new TreePath(currentPath, expression);
        return getEnhancedForLoopElementType(
            expression, state.withPath(expressionPath), calledFromDataflow);
      }
      // not the EnhancedForLoopTree; keep going
      currentPath = currentPath.getParentPath();
    }
    // unreachable; we should have always been asking about a variable inside an enhanced for loop
    throw new IllegalStateException(
        String.format(
            "%s is unexpectedly outside an enhanced for loop",
            state.getSourceForNode(state.getPath().getLeaf())));
  }

  /**
   * Gets the nullness of the element read from an enhanced-for expression during dataflow.
   *
   * @param expression expression iterated over by the enhanced-for loop
   * @param state visitor state whose path points to {@code expression}
   * @return the element nullness, or {@link Nullness#NONNULL} if the element type cannot be
   *     resolved
   */
  public Nullness getEnhancedForLoopElementNullness(ExpressionTree expression, VisitorState state) {
    Type elementType =
        getEnhancedForLoopElementType(expression, state, /* calledFromDataflow= */ true);
    if (!config.handleWildcardGenerics()
        && elementType != null
        && GenericsUtils.asWildcard(elementType) != null) {
      // if we're not handling wildcards, always treat as non-null
      return Nullness.NONNULL;
    }
    return elementType == null
        ? Nullness.NONNULL
        : getTypeNullnessForRead(
            elementType, state, /* followUnsubstitutedTypeVarUpperBound= */ true);
  }

  /**
   * Gets the element type of an enhanced-for expression, preserving nested nullability annotations.
   *
   * @param expression the expression being iterated over
   * @param state the visitor state
   * @param calledFromDataflow whether this function was called from dataflow analysis
   * @return the element type for the loop variable of the enhanced-for, or {@code null} if it can't
   *     be found
   */
  private @Nullable Type getEnhancedForLoopElementType(
      ExpressionTree expression, VisitorState state, boolean calledFromDataflow) {
    Type expressionType = getTreeType(expression, state, calledFromDataflow);
    if (expressionType instanceof Type.ArrayType arrayType) {
      return arrayType.getComponentType();
    }
    if (expressionType == null || expressionType.isRaw()) {
      return null;
    }
    // We have an enhanced for over an Iterable.  So, we want the effective upper bound of the
    // type argument (JLS 14.14.2)
    Type iterableType =
        TypeSubstitutionUtils.asSuper(
            state.getTypes(),
            expressionType,
            (Symbol.ClassSymbol) state.getSymtab().iterableType.tsym,
            config);
    if (iterableType == null || iterableType.isRaw()) {
      return null;
    }
    com.sun.tools.javac.util.List<Type> typeArguments = iterableType.getTypeArguments();
    return config.handleWildcardGenerics()
        ? GenericsUtils.effectiveWildcardUpperBound(typeArguments.head, state, config, handler)
        : typeArguments.head;
  }

  private @Nullable Type getInferredTypeForVarLocalDeclaration(
      VariableTree varTree,
      ExpressionTree initializer,
      TreePath pathToInitializer,
      VisitorState state,
      boolean calledFromDataflow) {
    Type rhsType =
        getTypeForRhsOfAssignment(
            initializer,
            pathToInitializer,
            null,
            isAssignmentToLocalVariable(varTree),
            state,
            calledFromDataflow);
    if (rhsType != null && okToCacheInferenceResult(calledFromDataflow)) {
      Symbol symbol = ASTHelpers.getSymbol(varTree);
      if (symbol != null) {
        inferredVarLocalTypes.put(symbol, rhsType);
      }
    }
    return rhsType;
  }

  /**
   * Types an assignment source against its context, keeping functional poly expressions on the
   * owning target rather than comparing javac's annotation-erased standalone type.
   */
  private @Nullable Type getTypeForRhsOfAssignment(
      ExpressionTree rhsTree,
      TreePath pathToRhs,
      @Nullable Type typeFromAssignmentContext,
      boolean assignedToLocal,
      VisitorState state,
      boolean calledFromDataflow) {
    NullabilityUtil.ExprTreeAndState rhsAndState =
        NullabilityUtil.stripParensAndUpdateTreePath(rhsTree, state.withPath(pathToRhs));
    rhsTree = rhsAndState.expr();
    state = rhsAndState.state();
    pathToRhs = state.getPath();
    if (isCallNeedingInference(rhsTree)) {
      return inferCallType(
          state.withPath(pathToRhs),
          rhsTree,
          pathToRhs,
          typeFromAssignmentContext,
          assignedToLocal,
          calledFromDataflow);
    } else if (rhsTree instanceof ConditionalExpressionTree conditionalExpressionTree) {
      return inferConditionalExpressionType(
          state.withPath(pathToRhs),
          conditionalExpressionTree,
          pathToRhs,
          typeFromAssignmentContext,
          calledFromDataflow);
    } else if (typeFromAssignmentContext != null
        && (rhsTree instanceof LambdaExpressionTree || rhsTree instanceof MemberReferenceTree)) {
      // A poly arm has the conditional's target, not javac's annotation-erased functional type.
      // Keep provisional owning-lambda targets local to this check rather than publishing them.
      if (okToCacheInferenceResult(calledFromDataflow)) {
        maybeStorePolyExpressionTypeFromTarget(rhsTree, typeFromAssignmentContext, state);
      }
      return GenericsUtils.groundTargetType(typeFromAssignmentContext, state, config, handler);
    } else {
      return getTreeType(rhsTree, state.withPath(pathToRhs), calledFromDataflow);
    }
  }

  private static boolean isAssignmentToKind(Tree tree, ElementKind kind) {
    Symbol treeSymbol;
    if (tree instanceof VariableTree variableTree) {
      treeSymbol = ASTHelpers.getSymbol(variableTree);
    } else if (tree instanceof AssignmentTree assignmentTree) {
      treeSymbol = ASTHelpers.getSymbol(assignmentTree.getVariable());
    } else {
      throw new RuntimeException("Unexpected tree type: " + tree.getKind());
    }
    return treeSymbol != null && treeSymbol.getKind().equals(kind);
  }

  private ConstraintSolver makeSolver(VisitorState state, NullAway analysis) {
    return new ConstraintSolverImpl(config, state, analysis);
  }

  /**
   * Infers the type of a generic method call or diamond constructor call based on its assignment
   * context. Side-effects the cache of inferred nullability substitutions for omitted type
   * arguments.
   *
   * @param state the visitor state
   * @param callTree the call expression representing the generic method call or diamond constructor
   *     call
   * @param path the tree path to {@code callTree} if available and possibly distinct from {@code
   *     state.getPath()}
   * @param typeFromAssignmentContext the type being "assigned to" in the assignment context
   * @param assignedToLocal true if the call result is assigned to a local variable, false otherwise
   * @param calledFromDataflow true if this inference is being done as part of dataflow analysis
   * @return the type of the call after inference
   */
  private Type inferCallType(
      VisitorState state,
      ExpressionTree callTree,
      @Nullable TreePath path,
      @Nullable Type typeFromAssignmentContext,
      boolean assignedToLocal,
      boolean calledFromDataflow) {
    Verify.verify(isCallNeedingInference(callTree));
    // Computing the executable type of a method invocation computes the type of its receiver,
    // which may itself require inference, so compute it only once
    InferenceCall inferenceCall = getCallForInference(callTree, path, state, calledFromDataflow);
    Map<Element, Type> typeVarNullability = null;
    CallInferenceResult result = inferredTypeVarNullabilityForGenericCalls.get(callTree);
    if (result == null) { // have not yet attempted inference for this call
      result =
          runInferenceForCall(
              state,
              path,
              inferenceCall,
              typeFromAssignmentContext,
              assignedToLocal,
              calledFromDataflow);
    }
    if (result instanceof InferenceWithTypes successResult) {
      typeVarNullability = successResult.inferredTypesForSite(callTree);
    }
    if (result instanceof InferenceSuccess complete) {
      return TypeSubstitutionUtils.substituteTypeVariables(
          inferenceCall.resultTemplate,
          complete.inferredTypesForSite(callTree),
          state.getTypes(),
          config);
    }
    if (inferenceCall.site.fixedResult) {
      return inferenceCall.resultTemplate;
    }
    return TypeSubstitutionUtils.updateTypeWithInferredNullability(
        inferenceCall.site.attributedResult,
        inferenceCall.resultTemplate,
        typeVarNullability,
        state,
        config);
  }

  /**
   * Runs inference for a generic call, side-effecting the
   * #inferredTypeVarNullabilityForGenericCalls map with the result.
   *
   * @param state the visitor state
   * @param path the tree path to the call tree if available and possibly distinct from {@code
   *     state.getPath()}
   * @param inferenceCall the annotated call description in this inference context
   * @param typeFromAssignmentContext the type being "assigned to" in the assignment context, or
   *     {@code null} if the type is unavailable or the method result is not assigned anywhere
   * @param assignedToLocal true if the call result is assigned to a local variable, false otherwise
   * @param calledFromDataflow true if this inference is being done as part of dataflow analysis
   * @return the inference result, either success with inferred nullness-annotated types or failure
   *     with an error message
   */
  private CallInferenceResult runInferenceForCall(
      VisitorState state,
      @Nullable TreePath path,
      InferenceCall inferenceCall,
      @Nullable Type typeFromAssignmentContext,
      boolean assignedToLocal,
      boolean calledFromDataflow) {
    ExpressionTree callTree = inferenceCall.site.tree;
    Type.MethodType executableType = inferenceCall.signature;
    ConstraintSolver solver = makeSolver(state, analysis);
    InferenceCacheState inferenceCacheState = new InferenceCacheState();
    // calls whose lambda and method reference arguments get published types on success
    Map<Tree, InferenceCall> callTypesForPolyArgs = new LinkedHashMap<>();
    try {
      Map<Tree, InferenceCall> enclosingCallTypesForPolyArgs = callTypesForPolyArguments;
      callTypesForPolyArguments = callTypesForPolyArgs;
      try {
        generateConstraintsForCall(
            state,
            path,
            typeFromAssignmentContext,
            assignedToLocal,
            solver,
            inferenceCall,
            inferenceCacheState,
            callTree,
            calledFromDataflow);
      } finally {
        callTypesForPolyArguments = enclosingCallTypesForPolyArgs;
      }
      Solution solution = solver.solve();

      InferenceWithTypes result =
          solution.isComplete()
              ? new InferenceSuccess(solution)
              : new InferencePartial(solution, inferenceCacheState.calls);
      boolean publishOutcome =
          canPublishDiagnosticInferenceOutcome(inferenceCacheState, calledFromDataflow);
      // Temporary lambda types during generation do not invalidate certified poly targets from
      // the completed solution. Only diagnostic or contradictory outcomes require this guard.
      if (result instanceof InferenceSuccess successResult
          && okToCacheInferenceResult(calledFromDataflow)) {
        if (typeFromAssignmentContext != null) {
          completedCallContexts.put(
              callTree, new CallAndContext(callTree, typeFromAssignmentContext, assignedToLocal));
        }
        inferenceCacheState.methodReferences.forEach(
            methodReference ->
                inferredResultsForGenericMethodReferences.put(methodReference, successResult));
        for (Tree inferredCall : inferenceCacheState.cacheableCalls) {
          inferredTypeVarNullabilityForGenericCalls.put(inferredCall, successResult);
        }
        // Store inferred types for lambda or method reference arguments of the participating
        // calls, including nested calls, each using the types inferred for that call
        callTypesForPolyArgs.forEach(
            (call, callExecutableType) ->
                storeInferredPolyArgumentTypes(
                    call,
                    callExecutableType.signature,
                    successResult.inferredTypesForSite(call),
                    state));
      } else if (result instanceof InferencePartial partial
          && solution.inconsistentVariables().isEmpty()
          && okToCacheInferenceResult(calledFromDataflow)) {
        // Certify each target independently: an unrelated unused variable may remain opaque.
        callTypesForPolyArgs.forEach(
            (call, callExecutableType) ->
                storeCertifiedPolyArgumentTypes(
                    call, callExecutableType.signature, partial, state));
      } else if (result instanceof InferencePartial
          && path != null
          && !solution.inconsistentVariables().isEmpty()
          && solution.inconsistentVariables().containsAll(solution.incompleteVariables())
          && hasShapedDiagnosticTypes(callTree, executableType, result, state)
          && publishOutcome) {
        // Preserve the final root's target-derived evidence for ordinary parameter diagnostics.
        // This is not a successful result and must not certify nested calls or poly expressions.
        inferredTypeVarNullabilityForGenericCalls.put(callTree, result);
      }
      if (publishOutcome) {
        publishDiagnosticInferenceOutcomes(inferenceCacheState, result, state);
      }
      return result;
    } catch (NestedUpperBoundViolationException e) {
      String message =
          errorMessageForIncompatibleTypesAtPseudoAssignment(
              e.getUpperBound(), e.getLowerBound(), state);
      ErrorMessage errorMessage =
          new ErrorMessage(ErrorMessage.MessageTypes.PASS_NULLABLE_GENERIC, message);
      if (reportedNestedUpperBoundViolations.add(e.getSite())) {
        VisitorState reportingState = stateForInferenceDiagnostic(e.getSite(), state);
        state.reportMatch(
            analysis
                .getErrorBuilder()
                .createErrorDescription(
                    errorMessage, analysis.buildDescription(e.getSite()), reportingState, null));
      }
      InferenceFailure failureResult = new InferenceFailure(message);
      if (canPublishDiagnosticInferenceOutcome(inferenceCacheState, calledFromDataflow)) {
        invalidateMethodReferenceResults(inferenceCacheState);
        // Solving stopped at this site. Other participating calls still need independent checking.
        inferredTypeVarNullabilityForGenericCalls.put(e.getSite(), failureResult);
        diagnosticInferenceResults.remove(e.getSite());
      }
      return failureResult;
    } catch (UnsatisfiableConstraintsException e) {
      String inferenceFailureMessage = inferenceFailureMessage(e);
      Tree inferenceSite = e.getInferenceSite() != null ? e.getInferenceSite() : callTree;
      if (config.warnOnGenericInferenceFailure()
          && callsWithReportedInferenceFailures.add(inferenceSite)) {
        ErrorBuilder errorBuilder = analysis.getErrorBuilder();
        ErrorMessage errorMessage =
            new ErrorMessage(
                ErrorMessage.MessageTypes.GENERIC_INFERENCE_FAILURE, inferenceFailureMessage);
        VisitorState reportingState = stateForInferenceDiagnostic(inferenceSite, state);
        state.reportMatch(
            errorBuilder.createErrorDescription(
                errorMessage, analysis.buildDescription(inferenceSite), reportingState, null));
      }
      InferenceFailure failureResult = new InferenceFailure(inferenceFailureMessage);
      if (canPublishDiagnosticInferenceOutcome(inferenceCacheState, calledFromDataflow)) {
        invalidateMethodReferenceResults(inferenceCacheState);
        // Contextual scalar contradictions complete only this root problem. Nested calls must
        // still infer their own arguments and contexts independently.
        inferredTypeVarNullabilityForGenericCalls.put(callTree, failureResult);
        diagnosticInferenceResults.remove(callTree);
      }
      return failureResult;
    }
  }

  /**
   * Checks that contradictory root substitutions retain javac's complete Java instantiations.
   * Contradictions can also be marked incomplete; that must not admit unshaped fallback sources.
   */
  private boolean hasShapedDiagnosticTypes(
      ExpressionTree call,
      Type.MethodType executableType,
      InferenceWithTypes result,
      VisitorState state) {
    List<Symbol.TypeVariableSymbol> variables = getCallTypeParameters(call);
    Map<Element, Type> shapes =
        getJavacInstantiationsForCall(call, executableType, variables, state);
    Map<Element, Type> diagnosticTypes = result.inferredTypesForSite(call);
    for (Symbol.TypeVariableSymbol variable : variables) {
      Type shape = shapes.get(variable);
      Type diagnosticType = diagnosticTypes.get(variable);
      if (shape == null
          || diagnosticType == null
          || !state.getTypes().isSameType(shape, diagnosticType)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Rejects diagnostic availability and contradictory call persistence for problems with dataflow
   * or provisional-lambda inputs. Certified poly targets use their separate publication checks.
   */
  private boolean canPublishDiagnosticInferenceOutcome(
      InferenceCacheState inputs, boolean calledFromDataflow) {
    return okToCacheInferenceResult(calledFromDataflow) && !inputs.provisionalLambdaTypesSeen;
  }

  /**
   * Replaces participating calls' diagnostic outcomes only after an eligible solve and its caching
   * decisions finish. A retained cached result takes precedence over newly computed evidence, just
   * as it does when supplying types. Reporting happens after the replacement, so reentrant checks
   * cannot observe a mixture of the old and new availability snapshots.
   */
  @SuppressWarnings("ReferenceEquality") // availability must belong to this exact outcome
  private void publishDiagnosticInferenceOutcomes(
      InferenceCacheState inputs, InferenceWithTypes result, VisitorState state) {
    Map<Tree, InferencePartial> outcomes = new LinkedHashMap<>();
    for (Tree site : inputs.calls.keySet()) {
      CallInferenceResult cached = inferredTypeVarNullabilityForGenericCalls.get(site);
      if (cached instanceof InferencePartial partial) {
        outcomes.put(site, partial);
      } else if (cached == null && result instanceof InferencePartial partial) {
        outcomes.put(site, partial);
      }
    }
    inputs.calls.keySet().forEach(diagnosticInferenceResults::remove);
    diagnosticInferenceResults.putAll(outcomes);
    for (InferencePartial partial : new LinkedHashSet<>(outcomes.values())) {
      for (LowerBoundConflict conflict : partial.solution.lowerConflicts()) {
        if (outcomes.get(conflict.variable().site()) == partial
            && usesDirectConflictDiagnostic(conflict)) {
          reportLowerBoundConflict(conflict, state);
        }
      }
    }
  }

  /**
   * Uses the same cached outcome that supplies checked types when one exists; otherwise uses the
   * last eligible completed diagnostic outcome. Speculative inference never changes either source.
   */
  private @Nullable InferencePartial diagnosticInferenceOutcome(Tree site) {
    CallInferenceResult cached = inferredTypeVarNullabilityForGenericCalls.get(site);
    return cached instanceof InferencePartial partial
        ? partial
        : cached == null ? diagnosticInferenceResults.get(site) : null;
  }

  /** Keeps established ordinary same-nominal checking as presentation policy, not solver policy. */
  private boolean usesDirectConflictDiagnostic(LowerBoundConflict conflict) {
    if (!(conflict.variable().site() instanceof MethodInvocationTree)
        && !(conflict.variable().site() instanceof NewClassTree)) {
      return false;
    }
    Type first = conflict.first().type();
    Type second = conflict.second().type();
    while (first instanceof Type.ArrayType a && second instanceof Type.ArrayType b) {
      first = a.elemtype;
      second = b.elemtype;
    }
    return !(first instanceof Type.ClassType
        && second instanceof Type.ClassType
        && first.tsym.equals(second.tsym));
  }

  /** Selects explicit ownership carried by the bound, preferring the receiving call's argument. */
  private @Nullable SourceOrigin sourceAtSite(BoundEvidence evidence, Tree site) {
    for (SourceOrigin origin : evidence.origins()) {
      if (origin.ownerSite().equals(site)) {
        return origin;
      }
    }
    return evidence.origins().isEmpty() ? null : evidence.origins().get(0);
  }

  /**
   * Reports source evidence directly; no type-to-AST matching or signature reconstruction occurs.
   */
  private void reportLowerBoundConflict(LowerBoundConflict conflict, VisitorState state) {
    SourceOrigin first = sourceAtSite(conflict.first(), conflict.variable().site());
    SourceOrigin second = sourceAtSite(conflict.second(), conflict.variable().site());
    BoundEvidence firstEvidence = conflict.first();
    BoundEvidence secondEvidence = conflict.second();
    if (first != null
        && second != null
        && ASTHelpers.getStartPosition(first.expression())
            > ASTHelpers.getStartPosition(second.expression())) {
      SourceOrigin temporary = first;
      first = second;
      second = temporary;
      firstEvidence = conflict.second();
      secondEvidence = conflict.first();
    }
    String message =
        String.format(
            "incompatible types: arguments '%s' (%s) and '%s' (%s) have conflicting nullability"
                + " for type variable %s",
            second != null ? second.expression() : "lower source",
            prettyTypeForError(
                second != null ? second.originalType() : secondEvidence.type(), state),
            first != null ? first.expression() : "lower source",
            prettyTypeForError(first != null ? first.originalType() : firstEvidence.type(), state),
            conflict.variable().typeVariable().getSimpleName());
    if (reportedLowerConflicts.add(conflict.variable())) {
      Tree site = second != null ? second.expression() : conflict.variable().site();
      VisitorState reportingState = stateForInferenceDiagnostic(site, state);
      ErrorMessage error =
          new ErrorMessage(ErrorMessage.MessageTypes.PASS_NULLABLE_GENERIC, message);
      state.reportMatch(
          analysis
              .getErrorBuilder()
              .createErrorDescription(
                  error, analysis.buildDescription(site), reportingState, null));
    }
  }

  /** Reads unavailable variable positions from a completed outcome, independently of reporting. */
  private boolean isOnlyFailedVariable(Type type, Element variable) {
    return type instanceof Type.TypeVar
        && type.asElement().equals(variable)
        && type.getAnnotationMirrors().isEmpty();
  }

  /**
   * Determines result availability from conflict facts and the exact signature used to infer it.
   */
  private boolean hasUnavailableLowerResult(ExpressionTree expression) {
    Tree site = ASTHelpers.stripParentheses(expression);
    InferencePartial outcome = diagnosticInferenceOutcome(site);
    if (outcome == null) {
      return false;
    }
    InferenceCall call = outcome.calls.get(site);
    return call != null
        && outcome.solution.lowerConflicts().stream()
            .anyMatch(
                conflict ->
                    conflict.variable().site().equals(site)
                        && usesDirectConflictDiagnostic(conflict)
                        && isOnlyFailedVariable(
                            call.resultTemplate, conflict.variable().typeVariable()));
  }

  /**
   * Keeps fixed annotations, array dimensions, and unrelated parameters independently checkable.
   */
  private boolean hasUnavailableLowerArgument(Tree site, ExpressionTree argument) {
    InferencePartial outcome = diagnosticInferenceOutcome(site);
    if (outcome == null) {
      return false;
    }
    InferenceCall call = outcome.calls.get(site);
    if (call == null) {
      return false;
    }
    AtomicReference<Boolean> unavailable = new AtomicReference<>(false);
    new InvocationArguments(site, call.signature)
        .forEach(
            (actual, position, formal, unused) -> {
              if (ASTHelpers.stripParentheses(actual)
                  .equals(ASTHelpers.stripParentheses(argument))) {
                unavailable.set(
                    outcome.solution.lowerConflicts().stream()
                        .anyMatch(
                            conflict ->
                                conflict.variable().site().equals(site)
                                    && usesDirectConflictDiagnostic(conflict)
                                    && isOnlyFailedVariable(
                                        formal, conflict.variable().typeVariable())));
              }
            });
    return unavailable.get();
  }

  /** Recovers the diagnostic site's real ancestors, including local-variable suppressions. */
  private VisitorState stateForInferenceDiagnostic(Tree site, VisitorState state) {
    TreePath sitePath = TreePath.getPath(state.getPath().getCompilationUnit(), site);
    return state.withPath(sitePath != null ? sitePath : pathWithLeaf(state.getPath(), site));
  }

  /** Invalidates completed reference results only when a publishable inference problem fails. */
  private void invalidateMethodReferenceResults(InferenceCacheState inferenceCacheState) {
    for (MemberReferenceTree reference : inferenceCacheState.methodReferences) {
      inferredResultsForGenericMethodReferences.remove(reference);
      inferredPolyExpressionTypes.remove(reference);
    }
  }

  /** Publishes a functional target only when every inferred variable it uses is certified. */
  private void storeCertifiedPolyArgumentTypes(
      Tree call, Type.MethodType executableType, InferencePartial partial, VisitorState state) {
    Set<Element> uncertified = new LinkedHashSet<>();
    partial
        .solution()
        .incompleteVariables()
        .forEach(
            variable -> {
              if (variable.site().equals(call)) {
                uncertified.add(variable.typeVariable());
              }
            });
    Map<Element, Type> inferred = partial.inferredTypesForSite(call);
    new InvocationArguments(call, executableType)
        .forEach(
            (argument, argPos, formalParamType, unused) -> {
              if (!(argument instanceof LambdaExpressionTree
                  || argument instanceof MemberReferenceTree)) {
                return;
              }
              for (Element variable : uncertified) {
                TypeVarWithSymbolCollector occurrences = new TypeVarWithSymbolCollector(variable);
                formalParamType.accept(occurrences, null);
                if (!occurrences.getMatches().isEmpty()) {
                  return;
                }
              }
              Type attributed = ASTHelpers.getType(argument);
              if (attributed != null) {
                Type groundTarget =
                    GenericsUtils.groundTargetType(formalParamType, state, config, handler);
                Type completedTarget =
                    TypeSubstitutionUtils.updateTypeWithInferredNullability(
                        attributed, groundTarget, inferred, state, config);
                if (!containsPendingInferenceVariables(completedTarget, new IdentityHashMap<>())) {
                  inferredPolyExpressionTypes.put(argument, completedTarget);
                }
              }
            });
  }

  /** Rejects solver-private fresh symbols in a target while retaining genuine fixed variables. */
  private boolean containsPendingInferenceVariables(
      Type type, IdentityHashMap<Type, Boolean> visiting) {
    if (visiting.put(type, Boolean.TRUE) != null) {
      return false;
    }
    try {
      if (type instanceof Type.CapturedType capture) {
        return containsPendingInferenceVariables(capture.getUpperBound(), visiting)
            || containsPendingInferenceVariables(capture.wildcard, visiting);
      }
      if (type instanceof Type.TypeVar variable) {
        Symbol owner = variable.tsym.owner;
        if (owner instanceof Symbol.MethodSymbol method) {
          return !method.getTypeParameters().contains(variable.tsym);
        }
        if (owner instanceof Symbol.ClassSymbol clazz) {
          return !clazz.getTypeParameters().contains(variable.tsym);
        }
        return false;
      }
      if (type instanceof Type.ArrayType array) {
        return containsPendingInferenceVariables(array.elemtype, visiting);
      }
      if (type instanceof Type.WildcardType wildcard) {
        return wildcard.type != null && containsPendingInferenceVariables(wildcard.type, visiting);
      }
      if (type instanceof Type.ClassType clazz) {
        if (clazz instanceof Type.IntersectionClassType intersection) {
          for (TypeMirror bound : intersection.getBounds()) {
            if (containsPendingInferenceVariables((Type) bound, visiting)) {
              return true;
            }
          }
        }
        for (Type argument : clazz.getTypeArguments()) {
          if (containsPendingInferenceVariables(argument, visiting)) {
            return true;
          }
        }
        return containsPendingInferenceVariables(clazz.getEnclosingType(), visiting);
      }
      return false;
    } finally {
      visiting.remove(type);
    }
  }

  /**
   * Stores the types of lambda and method reference arguments of a generic call in {@link
   * #inferredPolyExpressionTypes}, applying the types inferred for the call's type variables to the
   * javac types of the arguments.
   *
   * @param call the generic method invocation or diamond constructor call
   * @param executableType the annotated signature of {@code call}
   * @param inferredTypes the types inferred for the type variables of {@code call}
   * @param state the visitor state
   */
  private void storeInferredPolyArgumentTypes(
      Tree call,
      Type.MethodType executableType,
      Map<Element, Type> inferredTypes,
      VisitorState state) {
    new InvocationArguments(call, executableType)
        .forEach(
            (argument, argPos, formalParamType, unused) -> {
              if (argument instanceof LambdaExpressionTree
                  || argument instanceof MemberReferenceTree) {
                Type polyExprTreeType = ASTHelpers.getType(argument);
                if (polyExprTreeType != null) {
                  Type formalParamGroundTargetType =
                      GenericsUtils.groundTargetType(formalParamType, state, config, handler);
                  Type typeWithInferredNullability =
                      TypeSubstitutionUtils.updateTypeWithInferredNullability(
                          polyExprTreeType,
                          formalParamGroundTargetType,
                          inferredTypes,
                          state,
                          config);
                  inferredPolyExpressionTypes.put(argument, typeWithInferredNullability);
                }
              }
            });
  }

  private String inferenceFailureMessage(UnsatisfiableConstraintsException e) {
    if (e.isCausedByNonNullUpperBound()) {
      return String.format(
          "inference failure: type variable %s is constrained to be @Nullable, but its upper bound requires it to be @NonNull",
          e.getTypeVariable());
    }
    return String.format(
        "inference failure: type variable %s constrained to be both @NonNull and @Nullable",
        e.getTypeVariable());
  }

  /** Returns the type parameters whose nullability is inferred for {@code callTree}. */
  private List<Symbol.TypeVariableSymbol> getCallTypeParameters(ExpressionTree callTree) {
    return callSite(callTree).variables;
  }

  /** Returns variables whose owning declaration is in a nullness-marked context. */
  private Set<Element> variablesWithNullnessMarkedBounds(
      List<? extends Element> typeVariables, VisitorState state) {
    Set<Element> result = new LinkedHashSet<>();
    for (Element typeVariable : typeVariables) {
      Symbol owner = ((Symbol) typeVariable).owner;
      if (!CodeAnnotationInfo.instance(state.context).isSymbolUnannotated(owner, config, handler)) {
        result.add(typeVariable);
      }
    }
    return result;
  }

  /**
   * Recovers Java type shapes from javac attribution independently of NullAway's annotated
   * evidence. Diamond class variables also occur in the constructed type, even when absent from
   * parameters.
   */
  private Map<Element, Type> getJavacInstantiationsForCall(
      ExpressionTree call,
      Type.MethodType declaration,
      List<Symbol.TypeVariableSymbol> variables,
      VisitorState state) {
    Map<Element, Type> result = callSite(call).javacInstantiations(declaration, state);
    Map<Element, Type> instantiatedBounds = getInstantiatedUpperBounds(declaration, variables);
    for (Symbol.TypeVariableSymbol variable : variables) {
      if (result.containsKey(variable)) {
        continue;
      }
      if (InferenceTypeShapes.containsVariable(declaration, variable, state, config)) {
        continue;
      }
      Type bound =
          instantiatedBounds.getOrDefault(variable, ((Type.TypeVar) variable.type).getUpperBound());
      Type substitutedBound =
          TypeSubstitutionUtils.substituteTypeVariables(bound, result, state.getTypes(), config);
      boolean unresolved = false;
      for (Symbol.TypeVariableSymbol peer : variables) {
        TypeVarWithSymbolCollector references = new TypeVarWithSymbolCollector(peer);
        substitutedBound.accept(references, null);
        unresolved |= !references.getMatches().isEmpty();
      }
      if (!unresolved && !(substitutedBound instanceof Type.TypeVar)) {
        // A variable absent from the executable signature cannot affect observable call types.
        // Its non-recursive bound is a valid representative instantiation; root defaults remain
        // the solver's responsibility. Recursive or unresolved bounds remain explicitly incomplete.
        result.put(variable, substitutedBound);
      }
    }
    return result;
  }

  /**
   * Finds receiver/class-substituted upper bounds for inferred variables occurring in an executable
   * type. Variables absent from the executable type use their declaration bounds in the solver.
   */
  private Map<Element, Type> getInstantiatedUpperBounds(
      Type.MethodType executableType, List<Symbol.TypeVariableSymbol> typeVariables) {
    Map<Element, Type> result = new LinkedHashMap<>();
    for (Symbol.TypeVariableSymbol typeVariable : typeVariables) {
      TypeVarWithSymbolCollector collector = new TypeVarWithSymbolCollector(typeVariable);
      executableType.accept(collector, null);
      if (!collector.getMatches().isEmpty()) {
        Type.TypeVar instantiatedUse = collector.getMatches().iterator().next();
        Type declaredUpperBound = ((Type.TypeVar) typeVariable.type).getUpperBound();
        Type instantiatedUpperBound =
            TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
                declaredUpperBound, instantiatedUse.getUpperBound(), config);
        result.put(typeVariable, instantiatedUpperBound);
      }
    }
    return result;
  }

  /**
   * Resolves the shared call description for inference, then applies handler-provided models.
   *
   * <p>Type variables whose values are fixed by a method invocation's receiver are substituted. For
   * example, for a method returning class type variable {@code T} (for class {@code Foo<T>}) on a
   * receiver of type {@code Foo<@Nullable Object>}, the return type is {@code @Nullable Object}.
   *
   * <p>Type variables being inferred for this call remain unsubstituted so constraints can be
   * generated for them. These are method type variables for a generic method invocation, and class
   * and constructor type variables for a diamond constructor. Without a diamond, only omitted
   * constructor type variables are inferred, while class arguments remain fixed. Unlike {@link
   * #getInvokedMethodTypeAtCall}, this method does not resolve those variables using an
   * already-computed inference result.
   */
  private InferenceCall getCallForInference(
      ExpressionTree callTree,
      @Nullable TreePath path,
      VisitorState state,
      boolean calledFromDataflow) {
    InferenceCall call = callSite(callTree).resolve(path, state, calledFromDataflow);
    return call.withSignature(
        handler.onOverrideMethodType(
            call.site.symbol,
            call.signature,
            state,
            callTree instanceof MethodInvocationTree invocationTree ? invocationTree : null));
  }

  /** Resolves an anonymous allocation's synthetic constructor to its forwarding declaration. */
  private static Symbol.MethodSymbol resolveConstructorSymbol(NewClassTree construction) {
    Symbol.MethodSymbol symbol =
        (Symbol.MethodSymbol) castToNonNull(ASTHelpers.getSymbol(construction));
    if (construction.getClassBody() != null) {
      for (Tree member : construction.getClassBody().getMembers()) {
        if (member instanceof MethodTree constructor
            && symbol.equals(ASTHelpers.getSymbol(constructor))
            && constructor.getBody() != null) {
          for (Tree statement : constructor.getBody().getStatements()) {
            if (statement instanceof ExpressionStatementTree expressionStatement
                && expressionStatement.getExpression() instanceof MethodInvocationTree invocation) {
              Symbol.MethodSymbol invoked = ASTHelpers.getSymbol(invocation);
              if (invoked != null && invoked.isConstructor()) {
                return invoked;
              }
            }
          }
        }
      }
    }
    return symbol;
  }

  /**
   * Generates inference constraints for a generic call, including nested generic method calls and
   * diamond constructor calls.
   *
   * @param state the visitor state
   * @param path the tree path to the call tree if available and possibly distinct from {@code
   *     state.getPath()}
   * @param typeFromAssignmentContext the type being "assigned to" in the assignment context of the
   *     call, or {@code null} if the type is unavailable or the call result is not assigned
   *     anywhere
   * @param assignedToLocal whether the call result is assigned to a local variable
   * @param solver the constraint solver
   * @param inferenceCall the annotated signature and result template resolved for this context
   * @param inferenceCacheState tracks whether results from this inference problem can be persisted
   * @param calledFromDataflow whether this method is being called from dataflow analysis
   * @return the call's symbolic result type in this solver
   * @throws UnsatisfiableConstraintsException if the constraints are determined to be unsatisfiable
   */
  private Type generateConstraintsForCall(
      VisitorState state,
      @Nullable TreePath path,
      @Nullable Type typeFromAssignmentContext,
      boolean assignedToLocal,
      ConstraintSolver solver,
      InferenceCall inferenceCall,
      InferenceCacheState inferenceCacheState,
      Tree resultOwner,
      boolean calledFromDataflow)
      throws UnsatisfiableConstraintsException {
    ExpressionTree callTree = inferenceCall.site.tree;
    Type.MethodType methodType = inferenceCall.signature;
    inferenceCacheState.calls.put(callTree, inferenceCall);
    // Register all type variables whose nullability is inferred for this call, and use the
    // call-specific inference variables returned by the solver in place of the declared type
    // variables. This keeps the constraints for different calls to the same generic method
    // separate; see https://github.com/uber/NullAway/issues/1291
    List<Symbol.TypeVariableSymbol> callTypeParameters = getCallTypeParameters(callTree);
    Set<Element> variablesWithNullnessMarkedBounds =
        variablesWithNullnessMarkedBounds(callTypeParameters, state);
    Map<Element, Type> instantiatedUpperBounds =
        getInstantiatedUpperBounds(methodType, callTypeParameters);
    instantiatedUpperBounds.keySet().retainAll(variablesWithNullnessMarkedBounds);
    Map<Element, Type.TypeVar> inferenceVariables =
        solver.registerInferenceVariables(
            callTree,
            callTypeParameters,
            instantiatedUpperBounds,
            variablesWithNullnessMarkedBounds,
            getJavacInstantiationsForCall(callTree, methodType, callTypeParameters, state));
    Map<Tree, InferenceCall> callTypesForPolyArgs = callTypesForPolyArguments;
    boolean usesProvisionalLambdaTypes =
        !lambdaParameterTypesForInference.isEmpty() || !lambdaTargetTypesForInference.isEmpty();
    inferenceCacheState.recordCall(callTree, usesProvisionalLambdaTypes);
    if (!usesProvisionalLambdaTypes && callTypesForPolyArgs != null) {
      callTypesForPolyArgs.put(callTree, inferenceCall);
    }
    Type.MethodType methodTypeForSite =
        (Type.MethodType)
            TypeSubstitutionUtils.substituteTypeVariables(
                methodType, inferenceVariables, state.getTypes(), config);
    // first, handle the call result flow
    Type callResultType =
        TypeSubstitutionUtils.substituteTypeVariables(
            inferenceCall.resultTemplate, inferenceVariables, state.getTypes(), config);
    if (typeFromAssignmentContext != null) {
      solver.addSubtypeConstraint(
          callResultType,
          typeFromAssignmentContext,
          assignedToLocal,
          new SourceOrigin(callTree, resultOwner, inferenceCall.site.attributedResult));
    }
    // then, handle parameters
    TreePath pathToCall = path != null ? path : pathWithLeaf(state.getPath(), callTree);
    new InvocationArguments(callTree, methodTypeForSite)
        .forEach(
            (argument, argPos, formalParamType, unused) -> {
              TreePath pathToArgument = new TreePath(pathToCall, argument);
              generateConstraintsForPseudoAssignment(
                  state.withPath(pathToArgument),
                  solver,
                  inferenceCacheState,
                  argument,
                  formalParamType,
                  callTree,
                  calledFromDataflow);
            });
    return callResultType;
  }

  /**
   * In the context of generic method inference, generate constraints for a pseudo-assignment
   * (parameter passing or return).
   *
   * @param state the visitor state
   * @param solver the constraint solver
   * @param inferenceCacheState tracks whether results from this inference problem can be persisted
   * @param rhsExpr the right-hand side expression of the pseudo-assignment
   * @param lhsType the left-hand side type of the pseudo-assignment
   * @param calledFromDataflow whether this method is being called from dataflow analysis
   */
  private void generateConstraintsForPseudoAssignment(
      VisitorState state,
      ConstraintSolver solver,
      InferenceCacheState inferenceCacheState,
      ExpressionTree rhsExpr,
      Type lhsType,
      Tree ownerSite,
      boolean calledFromDataflow) {
    NullabilityUtil.ExprTreeAndState exprTreeAndState =
        NullabilityUtil.stripParensAndUpdateTreePath(rhsExpr, state);
    rhsExpr = exprTreeAndState.expr();
    state = exprTreeAndState.state();
    // if the parameter is itself a generic call requiring inference, generate constraints for
    // that call
    if (isCallNeedingInference(rhsExpr)) {
      generateConstraintsForCall(
          state,
          state.getPath(),
          lhsType,
          false,
          solver,
          getCallForInference(rhsExpr, state.getPath(), state, calledFromDataflow),
          inferenceCacheState,
          ownerSite,
          calledFromDataflow);
    } else if (rhsExpr instanceof ConditionalExpressionTree conditionalExpressionTree) {
      // generate constraints for both the true and false sub-expressions of the conditional
      // expression
      ExpressionTree trueExpression = conditionalExpressionTree.getTrueExpression();
      TreePath pathToTrueExpression = new TreePath(state.getPath(), trueExpression);
      generateConstraintsForPseudoAssignment(
          state.withPath(pathToTrueExpression),
          solver,
          inferenceCacheState,
          trueExpression,
          lhsType,
          ownerSite,
          calledFromDataflow);
      ExpressionTree falseExpression = conditionalExpressionTree.getFalseExpression();
      TreePath pathToFalseExpression = new TreePath(state.getPath(), falseExpression);
      generateConstraintsForPseudoAssignment(
          state.withPath(pathToFalseExpression),
          solver,
          inferenceCacheState,
          falseExpression,
          lhsType,
          ownerSite,
          calledFromDataflow);
    } else if (rhsExpr instanceof LambdaExpressionTree lambda) {
      handleLambdaInGenericMethodInference(
          state,
          state.getPath(),
          solver,
          inferenceCacheState,
          lhsType,
          lambda,
          ownerSite,
          calledFromDataflow);
    } else if (rhsExpr instanceof MemberReferenceTree memberReferenceTree) {
      handleMethodRefInGenericMethodInference(
          state, solver, inferenceCacheState, lhsType, memberReferenceTree, ownerSite);
    } else { // all other cases
      Type argumentType = getTreeType(rhsExpr, state, calledFromDataflow);
      if (argumentType == null) {
        // no type to constrain with; getTreeType returns null for a raw non-array type and for
        // cases it does not handle
        return;
      }
      argumentType = refineArgumentTypeWithDataflow(argumentType, rhsExpr, state, state.getPath());
      solver.addSubtypeConstraint(
          argumentType, lhsType, false, new SourceOrigin(rhsExpr, ownerSite, argumentType));
    }
  }

  /**
   * Generate constraints for any return expression inside lambda argument. If the return expression
   * is a method invocation then recursively call generateConstraintsForCall
   *
   * <p>Implicit parameter types come from the ground target's descriptor while generating body
   * constraints. Keep them separate from cached inference results, and restore the enclosing
   * parameter context on both success and failure.
   *
   * @param state the visitor state
   * @param path the tree path to the enclosing call if available and possibly distinct from {@code
   *     state.getPath()}
   * @param solver the constraint solver
   * @param inferenceCacheState tracks whether results from this inference problem can be persisted
   * @param lhsType the type to which the lambda is being assigned
   * @param lambda The lambda argument
   * @param calledFromDataflow whether this method is being called from dataflow analysis
   */
  @SuppressWarnings("ReferenceEquality") // solver identity defines inference ownership
  private void handleLambdaInGenericMethodInference(
      VisitorState state,
      @Nullable TreePath path,
      ConstraintSolver solver,
      InferenceCacheState inferenceCacheState,
      Type lhsType,
      LambdaExpressionTree lambda,
      Tree ownerSite,
      boolean calledFromDataflow) {
    Symbol.MethodSymbol fiMethod =
        NullabilityUtil.getFunctionalInterfaceMethod(lambda, state.getTypes());

    Type groundTargetType = GenericsUtils.groundTargetType(lhsType, state, config, handler);
    // get the return type of the functional interface method, viewed as a member of the lhs
    // type, so the generic method's type variables are substituted in
    Type.MethodType fiMethodTypeAsMember =
        TypeSubstitutionUtils.memberType(state.getTypes(), groundTargetType, fiMethod, config)
            .asMethodType();
    Type fiReturnType = fiMethodTypeAsMember.getReturnType();
    // save the previous lambdaParameterTypesForInference map so we can restore it after handling
    // the lambda body
    Map<Symbol, Type> previousParameterTypes = lambdaParameterTypesForInference;
    Map<LambdaExpressionTree, Type> previousTargetTypes = lambdaTargetTypesForInference;
    LambdaVarLocalInference previousVarLocals = lambdaVarLocalInference;
    Map<Symbol, Type> localTypes =
        previousVarLocals != null && previousVarLocals.solver() == solver
            ? new LinkedHashMap<>(previousVarLocals.types())
            : new LinkedHashMap<>();
    Map<Symbol, TreePath> localDeclarations =
        previousVarLocals != null && previousVarLocals.solver() == solver
            ? new LinkedHashMap<>(previousVarLocals.declarations())
            : new LinkedHashMap<>();
    lambdaVarLocalInference =
        new LambdaVarLocalInference(
            solver, inferenceCacheState, localTypes, localDeclarations, calledFromDataflow);
    Map<LambdaExpressionTree, Type> targetTypes = new LinkedHashMap<>(previousTargetTypes);
    targetTypes.put(lambda, groundTargetType);
    lambdaTargetTypesForInference = targetTypes;
    try {
      if (((JCTree.JCLambda) lambda).paramKind == JCTree.JCLambda.ParameterKind.IMPLICIT) {
        // If we have implicitly-typed lambda parameters, update the
        // lambdaParameterTypesForInference map to have entries for these parameters, with types
        // based on the parameter types in fiMethodTypeAsMember
        Map<Symbol, Type> parameterTypes = new LinkedHashMap<>(previousParameterTypes);
        var params = lambda.getParameters();
        var fiParameterTypes = fiMethodTypeAsMember.getParameterTypes();
        for (int i = 0; i < params.size(); i++) {
          parameterTypes.put(
              castToNonNull(ASTHelpers.getSymbol(params.get(i))), fiParameterTypes.get(i));
        }
        lambdaParameterTypesForInference = parameterTypes;
      }
      Tree body = lambda.getBody();
      // Ensure our current TreePath has the lambda as the leaf, in case dataflow analysis needs to
      // be run within it.
      TreePath lambdaPath = pathWithLeaf(path != null ? path : state.getPath(), lambda);
      if (body instanceof ExpressionTree returnedExpression) {
        // Case 1: Expression body, e.g., () -> null
        TreePath returnedExpressionPath = new TreePath(lambdaPath, returnedExpression);
        if (fiReturnType.hasTag(TypeTag.VOID)) {
          // Infer statement-call arguments in their owning solver without a return constraint.
          if (isCallNeedingInference(returnedExpression)) {
            generateConstraintsForCall(
                state.withPath(returnedExpressionPath),
                returnedExpressionPath,
                null,
                false,
                solver,
                getCallForInference(
                    returnedExpression, returnedExpressionPath, state, calledFromDataflow),
                inferenceCacheState,
                ownerSite,
                calledFromDataflow);
          }
        } else {
          generateConstraintsForPseudoAssignment(
              state.withPath(returnedExpressionPath),
              solver,
              inferenceCacheState,
              returnedExpression,
              fiReturnType,
              ownerSite,
              calledFromDataflow);
        }
      } else if (body instanceof BlockTree) {
        // Case 2: Block body, e.g., () -> { return null; }
        TreePath bodyPath = new TreePath(lambdaPath, body);
        ReturnAndVarLocalFinder finder = new ReturnAndVarLocalFinder(this);
        finder.scan(bodyPath, null);
        for (TreePath declarationPath : finder.varLocalPaths) {
          localDeclarations.put(
              castToNonNull(ASTHelpers.getSymbol(declarationPath.getLeaf())), declarationPath);
        }
        for (TreePath returnPath : finder.getReturnPaths()) {
          ReturnTree returnTree = (ReturnTree) returnPath.getLeaf();
          ExpressionTree returnExpr = returnTree.getExpression();
          if (returnExpr == null || fiReturnType.hasTag(TypeTag.VOID)) {
            continue;
          }
          TreePath returnExprPath = new TreePath(returnPath, returnExpr);
          generateConstraintsForPseudoAssignment(
              state.withPath(returnExprPath),
              solver,
              inferenceCacheState,
              returnExpr,
              fiReturnType,
              ownerSite,
              calledFromDataflow);
        }
      }
    } finally {
      // Restore even if constraint generation fails or re-enters inference for a nested lambda.
      lambdaParameterTypesForInference = previousParameterTypes;
      lambdaTargetTypesForInference = previousTargetTypes;
      lambdaVarLocalInference = previousVarLocals;
    }
  }

  /**
   * Resolves a block-lambda var initializer on demand in the solver owning the local read.
   *
   * <p>Generic initializers retain symbolic result types, keeping dependencies on provisional
   * lambda parameters in the same constraint graph. Seed the declaration type for reentrant
   * dataflow reads; the completed symbolic type remains lambda-scoped and is never published.
   * Scalar nullness is refined at each use rather than taken from the initializer.
   */
  private @Nullable Type getSymbolicLambdaVarLocalType(
      Symbol symbol,
      TreePath declarationPath,
      VisitorState state,
      LambdaVarLocalInference context) {
    context.types().put(symbol, symbol.type);
    VariableTree declaration = (VariableTree) declarationPath.getLeaf();
    ExpressionTree initializer = castToNonNull(declaration.getInitializer());
    TreePath initializerPath = new TreePath(declarationPath, initializer);
    NullabilityUtil.ExprTreeAndState initializerAndState =
        NullabilityUtil.stripParensAndUpdateTreePath(initializer, state.withPath(initializerPath));
    initializer = initializerAndState.expr();
    VisitorState initializerState = initializerAndState.state();
    Type initializerType =
        isCallNeedingInference(initializer)
            ? generateConstraintsForCall(
                initializerState,
                initializerState.getPath(),
                null,
                true,
                context.solver(),
                getCallForInference(
                    initializer,
                    initializerState.getPath(),
                    initializerState,
                    context.calledFromDataflow()),
                context.cacheState(),
                declarationPath.getLeaf(),
                context.calledFromDataflow())
            : getTreeType(initializer, initializerState, context.calledFromDataflow());

    if (initializerType != null) {
      context.types().put(symbol, initializerType);
    } else {
      context.types().remove(symbol);
    }
    return initializerType;
  }

  /** Recovers a generic reference's Java instantiations from its attributed functional target. */
  private Map<Element, Type> getJavacInstantiationsForReference(
      MemberReferenceTree reference, Symbol.MethodSymbol method, VisitorState state) {
    Map<Element, Type> result = new LinkedHashMap<>();
    Set<Element> conflicting = new LinkedHashSet<>();
    Type attributedTarget = ASTHelpers.getType(reference);
    if (attributedTarget == null || attributedTarget.isRaw()) {
      return result;
    }
    Type groundTarget = GenericsUtils.groundTargetType(attributedTarget, state, config, handler);
    GenericsUtils.processMethodRefTypeRelations(
        this,
        groundTarget,
        reference,
        state,
        (subtype, supertype, relationKind) -> {
          boolean returned = relationKind == MethodRefTypeRelationKind.RETURN;
          Map<Element, Type> relationShapes =
              InferenceTypeShapes.inferInstantiations(
                  returned ? subtype : supertype,
                  returned ? supertype : subtype,
                  method.getTypeParameters(),
                  state,
                  config);
          relationShapes.forEach(
              (variable, shape) -> {
                Type previous = result.get(variable);
                if (previous != null && !state.getTypes().isSameType(previous, shape)) {
                  conflicting.add(variable);
                } else {
                  result.put(variable, shape);
                }
              });
        });
    conflicting.forEach(result::remove);
    return result;
  }

  /**
   * Generate constraints for a method reference argument by comparing functional interface method
   * parameter and return types against the referenced method.
   *
   * @param state the visitor state
   * @param solver the constraint solver
   * @param inferenceCacheState cache and method-reference sites for the enclosing inference problem
   * @param lhsType the type to which the method reference is being assigned
   * @param memberReferenceTree the method reference argument
   * @param ownerSite the call receiving the reference, or the independently inferred reference
   */
  private void handleMethodRefInGenericMethodInference(
      VisitorState state,
      ConstraintSolver solver,
      InferenceCacheState inferenceCacheState,
      Type lhsType,
      MemberReferenceTree memberReferenceTree,
      Tree ownerSite) {
    inferenceCacheState.methodReferences.add(memberReferenceTree);
    // if we have a reference to a generic method, and the call site does not pass explicit type
    // arguments, register the referenced method's type variables as inference variables
    Symbol.MethodSymbol referencedMethod = ASTHelpers.getSymbol(memberReferenceTree);
    List<? extends ExpressionTree> explicitTypeArguments = memberReferenceTree.getTypeArguments();
    Type groundTargetType = GenericsUtils.groundTargetType(lhsType, state, config, handler);
    Map<Element, Type.TypeVar> inferenceVariables = Map.of();
    if (referencedMethod != null
        && !referencedMethod.getTypeParameters().isEmpty()
        && (explicitTypeArguments == null || explicitTypeArguments.isEmpty())) {
      ResolvedMethodReference resolvedReference =
          resolveMemberReference(memberReferenceTree, referencedMethod, groundTargetType, state);
      Set<Element> variablesWithNullnessMarkedBounds =
          variablesWithNullnessMarkedBounds(referencedMethod.getTypeParameters(), state);
      Map<Element, Type> instantiatedUpperBounds =
          resolvedReference != null
              ? getInstantiatedUpperBounds(
                  resolvedReference.methodType(), referencedMethod.getTypeParameters())
              : new LinkedHashMap<>();
      instantiatedUpperBounds.keySet().retainAll(variablesWithNullnessMarkedBounds);
      inferenceVariables =
          solver.registerInferenceVariables(
              memberReferenceTree,
              referencedMethod.getTypeParameters(),
              instantiatedUpperBounds,
              variablesWithNullnessMarkedBounds,
              getJavacInstantiationsForReference(memberReferenceTree, referencedMethod, state));
    }
    Map<Element, Type.TypeVar> referenceInferenceVariables = inferenceVariables;
    GenericsUtils.processMethodRefTypeRelations(
        this,
        groundTargetType,
        memberReferenceTree,
        state,
        (subtype, supertype, relationKind) -> {
          // Use the reference-specific inference variables in place of the referenced method's
          // declared type variables. Only the side of the relation coming from the referenced
          // method can mention those type variables as inference variables; the other side comes
          // from the functional interface type, which may mention the same type variables as
          // fixed types (e.g., in a recursive reference to an enclosing generic method).
          boolean referencedMethodTypeIsSubtype = relationKind == MethodRefTypeRelationKind.RETURN;
          Type subtypeForReference =
              referencedMethodTypeIsSubtype
                  ? TypeSubstitutionUtils.substituteTypeVariables(
                      subtype, referenceInferenceVariables, state.getTypes(), config)
                  : subtype;
          Type supertypeForReference =
              referencedMethodTypeIsSubtype
                  ? supertype
                  : TypeSubstitutionUtils.substituteTypeVariables(
                      supertype, referenceInferenceVariables, state.getTypes(), config);
          solver.addSubtypeConstraint(
              subtypeForReference,
              supertypeForReference,
              false,
              new SourceOrigin(
                  memberReferenceTree,
                  ownerSite,
                  referencedMethodTypeIsSubtype ? subtype : supertype));
        });
  }

  /**
   * Resolves a method reference's method and qualifier types using its functional-interface target.
   *
   * <p>For an unbound reference to an instance method in a generic class, javac leaves the
   * qualifier as the generic declaration type. The functional-interface receiver supplies the type
   * arguments needed to instantiate that qualifier and, in turn, the referenced method type.
   *
   * @param memberReferenceTree the method reference tree
   * @param referencedMethod the symbol for the referenced method
   * @param targetType the functional-interface target type
   * @param state visitor state whose current path ends at {@code memberReferenceTree}
   * @return the resolved types, or {@code null} if the method reference cannot be resolved
   */
  public @Nullable ResolvedMethodReference resolveMemberReference(
      MemberReferenceTree memberReferenceTree,
      Symbol.MethodSymbol referencedMethod,
      Type targetType,
      VisitorState state) {
    if (!config.isJSpecifyMode() || targetType.isRaw() || referencedMethod.isConstructor()) {
      // TODO handle constructor references like Foo::new;
      //  https://github.com/uber/NullAway/issues/1468
      return null;
    }
    Type groundTargetType = GenericsUtils.groundTargetType(targetType, state, config, handler);
    Type qualifierType = null;
    if (!referencedMethod.isStatic()) {
      ExpressionTree qualifierExpression = memberReferenceTree.getQualifierExpression();
      qualifierType =
          getTreeType(
              qualifierExpression,
              state.withPath(new TreePath(state.getPath(), qualifierExpression)));
      boolean unbound = ((JCTree.JCMemberReference) memberReferenceTree).kind.isUnbound();
      if (unbound) {
        if (qualifierType == null || qualifierType.isRaw()) {
          // javac attributes an annotated bare qualifier such as @A Box as raw. Use the generic
          // declaration as the substitution template; the receiver type supplies its arguments.
          qualifierType = referencedMethod.owner.type;
        }
        if (qualifierType instanceof Type.ClassType qualifierClassType) {
          Symbol.MethodSymbol fiMethod =
              NullabilityUtil.getFunctionalInterfaceMethod(memberReferenceTree, state.getTypes());
          Type.MethodType fiMethodTypeAsMember =
              TypeSubstitutionUtils.memberType(state.getTypes(), groundTargetType, fiMethod, config)
                  .asMethodType();
          com.sun.tools.javac.util.List<Type> fiParamTypes =
              fiMethodTypeAsMember.getParameterTypes();
          Verify.verify(
              !fiParamTypes.isEmpty(),
              "Expected receiver parameter for unbound method ref %s",
              memberReferenceTree);
          qualifierType =
              GenericsUtils.instantiateUnboundQualifierType(
                  qualifierClassType, fiParamTypes.get(0), state.getTypes(), config);
        }
      }
    }
    Type.MethodType methodType =
        getMemberReferenceMethodType(memberReferenceTree, referencedMethod, qualifierType, state);
    return methodType == null ? null : new ResolvedMethodReference(methodType, qualifierType);
  }

  /**
   * Gets the method type for a member reference handling generics, in JSpecify mode
   *
   * @param memberReferenceTree the member reference tree
   * @param overridingMethod the method symbol for the referenced method
   * @param qualifierExpressionType an adjusted type for the qualifier expression of the member
   *     reference ({@code Foo} in a reference {@code Foo::bar}). For an unbound reference to a
   *     generic instance method, javac compute the type of the qualifier expression as the generic
   *     declaration type. Callers can provide a type instantiated from the functional interface
   *     receiver, containing the appropriate type arguments.
   * @param state the visitor state
   * @return the method type for the member reference, with generics handled, or null if not in
   *     JSpecify mode
   */
  public Type.@Nullable MethodType getMemberReferenceMethodType(
      MemberReferenceTree memberReferenceTree,
      Symbol.MethodSymbol overridingMethod,
      @Nullable Type qualifierExpressionType,
      VisitorState state) {
    if (!config.isJSpecifyMode()) {
      return null;
    }
    Type.MethodType result = overridingMethod.asType().asMethodType();
    if (!overridingMethod.isStatic()) {
      // This handles any generic type parameters of the qualifier of the member reference, e.g. for
      // x::m, where x is of type Foo<Integer>, it handles the type parameter Integer whereever it
      // appears in the signature of m.
      Type qualifierType =
          qualifierExpressionType != null
              ? qualifierExpressionType
              : ASTHelpers.getType(memberReferenceTree.getQualifierExpression());
      if (qualifierType != null && !qualifierType.isRaw()) {
        result =
            TypeSubstitutionUtils.memberType(
                    state.getTypes(), qualifierType, overridingMethod, config)
                .asMethodType();
      }
    }
    List<? extends ExpressionTree> typeArgumentTrees = memberReferenceTree.getTypeArguments();
    if (typeArgumentTrees != null
        && !typeArgumentTrees.isEmpty()
        && overridingMethod.asType() instanceof Type.ForAll forAllType) {
      // handle explicit type arguments in method reference, e.g., x::<Foo>method
      result =
          TypeSubstitutionUtils.subst(
                  state.getTypes(),
                  result,
                  forAllType.tvars,
                  convertTreesToTypes(typeArgumentTrees),
                  config)
              .asMethodType();
    } else if (overridingMethod.asType() instanceof Type.ForAll) {
      // the referenced method is a generic method and there are no explicit type arguments
      // we need to substitute inferred nullability for type arguments if it was inferred
      result = getInferredMethodTypeForGenericMethodReference(result, state);
    }
    // finally, run any handlers
    return handler.onOverrideMethodType(overridingMethod, result, state, null);
  }

  /**
   * A visitor that scans a {@link TreePath} (typically to a lambda or method body) to find all
   * {@code return} statements and collect their paths.
   *
   * <p>This scanner is specifically designed to be "shallow." It will <b>not</b> descend into
   * nested lambdas, local classes, or anonymous classes, ensuring it only finds {@code return}
   * statements relevant to the *current* function body. It also registers var-declared locals so
   * their initializer types can be resolved when generating constraints for the return expressions.
   *
   * <p>Usage:
   *
   * <pre>{@code
   * Tree lambdaBody = myLambda.getBody();
   * TreePath lambdaBodyPath = new TreePath(lambdaPath, lambdaBody);
   * List<TreePath> returns =
   *     ReturnAndVarLocalFinder.findReturnPathsAndRegisterVarLocals(lambdaBodyPath, genericsChecks);
   * }</pre>
   */
  static class ReturnAndVarLocalFinder extends TreePathScanner<@Nullable Void, @Nullable Void> {

    private final List<TreePath> returnPaths = new ArrayList<>();
    private final List<TreePath> varLocalPaths = new ArrayList<>();
    private final GenericsChecks genericsChecks;

    ReturnAndVarLocalFinder(GenericsChecks genericsChecks) {
      this.genericsChecks = genericsChecks;
    }

    /**
     * Scans the given path, registers var-declared locals, and returns all found paths to return
     * statements with expressions.
     *
     * @param path The path to a tree (e.g., a lambda body) to scan.
     * @param genericsChecks the checker in which to register var-declared local declarations
     * @return A list of all paths to return statements with expressions found.
     */
    public static List<TreePath> findReturnPathsAndRegisterVarLocals(
        TreePath path, GenericsChecks genericsChecks) {
      ReturnAndVarLocalFinder finder = new ReturnAndVarLocalFinder(genericsChecks);
      finder.scan(path, null);
      return finder.getReturnPaths();
    }

    /**
     * Gets the list of return statement paths found by this visitor.
     *
     * @return The list of return statement paths.
     */
    public List<TreePath> getReturnPaths() {
      return returnPaths;
    }

    /** Makes var-local declarations available before generating constraints for their uses. */
    @Override
    public @Nullable Void visitVariable(VariableTree tree, @Nullable Void unused) {
      genericsChecks.registerVarLocalDeclaration(tree);
      if (isVarLocalVariableDeclaration(tree) && tree.getInitializer() != null) {
        varLocalPaths.add(getCurrentPath());
      }
      return super.visitVariable(tree, null);
    }

    @Override
    public @Nullable Void visitLambdaExpression(LambdaExpressionTree node, @Nullable Void p) {
      // Do not scan inside nested lambdas
      return null;
    }

    @Override
    public @Nullable Void visitClass(ClassTree node, @Nullable Void p) {
      // Do not scan inside nested (anonymous/local) classes
      return null;
    }

    @Override
    public @Nullable Void visitMethod(MethodTree node, @Nullable Void p) {
      // Do not scan inside methods of local classes
      return null;
    }

    @Override
    public @Nullable Void visitReturn(ReturnTree node, @Nullable Void p) {
      if (node.getExpression() != null) {
        returnPaths.add(getCurrentPath());
      }
      // We've processed this return, don't scan its children
      return null;
    }
  }

  /**
   * Refines the type of an expression using dataflow information, if available.
   *
   * @param exprType the original type of the expression
   * @param expr the expression tree
   * @param state the visitor state
   * @param path relevant tree path if available and possibly distinct from {@code state.getPath()}
   * @return the refined type of the expression
   */
  private Type refineArgumentTypeWithDataflow(
      Type exprType, ExpressionTree expr, VisitorState state, @Nullable TreePath path) {
    if (!shouldRunDataflowForExpression(exprType, expr)) {
      return exprType;
    }
    // We need a TreePath whose leaf is the expression, as the calls to `getNullness` /
    // `getNullnessFromRunning` below return the nullness of the leaf of the path.
    // Just appending expr to the current path is a bit sketchy, as it may not actually be the
    // valid tree path to the expression. However, all we need the path for (beyond the leaf) is to
    // discover the enclosing method/lambda/initializer, and for that purpose this should be
    // sufficient. pathWithLeaf avoids appending expr when the provided path is already at expr.
    TreePath exprPath = pathWithLeaf(path != null ? path : state.getPath(), expr);
    TreePath enclosingPath = NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(exprPath);
    if (enclosingPath == null) {
      return exprType;
    }
    // If the expression is inside a lambda, we need to ensure that the environment mapping for
    // the lambda is set up so that dataflow analysis can be run within the lambda body
    boolean enclosingIsLambda = enclosingPath.getLeaf() instanceof LambdaExpressionTree;
    if (enclosingIsLambda) {
      TreePath methodEnclosingLambda =
          NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(enclosingPath);
      if (methodEnclosingLambda == null) {
        return exprType;
      } else {
        updateEnvironmentMappingForLambda(state, methodEnclosingLambda, enclosingPath);
      }
    }
    Nullness refinedNullness;
    AccessPathNullnessAnalysis nullnessAnalysis = analysis.getNullnessAnalysis(state);
    if (nullnessAnalysis.isRunning(enclosingPath, state.context)) {
      // dataflow analysis is already running, so just get the current dataflow value for the
      // argument
      refinedNullness = nullnessAnalysis.getNullnessFromRunning(exprPath, state.context);
    } else {
      refinedNullness = nullnessAnalysis.getNullness(exprPath, state.context);
    }
    if (refinedNullness == null) {
      return exprType;
    }
    if (exprType instanceof Type.TypeVar
        && expr instanceof IdentifierTree
        && lambdaVarLocalInference != null
        && lambdaVarLocalInference.types().containsKey(ASTHelpers.getSymbol(expr))) {
      // A local read's scalar nullness must not constrain its initializer's symbolic root variable.
      // Keep structural ownership, but override root nullness with the dataflow value at this use.
      return TypeSubstitutionUtils.typeWithAnnot(
          exprType,
          NullabilityUtil.nullnessToBool(refinedNullness)
              ? getSyntheticNullableAnnotType(state)
              : getSyntheticNonNullAnnotType(state));
    }
    return updateTypeWithNullness(state, exprType, refinedNullness);
  }

  /**
   * Sets up the environment mapping for a lambda expression so that dataflow analysis can be run
   * within the lambda body, handling the case where dataflow analysis is already running on the
   * enclosing method.
   *
   * @param state the visitor state
   * @param enclosingForLambda the tree path to the enclosing method or initializer for the lambda
   * @param lambdaPath the tree path to the lambda expression
   */
  private void updateEnvironmentMappingForLambda(
      VisitorState state, TreePath enclosingForLambda, TreePath lambdaPath) {
    // if the enclosing method is itself a lambda, we need to recursively ensure its
    // environment mapping is set up first
    if (enclosingForLambda.getLeaf() instanceof LambdaExpressionTree) {
      TreePath nextEnclosing =
          NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(enclosingForLambda);
      if (nextEnclosing != null) {
        updateEnvironmentMappingForLambda(state, nextEnclosing, enclosingForLambda);
      }
    }
    AccessPathNullnessAnalysis nullnessAnalysis = analysis.getNullnessAnalysis(state);
    NullnessStore storeBeforeLambda;
    if (nullnessAnalysis.isRunning(enclosingForLambda, state.context)) {
      storeBeforeLambda =
          nullnessAnalysis.getNullnessInfoBeforeNestedMethodWithAnalysisRunning(
              lambdaPath, state, handler);
    } else {
      storeBeforeLambda =
          nullnessAnalysis.getNullnessInfoBeforeNestedMethodNode(lambdaPath, state, handler);
    }
    EnclosingEnvironmentNullness.instance(state.context)
        .addEnvironmentMapping(lambdaPath.getLeaf(), storeBeforeLambda);
  }

  /**
   * Checks if dataflow analysis should be run for the given expression to refine its nullability.
   *
   * @param exprType type of the expression
   * @param expr the expression
   * @return true if dataflow analysis could possibly refine the nullability of the expression,
   *     false otherwise
   */
  private static boolean shouldRunDataflowForExpression(Type exprType, ExpressionTree expr) {
    if (exprType.isPrimitive() || exprType instanceof NullType) {
      return false;
    }
    expr = NullabilityUtil.stripParensAndCasts(expr);
    return switch (expr.getKind()) {
      case ARRAY_ACCESS,
          MEMBER_SELECT,
          METHOD_INVOCATION,
          IDENTIFIER,
          ASSIGNMENT,
          CONDITIONAL_EXPRESSION,
          SWITCH_EXPRESSION ->
          true;
      default -> false;
    };
  }

  private Type updateTypeWithNullness(
      VisitorState state, Type argumentType, Nullness refinedNullness) {
    if (NullabilityUtil.nullnessToBool(refinedNullness)) {
      // refine to @Nullable
      if (isNullableAnnotated(argumentType)) {
        return argumentType;
      }
      return TypeSubstitutionUtils.typeWithAnnot(
          argumentType, getSyntheticNullableAnnotType(state));
    } else {
      // refine to @NonNull, by removing the top-level @Nullable annotation if present.
      if (!isNullableAnnotated(argumentType)) {
        return argumentType;
      }
      return TypeSubstitutionUtils.removeNullableAnnotation(argumentType, config);
    }
  }

  /**
   * Returns whether a call requires generic nullability inference.
   *
   * <p>This includes generic method invocations without explicit type arguments and constructor
   * calls using the diamond operator or omitting generic constructor type arguments, including
   * anonymous-class constructor calls.
   */
  private boolean isCallNeedingInference(ExpressionTree argument) {
    return (argument instanceof MethodInvocationTree || argument instanceof NewClassTree)
        && ASTHelpers.getSymbol(argument) != null
        && !callSite(argument).variables.isEmpty();
  }

  /**
   * Checks that the nullability of type parameters for a returned expression matches that of the
   * type parameters of the enclosing method's return type.
   *
   * @param retExpr the returned expression
   * @param methodSymbol symbol for enclosing method
   * @param state the visitor state
   */
  public void checkTypeParameterNullnessForFunctionReturnType(
      ExpressionTree retExpr, Symbol.MethodSymbol methodSymbol, VisitorState state) {
    Config config = analysis.getConfig();
    if (!config.isJSpecifyMode()) {
      return;
    }

    Type formalReturnType = methodSymbol.getReturnType();
    TreePath pathToRetExpr = pathWithLeaf(state.getPath(), retExpr);
    TreePath enclosingFunction =
        NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(pathToRetExpr);
    if (enclosingFunction != null
        && enclosingFunction.getLeaf() instanceof LambdaExpressionTree lambda
        && inferredPolyExpressionTypes.containsKey(lambda)) {
      Type targetReturnType = getLambdaReturnTargetType(lambda, state);

      if (targetReturnType != null) {
        formalReturnType = targetReturnType;
      }
    }
    if (formalReturnType.isRaw()) {
      // bail out of any checking involving raw types for now
      return;
    }
    checkTargetTypedMethodReference(retExpr, formalReturnType, pathToRetExpr, state);
    Type returnExpressionType =
        getTypeForRhsOfAssignment(retExpr, pathToRetExpr, formalReturnType, false, state, false);
    if (returnExpressionType != null && !hasUnavailableLowerResult(retExpr)) {
      boolean isReturnTypeValid =
          subtypeParameterNullability(formalReturnType, returnExpressionType, state);
      if (!isReturnTypeValid) {
        reportInvalidReturnTypeError(retExpr, formalReturnType, returnExpressionType, state);
      }
    }
  }

  /**
   * Compare two types for identical type parameter nullability, recursively checking nested generic
   * types. See <a href="https://jspecify.dev/docs/spec/#nullness-delegating-subtyping">the JSpecify
   * specification</a> and <a
   * href="https://docs.oracle.com/javase/specs/jls/se14/html/jls-4.html#jls-4.10.2">the JLS
   * subtyping rules for class and interface types</a>.
   *
   * @param lhsType type for the lhs of the assignment
   * @param rhsType type for the rhs of the assignment
   * @param visitor visitor carrying state for the recursive comparison
   */
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"}) // deliberate reference equality check
  private boolean identicalTypeParameterNullability(
      Type lhsType, Type rhsType, CheckIdenticalNullabilityVisitor visitor) {
    if (lhsType == rhsType) {
      return true;
    }
    return lhsType.accept(visitor, rhsType);
  }

  /**
   * Like {@link #identicalTypeParameterNullability(Type, Type, CheckIdenticalNullabilityVisitor)},
   * but allows for covariant array subtyping at every dimension of an array type.
   *
   * @param lhsType type for the lhs of the assignment
   * @param rhsType type for the rhs of the assignment
   * @param state the visitor state
   */
  boolean subtypeParameterNullability(Type lhsType, Type rhsType, VisitorState state) {
    return subtypeParameterNullability(
        lhsType,
        rhsType,
        state,
        new CheckIdenticalNullabilityVisitor(state, this, config, handler));
  }

  /**
   * Continues a subtype nullability comparison with an existing visitor so recursive wildcard
   * comparisons share cycle-detection state.
   *
   * @param lhsType type for the lhs of the assignment
   * @param rhsType type for the rhs of the assignment
   * @param state the visitor state
   * @param visitor visitor carrying state for the recursive comparison
   */
  boolean subtypeParameterNullability(
      Type lhsType, Type rhsType, VisitorState state, CheckIdenticalNullabilityVisitor visitor) {
    if (lhsType.isRaw()) {
      return true;
    }
    if (lhsType.getKind().equals(TypeKind.ARRAY) && rhsType.getKind().equals(TypeKind.ARRAY)) {
      // for array types we must allow covariance, i.e., an array of @NonNull references is a
      // subtype of an array of @Nullable references; see
      // https://github.com/jspecify/jspecify/issues/65
      Type.ArrayType lhsArrayType = (Type.ArrayType) lhsType;
      Type.ArrayType rhsArrayType = (Type.ArrayType) rhsType;
      Type lhsComponentType = lhsArrayType.getComponentType();
      Type rhsComponentType = rhsArrayType.getComponentType();
      boolean isLHSNullableAnnotated = isNullableAnnotated(lhsComponentType);
      boolean isRHSNullableAnnotated = isNullableAnnotated(rhsComponentType);
      // an array of @Nullable references is _not_ a subtype of an array of @NonNull references
      if (isRHSNullableAnnotated && !isLHSNullableAnnotated) {
        return false;
      }
      if (lhsComponentType.getKind().equals(TypeKind.ARRAY)
          && rhsComponentType.getKind().equals(TypeKind.ARRAY)) {
        // covariance applies at every dimension of a multi-dimensional array, not just the
        // outermost one.  The ARRAY check keeps array types nested inside generic type arguments
        // on the invariant path below, since generics are not covariant.
        return subtypeParameterNullability(lhsComponentType, rhsComponentType, state, visitor);
      }
      return identicalTypeParameterNullability(lhsComponentType, rhsComponentType, visitor);
    } else {
      return identicalTypeParameterNullability(lhsType, rhsType, visitor);
    }
  }

  /**
   * For the Parameterized typed trees, ASTHelpers.getType(tree) does not return a Type with
   * preserved annotations. This method takes a Parameterized typed tree as an input and returns the
   * Type of the tree with the annotations.
   *
   * @param tree A parameterized typed tree for which we need class type with preserved annotations.
   * @return A Type with preserved annotations.
   */
  private Type typeWithPreservedAnnotations(Tree tree) {
    return tree.accept(new PreservedAnnotationTreeVisitor(config), null);
  }

  /**
   * Replaces an inner allocation's declaration enclosing type with the annotated instance view.
   * Explicit qualifiers use their enhanced type. Without a qualifier, the nearest lexical class
   * supplying an outer instance is viewed as the enclosing owner, preserving inherited arguments.
   * Static allocations and unavailable views leave the original type unchanged.
   */
  private Type withEnclosingTypeFromQualifier(
      Type type, NewClassTree newClassTree, VisitorState state, boolean calledFromDataflow) {
    if (!(type instanceof Type.ClassType classType)
        || !(classType.getEnclosingType().tsym instanceof Symbol.ClassSymbol enclosingOwner)) {
      return type;
    }
    ExpressionTree enclosingExpr = newClassTree.getEnclosingExpression();
    Type enclosingInstanceType = null;
    if (enclosingExpr != null) {
      TreePath qualifierPath = pathWithLeaf(state.getPath(), enclosingExpr);
      enclosingInstanceType =
          getTreeType(enclosingExpr, state.withPath(qualifierPath), calledFromDataflow);
    } else {
      for (TreePath path = state.getPath(); path != null; path = path.getParentPath()) {
        if (path.getLeaf() instanceof ClassTree enclosingClass) {
          Type lexicalType = ASTHelpers.getType(enclosingClass);
          if (lexicalType == null) {
            continue;
          }
          Type view =
              TypeSubstitutionUtils.asSuper(state.getTypes(), lexicalType, enclosingOwner, config);
          if (view != null) {
            enclosingInstanceType = view;
            break;
          }
        }
      }
    }
    if (enclosingInstanceType == null) {
      return type;
    }
    return TYPE_METADATA_BUILDER.createClassType(
        classType, enclosingInstanceType, classType.getTypeArguments());
  }

  /**
   * For a conditional expression <em>c</em>, check whether the type parameter nullability for each
   * sub-expression of <em>c</em> matches the type parameter nullability of <em>c</em> itself.
   *
   * <p>Note that the type parameter nullability for <em>c</em> is computed by javac and reflects
   * what is required of the surrounding context (an assignment, parameter pass, etc.). It is
   * possible that both sub-expressions of <em>c</em> will have identical type parameter
   * nullability, but will still not match the type parameter nullability of <em>c</em> itself, due
   * to requirements from the surrounding context. In such a case, our error messages may be
   * somewhat confusing; we may want to improve this in the future.
   *
   * @param tree A conditional expression tree to check
   * @param state the visitor state
   */
  public void checkTypeParameterNullnessForConditionalExpression(
      ConditionalExpressionTree tree, VisitorState state) {
    Config config = analysis.getConfig();
    if (!config.isJSpecifyMode()) {
      return;
    }

    Tree truePartTree = tree.getTrueExpression();
    Tree falsePartTree = tree.getFalseExpression();

    TargetTypeAndAssignmentKind targetTypeAndAssignmentKind =
        getTargetTypeForConditionalExpression(tree, state, false);
    boolean hasTargetType =
        targetTypeAndAssignmentKind.typeFromAssignmentContext() != null
            || inferredPolyExpressionTypes.containsKey(tree);
    if (!hasTargetType) {
      return;
    }
    Type condExprType =
        inferConditionalExpressionType(
            state,
            tree,
            state.getPath(),
            targetTypeAndAssignmentKind.typeFromAssignmentContext(),
            false);
    if (condExprType == null) {
      return;
    }
    TreePath pathToTruePart = pathWithLeaf(state.getPath(), truePartTree);
    TreePath pathToFalsePart = pathWithLeaf(state.getPath(), falsePartTree);
    Type truePartType =
        getTypeForRhsOfAssignment(
            tree.getTrueExpression(),
            pathToTruePart,
            condExprType,
            targetTypeAndAssignmentKind.assignedToLocal(),
            state,
            false);
    Type falsePartType =
        getTypeForRhsOfAssignment(
            tree.getFalseExpression(),
            pathToFalsePart,
            condExprType,
            targetTypeAndAssignmentKind.assignedToLocal(),
            state,
            false);
    // The condExpr type should be the least-upper bound of the true and false part types.  To check
    // the nullability annotations, we check that the true and false parts are assignable to the
    // type of the whole expression
    checkTargetTypedMethodReference(tree.getTrueExpression(), condExprType, pathToTruePart, state);
    checkTargetTypedMethodReference(
        tree.getFalseExpression(), condExprType, pathToFalsePart, state);
    if (truePartType != null) {
      if (!subtypeParameterNullability(condExprType, truePartType, state)) {
        reportMismatchedTypeForTernaryOperator(truePartTree, condExprType, truePartType, state);
      }
    }
    if (falsePartType != null) {
      if (!subtypeParameterNullability(condExprType, falsePartType, state)) {
        reportMismatchedTypeForTernaryOperator(falsePartTree, condExprType, falsePartType, state);
      }
    }
  }

  /** Checks nested parameter and return contracts of a target-typed method reference. */
  private void checkTargetTypedMethodReference(
      ExpressionTree arm, Type target, TreePath path, VisitorState state) {
    NullabilityUtil.ExprTreeAndState armAndState =
        NullabilityUtil.stripParensAndUpdateTreePath(arm, state.withPath(path));
    if (armAndState.expr() instanceof MemberReferenceTree reference) {
      Type groundTarget = GenericsUtils.groundTargetType(target, state, config, handler);
      GenericsUtils.processMethodRefTypeRelations(
          this,
          groundTarget,
          reference,
          armAndState.state(),
          (subtype, supertype, relationKind) -> {
            if (!subtypeParameterNullability(supertype, subtype, state)) {
              if (relationKind == MethodRefTypeRelationKind.RETURN) {
                reportInvalidMethodReferenceReturnTypeError(reference, supertype, subtype, state);
              } else {
                reportInvalidMethodReferenceParameterTypeError(
                    reference, subtype, supertype, state);
              }
            }
          });
    }
  }

  /**
   * Returns the type to use for a conditional expression.
   *
   * <p>If a target/contextual type for the conditional expression has already been cached, returns
   * it. Otherwise, this method tries to recover a target type from the conditional expression's
   * parent context and caches it when {@link #okToCacheInferenceResult(boolean)} permits it. If no
   * target type is available, falls back to javac's type for the conditional expression.
   */
  private @Nullable Type getConditionalExpressionType(
      ConditionalExpressionTree tree, VisitorState state, boolean calledFromDataflow) {
    Type cachedType =
        lambdaTargetTypesForInference.isEmpty() ? inferredPolyExpressionTypes.get(tree) : null;
    if (cachedType != null) {
      return cachedType;
    }
    TargetTypeAndAssignmentKind targetTypeAndAssignmentKind =
        getTargetTypeForConditionalExpression(tree, state, calledFromDataflow);
    Type typeFromAssignmentContext = targetTypeAndAssignmentKind.typeFromAssignmentContext();
    if (typeFromAssignmentContext != null) {
      if (okToCacheInferenceResult(calledFromDataflow)) {
        inferredPolyExpressionTypes.put(tree, typeFromAssignmentContext);
      }
      return typeFromAssignmentContext;
    }
    return typeOrNullIfRawNonArray(ASTHelpers.getType(tree));
  }

  /**
   * Infers the type to use for a conditional expression in a pseudo-assignment context.
   *
   * <p>If {@code typeFromAssignmentContext} is non-null, it is used as the conditional expression's
   * target type. Otherwise, this method tries to recover a target type from the parent context.
   * When a target type is found, it is cached when {@link #okToCacheInferenceResult(boolean)}
   * permits it. If no target type is available, this method falls back to javac's type for the
   * conditional expression. Returns {@code null} for raw or otherwise unavailable types.
   */
  private @Nullable Type inferConditionalExpressionType(
      VisitorState state,
      ConditionalExpressionTree tree,
      TreePath path,
      @Nullable Type typeFromAssignmentContext,
      boolean calledFromDataflow) {
    Type cachedType =
        lambdaTargetTypesForInference.isEmpty() ? inferredPolyExpressionTypes.get(tree) : null;
    if (cachedType != null) {
      return cachedType;
    }
    boolean hasTargetType = typeFromAssignmentContext != null;
    Type condExprType = typeFromAssignmentContext;
    if (condExprType == null) {
      TargetTypeAndAssignmentKind targetTypeAndAssignmentKind =
          getTargetTypeForConditionalExpression(tree, state.withPath(path), calledFromDataflow);
      condExprType = targetTypeAndAssignmentKind.typeFromAssignmentContext();
      hasTargetType = condExprType != null;
    }
    if (condExprType == null) {
      condExprType = typeOrNullIfRawNonArray(ASTHelpers.getType(tree));
    }
    // A raw type still arrives here: typeOrNullIfRawNonArray lets a raw array through, and not
    // every target type reaching this point passed through it.
    if (condExprType == null || condExprType.isRaw()) {
      return null;
    }
    if (hasTargetType && okToCacheInferenceResult(calledFromDataflow)) {
      inferredPolyExpressionTypes.put(tree, condExprType);
    }
    return condExprType;
  }

  private record TargetTypeAndAssignmentKind(
      @Nullable Type typeFromAssignmentContext, boolean assignedToLocal) {}

  private TargetTypeAndAssignmentKind getTargetTypeForConditionalExpression(
      ConditionalExpressionTree tree, VisitorState state, boolean calledFromDataflow) {
    TreePath conditionalPath = pathWithLeaf(state.getPath(), tree);
    return getTargetTypeFromParentContext(tree, conditionalPath, state, calledFromDataflow);
  }

  /**
   * Returns the target type supplied by the parent context for an expression, if one is available.
   *
   * <p>Handles assignment and variable declaration contexts, method and constructor arguments,
   * return expressions, and expressions nested inside conditional expressions. If {@code path} is
   * inside a nested conditional expression, this method first climbs to the outermost conditional
   * expression, since the surrounding target type applies to that expression as a whole.
   *
   * <p>A {@code var}-declared local variable does not provide a target type; its type is inferred
   * from the initializer.
   */
  private TargetTypeAndAssignmentKind getTargetTypeFromParentContext(
      Tree tree, TreePath path, VisitorState state, boolean calledFromDataflow) {
    Tree expressionTree = tree;
    TreePath expressionPath = path;
    TreePath parentPath = expressionPath.getParentPath();
    if (parentPath == null) {
      return new TargetTypeAndAssignmentKind(null, false);
    }
    // 1. Climb up to corresponding "assignment-like" context:
    Tree parent = parentPath.getLeaf();
    while (parent instanceof ParenthesizedTree) {
      parentPath = parentPath.getParentPath();
      parent = parentPath.getLeaf();
    }
    if (parent instanceof ConditionalExpressionTree) {
      expressionPath = getOutermostConditionalExpressionPath(parentPath);
      expressionTree = expressionPath.getLeaf();
      parentPath = expressionPath.getParentPath();
      parent = parentPath.getLeaf();
      while (parent instanceof ParenthesizedTree) {
        parentPath = parentPath.getParentPath();
        parent = parentPath.getLeaf();
      }
    }
    VisitorState parentState = state.withPath(parentPath);
    // 2. We are at the right point in the tree now. Three cases:
    // 2a. `foo = [expr];` or `var foo = [expr];` (use getTargetTypeForAssignmentContext)
    if (parent instanceof AssignmentTree || parent instanceof VariableTree) {
      return getTargetTypeForAssignmentContext(parent, parentState, calledFromDataflow);
    }
    if (parent instanceof LambdaExpressionTree lambda) {
      return new TargetTypeAndAssignmentKind(getLambdaReturnTargetType(lambda, state), false);
    }
    // 2b. `return [expr];` => target is containing method's or lambda's formal return type
    if (parent instanceof ReturnTree) {
      TreePath enclosingMethodOrLambda =
          NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(parentPath);
      if (enclosingMethodOrLambda != null
          && enclosingMethodOrLambda.getLeaf() instanceof LambdaExpressionTree lambda) {
        return new TargetTypeAndAssignmentKind(getLambdaReturnTargetType(lambda, state), false);
      }
      if (enclosingMethodOrLambda != null
          && enclosingMethodOrLambda.getLeaf() instanceof MethodTree enclosingMethod) {
        Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(enclosingMethod);
        if (methodSymbol != null) {
          return new TargetTypeAndAssignmentKind(methodSymbol.getReturnType(), false);
        }
      }
    }
    // 2c. `foo(..., [expr]);` => target is called method's formal argument type
    if (parent instanceof MethodInvocationTree parentInvocation) {
      if (isCallNeedingInference(parentInvocation)) {
        if (inferredTypeVarNullabilityForGenericCalls.get(parentInvocation)
            instanceof InferenceWithTypes) {
          Type.MethodType methodType =
              getInvokedMethodTypeAtCall(
                  parentInvocation, parentPath, parentState, calledFromDataflow);
          return new TargetTypeAndAssignmentKind(
              getFormalParameterTypeForArgument(parentInvocation, methodType, expressionTree),
              false);
        }
        // An unsolved parent supplies inference variables, not an independent final target.
        return new TargetTypeAndAssignmentKind(null, false);
      }
      Type methodType = ASTHelpers.getType(parentInvocation.getMethodSelect());
      if (methodType != null) {
        return new TargetTypeAndAssignmentKind(
            getFormalParameterTypeForArgument(
                parentInvocation, methodType.asMethodType(), expressionTree),
            false);
      }
    }
    // (Also 2c, but for constructors)
    if (parent instanceof NewClassTree parentConstructorCall) {
      Type parentClassType = getTreeType(parentConstructorCall, parentState, calledFromDataflow);
      if (parentClassType != null) {
        Symbol parentCtorSymbol = ASTHelpers.getSymbol(parentConstructorCall);
        Type parentCtorType =
            TypeSubstitutionUtils.memberType(
                state.getTypes(), parentClassType, parentCtorSymbol, config);
        return new TargetTypeAndAssignmentKind(
            getFormalParameterTypeForArgument(
                parentConstructorCall, parentCtorType.asMethodType(), expressionTree),
            false);
      }
    }
    // Fall back to an unknown target type
    return new TargetTypeAndAssignmentKind(null, false);
  }

  /**
   * Returns target-type information for an assignment or variable declaration context.
   *
   * <p>For explicit variable declarations and assignments, the assigned location's type is the
   * target type. For {@code var}-declared locals, no target type is returned because the local's
   * type is inferred from the initializer.
   */
  private TargetTypeAndAssignmentKind getTargetTypeForAssignmentContext(
      Tree assignmentOrVariable, VisitorState state, boolean calledFromDataflow) {
    Preconditions.checkArgument(
        assignmentOrVariable instanceof AssignmentTree
            || assignmentOrVariable instanceof VariableTree);
    Type targetType =
        assignmentOrVariable instanceof VariableTree variableTree
                && isVarLocalVariableDeclaration(variableTree)
            ? null
            : getTreeType(assignmentOrVariable, state, calledFromDataflow);
    return new TargetTypeAndAssignmentKind(
        targetType, isAssignmentToLocalVariable(assignmentOrVariable));
  }

  /**
   * Returns the outermost conditional expression enclosing {@code path}, ignoring parentheses
   * between nested conditionals.
   *
   * <p>If {@code path} is not enclosed by another conditional expression, returns {@code path}
   * itself.
   */
  private TreePath getOutermostConditionalExpressionPath(TreePath path) {
    TreePath conditionalPath = path;
    TreePath parentPath = conditionalPath.getParentPath();
    while (parentPath != null) {
      Tree parent = parentPath.getLeaf();
      while (parent instanceof ParenthesizedTree) {
        parentPath = parentPath.getParentPath();
        parent = parentPath.getLeaf();
      }
      if (!(parent instanceof ConditionalExpressionTree)) {
        return conditionalPath;
      }
      conditionalPath = parentPath;
      parentPath = conditionalPath.getParentPath();
    }
    return conditionalPath;
  }

  /**
   * Checks that for each parameter p at a call, the type parameter nullability for p's type matches
   * that of the corresponding formal parameter. If a mismatch is found, report an error.
   *
   * @param methodSymbol the symbol for the method being called
   * @param tree the tree representing the method call
   * @param state the visitor state
   */
  @SuppressWarnings("ReferenceEquality") // deliberate reference equality checks
  public void compareGenericTypeParameterNullabilityForCall(
      Symbol.MethodSymbol methodSymbol, Tree tree, VisitorState state) {
    Config config = analysis.getConfig();
    if (!config.isJSpecifyMode()) {
      return;
    }
    Type.MethodType finalMethodType = getInvokedMethodTypeAtCall(tree, null, state, false);
    new InvocationArguments(tree, finalMethodType)
        .forEach(
            (currentActualParam, argPos, formalParameter, unused) -> {
              if (formalParameter.isRaw()) {
                // bail out of any checking involving raw types for now
                return;
              }

              if (hasUnavailableLowerArgument(tree, currentActualParam)) {
                return;
              }
              TreePath pathToParam = pathWithLeaf(state.getPath(), currentActualParam);
              NullabilityUtil.ExprTreeAndState actualParameterAndState =
                  NullabilityUtil.stripParensAndUpdateTreePath(
                      currentActualParam, state.withPath(pathToParam));
              ExpressionTree actualParameterWithoutParentheses = actualParameterAndState.expr();
              if (actualParameterWithoutParentheses
                  instanceof MemberReferenceTree memberReferenceTree) {
                maybeStorePolyExpressionTypeFromTarget(
                    actualParameterWithoutParentheses, formalParameter, state);
                Type groundFormalParameter =
                    GenericsUtils.groundTargetType(formalParameter, state, config, handler);
                // the type of the method reference tree provided by javac may not capture
                // nullability of nested types. So, do explicit type checks based on the return and
                // parameter types of the referenced method
                GenericsUtils.processMethodRefTypeRelations(
                    this,
                    groundFormalParameter,
                    memberReferenceTree,
                    actualParameterAndState.state(),
                    (subtype, supertype, relationKind) -> {
                      if (!subtypeParameterNullability(supertype, subtype, state)) {
                        if (relationKind == MethodRefTypeRelationKind.RETURN) {
                          reportInvalidMethodReferenceReturnTypeError(
                              memberReferenceTree, supertype, subtype, state);
                        } else {
                          reportInvalidMethodReferenceParameterTypeError(
                              memberReferenceTree, subtype, supertype, state);
                        }
                      }
                    });
                return;
              }

              Type actualParameterType;
              if (actualParameterWithoutParentheses instanceof LambdaExpressionTree) {
                maybeStorePolyExpressionTypeFromTarget(
                    actualParameterWithoutParentheses, formalParameter, state);
              }
              Type inferredPolyType =
                  inferredPolyExpressionTypes.get(actualParameterWithoutParentheses);
              if (inferredPolyType != null) {
                actualParameterType = inferredPolyType;
              } else {
                actualParameterType = getTreeType(currentActualParam, state.withPath(pathToParam));
              }
              if (actualParameterType != null) {
                if (hasUnavailableLowerResult(actualParameterWithoutParentheses)) {
                  return;
                }
                if (isCallNeedingInference(currentActualParam)) {
                  // infer the type of the method call based on the assignment context
                  // and the formal parameter type
                  actualParameterType =
                      inferCallType(
                          state.withPath(pathToParam),
                          currentActualParam,
                          pathToParam,
                          formalParameter,
                          false,
                          false);
                } else if (currentActualParam
                    instanceof ConditionalExpressionTree conditionalExpressionTree) {
                  actualParameterType =
                      inferConditionalExpressionType(
                          state.withPath(pathToParam),
                          conditionalExpressionTree,
                          pathToParam,
                          formalParameter,
                          false);
                }
                if (actualParameterType == null) {
                  return;
                }
                if (!subtypeParameterNullability(formalParameter, actualParameterType, state)) {
                  reportInvalidParametersNullabilityError(
                      formalParameter, actualParameterType, currentActualParam, state);
                }
              }
            });
  }

  /**
   * Returns a generic method reference's method type with inferred type arguments. Reuses a
   * completed enclosing solution when available; otherwise solves the reference independently
   * against its final target type. During constraint generation or re-entrant reference resolution,
   * returns the declaration type.
   *
   * @param methodType the original method type
   * @param state the visitor state (generic method reference should be leaf of {@code
   *     state.getPath()})
   * @return the method type with inferred nullability for type parameters if inference was
   *     performed, or the original method type otherwise
   */
  private Type.MethodType getInferredMethodTypeForGenericMethodReference(
      Type.MethodType methodType, VisitorState state) {
    if (callTypesForPolyArguments != null) {
      // Constraint generation must use the declaration, not a previous completed solution.
      return methodType;
    }
    Tree referenceTree = state.getPath().getLeaf();
    if (referenceTree instanceof MemberReferenceTree memberReferenceTree) {
      Symbol.MethodSymbol referencedMethod = ASTHelpers.getSymbol(memberReferenceTree);
      List<? extends ExpressionTree> explicitTypeArguments = memberReferenceTree.getTypeArguments();
      if (referencedMethod == null
          || referencedMethod.isConstructor()
          || (explicitTypeArguments != null && !explicitTypeArguments.isEmpty())
          || methodReferenceInferenceInProgress.contains(memberReferenceTree)) {
        return methodType;
      }
      InferenceWithTypes siteResult =
          inferredResultsForGenericMethodReferences.get(memberReferenceTree);
      if (siteResult != null) {
        return TypeSubstitutionUtils.substituteInferredTypesForGenericMethodReference(
            methodType, siteResult.inferredTypesForSite(memberReferenceTree), state, config);
      }
    }
    TreePath parentPath = state.getPath().getParentPath();
    while (parentPath != null && parentPath.getLeaf() instanceof ParenthesizedTree) {
      parentPath = parentPath.getParentPath();
    }
    Tree parentTree = parentPath != null ? parentPath.getLeaf() : null;
    if (parentTree instanceof MethodInvocationTree methodInvocationTree
        && isCallNeedingInference(methodInvocationTree)) {
      CallInferenceResult inferenceResult =
          inferredTypeVarNullabilityForGenericCalls.get(methodInvocationTree);
      if (inferenceResult instanceof InferenceSuccess successResult) {
        // Only reuse a parent solution if the reference actually participated in its constraints.
        Map<Element, Type> inferredTypes = successResult.inferredTypesForSite(referenceTree);
        if (!inferredTypes.isEmpty()) {
          return TypeSubstitutionUtils.substituteInferredTypesForGenericMethodReference(
              methodType, inferredTypes, state, config);
        }
      }
    }
    if (referenceTree instanceof MemberReferenceTree memberReferenceTree) {
      InferenceWithTypes result =
          inferGenericMethodReferenceIndependently(memberReferenceTree, state);
      if (result != null) {
        return TypeSubstitutionUtils.substituteInferredTypesForGenericMethodReference(
            methodType, result.inferredTypesForSite(memberReferenceTree), state, config);
      }
    }
    return methodType;
  }

  /**
   * Solves an unresolved generic method reference without publishing or invalidating call results.
   * This allows siblings of a failed enclosing inference problem to check their declaration bounds
   * independently. Only completed reference solutions from a cacheable context are persisted.
   *
   * @param reference the generic method reference without explicit type arguments
   * @param state visitor state whose path ends at the reference
   * @return the completed solution, or {@code null} if no final target is available or inference
   *     fails
   */
  private @Nullable InferenceWithTypes inferGenericMethodReferenceIndependently(
      MemberReferenceTree reference, VisitorState state) {
    if (callTypesForPolyArguments != null || !methodReferenceInferenceInProgress.add(reference)) {
      return null;
    }
    try {
      Type targetType =
          okToCacheInferenceResult(false) ? inferredPolyExpressionTypes.get(reference) : null;
      if (targetType == null) {
        targetType = ASTHelpers.getType(reference);
      }
      if (targetType == null || targetType.isRaw()) {
        return null;
      }
      ConstraintSolver solver = makeSolver(state, analysis);
      InferenceCacheState inferenceCacheState = new InferenceCacheState();
      handleMethodRefInGenericMethodInference(
          state, solver, inferenceCacheState, targetType, reference, reference);
      Solution solution = solver.solve();
      InferenceWithTypes result =
          solution.isComplete() ? new InferenceSuccess(solution) : new InferencePartial(solution);
      if (result instanceof InferenceSuccess successResult && okToCacheInferenceResult(false)) {
        inferredResultsForGenericMethodReferences.put(reference, successResult);
      }
      return result;
    } catch (NestedUpperBoundViolationException e) {
      if (reportedNestedUpperBoundViolations.add(e.getSite())) {
        VisitorState reportingState = stateForInferenceDiagnostic(e.getSite(), state);
        ErrorMessage errorMessage =
            new ErrorMessage(
                ErrorMessage.MessageTypes.PASS_NULLABLE_GENERIC,
                errorMessageForIncompatibleTypesAtPseudoAssignment(
                    e.getUpperBound(), e.getLowerBound(), reportingState));
        state.reportMatch(
            analysis
                .getErrorBuilder()
                .createErrorDescription(
                    errorMessage, analysis.buildDescription(e.getSite()), reportingState, null));
      }
      return null;
    } catch (UnsatisfiableConstraintsException e) {
      Tree site = e.getInferenceSite() != null ? e.getInferenceSite() : reference;
      if (config.warnOnGenericInferenceFailure() && callsWithReportedInferenceFailures.add(site)) {
        VisitorState reportingState = stateForInferenceDiagnostic(site, state);
        ErrorMessage errorMessage =
            new ErrorMessage(
                ErrorMessage.MessageTypes.GENERIC_INFERENCE_FAILURE, inferenceFailureMessage(e));
        state.reportMatch(
            analysis
                .getErrorBuilder()
                .createErrorDescription(
                    errorMessage, analysis.buildDescription(site), reportingState, null));
      }
      return null;
    } finally {
      methodReferenceInferenceInProgress.remove(reference);
    }
  }

  /**
   * Checks that type parameter nullability is consistent between an overriding method and the
   * corresponding overridden method.
   *
   * @param tree A method tree to check
   * @param overridingMethod A symbol of the overriding method
   * @param overriddenMethod A symbol of the overridden method
   * @param overriddenMethodType type of the overridden method after member-type substitution and
   *     application of library models
   * @param state the visitor state
   */
  public void checkTypeParameterNullnessForMethodOverriding(
      MethodTree tree,
      Symbol.MethodSymbol overridingMethod,
      Symbol.MethodSymbol overriddenMethod,
      Type overriddenMethodType,
      VisitorState state) {
    if (!analysis.getConfig().isJSpecifyMode()) {
      return;
    }
    checkTypeParameterNullnessForOverridingMethodReturnType(tree, overriddenMethodType, state);
    checkTypeParameterNullnessForOverridingMethodParameterType(tree, overriddenMethodType, state);
    checkMethodTypeVariableUpperBoundNullnessForOverriding(
        tree, overridingMethod, overriddenMethod, overriddenMethodType, state);
  }

  /**
   * Returns the overridden method type in the overriding class context with library models applied.
   *
   * <p>The {@link Type.ForAll} wrapper must be retained for subsequent checks of method type
   * variable bounds, while handlers operate on its underlying {@link Type.MethodType}.
   *
   * @param overridingMethod symbol of the overriding method
   * @param overriddenMethod symbol of the overridden method
   * @param state visitor state
   * @return the substituted, modeled overridden method type
   */
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"})
  public Type getModeledOverriddenMethodType(
      Symbol.MethodSymbol overridingMethod,
      Symbol.MethodSymbol overriddenMethod,
      VisitorState state) {
    Type typeForSymbol = getTypeForSymbol(overridingMethod.owner, state);
    Type substitutedMethodType =
        TypeSubstitutionUtils.memberType(
            state.getTypes(),
            typeForSymbol != null ? typeForSymbol : overridingMethod.owner.type,
            overriddenMethod,
            analysis.getConfig());
    Type.MethodType methodType = substitutedMethodType.asMethodType();
    Type.MethodType modeledMethodType =
        handler.onOverrideMethodType(overriddenMethod, methodType, state, null);
    if (modeledMethodType == methodType) {
      return substitutedMethodType;
    }
    if (substitutedMethodType instanceof Type.ForAll forAll) {
      return new Type.ForAll(forAll.tvars, modeledMethodType);
    }
    return modeledMethodType;
  }

  /**
   * Checks that corresponding method type variables have the same upper-bound nullability on an
   * overriding method and the method it overrides.
   *
   * <p>Narrowing a {@code @Nullable} upper bound to a non-null upper bound (or the reverse) is
   * unsound: callers can still instantiate the type variable via the overridden signature. See <a
   * href="https://github.com/uber/NullAway/issues/1512">issue 1512</a>.
   *
   * <p>Overridden method type-variable bounds are read from {@code overriddenMethodType}, the
   * overridden method type after member-type substitution in the overriding class context. That
   * ensures bounds that reference enclosing-class type variables are compared after those variables
   * have been instantiated (e.g. {@code <T extends X>} on {@code Foo<X>} becomes {@code <T
   * extends @Nullable Object>} when overriding in a {@code Foo<@Nullable Object>} subtype).
   *
   * <p>Overrides of methods from {@code @NullUnmarked} / unannotated code are skipped: method
   * type-variable bound nullness is not specified there, and treating unmarked bounds as nullable
   * would false-positive against typical {@code <T>} overrides in marked code.
   *
   * @param tree tree for the overriding method
   * @param overridingMethod symbol of the overriding method
   * @param overriddenMethod symbol of the overridden method
   * @param overriddenMethodType type of the overridden method after member-type substitution in the
   *     overriding class context
   * @param state the visitor state
   */
  private void checkMethodTypeVariableUpperBoundNullnessForOverriding(
      MethodTree tree,
      Symbol.MethodSymbol overridingMethod,
      Symbol.MethodSymbol overriddenMethod,
      Type overriddenMethodType,
      VisitorState state) {
    if (CodeAnnotationInfo.instance(state.context)
        .isSymbolUnannotated(overriddenMethod, config, handler)) {
      return;
    }
    // Generic methods are Type.ForAll; non-generic overridden methods have no method type vars.
    if (!(overriddenMethodType instanceof Type.ForAll forAll)) {
      return;
    }
    com.sun.tools.javac.util.List<Type> overriddenTypeVars = forAll.tvars;
    List<Symbol.TypeVariableSymbol> overridingTypeParams = overridingMethod.getTypeParameters();
    // If counts differ, javac would not treat this as a valid override; leave that to the
    // compiler.
    if (overridingTypeParams.size() != overriddenTypeVars.size()) {
      return;
    }
    List<? extends Tree> typeParameterTrees = tree.getTypeParameters();
    for (int i = 0; i < overridingTypeParams.size(); i++) {
      Symbol.TypeVariableSymbol overridingTv = overridingTypeParams.get(i);
      // ForAll.tvars are method type variables after member-type substitution.
      Type.TypeVar overriddenTypeVar = (Type.TypeVar) overriddenTypeVars.get(i);
      boolean overridingNullable =
          GenericsUtils.upperBoundIsNullable(overridingTv, config, handler, state);
      boolean overriddenNullable =
          substitutedMethodTypeVarUpperBoundIsNullable(
              overriddenTypeVar, overriddenMethod, i, state);
      if (overridingNullable != overriddenNullable) {
        reportMismatchedMethodTypeVariableBoundError(
            typeParameterTrees.get(i),
            overridingTv,
            overridingNullable,
            overriddenMethod,
            overriddenNullable,
            state);
      }
    }
  }

  /**
   * Returns whether the upper bound of a method type variable, viewed after member-type
   * substitution in the overriding class, should be treated as nullable.
   *
   * <p>Prefers annotations / nullability of the substituted bound so enclosing-class type variables
   * are accounted for. Falls back to library models for the original type variable index when
   * present.
   *
   * @param substitutedTypeVar type variable from the overridden method type after substitution
   * @param overriddenMethod symbol of the overridden method (for library models)
   * @param typeVarIndex index of the type variable on the overridden method
   * @param state the visitor state
   */
  private boolean substitutedMethodTypeVarUpperBoundIsNullable(
      Type.TypeVar substitutedTypeVar,
      Symbol.MethodSymbol overriddenMethod,
      int typeVarIndex,
      VisitorState state) {
    if (handler.onOverrideMethodTypeVariableUpperBound(overriddenMethod, typeVarIndex, state)) {
      return true;
    }
    Type upperBound = substitutedTypeVar.getUpperBound();
    if (TypeSubstitutionUtils.hasNullableRoot(upperBound, config)) {
      return true;
    }
    if (Nullness.hasNonNullAnnotation(upperBound.getAnnotationMirrors().stream(), config)) {
      return false;
    }
    // Member-type substitution (asMemberOf) can strip type-use @Nullable from a concrete method
    // type-variable bound while leaving the bound type itself (e.g. Object). Example that needs
    // this fallback:
    //   interface Foo { <T extends @Nullable Object> void bar(T arg); }
    //   class Baz implements Foo { public <T extends @Nullable Object> void bar(T arg) {} }
    // After substitution the bound may look like plain Object with no annotation mirrors; without
    // consulting the original declaration we would treat the overridden bound as non-null and
    // false-positive on a matching @Nullable override.
    List<Symbol.TypeVariableSymbol> originalTypeParams = overriddenMethod.getTypeParameters();
    Type originalBound =
        (Type) ((TypeVariable) originalTypeParams.get(typeVarIndex).asType()).getUpperBound();
    if (TypeSubstitutionUtils.hasNullableRoot(originalBound, config)) {
      return true;
    }
    if (Nullness.hasNonNullAnnotation(originalBound.getAnnotationMirrors().stream(), config)) {
      return false;
    }
    // Bound may still be a free type variable (e.g. subclass keeps the enclosing type parameter).
    // In that case, use the declaration-site nullability of that type variable's upper bound.
    if (upperBound.getKind() == TypeKind.TYPEVAR) {
      return GenericsUtils.upperBoundIsNullable(upperBound.asElement(), config, handler, state);
    }
    return false;
  }

  /**
   * Reports an error when an overriding method's type variable has a different upper-bound
   * nullability than the corresponding type variable of the overridden method.
   *
   * @param errorTree tree to attach the diagnostic to (usually the overriding type parameter)
   * @param overridingTv type variable of the overriding method
   * @param overridingNullable whether the overriding type variable's upper bound is nullable
   * @param overriddenMethod symbol of the overridden method
   * @param overriddenNullable whether the overridden type variable's upper bound is nullable (in
   *     the overriding class context)
   * @param state the visitor state
   */
  private void reportMismatchedMethodTypeVariableBoundError(
      Tree errorTree,
      Symbol.TypeVariableSymbol overridingTv,
      boolean overridingNullable,
      Symbol.MethodSymbol overriddenMethod,
      boolean overriddenNullable,
      VisitorState state) {
    ErrorBuilder errorBuilder = analysis.getErrorBuilder();
    String overridingBound = overridingNullable ? "@Nullable" : "non-null";
    String overriddenBound = overriddenNullable ? "@Nullable" : "non-null";
    ErrorMessage errorMessage =
        new ErrorMessage(
            ErrorMessage.MessageTypes.WRONG_OVERRIDE_PARAM_GENERIC,
            String.format(
                "Method type variable %s has a %s upper bound, but corresponding type variable of"
                    + " overridden method %s.%s has a %s upper bound",
                overridingTv.name,
                overridingBound,
                ASTHelpers.enclosingClass(overriddenMethod),
                overriddenMethod.name,
                overriddenBound));
    state.reportMatch(
        errorBuilder.createErrorDescription(
            errorMessage, analysis.buildDescription(errorTree), state, null));
  }

  /**
   * Computes the nullability of the return type of some generic method when seen as a member of
   * some class {@code C}, based on type parameter nullability within {@code C}.
   *
   * <p>Consider the following example:
   *
   * <pre>{@code
   * interface Fn<P extends @Nullable Object, R extends @Nullable Object> {
   *     R apply(P p);
   * }
   * class C implements Fn<String, @Nullable String> {
   *     public @Nullable String apply(String p) {
   *         return null;
   *     }
   * }
   * }</pre>
   *
   * <p>Within the context of class {@code C}, the method {@code Fn.apply} has a return type of
   * {@code @Nullable String}, since {@code @Nullable String} is passed as the type parameter for
   * {@code R}. Hence, it is valid for overriding method {@code C.apply} to return {@code @Nullable
   * String}.
   *
   * @param method the generic method
   * @param enclosingSymbol the enclosing class in which we want to know {@code method}'s return
   *     type nullability
   * @param state Visitor state
   * @return nullability of the return type of {@code method} in the context of {@code
   *     enclosingType}
   */
  public Nullness getGenericMethodReturnTypeNullness(
      Symbol.MethodSymbol method, Symbol enclosingSymbol, VisitorState state) {
    Type enclosingType = getTypeForSymbol(enclosingSymbol, state);
    return getGenericMethodReturnTypeNullness(method, enclosingType, state);
  }

  /**
   * Get the type for the symbol, accounting for anonymous classes
   *
   * @param symbol the symbol
   * @param state the visitor state
   * @return the type for {@code symbol}
   */
  private @Nullable Type getTypeForSymbol(Symbol symbol, VisitorState state) {
    if (symbol.isAnonymous()) {
      // For anonymous classes, symbol.type does not contain annotations on generic type parameters.
      // So, we get a correct type from the NewClassTree representing the anonymous class.
      // The nearest enclosing NewClassTree on the current path may be some other constructor call,
      // such as when `this` from an anonymous class is passed as an argument to a constructor.
      TreePath path = state.getPath();
      while (path != null) {
        if (path.getLeaf() instanceof NewClassTree newClassTree
            && newClassTree.getClassBody() != null) {
          Type newClassType = ASTHelpers.getType(newClassTree);
          if (newClassType != null && newClassType.tsym.equals(symbol)) {
            Type typeFromTree = getTreeType(newClassTree, state.withPath(path));
            if (typeFromTree != null) {
              verify(
                  state.getTypes().isAssignable(symbol.type, typeFromTree),
                  "%s is not assignable to %s",
                  symbol.type,
                  typeFromTree);
            }
            return typeFromTree;
          }
        }
        path = path.getParentPath();
      }
      throw new RuntimeException(
          "could not find anonymous NewClassTree for symbol "
              + symbol
              + " from path "
              + state.getSourceForNode(state.getPath().getLeaf()));
    }
    return symbol.type;
  }

  public Nullness getGenericMethodReturnTypeNullness(
      Symbol.MethodSymbol method, @Nullable Type enclosingType, VisitorState state) {
    if (enclosingType == null) {
      // we have no additional information from generics, so return NONNULL (presence of a @Nullable
      // annotation should have been handled by the caller)
      return Nullness.NONNULL;
    }
    Type overriddenMethodType =
        TypeSubstitutionUtils.memberType(state.getTypes(), enclosingType, method, config);
    verify(
        overriddenMethodType instanceof ExecutableType,
        "expected ExecutableType but instead got %s",
        overriddenMethodType.getClass());
    return getTypeNullnessForRead(overriddenMethodType.getReturnType(), state);
  }

  /**
   * Computes the nullness of the return of a generic method at an invocation, in the context of the
   * declared type of its receiver argument. If the return type is a type variable, its nullness
   * depends on the nullability of the corresponding type parameter in the receiver's type or the
   * type argument of the method call.
   *
   * <p>Consider the following example:
   *
   * <pre>{@code
   * interface Fn<P extends @Nullable Object, R extends @Nullable Object> {
   *     R apply(P p);
   * }
   * class C implements Fn<String, @Nullable String> {
   *     public @Nullable String apply(String p) {
   *         return null;
   *     }
   * }
   * static void m() {
   *     Fn<String, @Nullable String> f = new C();
   *     f.apply("hello").hashCode(); // NPE
   * }
   * }</pre>
   *
   * <p>The declared type of {@code f} passes {@code Nullable String} as the type parameter for type
   * variable {@code R}. So, the call {@code f.apply("hello")} returns {@code @Nullable} and an
   * error should be reported.
   *
   * @param invokedMethodSymbol symbol for the invoked method
   * @param tree the tree for the invocation
   * @param path the path to the invocation tree
   * @param state the visitor state
   * @param calledFromDataflow whether this method is being called from dataflow analysis
   * @return Nullness of invocation's return type, or {@code NONNULL} if the call does not invoke an
   *     instance method
   */
  public Nullness getGenericReturnNullnessAtInvocation(
      Symbol.MethodSymbol invokedMethodSymbol,
      MethodInvocationTree tree,
      TreePath path,
      VisitorState state,
      boolean calledFromDataflow) {
    // If the return type is not a type variable, just return NONNULL (explicit @Nullable should
    // have been handled by the caller)
    if (!invokedMethodSymbol.getReturnType().getKind().equals(TypeKind.TYPEVAR)) {
      return Nullness.NONNULL;
    }
    // If generic method invocation
    if (!invokedMethodSymbol.getTypeParameters().isEmpty()) {
      // Substitute type arguments inside the return type
      Type substitutedReturnType =
          getResolvedCallSignature(
                  callSite(tree).resolve(path, state, calledFromDataflow),
                  path,
                  state,
                  calledFromDataflow)
              .getReturnType();
      // If this condition evaluates to false, we fall through to the subsequent logic, to handle
      // type variables declared on the enclosing class
      if (substitutedReturnType != null
          && Objects.equals(getTypeNullness(substitutedReturnType), Nullness.NULLABLE)) {
        return Nullness.NULLABLE;
      }
    }

    Type enclosingType =
        getEnclosingTypeForCallExpression(
            invokedMethodSymbol, tree, path, state, calledFromDataflow);
    if (enclosingType == null) {
      return Nullness.NONNULL;
    } else {
      return getGenericMethodReturnTypeNullness(invokedMethodSymbol, enclosingType, state);
    }
  }

  private static com.sun.tools.javac.util.List<Type> convertTreesToTypes(
      List<? extends Tree> typeArgumentTrees) {
    List<Type> types = new ArrayList<>();
    for (Tree tree : typeArgumentTrees) {
      if (tree instanceof JCTree.JCExpression expression) {
        types.add(expression.type); // Retrieve the Type
      }
    }
    return com.sun.tools.javac.util.List.from(types);
  }

  /**
   * Substitutes site-scoped inferred arguments into a receiver- and witness-resolved signature.
   * Method and class inference variables use the same path without inventing a quantified
   * constructor signature. Existing partial and failed-result fallback policies are retained.
   *
   * @param call the context-resolved executable description
   * @param path the path to the invocation tree, or null if not available
   * @param state the visitor state
   * @param calledFromDataflow whether this method is being called from dataflow analysis
   * @return the substituted method type for the generic method
   */
  private Type.MethodType getResolvedCallSignature(
      InferenceCall call, @Nullable TreePath path, VisitorState state, boolean calledFromDataflow) {
    ExpressionTree invocationTree = call.site.tree;
    Type.MethodType methodType = call.signature;
    if (!call.site.variables.isEmpty()) {
      CallInferenceResult result = inferredTypeVarNullabilityForGenericCalls.get(invocationTree);
      if (result == null) {
        // have not yet attempted inference for this call
        CallAndContext invocationAndType =
            path == null
                ? completedCallContexts.getOrDefault(
                    invocationTree, new CallAndContext(invocationTree, null, false))
                : getCallAndContextForInference(path, state, calledFromDataflow);
        // The selected root may be an ancestor of the original invocation. Constraint generation
        // and lambda ownership must use that root's source path, not the nested call's path.
        TreePath inferencePath = path;
        while (inferencePath != null && !inferencePath.getLeaf().equals(invocationAndType.call)) {
          inferencePath = inferencePath.getParentPath();
        }
        VisitorState inferenceState = inferencePath != null ? state.withPath(inferencePath) : state;
        result =
            runInferenceForCall(
                inferenceState,
                inferencePath,
                getCallForInference(
                    invocationAndType.call, inferencePath, inferenceState, calledFromDataflow),
                invocationAndType.typeFromAssignmentContext,
                invocationAndType.assignedToLocal,
                calledFromDataflow);
      }
      Type.MethodType methodTypeAtCallSite = call.site.attributedSignature();
      if (result instanceof InferenceWithTypes inferred
          && (result instanceof InferenceSuccess || call.site.returnsAllocation())) {
        return (Type.MethodType)
            TypeSubstitutionUtils.substituteTypeVariables(
                methodType,
                inferred.inferredTypesForSite(invocationTree),
                state.getTypes(),
                config);
      }
      if (result instanceof InferenceWithTypes successResult
          && invocationTree instanceof MethodInvocationTree methodInvocation) {
        // Repairing dropped nested nullability annotations can itself inspect actual argument
        // types. For diamond constructor arguments, that can re-enter method-type computation for
        // this same invocation while we are still repairing it. In that case, use the already
        // inferred method type and skip the repair on the recursive call.
        if (!nestedNullabilityRepairInProgress.contains(invocationTree)) {
          nestedNullabilityRepairInProgress.add(methodInvocation);
          try {
            methodTypeAtCallSite =
                restoreNestedNullabilityForTypeVarArguments(
                    methodInvocation,
                    methodType,
                    methodTypeAtCallSite,
                    path,
                    state,
                    calledFromDataflow);
          } finally {
            nestedNullabilityRepairInProgress.remove(invocationTree);
          }
        }
        return TypeSubstitutionUtils.updateMethodTypeWithInferredNullability(
            methodTypeAtCallSite,
            methodType,
            successResult.inferredTypesForSite(invocationTree),
            state,
            config);
      } else {
        // A failed solution must not erase explicit annotations on declared variable uses.
        return TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
                methodType, methodTypeAtCallSite, config)
            .asMethodType();
      }
    }
    return methodType;
  }

  /**
   * In narrow cases, javac drops or misplaces nested type-use nullability annotations on type
   * variables in its inferred type for a generic method at a call site. See <a
   * href="https://github.com/uber/NullAway/issues/1455">issue 1455</a>. This method repairs those
   * annotations based on the types of actual parameters. It does not attempt to be a very general
   * fix, as we do not fully understand the scenarios where this can arise.
   *
   * @param invocationTree the method invocation tree for the generic method call
   * @param origMethodType the declared method type for the generic method (to identify formal
   *     parameters whose type is a type variable of the method)
   * @param methodTypeAtCallSite the method type for the generic method as inferred by javac at the
   *     call site
   * @param invocationPath the path to the invocation tree, or null if not available
   * @param state the visitor state
   * @return a method type based on {@code methodTypeAtCallSite} but with some nested nullability
   *     annotations on type variables restored to match those on actual parameters passed at the
   *     call site
   */
  private Type.MethodType restoreNestedNullabilityForTypeVarArguments(
      MethodInvocationTree invocationTree,
      Type.MethodType origMethodType,
      Type.MethodType methodTypeAtCallSite,
      @Nullable TreePath invocationPath,
      VisitorState state,
      boolean calledFromDataflow) {
    return NestedTypeVarSubstitutionRepairVisitor.repairMethodType(
        this,
        invocationTree,
        origMethodType,
        methodTypeAtCallSite,
        invocationPath,
        state,
        config,
        calledFromDataflow);
  }

  /**
   * A generic call, and the corresponding information about its assignment context, for the
   * purposes of inference.
   */
  private record CallAndContext(
      ExpressionTree call, @Nullable Type typeFromAssignmentContext, boolean assignedToLocal) {}

  /**
   * Given a {@link TreePath} to an invocation of a generic method, collect information about the
   * appropriate invocation on which to perform type inference, and the relevant information from
   * the assignment context for that invocation.
   *
   * <p>Note that in the case of nested invocations, we want to find the outermost invocation that
   * requires inference, since that is the one whose assignment context is relevant. For example, if
   * we have {@code <T extends @Nullable Object> T id(T t);} and the call {@code id(id(x))}, given a
   * {@link TreePath} to the inner call, we want to return the outer call, since its assignment
   * context is required for inference.
   *
   * @param path the path to the invocation
   * @param state the visitor state
   * @param calledFromDataflow true if this inference is being done as part of dataflow analysis
   * @return the correct invocation on which to perform inference, along with the relevant
   *     assignment context information. If no assignment context is available, the
   *     typeFromAssignmentContext field of the result will be null.
   */
  private CallAndContext getCallAndContextForInference(
      TreePath path, VisitorState state, boolean calledFromDataflow) {
    CallAndContext completedContext = completedCallContexts.get(path.getLeaf());
    if (completedContext != null) {
      return completedContext;
    }
    TreePath parentPath = path.getParentPath();
    Tree parent = parentPath.getLeaf();
    while (parent instanceof ParenthesizedTree) {
      parentPath = parentPath.getParentPath();
      parent = parentPath.getLeaf();
    }
    if (parent instanceof ExpressionTree parentCall
        && (parentCall instanceof MethodInvocationTree || parentCall instanceof NewClassTree)
        && isCallNeedingInference(parentCall)) {
      // This is a nested generic call, such as id(id(x)) or id(new Box<>(x)). Find the
      // outermost call requiring inference because its assignment context is the relevant one.
      return getCallAndContextForInference(
          parentPath, state.withPath(parentPath), calledFromDataflow);
    }
    return getDirectCallContextForInference(path, state, calledFromDataflow);
  }

  /**
   * Returns the context immediately surrounding a call, without traversing nested generic calls.
   */
  private CallAndContext getDirectCallContextForInference(
      TreePath path, VisitorState state, boolean calledFromDataflow) {
    ExpressionTree call = (ExpressionTree) path.getLeaf();
    TreePath parentPath = path.getParentPath();
    Tree parent = parentPath.getLeaf();
    while (parent instanceof ParenthesizedTree) {
      parentPath = parentPath.getParentPath();
      parent = parentPath.getLeaf();
    }
    if (parent instanceof AssignmentTree || parent instanceof VariableTree) {
      TargetTypeAndAssignmentKind targetTypeAndAssignmentKind =
          getTargetTypeForAssignmentContext(parent, state.withPath(parentPath), calledFromDataflow);
      return new CallAndContext(
          call,
          targetTypeAndAssignmentKind.typeFromAssignmentContext(),
          targetTypeAndAssignmentKind.assignedToLocal());
    } else if (parent instanceof ReturnTree) {
      // find the enclosing method and return its return type
      TreePath enclosingMethodOrLambda =
          NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(parentPath);
      if (enclosingMethodOrLambda != null
          && enclosingMethodOrLambda.getLeaf() instanceof LambdaExpressionTree lambda) {
        return new CallAndContext(call, getLambdaReturnTargetType(lambda, state), false);
      }
      if (enclosingMethodOrLambda != null
          && enclosingMethodOrLambda.getLeaf() instanceof MethodTree enclosingMethod) {
        Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(enclosingMethod);
        if (methodSymbol != null) {
          return new CallAndContext(call, methodSymbol.getReturnType(), false);
        }
      }
    } else if (parent instanceof LambdaExpressionTree lambda) {
      return new CallAndContext(call, getLambdaReturnTargetType(lambda, state), false);
    } else if (parent instanceof ExpressionTree exprParent) {
      // could be a parameter to another method call, or part of a conditional expression, etc.
      // in any case, just return the type of the parent expression
      if (exprParent instanceof MethodInvocationTree parentInvocation) {
        // the call is either a regular parameter to the parent call, or the receiver expression
        Type.MethodType parentMethodType =
            getInvokedMethodTypeAtCall(
                parentInvocation, parentPath, state.withPath(parentPath), calledFromDataflow);
        Type formalParamType =
            getFormalParameterTypeForArgument(parentInvocation, parentMethodType, call);
        if (formalParamType == null) {
          // this can happen if the call is the receiver expression of the parent call, e.g.,
          // id(x).foo() (note that foo() need not be generic)
          ExpressionTree methodSelect =
              ASTHelpers.stripParentheses(parentInvocation.getMethodSelect());
          if (methodSelect instanceof MemberSelectTree mst) {
            @SuppressWarnings("ReferenceEquality") // deliberate reference equality check
            boolean callIsReceiver = ASTHelpers.stripParentheses(mst.getExpression()) == call;
            if (callIsReceiver) {
              // the call is the receiver expression, so we want the enclosing type of the parent
              // invocation
              formalParamType =
                  getEnclosingTypeForCallExpression(
                      ASTHelpers.getSymbol(parentInvocation),
                      parentInvocation,
                      parentPath,
                      state.withPath(parentPath),
                      calledFromDataflow);
            } else {
              throw new RuntimeException(
                  "did not find invocation "
                      + state.getSourceForNode(call)
                      + " as receiver expression of "
                      + state.getSourceForNode(parentInvocation));
            }
          }
        }
        return new CallAndContext(call, formalParamType, false);
      } else if (exprParent instanceof ConditionalExpressionTree) {
        TreePath conditionalPath = getOutermostConditionalExpressionPath(parentPath);
        TargetTypeAndAssignmentKind targetTypeAndAssignmentKind =
            getTargetTypeForConditionalExpression(
                (ConditionalExpressionTree) conditionalPath.getLeaf(),
                state.withPath(conditionalPath),
                calledFromDataflow);
        return new CallAndContext(
            call,
            targetTypeAndAssignmentKind.typeFromAssignmentContext(),
            targetTypeAndAssignmentKind.assignedToLocal());
      } else if (exprParent instanceof NewClassTree parentConstructorCall) {
        Type parentClassType;
        if (isCallNeedingInference(parentConstructorCall)) {
          CallAndContext parentContext =
              getCallAndContextForInference(parentPath, state, calledFromDataflow);
          parentClassType =
              inferCallType(
                  state,
                  parentConstructorCall,
                  parentPath,
                  parentContext.typeFromAssignmentContext,
                  parentContext.assignedToLocal,
                  calledFromDataflow);
        } else {
          parentClassType =
              getTreeType(parentConstructorCall, state.withPath(parentPath), calledFromDataflow);
        }
        if (parentClassType != null) {
          Symbol.MethodSymbol parentCtorSymbol = ASTHelpers.getSymbol(parentConstructorCall);
          Type parentCtorType =
              TypeSubstitutionUtils.memberType(
                  state.getTypes(), parentClassType, parentCtorSymbol, config);
          return new CallAndContext(
              call,
              getFormalParameterTypeForArgument(
                  parentConstructorCall, parentCtorType.asMethodType(), call),
              false);
        }
      }
    }
    // an unhandled case; for now, give up and return no assignment context
    return new CallAndContext(call, null, false);
  }

  /**
   * Returns the resolved method type at a call site after receiver and inferred type-argument
   * substitutions and handler-provided models have been applied. Unlike {@link
   * #getCallForInference}, this method substitutes the omitted type variables with inferred type
   * arguments.
   */
  private Type.MethodType getInvokedMethodTypeAtCall(
      Tree tree, @Nullable TreePath path, VisitorState state, boolean calledFromDataflow) {
    InferenceCall call = callSite((ExpressionTree) tree).resolve(path, state, calledFromDataflow);
    Type.MethodType invokedMethodType =
        getResolvedCallSignature(call, path, state, calledFromDataflow);
    return handler.onOverrideMethodType(
        call.site.symbol,
        invokedMethodType,
        state,
        tree instanceof MethodInvocationTree invocationTree ? invocationTree : null);
  }

  /**
   * Computes the nullness of a formal parameter of a generic method at an invocation, in the
   * context of the declared type of its receiver argument. If the formal parameter's type is a type
   * variable, its nullness depends on the nullability of the corresponding type parameter in the
   * receiver's type.
   *
   * <p>Consider the following example:
   *
   * <pre>{@code
   * interface Fn<P extends @Nullable Object, R extends @Nullable Object> {
   *     R apply(P p);
   * }
   * class C implements Fn<@Nullable String, String> {
   *     public String apply(@Nullable String p) {
   *         return "";
   *     }
   * }
   * static void m() {
   *     Fn<@Nullable String, String> f = new C();
   *     f.apply(null);
   * }
   * }</pre>
   *
   * <p>The declared type of {@code f} passes {@code Nullable String} as the type parameter for type
   * variable {@code P}. So, it is legal to pass {@code null} as a parameter to {@code f.apply}.
   *
   * @param paramIndex parameter index
   * @param invokedMethodSymbol symbol for the invoked method
   * @param tree the tree for the invocation
   * @param state the visitor state
   * @return Nullness of parameter at {@code paramIndex}, or {@code NONNULL} if the call does not
   *     invoke an instance method
   */
  public Nullness getGenericParameterNullnessAtInvocation(
      int paramIndex, Symbol.MethodSymbol invokedMethodSymbol, Tree tree, VisitorState state) {
    boolean isVarargsParam =
        invokedMethodSymbol.isVarArgs()
            && paramIndex == invokedMethodSymbol.getParameters().size() - 1;
    // If generic method invocation
    if (!invokedMethodSymbol.getTypeParameters().isEmpty()) {
      // Substitute the argument types within the MethodType
      List<Type> substitutedParamTypes =
          getInvokedMethodTypeAtCall(tree, null, state, false).getParameterTypes();
      // If this condition evaluates to false, we fall through to the subsequent logic, to handle
      // type variables declared on the enclosing class
      if (substitutedParamTypes != null
          && Objects.equals(
              getParameterTypeNullness(
                  substitutedParamTypes.get(paramIndex), isVarargsParam, state),
              Nullness.NULLABLE)) {
        return Nullness.NULLABLE;
      }
    }

    Type enclosingType =
        getEnclosingTypeForCallExpression(invokedMethodSymbol, tree, null, state, false);
    if (enclosingType == null) {
      return Nullness.NONNULL;
    }

    return getGenericMethodParameterNullness(paramIndex, invokedMethodSymbol, enclosingType, state);
  }

  /**
   * Returns the nullness of the values in the map on which a method is invoked, e.g., a call to
   * {@code containsKey()}. The nullness comes from the {@code V} type argument of the receiver type
   * as a {@code java.util.Map}. For example, the result is {@link Nullness#NULLABLE} for a receiver
   * of type {@code Map<String, @Nullable Object>} or {@code Map<String, ? extends @Nullable
   * Object>}.
   *
   * <p>If {@code V} is a type variable without a {@code @Nullable} annotation, we return {@link
   * Nullness#NONNULL}, consistent with how reads of type-variable values are treated elsewhere.
   *
   * @param tree the invocation of a method on a map
   * @param path the path to {@code tree}
   * @param state the visitor state
   * @return the nullness of the map values, or {@link Nullness#NONNULL} if it cannot be determined
   */
  public Nullness getMapValueNullnessAtInvocation(
      MethodInvocationTree tree, TreePath path, VisitorState state) {
    Symbol.MethodSymbol invokedMethodSymbol = ASTHelpers.getSymbol(tree);
    Type receiverType =
        getEnclosingTypeForCallExpression(
            invokedMethodSymbol, tree, path, state, /* calledFromDataflow= */ true);
    if (receiverType == null) {
      return Nullness.NONNULL;
    }
    Symbol.ClassSymbol mapSymbol = (Symbol.ClassSymbol) MAP_TYPE_SUPPLIER.get(state).tsym;
    Type mapType = TypeSubstitutionUtils.asSuper(state.getTypes(), receiverType, mapSymbol, config);
    if (mapType == null || mapType.getTypeArguments().size() != 2) {
      // e.g., a raw map type
      return Nullness.NONNULL;
    }
    return getTypeNullnessForRead(mapType.getTypeArguments().get(1), state);
  }

  /**
   * Gets the enclosing type for a non-static method call expression, which is either the type of
   * the enclosing class (for implicit this calls) or the type of the receiver expression. For a
   * constructor call, we treat the type being allocated as the enclosing type.
   *
   * @param invokedMethodSymbol symbol for the invoked method
   * @param tree the tree for the invocation
   * @param path the path to the invocation tree, or null if not available
   * @param state the visitor state
   * @param calledFromDataflow whether this method is being called from dataflow analysis
   * @return the enclosing type for the method call, or null if it cannot be determined
   */
  private @Nullable Type getEnclosingTypeForCallExpression(
      Symbol.MethodSymbol invokedMethodSymbol,
      Tree tree,
      @Nullable TreePath path,
      VisitorState state,
      boolean calledFromDataflow) {
    Type enclosingType = null;
    if (tree instanceof MethodInvocationTree methodInvocationTree) {
      if (invokedMethodSymbol.isStatic()) {
        return null;
      }
      ExpressionTree methodSelect =
          ASTHelpers.stripParentheses(methodInvocationTree.getMethodSelect());
      if (methodSelect instanceof IdentifierTree) {
        // implicit this parameter, or a super call.  in either case, use the type of the enclosing
        // class.
        TreePath basePath = (path != null) ? path : state.getPath();
        ClassTree enclosingClassTree = ASTHelpers.findEnclosingNode(basePath, ClassTree.class);
        if (enclosingClassTree != null) {
          enclosingType = castToNonNull(ASTHelpers.getType(enclosingClassTree));
        }
      } else if (methodSelect instanceof MemberSelectTree memberSelectTree) {
        ExpressionTree receiver = ASTHelpers.stripParentheses(memberSelectTree.getExpression());
        TreePath curPath = path != null ? path : state.getPath();
        TreePath receiverPath = pathWithLeaf(curPath, receiver);
        if (isCallNeedingInference(receiver)) {
          enclosingType =
              inferCallType(
                  state.withPath(receiverPath),
                  receiver,
                  receiverPath,
                  null,
                  false,
                  calledFromDataflow);
        } else {
          enclosingType = getTreeType(receiver, state.withPath(receiverPath), calledFromDataflow);
        }
      }
    } else {
      Verify.verify(tree instanceof NewClassTree);
      // for a constructor invocation, the type from the invocation itself is the "enclosing type"
      // for the purposes of determining type arguments
      enclosingType = getTreeType(tree, state, calledFromDataflow);
    }
    return enclosingType;
  }

  /**
   * Computes the nullability of a parameter type of some generic method when seen as a member of
   * some class {@code C}, based on type parameter nullability within {@code C}.
   *
   * <p>Consider the following example:
   *
   * <pre>{@code
   * interface Fn<P extends @Nullable Object, R extends @Nullable Object> {
   *     R apply(P p);
   * }
   * class C implements Fn<@Nullable String, String> {
   *     public String apply(@Nullable String p) {
   *         return "";
   *     }
   * }
   * }</pre>
   *
   * <p>Within the context of class {@code C}, the method {@code Fn.apply} has a parameter type of
   * {@code @Nullable String}, since {@code @Nullable String} is passed as the type parameter for
   * {@code P}. Hence, overriding method {@code C.apply} must take a {@code @Nullable String} as a
   * parameter.
   *
   * @param parameterIndex index of the parameter
   * @param method the generic method
   * @param enclosingSymbol the enclosing symbol in which we want to know {@code method}'s parameter
   *     type nullability
   * @param state the visitor state
   * @return nullability of the relevant parameter type of {@code method} in the context of {@code
   *     enclosingSymbol}
   */
  public Nullness getGenericMethodParameterNullness(
      int parameterIndex, Symbol.MethodSymbol method, Symbol enclosingSymbol, VisitorState state) {
    Type enclosingType = getTypeForSymbol(enclosingSymbol, state);
    return getGenericMethodParameterNullness(parameterIndex, method, enclosingType, state);
  }

  /**
   * Just like {@link #getGenericMethodParameterNullness(int, Symbol.MethodSymbol, Symbol,
   * VisitorState)}, but takes the enclosing {@code Type} rather than the enclosing {@code Symbol}.
   *
   * @param parameterIndex index of the parameter
   * @param method the generic method
   * @param enclosingType the enclosing type in which we want to know {@code method}'s parameter
   *     type nullability
   * @param state the visitor state
   * @return nullability of the relevant parameter type of {@code method} in the context of {@code
   *     enclosingType}
   */
  public Nullness getGenericMethodParameterNullness(
      int parameterIndex,
      Symbol.MethodSymbol method,
      @Nullable Type enclosingType,
      VisitorState state) {
    if (enclosingType == null) {
      // we have no additional information from generics, so return NONNULL (presence of a top-level
      // @Nullable annotation is handled elsewhere)
      return Nullness.NONNULL;
    }
    boolean isVarargsParam =
        method.isVarArgs() && parameterIndex == method.getParameters().size() - 1;

    Type methodType =
        TypeSubstitutionUtils.memberType(state.getTypes(), enclosingType, method, config);
    Type paramType = methodType.getParameterTypes().get(parameterIndex);
    return getParameterTypeNullness(paramType, isVarargsParam, state);
  }

  /**
   * This method compares the type parameter annotations for overriding method parameters with
   * corresponding type parameters for the overridden method and reports an error if they don't
   * match
   *
   * @param tree tree for overriding method
   * @param overriddenMethodType type of the overridden method
   * @param state the visitor state
   */
  private void checkTypeParameterNullnessForOverridingMethodParameterType(
      MethodTree tree, Type overriddenMethodType, VisitorState state) {
    List<? extends VariableTree> methodParameters = tree.getParameters();
    List<Type> overriddenMethodParameterTypes = overriddenMethodType.getParameterTypes();
    for (int i = 0; i < methodParameters.size(); i++) {
      Type overridingMethodParameterType = getTreeType(methodParameters.get(i), state);
      Type overriddenMethodParameterType = overriddenMethodParameterTypes.get(i);
      if (overridingMethodParameterType != null) {
        // allow contravariant subtyping
        if (!subtypeParameterNullability(
            overridingMethodParameterType, overriddenMethodParameterType, state)) {
          reportInvalidOverridingMethodParamTypeError(
              methodParameters.get(i),
              overriddenMethodParameterType,
              overridingMethodParameterType,
              state);
        }
      }
    }
  }

  /**
   * This method compares the type parameter annotations for an overriding method's return type with
   * corresponding type parameters for the overridden method and reports an error if they don't
   * match
   *
   * @param tree tree for overriding method
   * @param overriddenMethodType type of the overridden method
   * @param state the visitor state
   */
  private void checkTypeParameterNullnessForOverridingMethodReturnType(
      MethodTree tree, Type overriddenMethodType, VisitorState state) {
    Type overriddenMethodReturnType = overriddenMethodType.getReturnType();
    // We get the return type from the Symbol; the type attached to tree may not have correct
    // annotations for array types
    Type overridingMethodReturnType = ASTHelpers.getSymbol(tree).getReturnType();
    if (overriddenMethodReturnType.isRaw() || overridingMethodReturnType.isRaw()) {
      return;
    }
    // allow covariant subtyping
    if (!subtypeParameterNullability(
        overriddenMethodReturnType, overridingMethodReturnType, state)) {
      reportInvalidOverridingMethodReturnTypeError(
          tree, overriddenMethodReturnType, overridingMethodReturnType, state);
    }
  }

  /**
   * Returns the nullness of a formal parameter type, based on the nullability annotations on the
   * type.
   *
   * @param type The type of the parameter
   * @param isVarargsParam true if the parameter is a varargs parameter
   * @return The nullness of the parameter type
   */
  private Nullness getParameterTypeNullness(Type type, boolean isVarargsParam, VisitorState state) {
    if (isVarargsParam) {
      // type better be an array type
      verify(
          type.getKind().equals(TypeKind.ARRAY),
          "expected array type for varargs parameter, got %s",
          type);
      // use the component type to determine nullness
      Type.ArrayType arrayType = (Type.ArrayType) type;
      Type componentType = arrayType.getComponentType();
      return getParameterTypeNullness(componentType, false, state);
    } else {
      if (config.handleWildcardGenerics()) {
        if (type instanceof Type.CapturedType captured
            && captured.getUpperBound().getAnnotationMirrors().stream()
                .anyMatch(annotation -> isSyntheticNullnessAnnotation(annotation.type))) {
          // Substitution stores solved capture nullness on its structural upper bound.
          return getTypeNullness(captured.getUpperBound());
        }
        Type.WildcardType wildcardType = GenericsUtils.asWildcard(type);
        if (wildcardType != null && wildcardType.kind == BoundKind.SUPER) {
          return getTypeNullness(castToNonNull(wildcardType.getSuperBound()));
        }
      }
      return getTypeNullness(type);
    }
  }

  /**
   * @param type A type for which we need the Nullness.
   * @return Returns the Nullness of the type based on the Nullability annotation.
   */
  private Nullness getTypeNullness(Type type) {
    return isNullableAnnotated(type) ? Nullness.NULLABLE : Nullness.NONNULL;
  }

  /**
   * Returns the nullness of a value read from {@code type}. For wildcard and javac captured
   * wildcard types, uses the effective upper bound: a read from {@code Foo<? extends @Nullable
   * Object>} or {@code Foo<? super String>} can produce any value permitted by the capture's upper
   * bound.
   *
   * @param type type from which a value is read
   * @param state visitor state
   * @return nullness of a value read from {@code type}
   */
  private Nullness getTypeNullnessForRead(Type type, VisitorState state) {
    return getTypeNullnessForRead(type, state, /* followUnsubstitutedTypeVarUpperBound= */ false);
  }

  /**
   * Returns the nullness of a value read from {@code type}, optionally following the upper bound of
   * an unsubstituted type variable.
   *
   * @param type type from which a value is read
   * @param state visitor state
   * @param followUnsubstitutedTypeVarUpperBound whether to use the upper bound when {@code type} is
   *     an unsubstituted type variable
   * @return nullness of a value read from {@code type}
   */
  private Nullness getTypeNullnessForRead(
      Type type, VisitorState state, boolean followUnsubstitutedTypeVarUpperBound) {
    if (getTypeNullness(type).equals(Nullness.NULLABLE)) {
      return Nullness.NULLABLE;
    }
    if (config.handleWildcardGenerics() && GenericsUtils.asWildcard(type) != null) {
      Type effectiveUpperBound =
          GenericsUtils.effectiveWildcardUpperBound(type, state, config, handler);
      return getTypeNullnessForRead(
          effectiveUpperBound, state, /* followUnsubstitutedTypeVarUpperBound= */ true);
    }
    if (followUnsubstitutedTypeVarUpperBound && type instanceof Type.TypeVar typeVar) {
      Type upperBound = typeVar.getUpperBound();
      if (upperBound != null) {
        return getTypeNullnessForRead(
            upperBound, state, /* followUnsubstitutedTypeVarUpperBound= */ true);
      }
    }
    return Nullness.NONNULL;
  }

  /**
   * Returns a pretty-printed representation of type suitable for error messages. The representation
   * uses simple names rather than fully-qualified names, and retains all type-use annotations.
   */
  private static String prettyTypeForError(Type type, VisitorState state) {
    return type.accept(new GenericTypePrettyPrintingVisitor(state), null);
  }

  /**
   * Returns a pretty-printed signature for {@code method}, with its parameter types substituted for
   * the type arguments of {@code enclosingType} and printed with their nullability annotations,
   * e.g. {@code function(@Nullable Object)} rather than the raw, unsubstituted {@code
   * function(T1)}.
   *
   * @param method the method, usually a generic functional interface method
   * @param enclosingType the enclosing type providing the type arguments for substitution, or
   *     {@code null} if no such type is available, in which case the raw, unsubstituted signature
   *     of {@code method} is returned
   * @param state the visitor state
   * @return a pretty-printed, substituted signature for {@code method}
   */
  public String prettySubstitutedMethodSignature(
      Symbol.MethodSymbol method, @Nullable Type enclosingType, VisitorState state) {
    if (enclosingType == null) {
      return method.toString();
    }
    Type methodType =
        TypeSubstitutionUtils.memberType(state.getTypes(), enclosingType, method, config);
    String paramTypesString =
        methodType.getParameterTypes().stream()
            .map(paramType -> prettyTypeForError(paramType, state))
            .collect(joining(","));
    return method.getSimpleName() + "(" + paramTypesString + ")";
  }

  /**
   * Checks if a given expression <em>e</em> is a lambda or method reference such that (1) the
   * declared return type of the method for <em>e</em> is a generic type variable, and (2)
   * <em>e</em> is being passed as a parameter to an unannotated method. In such cases, the caller
   * should treat <em>e</em> as being allowed to return a {@code Nullable} value, even if the
   * locally-computed type of the expression is not {@code @Nullable}. This special treatment is
   * necessary to properly avoid reporting errors when interacting with unannotated / unmarked code.
   *
   * @param methodSymbol the symbol for the method corresponding to <em>e</em>
   * @param expressionTree the expression <em>e</em>
   * @param state visitor state
   * @param codeAnnotationInfo information on which code is annotated
   */
  public boolean passingLambdaOrMethodRefWithGenericReturnToUnmarkedCode(
      Symbol.MethodSymbol methodSymbol,
      ExpressionTree expressionTree,
      VisitorState state,
      CodeAnnotationInfo codeAnnotationInfo) {
    Type methodType = methodSymbol.type;
    boolean returnsGeneric = methodType.getReturnType() instanceof TypeVariable;
    if (!returnsGeneric) {
      return false;
    }
    boolean callingUnannotated = false;
    TreePath path = state.getPath();
    while (path != null && !path.getLeaf().equals(expressionTree)) {
      path = path.getParentPath();
    }
    verify(path != null, "did not find lambda or method reference tree in TreePath");
    Tree parentOfLambdaTree = path.getParentPath().getLeaf();
    if (parentOfLambdaTree instanceof MethodInvocationTree methodInvocationTree) {
      Symbol.MethodSymbol parentMethodSymbol = ASTHelpers.getSymbol(methodInvocationTree);
      callingUnannotated =
          codeAnnotationInfo.isSymbolUnannotated(parentMethodSymbol, config, handler);
    }
    return callingUnannotated;
  }

  /**
   * Clears the cache of inferred substitutions for generic method calls. This should be invoked
   * after each CompilationUnit to avoid memory leaks.
   */
  public void clearCache() {
    inferredTypeVarNullabilityForGenericCalls.clear();
    callSites.clear();
    completedCallContexts.clear();
    inferredResultsForGenericMethodReferences.clear();
    methodReferenceInferenceInProgress.clear();
    callsWithReportedInferenceFailures.clear();
    reportedNestedUpperBoundViolations.clear();
    reportedLowerConflicts.clear();
    diagnosticInferenceResults.clear();
    inferredPolyExpressionTypes.clear();
    inferredVarLocalTypes.clear();
    varLocalDeclarations.clear();
    nestedNullabilityRepairInProgress.clear();
  }

  public boolean isNullableAnnotated(Type type) {
    return TypeSubstitutionUtils.hasNullableRoot(type, config);
  }

  /**
   * Store a poly expression's target type with explicit type-variable nullability from the
   * assignment context, when the expression type has not already been cached.
   *
   * <p>This is used to compensate for javac dropping annotations on type variables in poly
   * expression target types, so later checks use the correctly annotated functional interface type.
   */
  private void maybeStorePolyExpressionTypeFromTarget(
      Tree polyExpressionTree, Type targetType, VisitorState state) {
    if (targetType.isRaw() || inferredPolyExpressionTypes.containsKey(polyExpressionTree)) {
      return;
    }
    Type polyExpressionType = ASTHelpers.getType(polyExpressionTree);
    if (polyExpressionType == null) {
      return;
    }
    Type groundTargetType = GenericsUtils.groundTargetType(targetType, state, config, handler);
    Type polyExpressionTypeWithTargetAnnotations =
        TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
            groundTargetType, polyExpressionType, config);
    inferredPolyExpressionTypes.put(polyExpressionTree, polyExpressionTypeWithTargetAnnotations);
  }

  private static @Nullable Type syntheticNullableAnnotType;
  private static @Nullable Type syntheticNonNullAnnotType;

  /** Returns whether {@code annotationType} is one of NullAway's synthetic nullness annotations. */
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"}) // deliberate singleton identity checks
  static boolean isSyntheticNullnessAnnotation(Type annotationType) {
    return annotationType == syntheticNullableAnnotType
        || annotationType == syntheticNonNullAnnotType;
  }

  /**
   * Returns a "fake" {@link Type} object representing a synthetic {@code @Nullable} annotation.
   *
   * <p>This is needed for cases where we need to treat a type as nullable, but there is no actual
   * {@code @Nullable} annotation in the code. The returned type is an {@link Type.ErrorType}. We
   * cannot create a proper {@link Type.ClassType} from outside the {@code com.sun.tools.javac.code}
   * package, so this is the best we can do. Given this is a "fake" type, {@code ErrorType} seems
   * appropriate.
   *
   * @param state the visitor state, used to access javac internals like {@link Names} and {@link
   *     Symtab}.
   * @return a fake {@code Type} for a synthetic {@code @Nullable} annotation.
   */
  public static Type getSyntheticNullableAnnotType(VisitorState state) {
    if (syntheticNullableAnnotType == null) {
      Names names = Names.instance(state.context);
      Symtab symtab = Symtab.instance(state.context);
      Name name = names.fromString("nullaway.synthetic");
      Symbol.PackageSymbol packageSymbol = new Symbol.PackageSymbol(name, symtab.noSymbol);
      Name simpleName = names.fromString("Nullable");
      syntheticNullableAnnotType = new Type.ErrorType(simpleName, packageSymbol, Type.noType);
    }
    return syntheticNullableAnnotType;
  }

  /**
   * Returns a "fake" {@link Type} object representing a synthetic {@code @NonNull} annotation.
   *
   * <p>This is used when we need to treat a type as non-null, but no actual {@code @NonNull}
   * annotation exists in source.
   *
   * @param state the visitor state, used to access javac internals like {@link Names} and {@link
   *     Symtab}.
   * @return a fake {@code Type} for a synthetic {@code @NonNull} annotation.
   */
  public static Type getSyntheticNonNullAnnotType(VisitorState state) {
    if (syntheticNonNullAnnotType == null) {
      Names names = Names.instance(state.context);
      Symtab symtab = Symtab.instance(state.context);
      Name name = names.fromString("nullaway.synthetic");
      Symbol.PackageSymbol packageSymbol = new Symbol.PackageSymbol(name, symtab.noSymbol);
      Name simpleName = names.fromString("NonNull");
      syntheticNonNullAnnotType = new Type.ErrorType(simpleName, packageSymbol, Type.noType);
    }
    return syntheticNonNullAnnotType;
  }
}
