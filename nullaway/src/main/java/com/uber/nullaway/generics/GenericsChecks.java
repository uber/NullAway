package com.uber.nullaway.generics;

import static com.google.common.base.Verify.verify;
import static com.uber.nullaway.NullabilityUtil.castToNonNull;

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
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.TargetType;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeInfo;
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
import com.uber.nullaway.dataflow.AccessPathNullnessAnalysis;
import com.uber.nullaway.dataflow.EnclosingEnvironmentNullness;
import com.uber.nullaway.dataflow.NullnessStore;
import com.uber.nullaway.generics.ConstraintSolver.UnsatisfiableConstraintsException;
import com.uber.nullaway.generics.GenericsUtils.MethodRefTypeRelationKind;
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
import javax.lang.model.type.NullType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeVariable;
import org.jspecify.annotations.Nullable;

/** Methods for performing checks related to generic types and nullability. */
public final class GenericsChecks {

  /** Marker interface for results of attempting to infer nullability of type variables at a call */
  private interface CallInferenceResult {}

  /**
   * Indicates successful inference of nullability of type variables at a call. Stores the inferred
   * type variable nullability.
   */
  private static final class InferenceSuccess implements CallInferenceResult {
    final Map<Element, ConstraintSolver.InferredNullability> typeVarNullability;

    InferenceSuccess(Map<Element, ConstraintSolver.InferredNullability> typeVarNullability) {
      this.typeVarNullability = typeVarNullability;
    }
  }

  /** Indicates failed inference of nullability of type variables at a call */
  private static final class InferenceFailure implements CallInferenceResult {
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
  private final Map<Tree, CallInferenceResult> inferredTypeVarNullabilityForGenericCalls =
      new LinkedHashMap<>();

  /**
   * Maps each poly expression ({@code LambdaExpressionTree} or {@code MemberReferenceTree}) passed
   * as a parameter to a generic method to its inferred type, if inference succeeded.
   */
  private final Map<Tree, Type> inferredPolyExpressionTypes = new LinkedHashMap<>();

  /**
   * Tracks generic method invocations currently undergoing nested-nullability repair so re-entrant
   * requests for the same invocation can use the already inferred call-site method type rather than
   * recursing back through the same repair logic.
   */
  private final Set<MethodInvocationTree> nestedNullabilityRepairInProgress = new LinkedHashSet<>();

