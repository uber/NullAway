package com.uber.nullaway;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.common.base.Preconditions;
import com.google.errorprone.VisitorState;
import com.google.errorprone.suppliers.Supplier;
import com.google.errorprone.suppliers.Suppliers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Attribute;
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
  private VisitorState state;
  private Config config;
  private NullAway analysis;

  public GenericsChecks(VisitorState state, Config config, NullAway analysis) {
    this.state = state;
    this.config = config;
    this.analysis = analysis;
  }

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
                    + rhsType
                    + " to type "
                    + lhsType
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
                "Cannot return the type "
                    + returnType
                    + " to the method type "
                    + methodType
                    + " due to mismatched nullability of type parameters"));
    state.reportMatch(
        errorBuilder.createErrorDescription(
            errorMessage, analysis.buildDescription(tree), state, null));
  }

  /**
   * This method returns type of the tree considering that the parameterized typed tree annotations
   * are not preserved if obtained directly using ASTHelpers.
   *
   * @param tree A tree for which we need the type with preserved annotations.
   * @return Type of the tree with preserved annotations.
   */
  @Nullable
  private Type getTreeType(Tree tree) {
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
    } else {
      return ASTHelpers.getType(tree);
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
   */
  public void checkTypeParameterNullnessForAssignability(Tree tree) {
    if (!config.isJSpecifyMode()) {
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
    Type lhsType = getTreeType(lhsTree);
    Type rhsType = getTreeType(rhsTree);

    if (lhsType instanceof Type.ClassType && rhsType instanceof Type.ClassType) {
      boolean isAssignmentValid =
          compareNullabilityAnnotations((Type.ClassType) lhsType, (Type.ClassType) rhsType);
      if (!isAssignmentValid) {
        reportInvalidAssignmentInstantiationError(tree, lhsType, rhsType, state, analysis);
      }
    }
  }

  public void checkTypeParameterNullnessForFunctionReturnType(
      ExpressionTree retExpr, Symbol.MethodSymbol methodSymbol) {
    if (!config.isJSpecifyMode()) {
      return;
    }

    Type methodType = methodSymbol.getReturnType();
    // check nullability of parameters only for generics
    if (methodType.getTypeArguments().isEmpty()) {
      return;
    }
    Type returnExpressionType = getTreeType(retExpr);
    if (methodType instanceof Type.ClassType && returnExpressionType instanceof Type.ClassType) {
      boolean isReturnTypeValid =
          compareNullabilityAnnotations(
              (Type.ClassType) methodType, (Type.ClassType) returnExpressionType);
      if (!isReturnTypeValid) {
        reportInvalidReturnTypeError(retExpr, methodType, returnExpressionType, state, analysis);
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
   */
  private boolean compareNullabilityAnnotations(Type.ClassType lhsType, Type.ClassType rhsType) {
    Types types = state.getTypes();
    // The base type of rhsType may be a subtype of lhsType's base type.  In such cases, we must
    // compare lhsType against the supertype of rhsType with a matching base type.
    rhsType = (Type.ClassType) types.asSuper(rhsType, lhsType.tsym);
    // This is impossible, considering the fact that standard Java subtyping succeeds before running
    // NullAway
    if (rhsType == null) {
      throw new RuntimeException("Did not find supertype of " + rhsType + " matching " + lhsType);
    }
    List<Type> lhsTypeArguments = lhsType.getTypeArguments();
    List<Type> rhsTypeArguments = rhsType.getTypeArguments();
    // This is impossible, considering the fact that standard Java subtyping succeeds before running
    // NullAway
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
      if (lhsTypeArgument.getTypeArguments().length() > 0) {
        if (!compareNullabilityAnnotations(
            (Type.ClassType) lhsTypeArgument, (Type.ClassType) rhsTypeArgument)) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * For the Parameterized typed trees, ASTHelpers.getType(tree) does not return a Type with
   * preserved annotations. This method takes a Parameterized typed tree as an input and returns the
   * Type of the tree with the annotations.
   *
   * @param tree A parameterized typed tree for which we need class type with preserved annotations.
   * @return A Type with preserved annotations.
   */
  private Type.ClassType typeWithPreservedAnnotations(ParameterizedTypeTree tree) {
    Type.ClassType type = (Type.ClassType) ASTHelpers.getType(tree);
    Preconditions.checkNotNull(type);
    Type nullableType = NULLABLE_TYPE_SUPPLIER.get(state);
    List<? extends Tree> typeArguments = tree.getTypeArguments();
    List<Type> newTypeArgs = new ArrayList<>();
    boolean hasNullableAnnotation = false;
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
      Type currentTypeArgType = castToNonNull(ASTHelpers.getType(curTypeArg));
      if (currentTypeArgType.getTypeArguments().size() > 0) {
        // nested generic type; recursively preserve its nullability type argument annotations
        currentTypeArgType = typeWithPreservedAnnotations((ParameterizedTypeTree) curTypeArg);
      }
      Type.ClassType newTypeArgType =
          (Type.ClassType) currentTypeArgType.cloneWithMetadata(typeMetadata);
      newTypeArgs.add(newTypeArgType);
    }
    Type.ClassType finalType =
        new Type.ClassType(
            type.getEnclosingType(), com.sun.tools.javac.util.List.from(newTypeArgs), type.tsym);
    return finalType;
  }
}
