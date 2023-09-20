package com.uber.nullaway;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;
import static java.util.stream.Collectors.joining;

import com.google.common.base.Preconditions;
import com.google.errorprone.VisitorState;
import com.google.errorprone.suppliers.Supplier;
import com.google.errorprone.suppliers.Suppliers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SimpleTreeVisitor;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeMetadata;
import com.sun.tools.javac.code.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Methods for performing checks related to generic types and nullability. */
public final class GenericsChecks {

  private static final String NULLABLE_NAME = "org.jspecify.annotations.Nullable";

  private static final Supplier<Type> NULLABLE_TYPE_SUPPLIER =
      Suppliers.typeFromString(NULLABLE_NAME);

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
   */
  public static void checkInstantiationForParameterizedTypedTree(
      ParameterizedTypeTree tree, VisitorState state, NullAway analysis, Config config) {
    if (!config.isJSpecifyMode()) {
      return;
    }
    List<? extends Tree> typeArguments = tree.getTypeArguments();
    if (typeArguments.size() == 0) {
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
    com.sun.tools.javac.util.List<Type> baseTypeArgs = baseType.tsym.type.getTypeArguments();
    for (int i = 0; i < baseTypeArgs.size(); i++) {
      if (nullableTypeArguments.containsKey(i)) {

        Type typeVariable = baseTypeArgs.get(i);
        Type upperBound = typeVariable.getUpperBound();
        com.sun.tools.javac.util.List<Attribute.TypeCompound> annotationMirrors =
            upperBound.getAnnotationMirrors();
        boolean hasNullableAnnotation =
            Nullness.hasNullableAnnotation(annotationMirrors.stream(), config);
        // if base type argument does not have @Nullable annotation then the instantiation is
        // invalid
        if (!hasNullableAnnotation) {
          reportInvalidInstantiationError(
              nullableTypeArguments.get(i), baseType, typeVariable, state, analysis);
        }
      }
    }
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
                    + prettyTypeForError(rhsType)
                    + " to type "
                    + prettyTypeForError(lhsType)
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
                    + prettyTypeForError(returnType)
                    + " from method with return type "
                    + prettyTypeForError(methodType)
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
                    + prettyTypeForError(expressionType)
                    + " but the sub-expression has type "
                    + prettyTypeForError(subPartType)
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
                + prettyTypeForError(actualParameterType)
                + ", as formal parameter has type "
                + prettyTypeForError(formalParameterType)
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
                + prettyTypeForError(overridingMethodReturnType)
                + ", but overridden method returns "
                + prettyTypeForError(overriddenMethodReturnType)
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
                + prettyTypeForError(methodParamType)
                + ", but overridden method has parameter type "
                + prettyTypeForError(typeParameterType)
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
   * particularly when dealing with {@link com.sun.source.tree.NewClassTree} (e.g., {@code new
   * Foo<@Nullable A>}).
   *
   * @param tree A tree for which we need the type with preserved annotations.
   * @param state the visitor state
   * @return Type of the tree with preserved annotations.
   */
  @Nullable
  private static Type getTreeType(Tree tree, VisitorState state) {
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
    } else {
      Type result = ASTHelpers.getType(tree);
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
    Tree lhsTree;
    Tree rhsTree;
    if (tree instanceof VariableTree) {
      VariableTree varTree = (VariableTree) tree;
      lhsTree = varTree.getType();
      rhsTree = varTree.getInitializer();
    } else {
      AssignmentTree assignmentTree = (AssignmentTree) tree;
      lhsTree = assignmentTree.getVariable();
      rhsTree = assignmentTree.getExpression();
    }
    // rhsTree can be null for a VariableTree.  Also, we don't need to do a check
    // if rhsTree is the null literal
    if (rhsTree == null || rhsTree.getKind().equals(Tree.Kind.NULL_LITERAL)) {
      return;
    }
    Type lhsType = getTreeType(lhsTree, state);
    Type rhsType = getTreeType(rhsTree, state);

    if (lhsType instanceof Type.ClassType && rhsType instanceof Type.ClassType) {
      boolean isAssignmentValid =
          compareNullabilityAnnotations((Type.ClassType) lhsType, (Type.ClassType) rhsType, state);
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
    // check nullability of parameters only for generics
    if (formalReturnType.getTypeArguments().isEmpty()) {
      return;
    }
    Type returnExpressionType = getTreeType(retExpr, state);
    if (formalReturnType instanceof Type.ClassType
        && returnExpressionType instanceof Type.ClassType) {
      boolean isReturnTypeValid =
          compareNullabilityAnnotations(
              (Type.ClassType) formalReturnType, (Type.ClassType) returnExpressionType, state);
      if (!isReturnTypeValid) {
        reportInvalidReturnTypeError(
            retExpr, formalReturnType, returnExpressionType, state, analysis);
      }
    }
  }

  /**
   * Compare two types from an assignment for identical type parameter nullability, recursively
   * checking nested generic types. See <a
   * href="https://jspecify.dev/docs/spec/#nullness-delegating-subtyping">the JSpecify
   * specification</a> and <a
   * href="https://docs.oracle.com/javase/specs/jls/se14/html/jls-4.html#jls-4.10.2">the JLS
   * subtyping rules for class and interface types</a>.
   *
   * @param lhsType type for the lhs of the assignment
   * @param rhsType type for the rhs of the assignment
   * @param state the visitor state
   */
  private static boolean compareNullabilityAnnotations(
      Type lhsType, Type rhsType, VisitorState state) {
    // it is fair to assume rhyType should be the same as lhsType as the Java compiler has passed
    // before NullAway.
    return lhsType.accept(new CompareNullabilityVisitor(state), rhsType);
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
  private static Type.ClassType typeWithPreservedAnnotations(
      ParameterizedTypeTree tree, VisitorState state) {
    return (Type.ClassType) tree.accept(new PreservedAnnotationTreeVisitor(state), null);
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

    Type condExprType = getTreeType(tree, state);
    Type truePartType = getTreeType(truePartTree, state);
    Type falsePartType = getTreeType(falsePartTree, state);
    // The condExpr type should be the least-upper bound of the true and false part types.  To check
    // the nullability annotations, we check that the true and false parts are assignable to the
    // type of the whole expression
    if (condExprType instanceof Type.ClassType) {
      if (truePartType instanceof Type.ClassType) {
        if (!compareNullabilityAnnotations(
            (Type.ClassType) condExprType, (Type.ClassType) truePartType, state)) {
          reportMismatchedTypeForTernaryOperator(
              truePartTree, condExprType, truePartType, state, analysis);
        }
      }
      if (falsePartType instanceof Type.ClassType) {
        if (!compareNullabilityAnnotations(
            (Type.ClassType) condExprType, (Type.ClassType) falsePartType, state)) {
          reportMismatchedTypeForTernaryOperator(
              falsePartTree, condExprType, falsePartType, state, analysis);
        }
      }
    }
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
      if (!formalParameter.getTypeArguments().isEmpty()) {
        Type actualParameter = getTreeType(actualParams.get(i), state);
        if (formalParameter instanceof Type.ClassType
            && actualParameter instanceof Type.ClassType) {
          if (!compareNullabilityAnnotations(
              (Type.ClassType) formalParameter, (Type.ClassType) actualParameter, state)) {
            reportInvalidParametersNullabilityError(
                formalParameter, actualParameter, actualParams.get(i), state, analysis);
          }
        }
      }
    }
    if (isVarArgs && !formalParams.isEmpty()) {
      Type.ArrayType varargsArrayType =
          (Type.ArrayType) formalParams.get(formalParams.size() - 1).type;
      Type varargsElementType = varargsArrayType.elemtype;
      if (varargsElementType.getTypeArguments().size() > 0) {
        for (int i = formalParams.size() - 1; i < actualParams.size(); i++) {
          Type actualParameter = getTreeType(actualParams.get(i), state);
          if (varargsElementType instanceof Type.ClassType
              && actualParameter instanceof Type.ClassType) {
            if (!compareNullabilityAnnotations(
                (Type.ClassType) varargsElementType, (Type.ClassType) actualParameter, state)) {
              reportInvalidParametersNullabilityError(
                  varargsElementType, actualParameter, actualParams.get(i), state, analysis);
            }
          }
        }
      }
    }
  }

  /**
   * Visitor that is called from compareNullabilityAnnotations which recursively compares the
   * Nullability annotations for the nested generic type arguments. Compares the Type it is called
   * upon, i.e. the LHS type and the Type passed as an argument, i.e. The RHS type.
   */
  public static class CompareNullabilityVisitor extends Types.DefaultTypeVisitor<Boolean, Type> {
    private final VisitorState state;

    CompareNullabilityVisitor(VisitorState state) {
      this.state = state;
    }

    @Override
    public Boolean visitClassType(Type.ClassType lhsType, Type rhsType) {
      Types types = state.getTypes();
      // The base type of rhsType may be a subtype of lhsType's base type.  In such cases, we must
      // compare lhsType against the supertype of rhsType with a matching base type.
      rhsType = (Type.ClassType) types.asSuper(rhsType, lhsType.tsym);
      // This is impossible, considering the fact that standard Java subtyping succeeds before
      // running NullAway
      if (rhsType == null) {
        throw new RuntimeException("Did not find supertype of " + rhsType + " matching " + lhsType);
      }
      List<Type> lhsTypeArguments = lhsType.getTypeArguments();
      List<Type> rhsTypeArguments = rhsType.getTypeArguments();
      // This is impossible, considering the fact that standard Java subtyping succeeds before
      // running NullAway
      if (lhsTypeArguments.size() != rhsTypeArguments.size()) {
        throw new RuntimeException(
            "Number of types arguments in " + rhsType + " does not match " + lhsType);
      }
      for (int i = 0; i < lhsTypeArguments.size(); i++) {
        Type lhsTypeArgument = lhsTypeArguments.get(i);
        Type rhsTypeArgument = rhsTypeArguments.get(i);
        boolean isLHSNullableAnnotated = false;
        List<Attribute.TypeCompound> lhsAnnotations = lhsTypeArgument.getAnnotationMirrors();
        // To ensure that we are checking only jspecify nullable annotations
        for (Attribute.TypeCompound annotation : lhsAnnotations) {
          if (annotation.getAnnotationType().toString().equals(NULLABLE_NAME)) {
            isLHSNullableAnnotated = true;
            break;
          }
        }
        boolean isRHSNullableAnnotated = false;
        List<Attribute.TypeCompound> rhsAnnotations = rhsTypeArgument.getAnnotationMirrors();
        // To ensure that we are checking only jspecify nullable annotations
        for (Attribute.TypeCompound annotation : rhsAnnotations) {
          if (annotation.getAnnotationType().toString().equals(NULLABLE_NAME)) {
            isRHSNullableAnnotated = true;
            break;
          }
        }
        if (isLHSNullableAnnotated != isRHSNullableAnnotated) {
          return false;
        }
        // nested generics
        if (!lhsTypeArgument.accept(this, rhsTypeArgument)) {
          return false;
        }
      }
      return true;
    }

    @Override
    public Boolean visitArrayType(Type.ArrayType lhsType, Type rhsType) {
      Type.ArrayType arrRhsType = (Type.ArrayType) rhsType;
      return lhsType.getComponentType().accept(this, arrRhsType.getComponentType());
    }

    @Override
    public Boolean visitType(Type t, Type type) {
      return true;
    }
  }

  /**
   * Visitor For getting the preserved Annotation Type for the nested generic type arguments within
   * the ParameterizedTypeTree originally passed to TypeWithPreservedAnnotations method, since these
   * nested arguments may not always be ParameterizedTypeTrees and may be of different types for
   * e.g. ArrayTypeTree.
   */
  public static class PreservedAnnotationTreeVisitor extends SimpleTreeVisitor<Type, Void> {

    private final VisitorState state;

    PreservedAnnotationTreeVisitor(VisitorState state) {
      this.state = state;
    }

    @Override
    public Type visitArrayType(ArrayTypeTree tree, Void p) {
      Type elemType = tree.getType().accept(this, null);
      return new Type.ArrayType(elemType, castToNonNull(ASTHelpers.getType(tree)).tsym);
    }

    @Override
    public Type visitParameterizedType(ParameterizedTypeTree tree, Void p) {
      Type.ClassType type = (Type.ClassType) ASTHelpers.getType(tree);
      Preconditions.checkNotNull(type);
      Type nullableType = NULLABLE_TYPE_SUPPLIER.get(state);
      List<? extends Tree> typeArguments = tree.getTypeArguments();
      List<Type> newTypeArgs = new ArrayList<>();
      for (int i = 0; i < typeArguments.size(); i++) {
        AnnotatedTypeTree annotatedType = null;
        Tree curTypeArg = typeArguments.get(i);
        // If the type argument has an annotation, it will either be an AnnotatedTypeTree, or a
        // ParameterizedTypeTree in the case of a nested generic type
        if (curTypeArg instanceof AnnotatedTypeTree) {
          annotatedType = (AnnotatedTypeTree) curTypeArg;
        } else if (curTypeArg instanceof ParameterizedTypeTree
            && ((ParameterizedTypeTree) curTypeArg).getType() instanceof AnnotatedTypeTree) {
          annotatedType = (AnnotatedTypeTree) ((ParameterizedTypeTree) curTypeArg).getType();
        }
        List<? extends AnnotationTree> annotations =
            annotatedType != null ? annotatedType.getAnnotations() : Collections.emptyList();
        boolean hasNullableAnnotation = false;
        for (AnnotationTree annotation : annotations) {
          if (ASTHelpers.isSameType(
              nullableType, ASTHelpers.getType(annotation.getAnnotationType()), state)) {
            hasNullableAnnotation = true;
            break;
          }
        }
        // construct a TypeMetadata object containing a nullability annotation if needed
        com.sun.tools.javac.util.List<Attribute.TypeCompound> nullableAnnotationCompound =
            hasNullableAnnotation
                ? com.sun.tools.javac.util.List.from(
                    Collections.singletonList(
                        new Attribute.TypeCompound(
                            nullableType, com.sun.tools.javac.util.List.nil(), null)))
                : com.sun.tools.javac.util.List.nil();
        TypeMetadata typeMetadata =
            new TypeMetadata(new TypeMetadata.Annotations(nullableAnnotationCompound));
        Type currentTypeArgType = curTypeArg.accept(this, null);
        Type newTypeArgType = currentTypeArgType.cloneWithMetadata(typeMetadata);
        newTypeArgs.add(newTypeArgType);
      }
      Type.ClassType finalType =
          new Type.ClassType(
              type.getEnclosingType(), com.sun.tools.javac.util.List.from(newTypeArgs), type.tsym);
      return finalType;
    }

    /** By default, just use the type computed by javac */
    @Override
    protected Type defaultAction(Tree node, Void unused) {
      return castToNonNull(ASTHelpers.getType(node));
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
   * Within the context of class {@code C}, the method {@code Fn.apply} has a return type of
   * {@code @Nullable String}, since {@code @Nullable String} is passed as the type parameter for
   * {@code R}. Hence, it is valid for overriding method {@code C.apply} to return {@code @Nullable
   * String}.
   *
   * @param method the generic method
   * @param enclosingType the enclosing type in which we want to know {@code method}'s return type
   *     nullability
   * @param state Visitor state
   * @param config The analysis config
   * @return nullability of the return type of {@code method} in the context of {@code
   *     enclosingType}
   */
  public static Nullness getGenericMethodReturnTypeNullness(
      Symbol.MethodSymbol method, Type enclosingType, VisitorState state, Config config) {
    Type overriddenMethodType = state.getTypes().memberType(enclosingType, method);
    if (!(overriddenMethodType instanceof Type.MethodType)) {
      throw new RuntimeException("expected method type but instead got " + overriddenMethodType);
    }
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
   * The declared type of {@code f} passes {@code Nullable String} as the type parameter for type
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
    if (!(tree.getMethodSelect() instanceof MemberSelectTree)) {
      return Nullness.NONNULL;
    }
    Type methodReceiverType =
        castToNonNull(
            ASTHelpers.getType(((MemberSelectTree) tree.getMethodSelect()).getExpression()));
    return getGenericMethodReturnTypeNullness(
        invokedMethodSymbol, methodReceiverType, state, config);
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
   * The declared type of {@code f} passes {@code Nullable String} as the type parameter for type
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
    if (!(tree.getMethodSelect() instanceof MemberSelectTree)) {
      return Nullness.NONNULL;
    }
    Type methodReceiverType =
        castToNonNull(
            ASTHelpers.getType(((MemberSelectTree) tree.getMethodSelect()).getExpression()));
    return getGenericMethodParameterNullness(
        paramIndex, invokedMethodSymbol, methodReceiverType, state, config);
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
   * Within the context of class {@code C}, the method {@code Fn.apply} has a parameter type of
   * {@code @Nullable String}, since {@code @Nullable String} is passed as the type parameter for
   * {@code P}. Hence, overriding method {@code C.apply} must take a {@code @Nullable String} as a
   * parameter.
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
      Type enclosingType,
      VisitorState state,
      Config config) {
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
      Type overridingMethodParameterType = ASTHelpers.getType(methodParameters.get(i));
      Type overriddenMethodParameterType = overriddenMethodParameterTypes.get(i);
      if (overriddenMethodParameterType instanceof Type.ClassType
          && overridingMethodParameterType instanceof Type.ClassType) {
        if (!compareNullabilityAnnotations(
            (Type.ClassType) overriddenMethodParameterType,
            (Type.ClassType) overridingMethodParameterType,
            state)) {
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
    Type overridingMethodReturnType = ASTHelpers.getType(tree.getReturnType());
    if (!(overriddenMethodReturnType instanceof Type.ClassType)) {
      return;
    }
    Preconditions.checkArgument(overridingMethodReturnType instanceof Type.ClassType);
    if (!compareNullabilityAnnotations(
        (Type.ClassType) overriddenMethodReturnType,
        (Type.ClassType) overridingMethodReturnType,
        state)) {
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
  public static String prettyTypeForError(Type type) {
    return type.accept(PRETTY_TYPE_VISITOR, null);
  }

  /** This code is a modified version of code in {@link com.google.errorprone.util.Signatures} */
  private static final Type.Visitor<String, Void> PRETTY_TYPE_VISITOR =
      new Types.DefaultTypeVisitor<String, Void>() {
        @Override
        public String visitWildcardType(Type.WildcardType t, Void unused) {
          StringBuilder sb = new StringBuilder();
          sb.append(t.kind);
          if (t.kind != BoundKind.UNBOUND) {
            sb.append(t.type.accept(this, null));
          }
          return sb.toString();
        }

        @Override
        public String visitClassType(Type.ClassType t, Void s) {
          StringBuilder sb = new StringBuilder();
          for (Attribute.TypeCompound compound : t.getAnnotationMirrors()) {
            sb.append('@');
            sb.append(compound.type.accept(this, null));
            sb.append(' ');
          }
          sb.append(t.tsym.getSimpleName());
          if (t.getTypeArguments().nonEmpty()) {
            sb.append('<');
            sb.append(
                t.getTypeArguments().stream()
                    .map(a -> a.accept(this, null))
                    .collect(joining(", ")));
            sb.append(">");
          }
          return sb.toString();
        }

        @Override
        public String visitCapturedType(Type.CapturedType t, Void s) {
          return t.wildcard.accept(this, null);
        }

        @Override
        public String visitArrayType(Type.ArrayType t, Void unused) {
          // TODO properly print cases like int @Nullable[]
          return t.elemtype.accept(this, null) + "[]";
        }

        @Override
        public String visitType(Type t, Void s) {
          return t.toString();
        }
      };
}
