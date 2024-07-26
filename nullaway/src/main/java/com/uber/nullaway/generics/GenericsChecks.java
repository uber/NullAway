package com.uber.nullaway.generics;

import static com.google.common.base.Verify.verify;
import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.errorprone.VisitorState;
import com.google.errorprone.suppliers.Supplier;
import com.google.errorprone.suppliers.Suppliers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.TargetType;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.CodeAnnotationInfo;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorBuilder;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.handlers.Handler;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeVariable;
import org.jspecify.annotations.Nullable;

/** Methods for performing checks related to generic types and nullability. */
public final class GenericsChecks {

  /**
   * Supplier for the JSpecify {@code @Nullable} annotation. Required since for now, certain checks
   * related to generics specifically look for {@code @org.jspecify.annotations.Nullable}
   * annotations and do not apply to other {@code @Nullable} annotations.
   */
  static final Supplier<Type> JSPECIFY_NULLABLE_TYPE_SUPPLIER =
      Suppliers.typeFromString("org.jspecify.annotations.Nullable");

  /** Do not instantiate; all methods should be static */
  private GenericsChecks() {}

  /**
   * Checks that for an instantiated generic type, {@code @Nullable} types are only used for type
   * variables that have a {@code @Nullable} upper bound.
   *
   * @param tree the tree representing the instantiated type
   * @param state visitor state
   * @param analysis the analysis object
   * @param config the analysis config
   * @param handler the handler instance
   */
  public static void checkInstantiationForParameterizedTypedTree(
      ParameterizedTypeTree tree,
      VisitorState state,
      NullAway analysis,
      Config config,
      Handler handler) {
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
    boolean[] typeParamsWithNullableUpperBound =
        getTypeParamsWithNullableUpperBound(baseType, config, state, handler);
    com.sun.tools.javac.util.List<Type> baseTypeArgs = baseType.tsym.type.getTypeArguments();
    for (int i = 0; i < baseTypeArgs.size(); i++) {
      if (nullableTypeArguments.containsKey(i) && !typeParamsWithNullableUpperBound[i]) {
        // if base type variable does not have @Nullable upper bound then the instantiation is
        // invalid
        reportInvalidInstantiationError(
            nullableTypeArguments.get(i), baseType, baseTypeArgs.get(i), state, analysis);
      }
    }
  }

