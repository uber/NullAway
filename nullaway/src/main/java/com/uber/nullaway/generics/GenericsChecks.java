package com.uber.nullaway.generics;

import static com.google.common.base.Verify.verify;
import static com.uber.nullaway.NullabilityUtil.castToNonNull;
import static com.uber.nullaway.generics.ConstraintSolver.InferredNullability.NULLABLE;

import com.google.common.base.Verify;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
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
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeVariable;
import org.jspecify.annotations.Nullable;

/** Methods for performing checks related to generic types and nullability. */
public final class GenericsChecks {

  /**
   * Maps a Tree representing a call to a generic method or constructor to the inferred nullability
   * of its type arguments. The call must not have any explicit type arguments.
   */
  private final Map<MethodInvocationTree, Map<Element, ConstraintSolver.InferredNullability>>
      inferredTypeVarNullabilityForGenericCalls = new LinkedHashMap<>();

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
    ErrorMessage errorMessage =
        new ErrorMessage(
            ErrorMessage.MessageTypes.ASSIGN_GENERIC_NULLABLE,
            String.format(
                "Cannot assign from type "
                    + prettyTypeForError(rhsType, state)
                    + " to type "
                    + prettyTypeForError(lhsType, state)
                    + " due to mismatched nullability of type parameters"));
    state.reportMatch(
        errorBuilder.createErrorDescription(
            errorMessage, analysis.buildDescription(tree), state, null));
  }

  private void reportInvalidReturnTypeError(
      Tree tree, Type methodType, Type returnType, VisitorState state) {
    ErrorBuilder errorBuilder = analysis.getErrorBuilder();
    ErrorMessage errorMessage =
        new ErrorMessage(
            ErrorMessage.MessageTypes.RETURN_NULLABLE_GENERIC,
            String.format(
                "Cannot return expression of type "
                    + prettyTypeForError(returnType, state)
                    + " from method with return type "
                    + prettyTypeForError(methodType, state)
                    + " due to mismatched nullability of type parameters"));
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
            "Cannot pass parameter of type "
                + prettyTypeForError(actualParameterType, state)
                + ", as formal parameter has type "
                + prettyTypeForError(formalParameterType, state)
                + ", which has mismatched type parameter nullability");
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
  private @Nullable Type getTreeType(Tree tree) {
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
      if (tree instanceof VariableTree || tree instanceof IdentifierTree) {
        // type on the tree itself can be missing nested annotations for arrays; get the type from
        // the symbol for the variable instead
        result = castToNonNull(ASTHelpers.getSymbol(tree)).type;
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
    Type lhsType = getTreeType(tree);
    if (lhsType == null) {
      return;
    }
    ExpressionTree rhsTree;
    boolean assignedToLocal = false;
    if (tree instanceof VariableTree) {
      VariableTree varTree = (VariableTree) tree;
      rhsTree = varTree.getInitializer();
      Symbol treeSymbol = ASTHelpers.getSymbol(tree);
      assignedToLocal =
          treeSymbol != null && treeSymbol.getKind().equals(ElementKind.LOCAL_VARIABLE);
    } else if (tree instanceof AssignmentTree) {
      AssignmentTree assignmentTree = (AssignmentTree) tree;
      rhsTree = assignmentTree.getExpression();
      Symbol varSymbol = ASTHelpers.getSymbol(assignmentTree.getVariable());
      assignedToLocal = varSymbol != null && varSymbol.getKind().equals(ElementKind.LOCAL_VARIABLE);
    } else {
      throw new RuntimeException("Unexpected tree type: " + tree.getKind());
    }
    // rhsTree can be null for a VariableTree.  Also, we don't need to do a check
    // if rhsTree is the null literal
    if (rhsTree == null || rhsTree.getKind().equals(Tree.Kind.NULL_LITERAL)) {
      return;
    }
    Type rhsType = getTreeType(rhsTree);
    if (rhsType != null) {
      if (isGenericCallNeedingInference(rhsTree)) {
        rhsType =
            inferGenericMethodCallType(
                state, (MethodInvocationTree) rhsTree, lhsType, assignedToLocal);
      }
      boolean isAssignmentValid = subtypeParameterNullability(lhsType, rhsType, state);
      if (!isAssignmentValid) {
        reportInvalidAssignmentInstantiationError(tree, lhsType, rhsType, state);
      }
    }
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
   * @return the type of the method call after inference
   */
  private Type inferGenericMethodCallType(
      VisitorState state,
      MethodInvocationTree invocationTree,
      Type typeFromAssignmentContext,
      boolean assignedToLocal) {
    Verify.verify(isGenericCallNeedingInference(invocationTree));
    Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(invocationTree);
    Type type = methodSymbol.type;
    Map<Element, ConstraintSolver.InferredNullability> typeVarNullability =
        inferredTypeVarNullabilityForGenericCalls.get(invocationTree);
    if (typeVarNullability == null) {
      // generic method call with no explicit generic arguments
      // update inferred type arguments based on the assignment context
      ConstraintSolver solver = makeSolver(state, analysis);
      // allInvocations tracks the top-level invocations and any nested invocations that also
      // require inference
      Set<MethodInvocationTree> allInvocations = new LinkedHashSet<>();
      allInvocations.add(invocationTree);
      try {
        generateConstraintsForCall(
            typeFromAssignmentContext,
            assignedToLocal,
            solver,
            methodSymbol,
            invocationTree,
            allInvocations);
        typeVarNullability = solver.solve();
        for (MethodInvocationTree invTree : allInvocations) {
          inferredTypeVarNullabilityForGenericCalls.put(invTree, typeVarNullability);
        }
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
      }
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
   * @param typeFromAssignmentContext the type being "assigned to" in the assignment context of the
   *     call
   * @param assignedToLocal whether the method call result is assigned to a local variable
   * @param solver the constraint solver
   * @param methodSymbol the symbol for the method being called
   * @param methodInvocationTree the method invocation tree representing the call
   * @param allInvocations a set of all method invocations that require inference, including nested
   *     ones. This is an output parameter that gets mutated while generating the constraints to add
   *     nested invocations.
   * @throws UnsatisfiableConstraintsException if the constraints are determined to be unsatisfiable
   */
  private void generateConstraintsForCall(
      Type typeFromAssignmentContext,
      boolean assignedToLocal,
      ConstraintSolver solver,
      Symbol.MethodSymbol methodSymbol,
      MethodInvocationTree methodInvocationTree,
      Set<MethodInvocationTree> allInvocations)
      throws UnsatisfiableConstraintsException {
    // first, handle the return type flow
    solver.addSubtypeConstraint(
        methodSymbol.getReturnType(), typeFromAssignmentContext, assignedToLocal);
    // then, handle parameters
    List<? extends ExpressionTree> arguments = methodInvocationTree.getArguments();
    List<Symbol.VarSymbol> formalParams = methodSymbol.getParameters();
    boolean isVarArgs = methodSymbol.isVarArgs();
    int numNonVarargsParams = isVarArgs ? formalParams.size() - 1 : formalParams.size();
    for (int i = 0; i < numNonVarargsParams; i++) {
      ExpressionTree argument = arguments.get(i);
      Symbol.VarSymbol formalParam = formalParams.get(i);
      Type formalParamType = formalParam.type;
      generateConstraintsForParam(solver, allInvocations, argument, formalParamType);
    }
    if (isVarArgs
        && !formalParams.isEmpty()
        && NullabilityUtil.isVarArgsCall(methodInvocationTree)) {
      Symbol.VarSymbol varargsFormalParam = formalParams.get(formalParams.size() - 1);
      Type.ArrayType varargsArrayType = (Type.ArrayType) varargsFormalParam.type;
      Type varargsElementType = varargsArrayType.elemtype;
      for (int i = formalParams.size() - 1; i < arguments.size(); i++) {
        ExpressionTree argument = arguments.get(i);
        generateConstraintsForParam(solver, allInvocations, argument, varargsElementType);
      }
    }
  }

  private void generateConstraintsForParam(
      ConstraintSolver solver,
      Set<MethodInvocationTree> allInvocations,
      ExpressionTree argument,
      Type formalParamType) {
    // if the parameter is itself a generic call requiring inference, generate constraints for
    // that call
    if (isGenericCallNeedingInference(argument)) {
      MethodInvocationTree invTree = (MethodInvocationTree) argument;
      Symbol.MethodSymbol symbol = ASTHelpers.getSymbol(invTree);
      allInvocations.add(invTree);
      generateConstraintsForCall(formalParamType, false, solver, symbol, invTree, allInvocations);
    } else {
      Type argumentType = getTreeType(argument);
      if (argumentType == null) {
        // bail out of any checking involving raw types for now
        return;
      }
      solver.addSubtypeConstraint(argumentType, formalParamType, false);
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
    Type returnExpressionType = getTreeType(retExpr);
    if (returnExpressionType != null) {
      if (isGenericCallNeedingInference(retExpr)) {
        returnExpressionType =
            inferGenericMethodCallType(
                state, (MethodInvocationTree) retExpr, formalReturnType, false);
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
    Type truePartType = getTreeType(truePartTree);
    Type falsePartType = getTreeType(falsePartTree);
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
      return getTreeType(parent);
    }
    return getTreeType(tree);
  }

  /**
   * Checks that for each parameter p at a call, the type parameter nullability for p's type matches
   * that of the corresponding formal parameter. If a mismatch is found, report an error.
   *
   * @param methodSymbol the symbol for the method being called
   * @param tree the tree representing the method call
   * @param actualParams the actual parameters at the call
   * @param isVarArgs true if the call is to a varargs method
   * @param state the visitor state
   */
  public void compareGenericTypeParameterNullabilityForCall(
      Symbol.MethodSymbol methodSymbol,
      Tree tree,
      List<? extends ExpressionTree> actualParams,
      boolean isVarArgs,
      VisitorState state) {
    Config config = analysis.getConfig();
    if (!config.isJSpecifyMode()) {
      return;
    }
    Type invokedMethodType = methodSymbol.type;
    Type enclosingType = getEnclosingTypeForCallExpression(methodSymbol, tree, state);
    if (enclosingType != null) {
      invokedMethodType =
          TypeSubstitutionUtils.memberType(state.getTypes(), enclosingType, methodSymbol, config);
    }

    // substitute type arguments for generic methods with explicit type arguments
    if (tree instanceof MethodInvocationTree && invokedMethodType instanceof Type.ForAll) {
      invokedMethodType =
          substituteTypeArgsInGenericMethodType(tree, (Type.ForAll) invokedMethodType, state);
    }

    List<Type> formalParamTypes = invokedMethodType.getParameterTypes();
    int n = formalParamTypes.size();
    if (isVarArgs) {
      // If the last argument is var args, don't check it now, it will be checked against
      // all remaining actual arguments in the next loop.
      n = n - 1;
    }
    for (int i = 0; i < n; i++) {
      Type formalParameter = formalParamTypes.get(i);
      if (formalParameter.isRaw()) {
        // bail out of any checking involving raw types for now
        return;
      }
      ExpressionTree currentActualParam = actualParams.get(i);
      Type actualParameterType = getTreeType(currentActualParam);
      if (actualParameterType != null) {
        if (isGenericCallNeedingInference(currentActualParam)) {
          // infer the type of the method call based on the assignment context
          // and the formal parameter type
          actualParameterType =
              inferGenericMethodCallType(
                  state, (MethodInvocationTree) currentActualParam, formalParameter, false);
        }
        if (!subtypeParameterNullability(formalParameter, actualParameterType, state)) {
          reportInvalidParametersNullabilityError(
              formalParameter, actualParameterType, currentActualParam, state);
        }
      }
    }
    if (isVarArgs && !formalParamTypes.isEmpty() && NullabilityUtil.isVarArgsCall(tree)) {
      Type varargsElementType =
          ((Type.ArrayType) formalParamTypes.get(formalParamTypes.size() - 1)).elemtype;
      for (int i = formalParamTypes.size() - 1; i < actualParams.size(); i++) {
        ExpressionTree actualParamExpr = actualParams.get(i);
        Type actualParameterType = getTreeType(actualParamExpr);
        if (actualParameterType != null) {
          if (isGenericCallNeedingInference(actualParamExpr)) {
            actualParameterType =
                inferGenericMethodCallType(
                    state, (MethodInvocationTree) actualParamExpr, varargsElementType, false);
          }
          if (!subtypeParameterNullability(varargsElementType, actualParameterType, state)) {
            reportInvalidParametersNullabilityError(
                varargsElementType, actualParameterType, actualParamExpr, state);
          }
        }
      }
    }
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
      Type typeFromTree = getTreeType(newClassTree);
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
   * @return Nullness of invocation's return type, or {@code NONNULL} if the call does not invoke an
   *     instance method
   */
  public Nullness getGenericReturnNullnessAtInvocation(
      Symbol.MethodSymbol invokedMethodSymbol, MethodInvocationTree tree, VisitorState state) {
    // If generic method invocation
    if (!invokedMethodSymbol.getTypeParameters().isEmpty()) {
      // Substitute type arguments inside the return type
      Type.ForAll forAllType = (Type.ForAll) invokedMethodSymbol.type;
      Type substitutedReturnType =
          substituteTypeArgsInGenericMethodType(tree, forAllType, state).getReturnType();
      // If this condition evaluates to false, we fall through to the subsequent logic, to handle
      // type variables declared on the enclosing class
      if (substitutedReturnType != null
          && Objects.equals(getTypeNullness(substitutedReturnType), Nullness.NULLABLE)) {
        return Nullness.NULLABLE;
      }
    }

    Type enclosingType = getEnclosingTypeForCallExpression(invokedMethodSymbol, tree, state);
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
   * @param state the visitor state
   * @return the substituted method type for the generic method
   */
  private Type substituteTypeArgsInGenericMethodType(
      Tree tree, Type.ForAll forAllType, VisitorState state) {
    Type.MethodType methodType = forAllType.asMethodType();

    List<? extends Tree> typeArgumentTrees =
        (tree instanceof MethodInvocationTree)
            ? ((MethodInvocationTree) tree).getTypeArguments()
            : ((NewClassTree) tree).getTypeArguments();
    com.sun.tools.javac.util.List<Type> explicitTypeArgs = convertTreesToTypes(typeArgumentTrees);

    // There are no explicit type arguments, so use the inferred types
    if (explicitTypeArgs.isEmpty()) {
      if (inferredTypeVarNullabilityForGenericCalls.containsKey(tree)
          && tree instanceof MethodInvocationTree) {
        return getTypeWithInferredNullability(
            state, methodType, inferredTypeVarNullabilityForGenericCalls.get(tree));
      }
    }
    return TypeSubstitutionUtils.subst(
        state.getTypes(), methodType, forAllType.tvars, explicitTypeArgs, config);
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
          substituteTypeArgsInGenericMethodType(tree, forAllType, state).getParameterTypes();
      // If this condition evaluates to false, we fall through to the subsequent logic, to handle
      // type variables declared on the enclosing class
      if (substitutedParamTypes != null
          && Objects.equals(
              getParameterTypeNullness(substitutedParamTypes.get(paramIndex), isVarargsParam),
              Nullness.NULLABLE)) {
        return Nullness.NULLABLE;
      }
    }

    Type enclosingType = getEnclosingTypeForCallExpression(invokedMethodSymbol, tree, state);
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
   * @return the enclosing type for the method call, or null if it cannot be determined
   */
  private @Nullable Type getEnclosingTypeForCallExpression(
      Symbol.MethodSymbol invokedMethodSymbol, Tree tree, VisitorState state) {
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
        enclosingType = getTreeType(((MemberSelectTree) methodSelect).getExpression());
      }
    } else {
      Verify.verify(tree instanceof NewClassTree);
      // for a constructor invocation, the type from the invocation itself is the "enclosing type"
      // for the purposes of determining type arguments
      enclosingType = getTreeType(tree);
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
      Type overridingMethodParameterType = getTreeType(methodParameters.get(i));
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
  public static String prettyTypeForError(Type type, VisitorState state) {
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
  public Type getSyntheticNullAnnotType(VisitorState state) {
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
