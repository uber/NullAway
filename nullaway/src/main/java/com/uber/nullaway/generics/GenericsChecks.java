package com.uber.nullaway.generics;

import static com.google.common.base.Verify.verify;
import static com.uber.nullaway.NullabilityUtil.castToNonNull;
import static com.uber.nullaway.generics.ConstraintSolver.InferredNullability.NULLABLE;

import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
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
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.TargetType;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.ListBuffer;
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
import com.uber.nullaway.generics.ConstraintSolver.UnsatisfiableConstraintsException;
import com.uber.nullaway.handlers.Handler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeVariable;
import org.jspecify.annotations.Nullable;

/** Methods for performing checks related to generic types and nullability. */
public final class GenericsChecks {

  /** Marker interface for results of attempting to infer nullability of type variables at a call */
  private interface MethodInferenceResult {}

  /**
   * Indicates successful inference of nullability of type variables at a call. Stores the inferred
   * type variable nullability.
   */
  private static final class InferenceSuccess implements MethodInferenceResult {
    final Map<Element, ConstraintSolver.InferredNullability> typeVarNullability;

    InferenceSuccess(Map<Element, ConstraintSolver.InferredNullability> typeVarNullability) {
      this.typeVarNullability = typeVarNullability;
    }
  }

  /** Indicates failed inference of nullability of type variables at a call */
  private static final class InferenceFailure implements MethodInferenceResult {
    @SuppressWarnings("UnusedVariable") // keep this as it may be useful in the future
    final @Nullable String errorMessage;

    InferenceFailure(@Nullable String errorMessage) {
      this.errorMessage = errorMessage;
    }
  }

  /**
   * Maps a Tree representing a call to a generic method or constructor to the result of inferring
   * its type argument nullability. The call must not have any explicit type arguments. If a tree is
   * not present as a key in this map, it means inference has not yet been attempted for that call.
   */
  private final Map<MethodInvocationTree, MethodInferenceResult>
      inferredTypeVarNullabilityForGenericCalls = new LinkedHashMap<>();

  /**
   * Maps each {@code LambdaExpressionTree} passed as a parameter to a generic method to its
   * inferred type, if inference for the generic method call succeeded.
   */
  private final Map<LambdaExpressionTree, Type> inferredLambdaTypes = new LinkedHashMap<>();

  public @Nullable Type getInferredLambdaType(LambdaExpressionTree tree) {
    return inferredLambdaTypes.get(tree);
  }

  private final NullAway analysis;
  private final Config config;
  private final Handler handler;