  private static boolean[] getTypeParamsWithNullableUpperBound(
      Type type, Config config, VisitorState state, Handler handler) {
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
            && ASTHelpers.isSameType(
                typeCompound.type, JSPECIFY_NULLABLE_TYPE_SUPPLIER.get(state), state)) {
          int index = typeCompound.position.parameter_index;
          result[index] = true;
        }
      }
    }
    return result;
  }

  private static void reportInvalidInstantiationError(
      Tree tree, Type baseType, Type baseTypeVariable, VisitorState state, NullAway analysis) {
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

  private static void reportInvalidAssignmentInstantiationError(
      Tree tree, Type lhsType, Type rhsType, VisitorState state, NullAway analysis) {
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

  private static void reportInvalidReturnTypeError(
      Tree tree, Type methodType, Type returnType, VisitorState state, NullAway analysis) {
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

  private static void reportMismatchedTypeForTernaryOperator(
      Tree tree, Type expressionType, Type subPartType, VisitorState state, NullAway analysis) {
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

  private static void reportInvalidParametersNullabilityError(
      Type formalParameterType,
      Type actualParameterType,
      ExpressionTree paramExpression,
      VisitorState state,
      NullAway analysis) {
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

  private static void reportInvalidOverridingMethodReturnTypeError(
      Tree methodTree,
      Type overriddenMethodReturnType,
      Type overridingMethodReturnType,
      NullAway analysis,
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

  private static void reportInvalidOverridingMethodParamTypeError(
      Tree formalParameterTree,
      Type typeParameterType,
      Type methodParamType,
      NullAway analysis,
      VisitorState state) {
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
   * @return Type of the tree with preserved annotations.
   */
  private static @Nullable Type getTreeType(Tree tree, VisitorState state) {
    if (tree instanceof NewClassTree
        && ((NewClassTree) tree).getIdentifier() instanceof ParameterizedTypeTree) {
      ParameterizedTypeTree paramTypedTree =
          (ParameterizedTypeTree) ((NewClassTree) tree).getIdentifier();
      if (paramTypedTree.getTypeArguments().isEmpty()) {
        // diamond operator, which we do not yet support; for now, return null
        // TODO: support diamond operators
        return null;
      }
      return typeWithPreservedAnnotations(paramTypedTree, state);
    } else if (tree instanceof NewArrayTree
        && ((NewArrayTree) tree).getType() instanceof AnnotatedTypeTree) {
      return typeWithPreservedAnnotations(tree, state);
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
   * @param analysis the analysis object
   * @param state the visitor state
   */
  public static void checkTypeParameterNullnessForAssignability(
      Tree tree, NullAway analysis, VisitorState state) {
    if (!analysis.getConfig().isJSpecifyMode()) {
      return;
    }
    Type lhsType = getTreeType(tree, state);
    Tree rhsTree;
    if (tree instanceof VariableTree) {
      VariableTree varTree = (VariableTree) tree;
      rhsTree = varTree.getInitializer();
    } else {
      AssignmentTree assignmentTree = (AssignmentTree) tree;
      rhsTree = assignmentTree.getExpression();
    }
    // rhsTree can be null for a VariableTree.  Also, we don't need to do a check
    // if rhsTree is the null literal
    if (rhsTree == null || rhsTree.getKind().equals(Tree.Kind.NULL_LITERAL)) {
      return;
    }
    Type rhsType = getTreeType(rhsTree, state);

    if (lhsType != null && rhsType != null) {
      boolean isAssignmentValid = subtypeParameterNullability(lhsType, rhsType, state);
      if (!isAssignmentValid) {
        reportInvalidAssignmentInstantiationError(tree, lhsType, rhsType, state, analysis);
      }
    }
  }

  /**
   * Checks that the nullability of type parameters for a returned expression matches that of the
   * type parameters of the enclosing method's return type.
   *
   * @param retExpr the returned expression
   * @param methodSymbol symbol for enclosing method
   * @param analysis the analysis object
   * @param state the visitor state
   */
  public static void checkTypeParameterNullnessForFunctionReturnType(
      ExpressionTree retExpr,
      Symbol.MethodSymbol methodSymbol,
      NullAway analysis,
      VisitorState state) {
    if (!analysis.getConfig().isJSpecifyMode()) {
      return;
    }

    Type formalReturnType = methodSymbol.getReturnType();
    if (formalReturnType.isRaw()) {
      // bail out of any checking involving raw types for now
      return;
    }
    Type returnExpressionType = getTreeType(retExpr, state);
    if (formalReturnType != null && returnExpressionType != null) {
      boolean isReturnTypeValid =
          subtypeParameterNullability(formalReturnType, returnExpressionType, state);
      if (!isReturnTypeValid) {
        reportInvalidReturnTypeError(
            retExpr, formalReturnType, returnExpressionType, state, analysis);
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
  private static boolean identicalTypeParameterNullability(
      Type lhsType, Type rhsType, VisitorState state) {
    return lhsType.accept(new CheckIdenticalNullabilityVisitor(state), rhsType);
  }

  /**
   * Like {@link #identicalTypeParameterNullability(Type, Type, VisitorState)}, but allows for
   * covariant array subtyping at the top level.
   *
   * @param lhsType type for the lhs of the assignment
   * @param rhsType type for the rhs of the assignment
   * @param state the visitor state
   */
  private static boolean subtypeParameterNullability(
      Type lhsType, Type rhsType, VisitorState state) {
    if (lhsType.getKind().equals(TypeKind.ARRAY) && rhsType.getKind().equals(TypeKind.ARRAY)) {
      // for array types we must allow covariance, i.e., an array of @NonNull references is a
      // subtype of an array of @Nullable references; see
      // https://github.com/jspecify/jspecify/issues/65
      Type.ArrayType lhsArrayType = (Type.ArrayType) lhsType;
      Type.ArrayType rhsArrayType = (Type.ArrayType) rhsType;
      Type lhsComponentType = lhsArrayType.getComponentType();
      Type rhsComponentType = rhsArrayType.getComponentType();
      boolean isLHSNullableAnnotated = isNullableAnnotated(lhsComponentType, state);
      boolean isRHSNullableAnnotated = isNullableAnnotated(rhsComponentType, state);
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
   * @param state the visitor state
   * @return A Type with preserved annotations.
   */
  private static Type typeWithPreservedAnnotations(Tree tree, VisitorState state) {
    return tree.accept(new PreservedAnnotationTreeVisitor(state), null);
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
   * @param analysis the analysis object
   * @param state the visitor state
   */
  public static void checkTypeParameterNullnessForConditionalExpression(
      ConditionalExpressionTree tree, NullAway analysis, VisitorState state) {
    if (!analysis.getConfig().isJSpecifyMode()) {
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
          reportMismatchedTypeForTernaryOperator(
              truePartTree, condExprType, truePartType, state, analysis);
        }
      }
      if (falsePartType != null) {
        if (!subtypeParameterNullability(condExprType, falsePartType, state)) {
          reportMismatchedTypeForTernaryOperator(
              falsePartTree, condExprType, falsePartType, state, analysis);
        }
      }
    }
  }

  private static @Nullable Type getConditionalExpressionType(
      ConditionalExpressionTree tree, VisitorState state) {
    // hack: sometimes array nullability doesn't get computed correctly for a conditional expression
    // on the RHS of an assignment.  So, look at the type of the assignment tree.
    Tree parent = state.getPath().getParentPath().getLeaf();
    if (parent instanceof AssignmentTree || parent instanceof VariableTree) {
      return getTreeType(parent, state);
    }
    return getTreeType(tree, state);
  }

  /**
   * Checks that for each parameter p at a call, the type parameter nullability for p's type matches
   * that of the corresponding formal parameter. If a mismatch is found, report an error.
   *
   * @param formalParams the formal parameters
   * @param actualParams the actual parameters
   * @param isVarArgs true if the call is to a varargs method
   * @param analysis the analysis object
   * @param state the visitor state
   */
  public static void compareGenericTypeParameterNullabilityForCall(
      List<Symbol.VarSymbol> formalParams,
      List<? extends ExpressionTree> actualParams,
      boolean isVarArgs,
      NullAway analysis,
      VisitorState state) {
    if (!analysis.getConfig().isJSpecifyMode()) {
      return;
    }
    int n = formalParams.size();
    if (isVarArgs) {
      // If the last argument is var args, don't check it now, it will be checked against
      // all remaining actual arguments in the next loop.
      n = n - 1;
    }
    for (int i = 0; i < n; i++) {
      Type formalParameter = formalParams.get(i).type;
      if (formalParameter.isRaw()) {
        // bail out of any checking involving raw types for now
        return;
      }
      Type actualParameter = getTreeType(actualParams.get(i), state);
      if (actualParameter != null) {
        if (!subtypeParameterNullability(formalParameter, actualParameter, state)) {
          reportInvalidParametersNullabilityError(
              formalParameter, actualParameter, actualParams.get(i), state, analysis);
        }
      }
    }
    if (isVarArgs && !formalParams.isEmpty()) {
      Type.ArrayType varargsArrayType =
          (Type.ArrayType) formalParams.get(formalParams.size() - 1).type;
      Type varargsElementType = varargsArrayType.elemtype;
      for (int i = formalParams.size() - 1; i < actualParams.size(); i++) {
        Type actualParameterType = getTreeType(actualParams.get(i), state);
        // If the actual parameter type is assignable to the varargs array type, then the call site
        // is passing the varargs directly in an array, and we should skip our check.
        if (actualParameterType != null
            && !state.getTypes().isAssignable(actualParameterType, varargsArrayType)) {
          if (!subtypeParameterNullability(varargsElementType, actualParameterType, state)) {
            reportInvalidParametersNullabilityError(
                varargsElementType, actualParameterType, actualParams.get(i), state, analysis);
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
   * @param analysis the analysis object
   * @param state the visitor state
   */
  public static void checkTypeParameterNullnessForMethodOverriding(
      MethodTree tree,
      Symbol.MethodSymbol overridingMethod,
      Symbol.MethodSymbol overriddenMethod,
      NullAway analysis,
      VisitorState state) {
    if (!analysis.getConfig().isJSpecifyMode()) {
      return;
    }
    // Obtain type parameters for the overridden method within the context of the overriding
    // method's class
    Type methodWithTypeParams =
        state.getTypes().memberType(overridingMethod.owner.type, overriddenMethod);

    checkTypeParameterNullnessForOverridingMethodReturnType(
        tree, methodWithTypeParams, analysis, state);
    checkTypeParameterNullnessForOverridingMethodParameterType(
        tree, methodWithTypeParams, analysis, state);
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
   * @param config The analysis config
   * @return nullability of the return type of {@code method} in the context of {@code
   *     enclosingType}
   */
  public static Nullness getGenericMethodReturnTypeNullness(
      Symbol.MethodSymbol method, Symbol enclosingSymbol, VisitorState state, Config config) {
    Type enclosingType = getTypeForSymbol(enclosingSymbol, state);
    return getGenericMethodReturnTypeNullness(method, enclosingType, state, config);
  }

  /**
   * Get the type for the symbol, accounting for anonymous classes
   *
   * @param symbol the symbol
   * @param state the visitor state
   * @return the type for {@code symbol}
   */
  private static @Nullable Type getTypeForSymbol(Symbol symbol, VisitorState state) {
    if (symbol.isAnonymous()) {
      // For anonymous classes, symbol.type does not contain annotations on generic type parameters.
      // So, we get a correct type from the enclosing NewClassTree.
      TreePath path = state.getPath();
      NewClassTree newClassTree = ASTHelpers.findEnclosingNode(path, NewClassTree.class);
      if (newClassTree == null) {
        throw new RuntimeException(
            "method should be inside a NewClassTree " + state.getSourceForNode(path.getLeaf()));
      }
      Type typeFromTree = getTreeType(newClassTree, state);
      if (typeFromTree != null) {
        verify(state.getTypes().isAssignable(symbol.type, typeFromTree));
      }
      return typeFromTree;
    } else {
      return symbol.type;
    }
  }

  public static Nullness getGenericMethodReturnTypeNullness(
      Symbol.MethodSymbol method, @Nullable Type enclosingType, VisitorState state, Config config) {
    if (enclosingType == null) {
      // we have no additional information from generics, so return NONNULL (presence of a @Nullable
      // annotation should have been handled by the caller)
      return Nullness.NONNULL;
    }
    Type overriddenMethodType = state.getTypes().memberType(enclosingType, method);
    verify(
        overriddenMethodType instanceof ExecutableType,
        "expected ExecutableType but instead got %s",
        overriddenMethodType.getClass());
    return getTypeNullness(overriddenMethodType.getReturnType(), config);
  }

  /**
   * Computes the nullness of the return of a generic method at an invocation, in the context of the
   * declared type of its receiver argument. If the return type is a type variable, its nullness
   * depends on the nullability of the corresponding type parameter in the receiver's type.
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
  public static Nullness getGenericReturnNullnessAtInvocation(
      Symbol.MethodSymbol invokedMethodSymbol,
      MethodInvocationTree tree,
      VisitorState state,
      Config config) {
    if (!(tree.getMethodSelect() instanceof MemberSelectTree) || invokedMethodSymbol.isStatic()) {
      return Nullness.NONNULL;
    }
    Type methodReceiverType =
        getTreeType(((MemberSelectTree) tree.getMethodSelect()).getExpression(), state);
    if (methodReceiverType == null) {
      return Nullness.NONNULL;
    } else {
      return getGenericMethodReturnTypeNullness(
          invokedMethodSymbol, methodReceiverType, state, config);
    }
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
   * @param config the analysis config
   * @return Nullness of parameter at {@code paramIndex}, or {@code NONNULL} if the call does not
   *     invoke an instance method
   */
  public static Nullness getGenericParameterNullnessAtInvocation(
      int paramIndex,
      Symbol.MethodSymbol invokedMethodSymbol,
      MethodInvocationTree tree,
      VisitorState state,
      Config config) {
    if (!(tree.getMethodSelect() instanceof MemberSelectTree) || invokedMethodSymbol.isStatic()) {
      return Nullness.NONNULL;
    }
    Type enclosingType =
        getTreeType(((MemberSelectTree) tree.getMethodSelect()).getExpression(), state);
    return getGenericMethodParameterNullness(
        paramIndex, invokedMethodSymbol, enclosingType, state, config);
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
   * @param config the config
   * @return nullability of the relevant parameter type of {@code method} in the context of {@code
   *     enclosingSymbol}
   */
  public static Nullness getGenericMethodParameterNullness(
      int parameterIndex,
      Symbol.MethodSymbol method,
      Symbol enclosingSymbol,
      VisitorState state,
      Config config) {
    Type enclosingType = getTypeForSymbol(enclosingSymbol, state);
    return getGenericMethodParameterNullness(parameterIndex, method, enclosingType, state, config);
  }

  /**
   * Just like {@link #getGenericMethodParameterNullness(int, Symbol.MethodSymbol, Symbol,
   * VisitorState, Config)}, but takes the enclosing {@code Type} rather than the enclosing {@code
   * Symbol}.
   *
   * @param parameterIndex index of the parameter
   * @param method the generic method
   * @param enclosingType the enclosing type in which we want to know {@code method}'s parameter
   *     type nullability
   * @param state the visitor state
   * @param config the analysis config
   * @return nullability of the relevant parameter type of {@code method} in the context of {@code
   *     enclosingType}
   */
  public static Nullness getGenericMethodParameterNullness(
      int parameterIndex,
      Symbol.MethodSymbol method,
      @Nullable Type enclosingType,
      VisitorState state,
      Config config) {
    if (enclosingType == null) {
      // we have no additional information from generics, so return NONNULL (presence of a top-level
      // @Nullable annotation is handled elsewhere)
      return Nullness.NONNULL;
    }
    Type methodType = state.getTypes().memberType(enclosingType, method);
    Type paramType = methodType.getParameterTypes().get(parameterIndex);
    return getTypeNullness(paramType, config);
  }

  /**
   * This method compares the type parameter annotations for overriding method parameters with
   * corresponding type parameters for the overridden method and reports an error if they don't
   * match
   *
   * @param tree tree for overriding method
   * @param overriddenMethodType type of the overridden method
   * @param analysis the analysis object
   * @param state the visitor state
   */
  private static void checkTypeParameterNullnessForOverridingMethodParameterType(
      MethodTree tree, Type overriddenMethodType, NullAway analysis, VisitorState state) {
    List<? extends VariableTree> methodParameters = tree.getParameters();
    List<Type> overriddenMethodParameterTypes = overriddenMethodType.getParameterTypes();
    // TODO handle varargs; they are not handled for now
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
              analysis,
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
   * @param analysis the analysis object
   * @param state the visitor state
   */
  private static void checkTypeParameterNullnessForOverridingMethodReturnType(
      MethodTree tree, Type overriddenMethodType, NullAway analysis, VisitorState state) {
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
          tree, overriddenMethodReturnType, overridingMethodReturnType, analysis, state);
    }
  }

  /**
   * @param type A type for which we need the Nullness.
   * @param config The analysis config
   * @return Returns the Nullness of the type based on the Nullability annotation.
   */
  private static Nullness getTypeNullness(Type type, Config config) {
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
   * @param config NullAway configuration
   * @param handler NullAway handler
   * @param codeAnnotationInfo information on which code is annotated
   */
  public static boolean passingLambdaOrMethodRefWithGenericReturnToUnmarkedCode(
      Symbol.MethodSymbol methodSymbol,
      ExpressionTree expressionTree,
      VisitorState state,
      Config config,
      CodeAnnotationInfo codeAnnotationInfo,
      Handler handler) {
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

  public static boolean isNullableAnnotated(Type type, VisitorState state) {
    boolean result = false;
    // To ensure that we are checking only jspecify nullable annotations
    Type jspecifyNullableType = JSPECIFY_NULLABLE_TYPE_SUPPLIER.get(state);
    List<Attribute.TypeCompound> lhsAnnotations = type.getAnnotationMirrors();
    for (Attribute.TypeCompound annotation : lhsAnnotations) {
      if (ASTHelpers.isSameType(
          (Type) annotation.getAnnotationType(), jspecifyNullableType, state)) {
        result = true;
        break;
      }
    }
    return result;
  }
}