  public @Nullable Type getInferredPolyExpressionType(Tree tree) {
    Preconditions.checkArgument(
        tree instanceof LambdaExpressionTree || tree instanceof MemberReferenceTree,
        "Expected lambda or method reference tree but got: %s",
        tree.getKind());
    return inferredPolyExpressionTypes.get(tree);
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
          || handler.onOverrideClassTypeVariableUpperBound(type.tsym.toString(), i)) {
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
    String result =
        String.format(
            "incompatible types: %s cannot be converted to %s",
            prettyRhsType, prettyTypeForError(lhsType, state));
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
   * @return Type of the tree with preserved annotations.
   */
  /* package-private */ @Nullable Type getTreeType(Tree tree, VisitorState state) {
    if (tree instanceof ExpressionTree exprTree) {
      NullabilityUtil.ExprTreeAndState exprTreeAndState =
          NullabilityUtil.stripParensAndUpdateTreePath(exprTree, state);
      tree = exprTreeAndState.expr();
      state = exprTreeAndState.state();
    }
    if (tree instanceof LambdaExpressionTree || tree instanceof MemberReferenceTree) {
      Type result = inferredPolyExpressionTypes.get(tree);
      if (result == null) {
        result = ASTHelpers.getType(tree);
      }
      return typeOrNullIfRaw(result);
    }
    if (tree instanceof NewClassTree newClassTree) {
      if (TreeInfo.isDiamond((JCTree) newClassTree) && newClassTree.getClassBody() != null) {
        TreePath currentPath = state.getPath();
        if (currentPath != null && ASTHelpers.stripParentheses(currentPath.getLeaf()) == tree) {
          return getDirectCallContextForInference(currentPath, state, false)
              .typeFromAssignmentContext;
        }
        return null;
      }
      if (hasInferredClassTypeArguments(newClassTree)) {
        TreePath currentPath = state.getPath();
        if (currentPath != null && ASTHelpers.stripParentheses(currentPath.getLeaf()) == tree) {
          DirectCallContext directContext =
              getDirectCallContextForInference(currentPath, state, false);
          Type constructorAssignmentContext =
              sanitizeAssignmentContextForDiamondConstructor(
                  directContext.typeFromAssignmentContext);
          return inferCallType(
              state,
              newClassTree,
              currentPath,
              constructorAssignmentContext,
              directContext.assignedToLocal,
              false);
        }
      }
      if (newClassTree.getIdentifier() instanceof ParameterizedTypeTree paramTypedTree
          && !paramTypedTree.getTypeArguments().isEmpty()) {
        return typeWithPreservedAnnotations(paramTypedTree);
      }
      return typeOrNullIfRaw(ASTHelpers.getType(tree));
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
          // if it's a lambda parameter, and we inferred the type of the lambda, we want the
          // inferred type of the parameter
          Type lambdaParameterType = getInferredLambdaParameterType(symbol, state);
          if (lambdaParameterType != null) {
            return lambdaParameterType;
          }
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
        // type on the tree itself can be missing nested annotations for arrays; get the type from
        // the symbol for the assigned location instead, if available
        Symbol lhsSymbol = ASTHelpers.getSymbol(assignmentTree.getVariable());
        if (lhsSymbol != null) {
          result = lhsSymbol.type;
        } else {
          result = ASTHelpers.getType(assignmentTree);
        }
      } else {
        result = ASTHelpers.getType(tree);
        if (result != null) {
          // for method invocations and field reads, there may be annotations on type variables in
          // the return / field type that need to be restored
          if (tree instanceof MethodInvocationTree invocationTree) {
            Symbol.MethodSymbol symbol = castToNonNull(ASTHelpers.getSymbol(invocationTree));
            Type.MethodType methodType =
                handler.onOverrideMethodType(symbol, symbol.type.asMethodType(), state);
            Type returnType = methodType.getReturnType();
            result =
                TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
                    returnType, result, config, Collections.emptyMap());
          } else if (tree instanceof MemberSelectTree memberSelectTree) {
            Symbol memberSelectSymbol = ASTHelpers.getSymbol(memberSelectTree);
            if (memberSelectSymbol != null && memberSelectSymbol.getKind().isField()) {
              Type fieldType = memberSelectSymbol.type;
              result =
                  TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
                      fieldType, result, config, Collections.emptyMap());
            }
          }
        }
      }
      return typeOrNullIfRaw(result);
    }
  }

  /**
   * @param type a type to check
   * @return the given type, or null if the type is a raw type
   */
  private static @Nullable Type typeOrNullIfRaw(@Nullable Type type) {
    if (type != null && type.isRaw()) {
      return null;
    }
    return type;
  }

  /**
   * Returns the inferred/declared formal parameter type corresponding to actual parameter {@code
   * argumentTree}.
   */
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
   * Returns true when javac inferred class type arguments for a constructor call, i.e. there are
   * instantiated type arguments at the type level, but no explicit non-diamond source type args.
   */
  private static boolean hasInferredClassTypeArguments(NewClassTree newClassTree) {
    if (newClassTree.getClassBody() != null) {
      // we still need to properly handle anonymous classes
      return false;
    }
    if (!TreeInfo.isDiamond((JCTree) newClassTree)) {
      // explicit class type arguments in source
      return false;
    }
    Type newClassType = ASTHelpers.getType(newClassTree);
    return newClassType != null && !newClassType.getTypeArguments().isEmpty();
  }

  private static boolean isCallNeedingInference(ExpressionTree expressionTree) {
    if (expressionTree instanceof MethodInvocationTree methodInvocation) {
      Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(methodInvocation);
      return methodSymbol != null
          && methodSymbol.type instanceof Type.ForAll
          && methodInvocation.getTypeArguments().isEmpty();
    }
    return expressionTree instanceof NewClassTree newClassTree
        && hasInferredClassTypeArguments(newClassTree);
  }

  /**
   * Gets the inferred type of lambda parameter, if the lambda was passed to a generic method and
   * its type was inferred previously
   *
   * @param symbol the symbol for the parameter (possibly not of a lambda, just needs kind to be
   *     {@code ElementKind.PARAMETER})
   * @param state the visitor state
   * @return the inferred type of the lambda parameter, or null if not found
   */
  private @Nullable Type getInferredLambdaParameterType(Symbol symbol, VisitorState state) {
    if (symbol.owner != null && symbol.owner.getKind() == ElementKind.METHOD) {
      Symbol.MethodSymbol containingMethodSymbol = (Symbol.MethodSymbol) symbol.owner;
      if (!containingMethodSymbol.getParameters().contains(symbol)) {
        // we have a lambda parameter
        LambdaExpressionTree lambdaTree =
            ASTHelpers.findEnclosingNode(state.getPath(), LambdaExpressionTree.class);
        if (lambdaTree != null) {
          Type inferredLambdaType = inferredPolyExpressionTypes.get(lambdaTree);
          if (inferredLambdaType != null) {
            // type of lambda was inferred
            var params = lambdaTree.getParameters();
            for (int i = 0; i < params.size(); i++) {
              VariableTree param = params.get(i);
              Symbol paramSymbol = ASTHelpers.getSymbol(param);
              if (paramSymbol != null && paramSymbol.equals(symbol)) {
                // get the type of the functional interface method as a member of the inferred type
                // of the lambda
                Types types = state.getTypes();
                var fiMethodType =
                    TypeSubstitutionUtils.memberType(
                        types,
                        inferredLambdaType,
                        NullabilityUtil.getFunctionalInterfaceMethod(lambdaTree, types),
                        config);
                return fiMethodType.getParameterTypes().get(i);
              }
            }
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
    if (!assignedToLocal
        && (rhsTree instanceof LambdaExpressionTree || rhsTree instanceof MemberReferenceTree)
        && isAssignmentToField(tree)) {
      maybeStorePolyExpressionTypeFromTarget(rhsTree, lhsType);
    }
    TreePath pathToRhs = new TreePath(state.getPath(), rhsTree);
    Type rhsType = getTreeType(rhsTree, state.withPath(pathToRhs));
    if (rhsType != null) {
      if (isCallNeedingInference(rhsTree)) {
        rhsType = inferCallType(state, rhsTree, pathToRhs, lhsType, assignedToLocal, false);
      }
      boolean isAssignmentValid = subtypeParameterNullability(lhsType, rhsType, state);
      if (!isAssignmentValid) {
        reportInvalidAssignmentInstantiationError(tree, lhsType, rhsType, state);
      }
    }
  }

  private static boolean isAssignmentToLocalVariable(Tree tree) {
    return isAssignmentToKind(tree, ElementKind.LOCAL_VARIABLE);
  }

  private static boolean isAssignmentToField(Tree tree) {
    return isAssignmentToKind(tree, ElementKind.FIELD);
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
    Map<Element, ConstraintSolver.InferredNullability> typeVarNullability = null;
    CallInferenceResult result = inferredTypeVarNullabilityForGenericCalls.get(callTree);
    if (result == null) {
      result =
          runInferenceForCall(
              state,
              path,
              callTree,
              typeFromAssignmentContext,
              assignedToLocal,
              calledFromDataflow);
    }
    if (result instanceof InferenceSuccess success) {
      typeVarNullability = success.typeVarNullability;
    }
    Type typeAtCallSite = castToNonNull(ASTHelpers.getType(callTree));
    if (callTree instanceof MethodInvocationTree invocationTree) {
      Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(invocationTree);
      Type methodType = methodSymbol.type;
      Type methodReturnType = ((Type.ForAll) methodType).qtype.getReturnType();
      return TypeSubstitutionUtils.updateTypeWithInferredNullability(
          typeAtCallSite, methodReturnType, typeVarNullability, state, config);
    }
    Verify.verify(callTree instanceof NewClassTree);
    Symbol.MethodSymbol ctorSymbol = getMethodSymbolForCall(callTree);
    Type constructedTypeWithTypeVars = ctorSymbol.owner.type;
    return TypeSubstitutionUtils.updateTypeWithInferredNullability(
        typeAtCallSite, constructedTypeWithTypeVars, typeVarNullability, state, config);
  }

  /**
   * Runs inference for a generic call, side-effecting the
   * #inferredTypeVarNullabilityForGenericCalls map with the result.
   *
   * @param state the visitor state
   * @param path the tree path to the call tree if available and possibly distinct from {@code
   *     state.getPath()}
   * @param callTree the method invocation tree or constructor call tree representing the call
   * @param typeFromAssignmentContext the type being "assigned to" in the assignment context, or
   *     {@code null} if the type is unavailable or the method result is not assigned anywhere
   * @param assignedToLocal true if the call result is assigned to a local variable, false otherwise
   * @param calledFromDataflow true if this inference is being done as part of dataflow analysis
   * @return the inference result, either success with inferred type variable nullability or failure
   *     with an error message
   */
  private CallInferenceResult runInferenceForCall(
      VisitorState state,
      @Nullable TreePath path,
      ExpressionTree callTree,
      @Nullable Type typeFromAssignmentContext,
      boolean assignedToLocal,
      boolean calledFromDataflow) {
    ConstraintSolver solver = makeSolver(state, analysis);
    Set<Tree> allCalls = new LinkedHashSet<>();
    allCalls.add(callTree);
    Map<Element, ConstraintSolver.InferredNullability> typeVarNullability;
    try {
      generateConstraintsForCall(
          state, path, typeFromAssignmentContext, assignedToLocal, solver, callTree, allCalls);
      typeVarNullability = new HashMap<>(solver.solve());
      for (Symbol.TypeVariableSymbol typeVar : getCallTypeParameters(callTree)) {
        typeVarNullability.putIfAbsent(typeVar, ConstraintSolver.InferredNullability.NONNULL);
      }

      Type.MethodType callMethodType = getInferenceExecutableType(callTree, state);
      new InvocationArguments(callTree, callMethodType)
          .forEach(
              (argument, argPos, formalParamType, unused) -> {
                if (argument instanceof LambdaExpressionTree
                    || argument instanceof MemberReferenceTree) {
                  Type polyExprTreeType = ASTHelpers.getType(argument);
                  if (polyExprTreeType != null) {
                    Type typeWithInferredNullability =
                        TypeSubstitutionUtils.updateTypeWithInferredNullability(
                            polyExprTreeType, formalParamType, typeVarNullability, state, config);
                    inferredPolyExpressionTypes.put(argument, typeWithInferredNullability);
                  }
                }
              });

      InferenceSuccess successResult = new InferenceSuccess(typeVarNullability);
      if (!calledFromDataflow) {
        for (Tree inferredCall : allCalls) {
          inferredTypeVarNullabilityForGenericCalls.put(inferredCall, successResult);
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
                    state.getSourceForNode(callTree), e.getMessage()));
        state.reportMatch(
            errorBuilder.createErrorDescription(
                errorMessage, analysis.buildDescription(callTree), state, null));
      }
      InferenceFailure failureResult = new InferenceFailure(e.getMessage());
      if (!calledFromDataflow) {
        for (Tree inferredCall : allCalls) {
          inferredTypeVarNullabilityForGenericCalls.put(inferredCall, failureResult);
        }
      }
      return failureResult;
    }
  }

  private com.sun.tools.javac.util.List<Symbol.TypeVariableSymbol> getCallTypeParameters(
      ExpressionTree callTree) {
    if (callTree instanceof MethodInvocationTree invocationTree) {
      return ASTHelpers.getSymbol(invocationTree).getTypeParameters();
    }
    Verify.verify(callTree instanceof NewClassTree);
    Symbol.MethodSymbol ctorSymbol = getMethodSymbolForCall(callTree);
    return ctorSymbol.owner.getTypeParameters();
  }

  private Type.MethodType getInferenceExecutableType(ExpressionTree callTree, VisitorState state) {
    Symbol.MethodSymbol methodSymbol = getMethodSymbolForCall(callTree);
    Type.MethodType methodType =
        handler.onOverrideMethodType(methodSymbol, methodSymbol.type.asMethodType(), state);
    if (!(callTree instanceof NewClassTree)) {
      return methodType;
    }
    return methodType;
  }

  private Symbol.MethodSymbol getMethodSymbolForCall(ExpressionTree callTree) {
    return (Symbol.MethodSymbol) castToNonNull(ASTHelpers.getSymbol(callTree));
  }

  /**
   * A bare method type variable does not provide useful structure for inferring nullability of a
   * diamond constructor's class type arguments. In such cases we let constructor inference rely on
   * constructor arguments and any more structured surrounding context instead.
   */
  private @Nullable Type sanitizeAssignmentContextForDiamondConstructor(
      @Nullable Type contextType) {
    return contextType instanceof Type.TypeVar ? null : contextType;
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
   * @param callTree the call tree representing the generic method call or diamond constructor call
   * @param allCalls a set of all calls that require inference, including nested ones. This is an
   *     output parameter that gets mutated while generating the constraints to add nested calls.
   * @throws UnsatisfiableConstraintsException if the constraints are determined to be unsatisfiable
   */
  private void generateConstraintsForCall(
      VisitorState state,
      @Nullable TreePath path,
      @Nullable Type typeFromAssignmentContext,
      boolean assignedToLocal,
      ConstraintSolver solver,
      ExpressionTree callTree,
      Set<Tree> allCalls)
      throws UnsatisfiableConstraintsException {
    Symbol.MethodSymbol methodSymbol = getMethodSymbolForCall(callTree);
    Type.MethodType methodType =
        handler.onOverrideMethodType(methodSymbol, methodSymbol.type.asMethodType(), state);
    if (typeFromAssignmentContext != null) {
      Type callResultType =
          (callTree instanceof MethodInvocationTree)
              ? methodType.getReturnType()
              : methodSymbol.owner.type;
      solver.addSubtypeConstraint(callResultType, typeFromAssignmentContext, assignedToLocal);
    }
    new InvocationArguments(callTree, methodType)
        .forEach(
            (argument, argPos, formalParamType, unused) ->
                generateConstraintsForPseudoAssignment(
                    state, path, solver, allCalls, argument, formalParamType));
  }

  /**
   * In the context of generic method inference, generate constraints for a pseudo-assignment
   * (parameter passing or return).
   *
   * @param state the visitor state
   * @param path the tree path to {@code rhsExpr} if available
   * @param solver the constraint solver
   * @param allCalls a set of all calls that require inference, including nested ones. This is an
   *     output parameter that gets mutated while generating the constraints to add nested calls.
   * @param rhsExpr the right-hand side expression of the pseudo-assignment
   * @param lhsType the left-hand side type of the pseudo-assignment
   */
  private void generateConstraintsForPseudoAssignment(
      VisitorState state,
      @Nullable TreePath path,
      ConstraintSolver solver,
      Set<Tree> allCalls,
      ExpressionTree rhsExpr,
      Type lhsType) {
    rhsExpr = ASTHelpers.stripParentheses(rhsExpr);
    if (isCallNeedingInference(rhsExpr)) {
      allCalls.add(rhsExpr);
      generateConstraintsForCall(state, path, lhsType, false, solver, rhsExpr, allCalls);
    } else if (rhsExpr instanceof LambdaExpressionTree lambda) {
      handleLambdaInGenericMethodInference(state, path, solver, allCalls, lhsType, lambda);
    } else if (rhsExpr instanceof MemberReferenceTree memberReferenceTree) {
      handleMethodRefInGenericMethodInference(state, solver, lhsType, memberReferenceTree);
    } else { // all other cases
      Type argumentType = getTreeType(rhsExpr, state);
      if (argumentType == null) {
        // bail out of any checking involving raw types for now
        return;
      }
      argumentType = refineArgumentTypeWithDataflow(argumentType, rhsExpr, state, path);
      solver.addSubtypeConstraint(argumentType, lhsType, false);
    }
  }

  /**
   * Generate constraints for any return expression inside lambda argument. If the return expression
   * is a method invocation then recursively call generateConstraintsForCall
   *
   * @param state the visitor state
   * @param path the tree path to the enclosing call if available and possibly distinct from {@code
   *     state.getPath()}
   * @param solver the constraint solver
   * @param allCalls a set of all calls that require inference, including nested ones. This is an
   *     output parameter that gets mutated while generating the constraints to add nested calls.
   * @param lhsType the type to which the lambda is being assigned
   * @param lambda The lambda argument
   */
  private void handleLambdaInGenericMethodInference(
      VisitorState state,
      @Nullable TreePath path,
      ConstraintSolver solver,
      Set<Tree> allCalls,
      Type lhsType,
      LambdaExpressionTree lambda) {
    Symbol.MethodSymbol fiMethod =
        NullabilityUtil.getFunctionalInterfaceMethod(lambda, state.getTypes());

    // get the return type of the functional interface method, viewed as a member of the lhs
    // type, so the generic method's type variables are substituted in
    Type.MethodType fiMethodTypeAsMember =
        TypeSubstitutionUtils.memberType(state.getTypes(), lhsType, fiMethod, config)
            .asMethodType();
    Type fiReturnType = fiMethodTypeAsMember.getReturnType();
    Tree body = lambda.getBody();
    // augment our current TreePath so that the lambda is the leaf, in case dataflow analysis needs
    // to be run within it
    if (path == null) {
      path = state.getPath();
    }
    TreePath lambdaPath = new TreePath(path, lambda);
    if (body instanceof ExpressionTree returnedExpression) {
      // Case 1: Expression body, e.g., () -> null
      generateConstraintsForPseudoAssignment(
          state, lambdaPath, solver, allCalls, returnedExpression, fiReturnType);
    } else if (body instanceof BlockTree) {
      // Case 2: Block body, e.g., () -> { return null; }
      List<ExpressionTree> returnExpressions = ReturnFinder.findReturnExpressions(body);
      for (ExpressionTree returnExpr : returnExpressions) {
        generateConstraintsForPseudoAssignment(
            state, lambdaPath, solver, allCalls, returnExpr, fiReturnType);
      }
    }
  }

  /**
   * Generate constraints for a method reference argument by comparing functional interface method
   * parameter and return types against the referenced method.
   *
   * @param state the visitor state
   * @param solver the constraint solver
   * @param lhsType the type to which the method reference is being assigned
   * @param memberReferenceTree the method reference argument
   */
  private void handleMethodRefInGenericMethodInference(
      VisitorState state,
      ConstraintSolver solver,
      Type lhsType,
      MemberReferenceTree memberReferenceTree) {
    GenericsUtils.processMethodRefTypeRelations(
        this,
        lhsType,
        memberReferenceTree,
        state,
        (subtype, supertype, unused) -> {
          solver.addSubtypeConstraint(subtype, supertype, false);
        });
  }

  /**
   * Gets the method type for a member reference handling generics, in JSpecify mode
   *
   * @param memberReferenceTree the member reference tree
   * @param overridingMethod the method symbol for the method referenced by {@code
   *     memberReferenceTree}
   * @param state the visitor state
   * @return the method type for the member reference, with generics handled, or null if not in
   *     JSpecify mode
   */
  public Type.@Nullable MethodType getMemberReferenceMethodType(
      MemberReferenceTree memberReferenceTree,
      Symbol.MethodSymbol overridingMethod,
      VisitorState state) {
    if (!config.isJSpecifyMode()) {
      return null;
    }
    Type.MethodType result = overridingMethod.asType().asMethodType();
    if (!overridingMethod.isStatic()) {
      // This handles any generic type parameters of the qualifier of the member reference, e.g. for
      // x::m, where x is of type Foo<Integer>, it handles the type parameter Integer whereever it
      // appears in the signature of m.
      Type qualifierType = ASTHelpers.getType(memberReferenceTree.getQualifierExpression());
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
    return handler.onOverrideMethodType(overridingMethod, result, state);
  }

  /**
   * A visitor that scans a {@link Tree} (typically a lambda or method body) to find all {@code
   * return} statements and collect their expressions.
   *
   * <p>This scanner is specifically designed to be "shallow." It will <b>not</b> descend into
   * nested lambdas, local classes, or anonymous classes, ensuring it only finds {@code return}
   * statements relevant to the *current* function body.
   *
   * <p>Usage:
   *
   * <pre>
   * Tree lambdaBody = myLambda.getBody();
   * List<ExpressionTree> returns = ReturnFinder.findReturnExpressions(lambdaBody);
   * </pre>
   */
  static class ReturnFinder extends TreeScanner<@Nullable Void, @Nullable Void> {

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
      ExpressionTree expression = node.getExpression();
      if (expression != null) {
        returnExpressions.add(expression);
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
    TreePath currentPath = path != null ? path : state.getPath();
    // We need a TreePath whose leaf is the expression, as the calls to `getNullness` /
    // `getNullnessFromRunning` below return the nullness of the leaf of the path.
    // Just appending expr to currentPath is a bit sketchy, as it may not actually be the valid
    // tree path to the expression.  However, all we need the path for (beyond the leaf) is to
    // discover the enclosing method/lambda/initializer, and for that purpose this should be
    // sufficient.
    TreePath exprPath = new TreePath(currentPath, expr);
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
    TreePath pathToRetExpr = new TreePath(state.getPath(), retExpr);
    Type returnExpressionType = getTreeType(retExpr, state.withPath(pathToRetExpr));
    if (returnExpressionType != null) {
      if (isCallNeedingInference(retExpr)) {
        returnExpressionType =
            inferCallType(state, retExpr, pathToRetExpr, formalReturnType, false, false);
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
    Type enclosingType = null;
    if (enclosingType == null) {
      enclosingType = getEnclosingTypeForCallExpression(methodSymbol, tree, null, state, false);
    }
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

    Type.MethodType finalMethodType =
        handler.onOverrideMethodType(methodSymbol, invokedMethodType.asMethodType(), state);
    new InvocationArguments(tree, finalMethodType)
        .forEach(
            (currentActualParam, argPos, formalParameter, unused) -> {
              if (formalParameter.isRaw()) {
                // bail out of any checking involving raw types for now
                return;
              }

              if (currentActualParam instanceof MemberReferenceTree memberReferenceTree) {
                // the type of the method reference tree provided by javac may not capture
                // nullability of nested types. So, do explicit type checks based on the return and
                // parameter types of the referenced method
                GenericsUtils.processMethodRefTypeRelations(
                    this,
                    formalParameter,
                    memberReferenceTree,
                    state,
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

              Type actualParameterType = null;
              if (currentActualParam instanceof LambdaExpressionTree) {
                maybeStorePolyExpressionTypeFromTarget(currentActualParam, formalParameter);
              }
              Type inferredPolyType = inferredPolyExpressionTypes.get(currentActualParam);
              if (inferredPolyType != null) {
                actualParameterType = inferredPolyType;
              } else {
                TreePath pathToActualParam = new TreePath(state.getPath(), currentActualParam);
                actualParameterType =
                    getTreeType(currentActualParam, state.withPath(pathToActualParam));
              }
              if (actualParameterType != null) {
                if (isCallNeedingInference(currentActualParam)) {
                  TreePath actualParamPath = new TreePath(state.getPath(), currentActualParam);
                  actualParameterType =
                      inferCallType(
                          state,
                          currentActualParam,
                          actualParamPath,
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
   * For a generic method reference, if it is being called in a context that requires type argument
   * nullability inference, return the method type with inferred nullability for type parameters.
   * Otherwise, return the original method type.
   *
   * @param methodType the original method type
   * @param state the visitor state (generic method reference should be leaf of {@code
   *     state.getPath()})
   * @return the method type with inferred nullability for type parameters if inference was
   *     performed, or the original method type otherwise
   */
  private Type.MethodType getInferredMethodTypeForGenericMethodReference(
      Type.MethodType methodType, VisitorState state) {
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
        return TypeSubstitutionUtils.updateMethodTypeWithInferredNullability(
            methodType, methodType, successResult.typeVarNullability, state, config);
      }
    }
    return methodType;
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
      // So, we get a correct type from the NewClassTree representing the anonymous class.
      // The nearest enclosing NewClassTree on the current path may be some other constructor call,
      // such as when `this` from an anonymous class is passed as an argument to a constructor.
      TreePath path = state.getPath();
      while (path != null) {
        if (path.getLeaf() instanceof NewClassTree newClassTree
            && newClassTree.getClassBody() != null) {
          Type newClassType = ASTHelpers.getType(newClassTree);
          if (newClassType != null && newClassType.tsym.equals(symbol)) {
            Type typeFromTree = getTreeType(newClassTree, state);
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
        (tree instanceof MethodInvocationTree methodInvocationTree)
            ? methodInvocationTree.getTypeArguments()
            : ((NewClassTree) tree).getTypeArguments();
    com.sun.tools.javac.util.List<Type> explicitTypeArgs = convertTreesToTypes(typeArgumentTrees);

    // There are no explicit type arguments, so use the inferred types
    if (explicitTypeArgs.isEmpty() && tree instanceof MethodInvocationTree invocationTree) {
      CallInferenceResult result = inferredTypeVarNullabilityForGenericCalls.get(tree);
      if (result == null) {
        // have not yet attempted inference for this call
        CallAndContext invocationAndType =
            path == null
                ? new CallAndContext(invocationTree, null, false)
                : getCallAndContextForInference(path, state, calledFromDataflow);
        result =
            runInferenceForCall(
                state,
                path,
                invocationAndType.call,
                invocationAndType.typeFromAssignmentContext,
                invocationAndType.assignedToLocal,
                calledFromDataflow);
      }
      Type.MethodType methodTypeAtCallSite =
          castToNonNull(ASTHelpers.getType(invocationTree.getMethodSelect())).asMethodType();
      if (result instanceof InferenceSuccess successResult) {
        // Repairing dropped nested nullability annotations can itself inspect actual argument
        // types. For diamond constructor arguments, that can re-enter method-type computation for
        // this same invocation while we are still repairing it. In that case, use the already
        // inferred method type and skip the repair on the recursive call.
        if (!nestedNullabilityRepairInProgress.contains(invocationTree)) {
          nestedNullabilityRepairInProgress.add(invocationTree);
          try {
            methodTypeAtCallSite =
                restoreNestedNullabilityForTypeVarArguments(
                    invocationTree, methodType, methodTypeAtCallSite, state);
          } finally {
            nestedNullabilityRepairInProgress.remove(invocationTree);
          }
        }
        return TypeSubstitutionUtils.updateMethodTypeWithInferredNullability(
            methodTypeAtCallSite, methodType, successResult.typeVarNullability, state, config);
      } else {
        // inference failed; just return the method type at the call site with no substitutions
        return methodTypeAtCallSite;
      }
    }
    return TypeSubstitutionUtils.subst(
        state.getTypes(), methodType, forAllType.tvars, explicitTypeArgs, config);
  }

  /**
   * In narrow cases, javac drops nested type-use nullability annotations on type variables in its
   * inferred type for a generic method at a call site. See
   * https://github.com/uber/NullAway/issues/1455. This method aims to restore those annotations
   * based on the types of actual parameters. It does not attempt to be a very general fix, as we do
   * not fully understand the scenarios where this can arise.
   *
   * @param invocationTree the method invocation tree for the generic method call
   * @param origMethodType the declared method type for the generic method (to identify formal
   *     parameters whose type is a type variable of the method)
   * @param methodTypeAtCallSite the method type for the generic method as inferred by javac at the
   *     call site
   * @param state the visitor state
   * @return a method type based on {@code methodTypeAtCallSite} but with some nested nullability
   *     annotations on type variables restored to match those on actual parameters passed at the
   *     call site
   */
  @SuppressWarnings("ReferenceEquality")
  private Type.MethodType restoreNestedNullabilityForTypeVarArguments(
      MethodInvocationTree invocationTree,
      Type.MethodType origMethodType,
      Type.MethodType methodTypeAtCallSite,
      VisitorState state) {
    Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(invocationTree);
    if (methodSymbol.isVarArgs()) {
      // skip handling of varargs for now
      return methodTypeAtCallSite;
    }
    com.sun.tools.javac.util.List<Type> genericMethodParamTypes =
        origMethodType.getParameterTypes();
    com.sun.tools.javac.util.List<Type> callSiteParamTypes =
        methodTypeAtCallSite.getParameterTypes();
    List<? extends ExpressionTree> actualParams = invocationTree.getArguments();

    // use this map to store repaired substitutions for method type variables, to ensure we use the
    // same repaired substitution for all occurrences of the same type variable
    Map<Symbol.TypeVariableSymbol, Type> repairedTopLevelSubstitutions = new HashMap<>();
    ListBuffer<Type> updatedArgTypes = new ListBuffer<>();
    boolean changed = false;
    for (int i = 0; i < genericMethodParamTypes.size(); i++) {
      Type callSiteParamType = callSiteParamTypes.get(i);
      Type genericMethodParamType = genericMethodParamTypes.get(i);
      // only attempt a repair when the generic method's parameter type is a type variable of the
      // method
      if (genericMethodParamType instanceof Type.TypeVar typeVar
          && typeVar.tsym.owner == methodSymbol) {
        Symbol.TypeVariableSymbol typeVarSymbol = (Symbol.TypeVariableSymbol) typeVar.tsym;
        Type repairedSubstitution = repairedTopLevelSubstitutions.get(typeVarSymbol);
        if (repairedSubstitution != null) {
          // re-use the previous substitution, to ensure consistency
          if (repairedSubstitution != callSiteParamType) {
            changed = true;
            callSiteParamType = repairedSubstitution;
          }
        } else { // need to compute the substitution
          ExpressionTree actualParam = actualParams.get(i);
          Type actualArgType =
              getTreeType(actualParam, state.withPath(new TreePath(state.getPath(), actualParam)));
          // only handle cases of non-raw actual parameter types that have the same base type as the
          // inferred parameter type at the call site
          if (actualArgType != null
              && !actualArgType.isRaw()
              && state
                  .getTypes()
                  .isSameType(
                      state.getTypes().erasure(actualArgType),
                      state.getTypes().erasure(callSiteParamType))) {
            // restore explicit nested annotations from the actual parameter type to the call site
            // parameter type (this will only apply to nested type variables within
            // callSiteParamType)
            Type restoredType =
                TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
                    actualArgType, callSiteParamType, config, Collections.emptyMap());
            // remember the substitution so we use it consistently at other parameter positions
            repairedTopLevelSubstitutions.put(typeVarSymbol, restoredType);
            if (restoredType != callSiteParamType) {
              changed = true;
              callSiteParamType = restoredType;
            }
          } else {
            // remember that we did _not_ change anything, again for consistency across parameter
            // positions
            repairedTopLevelSubstitutions.put(typeVarSymbol, callSiteParamType);
          }
        }
      }
      updatedArgTypes.append(callSiteParamType);
    }
    if (!changed) {
      return methodTypeAtCallSite;
    }
    return new Type.MethodType(
        updatedArgTypes.toList(),
        methodTypeAtCallSite.getReturnType(),
        methodTypeAtCallSite.getThrownTypes(),
        methodTypeAtCallSite.tsym);
  }

  /**
   * An invocation of a generic method, and the corresponding information about its assignment
   * context, for the purposes of inference.
   */
  private record CallAndContext(
      ExpressionTree call, @Nullable Type typeFromAssignmentContext, boolean assignedToLocal) {}

  private record DirectCallContext(
      @Nullable Type typeFromAssignmentContext, boolean assignedToLocal) {}

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
    ExpressionTree call = (ExpressionTree) path.getLeaf();
    DirectCallContext directContext =
        getDirectCallContextForInference(path, state, calledFromDataflow);
    TreePath parentPath = path.getParentPath();
    Tree parent = parentPath.getLeaf();
    while (parent instanceof ParenthesizedTree) {
      parentPath = parentPath.getParentPath();
      parent = parentPath.getLeaf();
    }
    if (parent instanceof ExpressionTree exprParent) {
      if ((exprParent instanceof MethodInvocationTree || exprParent instanceof NewClassTree)
          && isCallNeedingInference(exprParent)) {
        return getCallAndContextForInference(parentPath, state, calledFromDataflow);
      }
    }
    return new CallAndContext(
        call, directContext.typeFromAssignmentContext, directContext.assignedToLocal);
  }

  private DirectCallContext getDirectCallContextForInference(
      TreePath path, VisitorState state, boolean calledFromDataflow) {
    ExpressionTree call = (ExpressionTree) path.getLeaf();
    TreePath parentPath = path.getParentPath();
    Tree parent = parentPath.getLeaf();
    while (parent instanceof ParenthesizedTree) {
      parentPath = parentPath.getParentPath();
      parent = parentPath.getLeaf();
    }
    if (parent instanceof AssignmentTree || parent instanceof VariableTree) {
      Type treeType = getTreeType(parent, state);
      return new DirectCallContext(treeType, isAssignmentToLocalVariable(parent));
    }
    if (parent instanceof ReturnTree) {
      TreePath enclosingMethodOrLambda =
          NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(parentPath);
      if (enclosingMethodOrLambda != null
          && enclosingMethodOrLambda.getLeaf() instanceof MethodTree enclosingMethod) {
        Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(enclosingMethod);
        if (methodSymbol != null) {
          return new DirectCallContext(methodSymbol.getReturnType(), false);
        }
      }
      return new DirectCallContext(null, false);
    }
    if (parent instanceof MethodInvocationTree parentInvocation) {
      Type.MethodType parentMethodType =
          getInvokedMethodTypeAtCall(
              ASTHelpers.getSymbol(parentInvocation),
              parentInvocation,
              parentPath,
              state,
              calledFromDataflow);
      Type formalParamType =
          getFormalParameterTypeForArgument(parentInvocation, parentMethodType, call);
      if (formalParamType == null) {
        ExpressionTree methodSelect =
            ASTHelpers.stripParentheses(parentInvocation.getMethodSelect());
        if (methodSelect instanceof MemberSelectTree mst) {
          if (ASTHelpers.stripParentheses(mst.getExpression()) == call) {
            formalParamType =
                getEnclosingTypeForCallExpression(
                    ASTHelpers.getSymbol(parentInvocation),
                    parentInvocation,
                    parentPath,
                    state,
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
      return new DirectCallContext(formalParamType, false);
    }
    if (parent instanceof NewClassTree parentConstructorCall) {
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
        parentClassType = getTreeType(parentConstructorCall, state.withPath(parentPath));
      }
      if (parentClassType != null) {
        Symbol.MethodSymbol parentCtorSymbol = ASTHelpers.getSymbol(parentConstructorCall);
        Type parentCtorType =
            TypeSubstitutionUtils.memberType(
                state.getTypes(), parentClassType, parentCtorSymbol, config);
        return new DirectCallContext(
            getFormalParameterTypeForArgument(
                parentConstructorCall, parentCtorType.asMethodType(), call),
            false);
      }
      return new DirectCallContext(null, false);
    }
    return new DirectCallContext(null, false);
  }

  private Type.MethodType getInvokedMethodTypeAtCall(
      Symbol.MethodSymbol methodSymbol,
      Tree tree,
      @Nullable TreePath path,
      VisitorState state,
      boolean calledFromDataflow) {
    Type invokedMethodType = methodSymbol.type;
    Type enclosingType =
        getEnclosingTypeForCallExpression(methodSymbol, tree, path, state, calledFromDataflow);
    if (enclosingType != null) {
      invokedMethodType =
          TypeSubstitutionUtils.memberType(state.getTypes(), enclosingType, methodSymbol, config);
    }
    if (tree instanceof MethodInvocationTree
        && invokedMethodType instanceof Type.ForAll forAllType) {
      invokedMethodType =
          substituteTypeArgsInGenericMethodType(tree, forAllType, path, state, calledFromDataflow);
    }
    return handler.onOverrideMethodType(methodSymbol, invokedMethodType.asMethodType(), state);
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

    Type enclosingType =
        getEnclosingTypeForCallExpression(invokedMethodSymbol, tree, null, state, false);
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
        if (isCallNeedingInference(receiver)) {
          TreePath receiverPath = path != null ? new TreePath(path, receiver) : null;
          enclosingType =
              inferCallType(state, receiver, receiverPath, null, false, calledFromDataflow);
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
    inferredPolyExpressionTypes.clear();
    nestedNullabilityRepairInProgress.clear();
  }

  public boolean isNullableAnnotated(Type type) {
    return Nullness.hasNullableAnnotation(type.getAnnotationMirrors().stream(), config);
  }

  /**
   * Store a poly expression's target type with explicit type-variable nullability from the
   * assignment context, when the expression type has not already been cached.
   *
   * <p>This is used to compensate for javac dropping annotations on type variables in poly
   * expression target types, so later checks use the correctly annotated functional interface type.
   */
  private void maybeStorePolyExpressionTypeFromTarget(Tree polyExpressionTree, Type targetType) {
    if (targetType.isRaw() || inferredPolyExpressionTypes.containsKey(polyExpressionTree)) {
      return;
    }
    Type polyExpressionType = ASTHelpers.getType(polyExpressionTree);
    if (polyExpressionType == null) {
      return;
    }
    Type polyExpressionTypeWithTargetAnnotations =
        TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
            targetType, polyExpressionType, config, Collections.emptyMap());
    inferredPolyExpressionTypes.put(polyExpressionTree, polyExpressionTypeWithTargetAnnotations);
  }

  private static @Nullable Type syntheticNullableAnnotType;
  private static @Nullable Type syntheticNonNullAnnotType;

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