  public GenericsChecks(NullAway analysis, Config config, Handler handler) {
    this.analysis = analysis;
    this.config = config;
    this.handler = handler;
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
      if (curTypeArg instanceof AnnotatedTypeTree) {
        AnnotatedTypeTree annotatedType = (AnnotatedTypeTree) curTypeArg;
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

  private boolean[] getTypeParamsWithNullableUpperBound(Type type) {
    Symbol.TypeSymbol tsym = type.tsym;
    com.sun.tools.javac.util.List<Type> baseTypeArgs = tsym.type.getTypeArguments();
    boolean[] result = new boolean[baseTypeArgs.size()];
    for (int i = 0; i < baseTypeArgs.size(); i++) {
      Type typeVariable = baseTypeArgs.get(i);
      Type upperBound = typeVariable.getUpperBound();
      com.sun.tools.javac.util.List<Attribute.TypeCompound> annotationMirrors =
          upperBound.getAnnotationMirrors();
      if (Nullness.hasNullableAnnotation(annotationMirrors.stream(), config)
          || handler.onOverrideTypeParameterUpperBound(type.tsym.toString(), i)) {
        result[i] = true;
      }
    }
    // For handling types declared in bytecode rather than source code.
    // Due to a bug in javac versions before JDK 22 (https://bugs.openjdk.org/browse/JDK-8225377),
    // the above code does not work for types declared in bytecode.  We need to read the raw type
    // attributes instead.
    com.sun.tools.javac.util.List<Attribute.TypeCompound> rawTypeAttributes =
        tsym.getRawTypeAttributes();
    if (rawTypeAttributes != null) {
      for (Attribute.TypeCompound typeCompound : rawTypeAttributes) {
        if (typeCompound.position.type.equals(TargetType.CLASS_TYPE_PARAMETER_BOUND)
            && Nullness.isNullableAnnotation(
                typeCompound.type.tsym.getQualifiedName().toString(), config)) {
          int index = typeCompound.position.parameter_index;
          result[index] = true;
        }
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
    List<? extends Tree> typeArguments;
    switch (tree.getKind()) {
      case METHOD_INVOCATION:
        typeArguments = ((MethodInvocationTree) tree).getTypeArguments();
        break;
      case NEW_CLASS:
        typeArguments = ((NewClassTree) tree).getTypeArguments();
        break;
      default:
        throw new RuntimeException("Unexpected tree kind: " + tree.getKind());
    }
    if (typeArguments.isEmpty()) {
      return;
    }
    // get Nullable annotated type arguments
    Map<Integer, Tree> nullableTypeArguments = new HashMap<>();
    for (int i = 0; i < typeArguments.size(); i++) {
      Tree curTypeArg = typeArguments.get(i);
      if (curTypeArg instanceof AnnotatedTypeTree) {
        AnnotatedTypeTree annotatedType = (AnnotatedTypeTree) curTypeArg;
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
    Symbol.MethodSymbol methodSymbol =
        castToNonNull((Symbol.MethodSymbol) ASTHelpers.getSymbol(tree));

    // check if type variables are allowed to be Nullable
    Type baseType = methodSymbol.asType();
    List<Type> baseTypeVariables = baseType.getTypeArguments();
    for (int i = 0; i < baseTypeVariables.size(); i++) {
      if (nullableTypeArguments.containsKey(i)) {
        Type typeVariable = baseTypeVariables.get(i);
        Type upperBound = typeVariable.getUpperBound();
        com.sun.tools.javac.util.List<Attribute.TypeCompound> annotationMirrors =
            upperBound.getAnnotationMirrors();
        boolean hasNullableAnnotation =
            Nullness.hasNullableAnnotation(annotationMirrors.stream(), config)
                || handler.onOverrideTypeParameterUpperBound(baseType.tsym.toString(), i);
        // if type variable's upper bound does not have @Nullable annotation then the instantiation
        // is invalid
        if (!hasNullableAnnotation) {
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
                methodSymbol.toString(), typeVariable.tsym.toString()));
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
    String result =
        String.format(
            "incompatible types: %s cannot be converted to %s",
            prettyRhsType, prettyTypeForError(lhsType, state));
    if (!ASTHelpers.isSameType(lhsType, rhsType, state)
        && lhsType.getKind() == TypeKind.DECLARED
        && rhsType.getKind() == TypeKind.DECLARED) {
      Symbol.TypeSymbol lhsSym = lhsType.asElement();
      if (lhsSym instanceof Symbol.ClassSymbol) {
        Type asSuper =
            TypeSubstitutionUtils.asSuper(
                state.getTypes(), rhsType, (Symbol.ClassSymbol) lhsSym, config);
        if (asSuper != null) {
          result +=
              String.format(
                  " (%s is a subtype of %s)", prettyRhsType, prettyTypeForError(asSuper, state));
        }
      }
    }
    return result;
  }

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
   * @return Type of the tree with preserved annotations.
   */
  private @Nullable Type getTreeType(Tree tree, VisitorState state) {
    tree = ASTHelpers.stripParentheses(tree);
    if (tree instanceof NewClassTree
        && ((NewClassTree) tree).getIdentifier() instanceof ParameterizedTypeTree) {
      ParameterizedTypeTree paramTypedTree =
          (ParameterizedTypeTree) ((NewClassTree) tree).getIdentifier();
      if (paramTypedTree.getTypeArguments().isEmpty()) {
        // diamond operator, which we do not yet support; for now, return null
        // TODO: support diamond operators
        return null;
      }
      return typeWithPreservedAnnotations(paramTypedTree);
    } else if (tree instanceof NewArrayTree
        && ((NewArrayTree) tree).getType() instanceof AnnotatedTypeTree) {
      return typeWithPreservedAnnotations(tree);
    } else {
      Type result;
      if (tree instanceof VariableTree) {
        // type on the tree itself can be missing nested annotations for arrays; get the type from
        // the symbol for the variable instead
        result = castToNonNull(ASTHelpers.getSymbol(tree)).type;
      } else if (tree instanceof IdentifierTree) {
        // handle "this" specially, for cases where it appears inside an anonymous class
        IdentifierTree identifierTree = (IdentifierTree) tree;
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
        }
        result = symbol.type;
      } else if (tree instanceof AssignmentTree) {
        // type on the tree itself can be missing nested annotations for arrays; get the type from
        // the symbol for the assigned location instead, if available
        AssignmentTree assignmentTree = (AssignmentTree) tree;
        Symbol lhsSymbol = ASTHelpers.getSymbol(assignmentTree.getVariable());
        if (lhsSymbol != null) {
          result = lhsSymbol.type;
        } else {
          result = ASTHelpers.getType(assignmentTree);
        }
      } else {
        result = ASTHelpers.getType(tree);
      }
      if (result != null && result.isRaw()) {
        // bail out of any checking involving raw types for now
        return null;
      }
      return result;
    }
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
    if (tree instanceof VariableTree) {
      VariableTree varTree = (VariableTree) tree;
      rhsTree = varTree.getInitializer();
      assignedToLocal = isAssignmentToLocalVariable(varTree);
    } else if (tree instanceof AssignmentTree) {
      AssignmentTree assignmentTree = (AssignmentTree) tree;
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
    Type rhsType = getTreeType(rhsTree, state);
    if (rhsType != null) {
      if (isGenericCallNeedingInference(rhsTree)) {
        rhsType =
            inferGenericMethodCallType(
                state, (MethodInvocationTree) rhsTree, lhsType, assignedToLocal, false);
      }
      boolean isAssignmentValid = subtypeParameterNullability(lhsType, rhsType, state);
      if (!isAssignmentValid) {
        reportInvalidAssignmentInstantiationError(tree, lhsType, rhsType, state);
      }
    }
  }

  private static boolean isAssignmentToLocalVariable(Tree tree) {
    Symbol treeSymbol;
    if (tree instanceof VariableTree) {
      treeSymbol = ASTHelpers.getSymbol((VariableTree) tree);
    } else if (tree instanceof AssignmentTree) {
      AssignmentTree assignmentTree = (AssignmentTree) tree;
      treeSymbol = ASTHelpers.getSymbol(assignmentTree.getVariable());
    } else {
      throw new RuntimeException("Unexpected tree type: " + tree.getKind());
    }
    return treeSymbol != null && treeSymbol.getKind().equals(ElementKind.LOCAL_VARIABLE);
  }

  private ConstraintSolver makeSolver(VisitorState state, NullAway analysis) {
    return new ConstraintSolverImpl(config, state, analysis);
  }

  /**
   * Infers the type of a generic method call based on the assignment context. Side-effects the
   * #inferredSubstitutionsForGenericMethodCalls map with the inferred type.
   *
   * @param state the visitor state
   * @param invocationTree the method invocation tree representing the call to a generic method
   * @param typeFromAssignmentContext the type being "assigned to" in the assignment context
   * @param assignedToLocal true if the method call result is assigned to a local variable, false
   *     otherwise
   * @param calledFromDataflow true if this inference is being done as part of dataflow analysis
   * @return the type of the method call after inference
   */
  private Type inferGenericMethodCallType(
      VisitorState state,
      MethodInvocationTree invocationTree,
      @Nullable Type typeFromAssignmentContext,
      boolean assignedToLocal,
      boolean calledFromDataflow) {
    Verify.verify(isGenericCallNeedingInference(invocationTree));
    Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(invocationTree);
    Type type = methodSymbol.type;
    Map<Element, ConstraintSolver.InferredNullability> typeVarNullability = null;
    MethodInferenceResult result = inferredTypeVarNullabilityForGenericCalls.get(invocationTree);
    if (result == null) { // have not yet attempted inference for this call
      result =
          runInferenceForCall(
              state,
              null,
              invocationTree,
              typeFromAssignmentContext,
              assignedToLocal,
              calledFromDataflow);
    }
    if (result instanceof InferenceSuccess) {
      typeVarNullability = ((InferenceSuccess) result).typeVarNullability;
    }
    // we get the return type of the method call with inferred nullability of type variables
    // substituted in.  So, if the method returns List<T>, and we inferred T to be nullable, then
    // methodReturnTypeWithInferredNullability will be List<@Nullable T>.
    Type methodReturnTypeWithInferredNullability =
        getTypeWithInferredNullability(state, ((Type.ForAll) type).qtype, typeVarNullability)
            .getReturnType();
    Type returnTypeAtCallSite = castToNonNull(ASTHelpers.getType(invocationTree));
    // then, we apply those nullability annotations to the return type at the call site.
    // So, continuing the above example, if javac inferred the type of the call to be List<String>,
    // we will return List<@Nullable String>, correcting its nullability based on our own inference.
    // TODO optimize the above steps to avoid doing so many substitutions in the future, if needed
    return TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
        methodReturnTypeWithInferredNullability,
        returnTypeAtCallSite,
        config,
        Collections.emptyMap());
  }

  /**
   * Runs inference for a generic method call, side-effecting the
   * #inferredTypeVarNullabilityForGenericCalls map with the result.
   *
   * @param state the visitor state
   * @param path the tree path to the invocationTree if available and possibly distinct from {@code
   *     state.getPath()}
   * @param invocationTree the method invocation tree representing the call to a generic method
   * @param typeFromAssignmentContext the type being "assigned to" in the assignment context, or
   *     {@code null} if the type is unavailable or the method result is not assigned anywhere
   * @param assignedToLocal true if the method call result is assigned to a local variable, false
   *     otherwise
   * @param calledFromDataflow true if this inference is being done as part of dataflow analysis
   * @return the inference result, either success with inferred type variable nullability or failure
   *     with an error message
   */
  private MethodInferenceResult runInferenceForCall(
      VisitorState state,
      @Nullable TreePath path,
      MethodInvocationTree invocationTree,
      @Nullable Type typeFromAssignmentContext,
      boolean assignedToLocal,
      boolean calledFromDataflow) {
    Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(invocationTree);
    ConstraintSolver solver = makeSolver(state, analysis);
    // allInvocations tracks the top-level invocations and any nested invocations that also
    // require inference
    Set<MethodInvocationTree> allInvocations = new LinkedHashSet<>();
    allInvocations.add(invocationTree);
    Map<Element, ConstraintSolver.InferredNullability> typeVarNullability;
    try {
      generateConstraintsForCall(
          state,
          path,
          typeFromAssignmentContext,
          assignedToLocal,
          solver,
          methodSymbol,
          invocationTree,
          allInvocations,
          calledFromDataflow);
      typeVarNullability = solver.solve();

      // Store inferred types for lambda arguments
      new InvocationArguments(invocationTree, methodSymbol.type.asMethodType())
          .forEach(
              (argument, argPos, formalParamType, unused) -> {
                if (argument instanceof LambdaExpressionTree) {
                  Type inferredType =
                      getTypeWithInferredNullability(state, formalParamType, typeVarNullability);
                  inferredLambdaTypes.put((LambdaExpressionTree) argument, inferredType);
                }
              });

      InferenceSuccess successResult = new InferenceSuccess(typeVarNullability);
      // don't cache result if we were called from dataflow, since the result may rely on dataflow
      // facts that do not reflect the fixed point
      if (!calledFromDataflow) {
        for (MethodInvocationTree invTree : allInvocations) {
          inferredTypeVarNullabilityForGenericCalls.put(invTree, successResult);
        }
      }
      return successResult;
    } catch (UnsatisfiableConstraintsException e) {
      if (config.warnOnGenericInferenceFailure()) {
        ErrorBuilder errorBuilder = analysis.getErrorBuilder();
        ErrorMessage errorMessage =
            new ErrorMessage(
                ErrorMessage.MessageTypes.GENERIC_INFERENCE_FAILURE,
                String.format(
                    "Failed to infer type argument nullability for call %s: %s",
                    state.getSourceForNode(invocationTree), e.getMessage()));
        state.reportMatch(
            errorBuilder.createErrorDescription(
                errorMessage, analysis.buildDescription(invocationTree), state, null));
      }
      InferenceFailure failureResult = new InferenceFailure(e.getMessage());
      // don't cache result if we were called from dataflow, since the result may rely on dataflow
      // facts that do not reflect the fixed point
      if (!calledFromDataflow) {
        for (MethodInvocationTree invTree : allInvocations) {
          inferredTypeVarNullabilityForGenericCalls.put(invTree, failureResult);
        }
      }
      return failureResult;
    }
  }

  /**
   * Creates an updated version of type with nullability of type variable occurrences matching those
   * indicated in typeVarNullability, while preserving explicit nullability annotations on type
   * variable occurrences.
   *
   * @param state the visitor state
   * @param type the type to update
   * @param typeVarNullability a map from type variables their nullability
   * @return the type with nullability of type variable occurrences updated
   */
  private Type getTypeWithInferredNullability(
      VisitorState state,
      Type type,
      @Nullable Map<Element, ConstraintSolver.InferredNullability> typeVarNullability) {
    if (typeVarNullability == null) {
      return type;
    }
    Type withInferredNullability =
        substituteInferredNullabilityForTypeVariables(state, type, typeVarNullability);
    return TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
        type, withInferredNullability, config, Collections.emptyMap());
  }

  /**
   * Generates inference constraints for a generic method call, including nested calls.
   *
   * @param state the visitor state
   * @param path the tree path to the invocationTree if available and possibly distinct from {@code
   *     state.getPath()}
   * @param typeFromAssignmentContext the type being "assigned to" in the assignment context of the
   *     call, or {@code null} if the type is unavailable or the method result is not assigned
   *     anywhere
   * @param assignedToLocal whether the method call result is assigned to a local variable
   * @param solver the constraint solver
   * @param methodSymbol the symbol for the method being called
   * @param methodInvocationTree the method invocation tree representing the call
   * @param allInvocations a set of all method invocations that require inference, including nested
   *     ones. This is an output parameter that gets mutated while generating the constraints to add
   *     nested invocations.
   * @param calledFromDataflow true if this inference is being done as part of dataflow analysis
   * @throws UnsatisfiableConstraintsException if the constraints are determined to be unsatisfiable
   */
  private void generateConstraintsForCall(
      VisitorState state,
      @Nullable TreePath path,
      @Nullable Type typeFromAssignmentContext,
      boolean assignedToLocal,
      ConstraintSolver solver,
      Symbol.MethodSymbol methodSymbol,
      MethodInvocationTree methodInvocationTree,
      Set<MethodInvocationTree> allInvocations,
      boolean calledFromDataflow)
      throws UnsatisfiableConstraintsException {
    // first, handle the return type flow
    if (typeFromAssignmentContext != null) {
      solver.addSubtypeConstraint(
          methodSymbol.getReturnType(), typeFromAssignmentContext, assignedToLocal);
    }
    // then, handle parameters
    new InvocationArguments(methodInvocationTree, methodSymbol.type.asMethodType())
        .forEach(
            (argument, argPos, formalParamType, unused) ->
                generateConstraintsForParam(
                    state,
                    path,
                    solver,
                    allInvocations,
                    argument,
                    formalParamType,
                    calledFromDataflow));
  }

  private void generateConstraintsForParam(
      VisitorState state,
      @Nullable TreePath path,
      ConstraintSolver solver,
      Set<MethodInvocationTree> allInvocations,
      ExpressionTree argument,
      Type formalParamType,
      boolean calledFromDataflow) {
    // if the parameter is itself a generic call requiring inference, generate constraints for
    // that call
    if (isGenericCallNeedingInference(argument)) {
      MethodInvocationTree invTree = (MethodInvocationTree) argument;
      Symbol.MethodSymbol symbol = ASTHelpers.getSymbol(invTree);
      allInvocations.add(invTree);
      generateConstraintsForCall(
          state,
          path,
          formalParamType,
          false,
          solver,
          symbol,
          invTree,
          allInvocations,
          calledFromDataflow);
    } else if (!(argument instanceof LambdaExpressionTree)) {
      // Skip adding a subtype constraint for lambda arguments; we want to also infer the type of
      // the lambda expression
      Type argumentType = getTreeType(argument, state);
      if (argumentType == null) {
        // bail out of any checking involving raw types for now
        return;
      }
      argumentType =
          refineArgumentTypeWithDataflow(argumentType, argument, state, path, calledFromDataflow);
      solver.addSubtypeConstraint(argumentType, formalParamType, false);
    } else if (argument instanceof LambdaExpressionTree) {
      LambdaExpressionTree lambda = (LambdaExpressionTree) argument;
      Symbol.MethodSymbol fiMethod =
          NullabilityUtil.getFunctionalInterfaceMethod(lambda, state.getTypes());
      Type fiReturnType = fiMethod.getReturnType();
      Tree body = lambda.getBody();
      if (body instanceof ExpressionTree) {
        // Case 1: Expression body, e.g., () -> null
        Type returnedExpressionType = getTreeType((ExpressionTree) body, state);
        if (returnedExpressionType != null) {
          solver.addSubtypeConstraint(returnedExpressionType, fiReturnType, false);
        }
      } else if (body instanceof BlockTree) {
        // Case 2: Block body, e.g., () -> { return null; }

        List<ExpressionTree> returnExpressions = ReturnFinder.findReturnExpressions(body);

        for (ExpressionTree returnExpr : returnExpressions) {
          Type returnedExpressionType = getTreeType(returnExpr, state);
          if (returnedExpressionType != null) {
            // Add a constraint that the returned expression's type
            // must be a subtype of the functional interface's return type.
            solver.addSubtypeConstraint(returnedExpressionType, fiReturnType, false);
          }
        }
      }
    }
  }

  static class ReturnFinder extends TreeScanner<Void, Void> {

    private final List<ExpressionTree> returnExpressions = new ArrayList<>();

    /**
     * Scans the given tree and returns all found return expressions.
     *
     * @param tree The tree (e.g., a lambda body) to scan.
     * @return A list of all return expressions found.
     */
    public static List<ExpressionTree> findReturnExpressions(Tree tree) {
      ReturnFinder finder = new ReturnFinder();
      finder.scan(tree, null);
      return finder.getReturnExpressions();
    }

    /**
     * Gets the list of return expressions found by this visitor.
     *
     * @return The list of return expressions.
     */
    public List<ExpressionTree> getReturnExpressions() {
      return returnExpressions;
    }

    @Override
    public Void visitLambdaExpression(LambdaExpressionTree node, Void p) {
      // Do not scan inside nested lambdas
      return null;
    }

    @Override
    public Void visitClass(ClassTree node, Void p) {
      // Do not scan inside nested (anonymous/local) classes
      return null;
    }

    @Override
    public Void visitMethod(MethodTree node, Void p) {
      // Do not scan inside methods of local classes
      return null;
    }

    @Override
    public Void visitReturn(ReturnTree node, Void p) {
      ExpressionTree expression = node.getExpression();
      if (expression != null) {
        returnExpressions.add(expression);
      }
      // We've processed this return, don't scan its children
      return null;
    }
  }

  /**
   * Refines the type of an argument using dataflow information, if available.
   *
   * @param argumentType the original type of the argument
   * @param argument the argument expression tree
   * @param state the visitor state
   * @param path the tree path to the invocation if available and possibly distinct from {@code
   *     state.getPath()}
   * @param calledFromDataflow true if this refinement is being done as part of dataflow analysis
   * @return the refined type of the argument
   */
  private Type refineArgumentTypeWithDataflow(
      Type argumentType,
      ExpressionTree argument,
      VisitorState state,
      @Nullable TreePath path,
      boolean calledFromDataflow) {
    if (argumentType.isPrimitive()) {
      return argumentType;
    }
    Symbol argumentSymbol = ASTHelpers.getSymbol(argument);
    if (argumentSymbol == null) {
      return argumentType;
    }
    TreePath currentPath = path != null ? path : state.getPath();
    // We need a TreePath whose leaf is the argument expression, as the calls to `getNullness` /
    // `getNullnessFromRunning` below return the nullness of the leaf of the path.
    // Just appending argument to currentPath is a bit sketchy, as it may not actually be the valid
    // tree path to the argument.  However, all we need the path for (beyond the leaf) is to
    // discover the enclosing method/lambda/initializer, and for that purpose this should be
    // sufficient.
    TreePath argumentPath = new TreePath(currentPath, argument);
    if (NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(argumentPath) == null) {
      return argumentType;
    }
    Nullness refinedNullness;
    if (calledFromDataflow) {
      // dataflow analysis is already running, so just get the current dataflow value for the
      // argument
      refinedNullness =
          analysis.getNullnessAnalysis(state).getNullnessFromRunning(argumentPath, state.context);
    } else {
      refinedNullness =
          analysis.getNullnessAnalysis(state).getNullness(argumentPath, state.context);
    }
    if (refinedNullness == null) {
      return argumentType;
    }
    return updateTypeWithNullness(state, argumentType, refinedNullness);
  }

  private Type updateTypeWithNullness(
      VisitorState state, Type argumentType, Nullness refinedNullness) {
    if (NullabilityUtil.nullnessToBool(refinedNullness)) {
      // refine to @Nullable
      if (isNullableAnnotated(argumentType)) {
        return argumentType;
      }
      return TypeSubstitutionUtils.typeWithAnnot(argumentType, getSyntheticNullAnnotType(state));
    } else {
      // refine to @NonNull, by removing the top-level @Nullable annotation if present.
      if (!isNullableAnnotated(argumentType)) {
        return argumentType;
      }
      return TypeSubstitutionUtils.removeNullableAnnotation(argumentType, config);
    }
  }

  private static boolean isGenericCallNeedingInference(ExpressionTree argument) {
    // For now, we only support calls to generic methods.
    // TODO also support calls to generic constructors that use the diamond operator
    if (argument instanceof MethodInvocationTree) {
      MethodInvocationTree methodInvocation = (MethodInvocationTree) argument;
      Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(methodInvocation);
      // true for generic method calls with no explicit type arguments
      return methodSymbol != null
          && methodSymbol.type instanceof Type.ForAll
          && methodInvocation.getTypeArguments().isEmpty();
    }
    return false;
  }

  private Type substituteInferredNullabilityForTypeVariables(
      VisitorState state,
      Type targetType,
      Map<Element, ConstraintSolver.InferredNullability> typeVarNullability) {
    ListBuffer<Type> typeVars = new ListBuffer<>();
    ListBuffer<Type> inferredTypes = new ListBuffer<>();
    for (Map.Entry<Element, ConstraintSolver.InferredNullability> entry :
        typeVarNullability.entrySet()) {
      if (entry.getValue() == NULLABLE) {
        // find all TypeVars occurring in targetType with the same symbol and substitute for those.
        // we can have multiple such TypeVars due to previous substitutions that modified the type
        // in some way, e.g., by changing its bounds
        Element symbol = entry.getKey();
        TypeVarWithSymbolCollector tvc = new TypeVarWithSymbolCollector(symbol);
        targetType.accept(tvc, null);
        for (Type.TypeVar tv : tvc.getMatches()) {
          typeVars.append(tv);
          inferredTypes.append(
              TypeSubstitutionUtils.typeWithAnnot(tv, getSyntheticNullAnnotType(state)));
        }
      }
    }
    com.sun.tools.javac.util.List<Type> typeVarsToReplace = typeVars.toList();
    if (!typeVarsToReplace.isEmpty()) {
      return TypeSubstitutionUtils.subst(
          state.getTypes(), targetType, typeVarsToReplace, inferredTypes.toList(), config);
    } else {
      return targetType;
    }
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
    if (formalReturnType.isRaw()) {
      // bail out of any checking involving raw types for now
      return;
    }
    Type returnExpressionType = getTreeType(retExpr, state);
    if (returnExpressionType != null) {
      if (isGenericCallNeedingInference(retExpr)) {
        returnExpressionType =
            inferGenericMethodCallType(
                state, (MethodInvocationTree) retExpr, formalReturnType, false, false);
      }
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
   * @param state the visitor state
   */
  private boolean identicalTypeParameterNullability(
      Type lhsType, Type rhsType, VisitorState state) {
    return lhsType.accept(new CheckIdenticalNullabilityVisitor(state, this, config), rhsType);
  }

  /**
   * Like {@link #identicalTypeParameterNullability(Type, Type, VisitorState)}, but allows for
   * covariant array subtyping at the top level.
   *
   * @param lhsType type for the lhs of the assignment
   * @param rhsType type for the rhs of the assignment
   * @param state the visitor state
   */
  private boolean subtypeParameterNullability(Type lhsType, Type rhsType, VisitorState state) {
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
      return identicalTypeParameterNullability(lhsComponentType, rhsComponentType, state);
    } else {
      return identicalTypeParameterNullability(lhsType, rhsType, state);
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

    Type condExprType = getConditionalExpressionType(tree, state);
    Type truePartType = getTreeType(truePartTree, state);
    Type falsePartType = getTreeType(falsePartTree, state);
    // The condExpr type should be the least-upper bound of the true and false part types.  To check
    // the nullability annotations, we check that the true and false parts are assignable to the
    // type of the whole expression
    if (condExprType != null) {
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
  }

  private @Nullable Type getConditionalExpressionType(
      ConditionalExpressionTree tree, VisitorState state) {
    // hack: sometimes array nullability doesn't get computed correctly for a conditional expression
    // on the RHS of an assignment.  So, look at the type of the assignment tree.
    TreePath parentPath = state.getPath().getParentPath();
    Tree parent = parentPath.getLeaf();
    while (parent instanceof ParenthesizedTree) {
      parentPath = parentPath.getParentPath();
      parent = parentPath.getLeaf();
    }
    if (parent instanceof AssignmentTree || parent instanceof VariableTree) {
      return getTreeType(parent, state);
    }
    return getTreeType(tree, state);
  }

  /**
   * Checks that for each parameter p at a call, the type parameter nullability for p's type matches
   * that of the corresponding formal parameter. If a mismatch is found, report an error.
   *
   * @param methodSymbol the symbol for the method being called
   * @param tree the tree representing the method call
   * @param state the visitor state
   */
  public void compareGenericTypeParameterNullabilityForCall(
      Symbol.MethodSymbol methodSymbol, Tree tree, VisitorState state) {
    Config config = analysis.getConfig();
    if (!config.isJSpecifyMode()) {
      return;
    }
    Type invokedMethodType = methodSymbol.type;
    Type enclosingType = getEnclosingTypeForCallExpression(methodSymbol, tree, state, false);
    if (enclosingType != null) {
      invokedMethodType =
          TypeSubstitutionUtils.memberType(state.getTypes(), enclosingType, methodSymbol, config);
    }

    // substitute type arguments for generic methods with explicit type arguments
    if (tree instanceof MethodInvocationTree && invokedMethodType instanceof Type.ForAll) {
      invokedMethodType =
          substituteTypeArgsInGenericMethodType(
              tree, (Type.ForAll) invokedMethodType, null, state, false);
    }

    new InvocationArguments(tree, invokedMethodType.asMethodType())
        .forEach(
            (currentActualParam, argPos, formalParameter, unused) -> {
              if (formalParameter.isRaw()) {
                // bail out of any checking involving raw types for now
                return;
              }

              Type actualParameterType = null;
              if ((currentActualParam instanceof LambdaExpressionTree)) {
                Type lambdaInferredType = inferredLambdaTypes.get(currentActualParam);
                if (lambdaInferredType != null) {
                  actualParameterType = lambdaInferredType;
                }
              } else {
                actualParameterType = getTreeType(currentActualParam, state);
              }
              if (actualParameterType != null) {
                if (isGenericCallNeedingInference(currentActualParam)) {
                  // infer the type of the method call based on the assignment context
                  // and the formal parameter type
                  actualParameterType =
                      inferGenericMethodCallType(
                          state,
                          (MethodInvocationTree) currentActualParam,
                          formalParameter,
                          false,
                          false);
                }
                if (!subtypeParameterNullability(formalParameter, actualParameterType, state)) {
                  reportInvalidParametersNullabilityError(
                      formalParameter, actualParameterType, currentActualParam, state);
                }
              }
            });
  }

  /**
   * Checks that type parameter nullability is consistent between an overriding method and the
   * corresponding overridden method.
   *
   * @param tree A method tree to check
   * @param overridingMethod A symbol of the overriding method
   * @param overriddenMethod A symbol of the overridden method
   * @param state the visitor state
   */
  public void checkTypeParameterNullnessForMethodOverriding(
      MethodTree tree,
      Symbol.MethodSymbol overridingMethod,
      Symbol.MethodSymbol overriddenMethod,
      VisitorState state) {
    if (!analysis.getConfig().isJSpecifyMode()) {
      return;
    }
    // Obtain type parameters for the overridden method within the context of the overriding
    // method's class
    Type methodWithTypeParams =
        TypeSubstitutionUtils.memberType(
            state.getTypes(), overridingMethod.owner.type, overriddenMethod, analysis.getConfig());

    checkTypeParameterNullnessForOverridingMethodReturnType(tree, methodWithTypeParams, state);
    checkTypeParameterNullnessForOverridingMethodParameterType(tree, methodWithTypeParams, state);
  }

  /**
   * Computes the nullability of the return type of some generic method when seen as a member of
   * some class {@code C}, based on type parameter nullability within {@code C}.
   *
   * <p>Consider the following example:
   *
   * <pre>
   *     interface Fn<P extends @Nullable Object, R extends @Nullable Object> {
   *         R apply(P p);
   *     }
   *     class C implements Fn<String, @Nullable String> {
   *         public @Nullable String apply(String p) {
   *             return null;
   *         }
   *     }
   * </pre>
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
      // So, we get a correct type from the enclosing NewClassTree representing the anonymous class.
      TreePath path = state.getPath();
      path =
          castToNonNull(ASTHelpers.findPathFromEnclosingNodeToTopLevel(path, NewClassTree.class));
      NewClassTree newClassTree = (NewClassTree) path.getLeaf();
      if (newClassTree.getClassBody() == null) {
        throw new RuntimeException(
            "method should be directly inside an anonymous NewClassTree "
                + state.getSourceForNode(path.getLeaf()));
      }
      Type typeFromTree = getTreeType(newClassTree, state);
      if (typeFromTree != null) {
        verify(
            state.getTypes().isAssignable(symbol.type, typeFromTree),
            "%s is not assignable to %s",
            symbol.type,
            typeFromTree);
      }
      return typeFromTree;
    } else {
      return symbol.type;
    }
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
    return getTypeNullness(overriddenMethodType.getReturnType());
  }

  /**
   * Computes the nullness of the return of a generic method at an invocation, in the context of the
   * declared type of its receiver argument. If the return type is a type variable, its nullness
   * depends on the nullability of the corresponding type parameter in the receiver's type or the
   * type argument of the method call.
   *
   * <p>Consider the following example:
   *
   * <pre>
   *     interface Fn<P extends @Nullable Object, R extends @Nullable Object> {
   *         R apply(P p);
   *     }
   *     class C implements Fn<String, @Nullable String> {
   *         public @Nullable String apply(String p) {
   *             return null;
   *         }
   *     }
   *     static void m() {
   *         Fn<String, @Nullable String> f = new C();
   *         f.apply("hello").hashCode(); // NPE
   *     }
   * </pre>
   *
   * <p>The declared type of {@code f} passes {@code Nullable String} as the type parameter for type
   * variable {@code R}. So, the call {@code f.apply("hello")} returns {@code @Nullable} and an
   * error should be reported.
   *
   * @param invokedMethodSymbol symbol for the invoked method
   * @param tree the tree for the invocation
   * @param path the path to the invocation tree, or null if not available
   * @param state the visitor state
   * @param calledFromDataflow whether this method is being called from dataflow analysis
   * @return Nullness of invocation's return type, or {@code NONNULL} if the call does not invoke an
   *     instance method
   */
  public Nullness getGenericReturnNullnessAtInvocation(
      Symbol.MethodSymbol invokedMethodSymbol,
      MethodInvocationTree tree,
      @Nullable TreePath path,
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
      Type.ForAll forAllType = (Type.ForAll) invokedMethodSymbol.type;
      Type substitutedReturnType =
          substituteTypeArgsInGenericMethodType(tree, forAllType, path, state, calledFromDataflow)
              .getReturnType();
      // If this condition evaluates to false, we fall through to the subsequent logic, to handle
      // type variables declared on the enclosing class
      if (substitutedReturnType != null
          && Objects.equals(getTypeNullness(substitutedReturnType), Nullness.NULLABLE)) {
        return Nullness.NULLABLE;
      }
    }

    Type enclosingType =
        getEnclosingTypeForCallExpression(invokedMethodSymbol, tree, state, calledFromDataflow);
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
      if (tree instanceof JCTree.JCExpression) {
        JCTree.JCExpression expression = (JCTree.JCExpression) tree;
        types.add(expression.type); // Retrieve the Type
      }
    }
    return com.sun.tools.javac.util.List.from(types);
  }

  /**
   * Substitutes the type arguments from a generic method invocation into the method's type.
   *
   * @param tree the method invocation tree
   * @param forAllType the generic method type
   * @param path the path to the invocation tree, or null if not available
   * @param state the visitor state
   * @param calledFromDataflow whether this method is being called from dataflow analysis
   * @return the substituted method type for the generic method
   */
  private Type substituteTypeArgsInGenericMethodType(
      Tree tree,
      Type.ForAll forAllType,
      @Nullable TreePath path,
      VisitorState state,
      boolean calledFromDataflow) {
    Type.MethodType methodType = forAllType.asMethodType();

    List<? extends Tree> typeArgumentTrees =
        (tree instanceof MethodInvocationTree)
            ? ((MethodInvocationTree) tree).getTypeArguments()
            : ((NewClassTree) tree).getTypeArguments();
    com.sun.tools.javac.util.List<Type> explicitTypeArgs = convertTreesToTypes(typeArgumentTrees);

    // There are no explicit type arguments, so use the inferred types
    if (explicitTypeArgs.isEmpty() && tree instanceof MethodInvocationTree) {
      MethodInferenceResult result = inferredTypeVarNullabilityForGenericCalls.get(tree);
      if (result == null) {
        // have not yet attempted inference for this call
        MethodInvocationTree invocationTree = (MethodInvocationTree) tree;
        InvocationAndContext invocationAndType =
            path == null
                ? new InvocationAndContext(invocationTree, null, false)
                : getInvocationAndContextForInference(path, state, calledFromDataflow);
        result =
            runInferenceForCall(
                state,
                path,
                invocationAndType.invocation,
                invocationAndType.typeFromAssignmentContext,
                invocationAndType.assignedToLocal,
                calledFromDataflow);
      }
      if (result instanceof InferenceSuccess) {
        return getTypeWithInferredNullability(
            state, methodType, ((InferenceSuccess) result).typeVarNullability);
      }
    }
    return TypeSubstitutionUtils.subst(
        state.getTypes(), methodType, forAllType.tvars, explicitTypeArgs, config);
  }

  /**
   * An invocation of a generic method, and the corresponding information about its assignment
   * context, for the purposes of inference.
   */
  private static final class InvocationAndContext {
    final MethodInvocationTree invocation;
    final @Nullable Type typeFromAssignmentContext;
    final boolean assignedToLocal;

    InvocationAndContext(
        MethodInvocationTree invocation,
        @Nullable Type typeFromAssignmentContext,
        boolean assignedToLocal) {
      this.invocation = invocation;
      this.typeFromAssignmentContext = typeFromAssignmentContext;
      this.assignedToLocal = assignedToLocal;
    }
  }

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
  private InvocationAndContext getInvocationAndContextForInference(
      TreePath path, VisitorState state, boolean calledFromDataflow) {
    MethodInvocationTree invocation = (MethodInvocationTree) path.getLeaf();
    TreePath parentPath = path.getParentPath();
    Tree parent = parentPath.getLeaf();
    while (parent instanceof ParenthesizedTree) {
      parentPath = parentPath.getParentPath();
      parent = parentPath.getLeaf();
    }
    if (parent instanceof AssignmentTree || parent instanceof VariableTree) {
      return getInvocationInferenceInfoForAssignment(parent, invocation, state);
    } else if (parent instanceof ReturnTree) {
      // find the enclosing method and return its return type
      TreePath enclosingMethodOrLambda =
          NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(parentPath);
      // TODO handle lambdas; https://github.com/uber/NullAway/issues/1288
      if (enclosingMethodOrLambda != null
          && enclosingMethodOrLambda.getLeaf() instanceof MethodTree) {
        MethodTree enclosingMethod = (MethodTree) enclosingMethodOrLambda.getLeaf();
        Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(enclosingMethod);
        if (methodSymbol != null) {
          return new InvocationAndContext(invocation, methodSymbol.getReturnType(), false);
        }
      }
    } else if (parent instanceof ExpressionTree) {
      // could be a parameter to another method call, or part of a conditional expression, etc.
      // in any case, just return the type of the parent expression
      ExpressionTree exprParent = (ExpressionTree) parent;
      if (exprParent instanceof MethodInvocationTree) {
        MethodInvocationTree parentInvocation = (MethodInvocationTree) exprParent;
        if (isGenericCallNeedingInference(parentInvocation)) {
          // this is the case of a nested generic call, e.g., id(id(x)) where id is generic
          // we want to find the outermost invocation that requires inference, since that is
          // the one whose assignment context is relevant
          return getInvocationAndContextForInference(parentPath, state, calledFromDataflow);
        }
        // the generic invocation is either a regular parameter to the parent call, or the
        // receiver expression
        AtomicReference<Type> formalParamTypeRef = new AtomicReference<>();
        Type type = ASTHelpers.getSymbol(parentInvocation).type;
        new InvocationArguments(parentInvocation, type.asMethodType())
            .forEach(
                (arg, pos, formalParamType, unused) -> {
                  if (ASTHelpers.stripParentheses(arg) == invocation) {
                    formalParamTypeRef.set(formalParamType);
                  }
                });
        Type formalParamType = formalParamTypeRef.get();
        if (formalParamType == null) {
          // this can happen if the invocation is the receiver expression of the call, e.g.,
          // id(x).foo() (note that foo() need not be generic)
          ExpressionTree methodSelect =
              ASTHelpers.stripParentheses(parentInvocation.getMethodSelect());
          if (methodSelect instanceof MemberSelectTree) {
            MemberSelectTree mst = (MemberSelectTree) methodSelect;
            if (ASTHelpers.stripParentheses(mst.getExpression()) == invocation) {
              // the invocation is the receiver expression, so we want the enclosing type of the
              // parent invocation
              formalParamType =
                  getEnclosingTypeForCallExpression(
                      ASTHelpers.getSymbol(parentInvocation),
                      parentInvocation,
                      state,
                      calledFromDataflow);
            } else {
              throw new RuntimeException(
                  "did not find invocation "
                      + state.getSourceForNode(invocation)
                      + " as receiver expression of "
                      + state.getSourceForNode(parentInvocation));
            }
          }
        }
        return new InvocationAndContext(invocation, formalParamType, false);
      }
    }
    // an unhandled case; for now, give up and return no assignment context
    return new InvocationAndContext(invocation, null, false);
  }

  private InvocationAndContext getInvocationInferenceInfoForAssignment(
      Tree assignment, MethodInvocationTree invocation, VisitorState state) {
    Preconditions.checkArgument(
        assignment instanceof AssignmentTree || assignment instanceof VariableTree);
    Type treeType = getTreeType(assignment, state);
    return new InvocationAndContext(invocation, treeType, isAssignmentToLocalVariable(assignment));
  }

  /**
   * Computes the nullness of a formal parameter of a generic method at an invocation, in the
   * context of the declared type of its receiver argument. If the formal parameter's type is a type
   * variable, its nullness depends on the nullability of the corresponding type parameter in the
   * receiver's type.
   *
   * <p>Consider the following example:
   *
   * <pre>
   *     interface Fn<P extends @Nullable Object, R extends @Nullable Object> {
   *         R apply(P p);
   *     }
   *     class C implements Fn<@Nullable String, String> {
   *         public String apply(@Nullable String p) {
   *             return "";
   *         }
   *     }
   *     static void m() {
   *         Fn<@Nullable String, String> f = new C();
   *         f.apply(null);
   *     }
   * </pre>
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
      Type.ForAll forAllType = (Type.ForAll) invokedMethodSymbol.type;
      List<Type> substitutedParamTypes =
          substituteTypeArgsInGenericMethodType(tree, forAllType, null, state, false)
              .getParameterTypes();
      // If this condition evaluates to false, we fall through to the subsequent logic, to handle
      // type variables declared on the enclosing class
      if (substitutedParamTypes != null
          && Objects.equals(
              getParameterTypeNullness(substitutedParamTypes.get(paramIndex), isVarargsParam),
              Nullness.NULLABLE)) {
        return Nullness.NULLABLE;
      }
    }

    Type enclosingType = getEnclosingTypeForCallExpression(invokedMethodSymbol, tree, state, false);
    if (enclosingType == null) {
      return Nullness.NONNULL;
    }

    return getGenericMethodParameterNullness(paramIndex, invokedMethodSymbol, enclosingType, state);
  }

  /**
   * Gets the enclosing type for a non-static method call expression, which is either the type of
   * the enclosing class (for implicit this calls) or the type of the receiver expression. For a
   * constructor call, we treat the type being allocated as the enclosing type.
   *
   * @param invokedMethodSymbol symbol for the invoked method
   * @param tree the tree for the invocation
   * @param state the visitor state
   * @param calledFromDataflow whether this method is being called from dataflow analysis
   * @return the enclosing type for the method call, or null if it cannot be determined
   */
  private @Nullable Type getEnclosingTypeForCallExpression(
      Symbol.MethodSymbol invokedMethodSymbol,
      Tree tree,
      VisitorState state,
      boolean calledFromDataflow) {
    Type enclosingType = null;
    if (tree instanceof MethodInvocationTree) {
      if (invokedMethodSymbol.isStatic()) {
        return null;
      }
      ExpressionTree methodSelect =
          ASTHelpers.stripParentheses(((MethodInvocationTree) tree).getMethodSelect());
      if (methodSelect instanceof IdentifierTree) {
        // implicit this parameter, or a super call.  in either case, use the type of the enclosing
        // class.
        ClassTree enclosingClassTree =
            ASTHelpers.findEnclosingNode(state.getPath(), ClassTree.class);
        if (enclosingClassTree != null) {
          enclosingType = castToNonNull(ASTHelpers.getType(enclosingClassTree));
        }
      } else if (methodSelect instanceof MemberSelectTree) {
        ExpressionTree receiver =
            ASTHelpers.stripParentheses(((MemberSelectTree) methodSelect).getExpression());
        if (isGenericCallNeedingInference(receiver)) {
          enclosingType =
              inferGenericMethodCallType(
                  state, (MethodInvocationTree) receiver, null, false, calledFromDataflow);
        } else {
          enclosingType = getTreeType(receiver, state);
        }
      }
    } else {
      Verify.verify(tree instanceof NewClassTree);
      // for a constructor invocation, the type from the invocation itself is the "enclosing type"
      // for the purposes of determining type arguments
      enclosingType = getTreeType(tree, state);
    }
    return enclosingType;
  }

  /**
   * Computes the nullability of a parameter type of some generic method when seen as a member of
   * some class {@code C}, based on type parameter nullability within {@code C}.
   *
   * <p>Consider the following example:
   *
   * <pre>
   *     interface Fn<P extends @Nullable Object, R extends @Nullable Object> {
   *         R apply(P p);
   *     }
   *     class C implements Fn<@Nullable String, String> {
   *         public String apply(@Nullable String p) {
   *             return "";
   *         }
   *     }
   * </pre>
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
    return getParameterTypeNullness(paramType, isVarargsParam);
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
      if (overriddenMethodParameterType != null && overridingMethodParameterType != null) {
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
  private Nullness getParameterTypeNullness(Type type, boolean isVarargsParam) {
    if (isVarargsParam) {
      // type better be an array type
      verify(
          type.getKind().equals(TypeKind.ARRAY),
          "expected array type for varargs parameter, got %s",
          type);
      // use the component type to determine nullness
      Type.ArrayType arrayType = (Type.ArrayType) type;
      Type componentType = arrayType.getComponentType();
      return getTypeNullness(componentType);
    } else {
      // For non-varargs, we just check the type itself
      return getTypeNullness(type);
    }
  }

  /**
   * @param type A type for which we need the Nullness.
   * @return Returns the Nullness of the type based on the Nullability annotation.
   */
  private Nullness getTypeNullness(Type type) {
    boolean hasNullableAnnotation =
        Nullness.hasNullableAnnotation(type.getAnnotationMirrors().stream(), config);
    if (hasNullableAnnotation) {
      return Nullness.NULLABLE;
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
    if (parentOfLambdaTree instanceof MethodInvocationTree) {
      Symbol.MethodSymbol parentMethodSymbol =
          ASTHelpers.getSymbol((MethodInvocationTree) parentOfLambdaTree);
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
    inferredLambdaTypes.clear();
  }

  public boolean isNullableAnnotated(Type type) {
    return Nullness.hasNullableAnnotation(type.getAnnotationMirrors().stream(), config);
  }

  private @Nullable Type syntheticNullAnnotType;

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
  private Type getSyntheticNullAnnotType(VisitorState state) {
    if (syntheticNullAnnotType == null) {
      Names names = Names.instance(state.context);
      Symtab symtab = Symtab.instance(state.context);
      Name name = names.fromString("nullaway.synthetic");
      Symbol.PackageSymbol packageSymbol = new Symbol.PackageSymbol(name, symtab.noSymbol);
      Name simpleName = names.fromString("Nullable");
      syntheticNullAnnotType = new Type.ErrorType(simpleName, packageSymbol, Type.noType);
    }
    return syntheticNullAnnotType;
  }
}
