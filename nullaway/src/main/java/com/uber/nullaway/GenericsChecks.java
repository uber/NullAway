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
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeMetadata;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Methods for performing checks related to generic types and nullability. */
public final class GenericsChecks {

  private static final String NULLABLE_NAME = "org.jspecify.annotations.Nullable";

  private static final String NULLABLE_TYPE = "@" + NULLABLE_NAME;
  private static final Supplier<Type> NULLABLE_TYPE_SUPPLIER =
      Suppliers.typeFromString(NULLABLE_NAME);
  VisitorState state;
  Config config;
  NullAway analysis;

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
          invalidInstantiationError(
              nullableTypeArguments.get(i), baseType, typeVariable, state, analysis);
        }
      }
    }
  }

  private static void invalidInstantiationError(
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

  private static void invalidAssignmentInstantiationError(
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

  public void checkInstantiationForAssignments(Tree tree) {
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
    Type lhsType = ASTHelpers.getType(lhsTree);
    Type rhsType = ASTHelpers.getType(rhsTree);
    if (rhsTree instanceof NewClassTree
        && ((NewClassTree) rhsTree).getIdentifier() instanceof ParameterizedTypeTree) {
      ParameterizedTypeTree paramTypedTree =
          (ParameterizedTypeTree) ((NewClassTree) rhsTree).getIdentifier();
      // not generic
      if (paramTypedTree.getTypeArguments().size() <= 0) {
        return;
      }
      // for the parameterized typed tree ASTHelpers.getType() returns a type that does not have
      // annotations preserved
      rhsType =
          typeWithPreservedAnnotations(
              (ParameterizedTypeTree) ((NewClassTree) rhsTree).getIdentifier());
    }
    if (lhsType != null && rhsType != null) {
      compareAnnotations((Type.ClassType) lhsType, (Type.ClassType) rhsType, tree);
    }
  }

  private void compareAnnotations(Type.ClassType lhsType, Type.ClassType rhsType, Tree tree) {
    Types types = state.getTypes();
    rhsType = (Type.ClassType) types.asSuper(rhsType, lhsType.tsym);
    if (rhsType == null) {
      throw new RuntimeException("did not find supertype of " + rhsType + " matching " + lhsType);
    }
    List<Type> lhsTypeArguments = lhsType.getTypeArguments();
    List<Type> rhsTypeArguments = rhsType.getTypeArguments();
    if (lhsTypeArguments.size() != rhsTypeArguments.size()) {
      throw new RuntimeException(
          "number of types arguments in " + rhsType + " does not match " + lhsType);
    }
    for (int i = 0; i < lhsTypeArguments.size(); i++) {
      Type lhsTypeArgument = lhsTypeArguments.get(i);
      Type rhsTypeArgument = rhsTypeArguments.get(i);
      com.sun.tools.javac.util.List<Attribute.TypeCompound> annotationMirrorsLHS =
          lhsTypeArgument.getAnnotationMirrors();
      com.sun.tools.javac.util.List<Attribute.TypeCompound> annotationMirrorsRHS =
          rhsTypeArgument.getAnnotationMirrors();
      boolean isLHSNullableAnnotated =
          Nullness.hasNullableAnnotation(annotationMirrorsLHS.stream(), config);
      boolean isRHSNullableAnnotated =
          Nullness.hasNullableAnnotation(annotationMirrorsRHS.stream(), config);
      if (isLHSNullableAnnotated != isRHSNullableAnnotated) {
        invalidAssignmentInstantiationError(tree, lhsType, rhsType, state, analysis);
        return;
      }
      // nested generics
      if (lhsTypeArgument.getTypeArguments().length() > 0) {
        compareAnnotations(
            (Type.ClassType) lhsTypeArgument, (Type.ClassType) rhsTypeArgument, tree);
      }
    }
  }

  /**
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
      List<JCTree.JCAnnotation> annotations = new ArrayList<>();
      JCTree.JCAnnotatedType annotatedType;
      if (typeArguments.get(i).getClass().equals(JCTree.JCAnnotatedType.class)) {
        annotatedType = (JCTree.JCAnnotatedType) typeArguments.get(i);
        annotations = annotatedType.getAnnotations();
      } else if (typeArguments.get(i) instanceof JCTree.JCTypeApply
          && ((JCTree.JCTypeApply) typeArguments.get(i)).clazz instanceof AnnotatedTypeTree) {
        annotatedType = (JCTree.JCAnnotatedType) ((JCTree.JCTypeApply) typeArguments.get(i)).clazz;
        annotations = annotatedType.getAnnotations();
      }
      for (JCTree.JCAnnotation annotation : annotations) {
        Attribute.Compound attribute = annotation.attribute;
        if (attribute.toString().equals(NULLABLE_TYPE)) {
          hasNullableAnnotation = true;
        }
      }
      com.sun.tools.javac.util.List<Attribute.TypeCompound> nullableAnnotations =
          com.sun.tools.javac.util.List.from(new ArrayList<>());
      if (hasNullableAnnotation) {
        nullableAnnotations =
            com.sun.tools.javac.util.List.from(
                Collections.singletonList(
                    new Attribute.TypeCompound(
                        nullableType, com.sun.tools.javac.util.List.nil(), null)));
      }
      TypeMetadata metaData = new TypeMetadata(new TypeMetadata.Annotations(nullableAnnotations));
      // nested generics checks
      Type currentArgType = ASTHelpers.getType(typeArguments.get(i));
      if (currentArgType != null && currentArgType.getTypeArguments().size() > 0) {
        Type.ClassType nestedTypArg =
            castToNonNull(
                    typeWithPreservedAnnotations((ParameterizedTypeTree) typeArguments.get(i)))
                .cloneWithMetadata(metaData);
        newTypeArgs.add(nestedTypArg);
      } else {
        Type.ClassType newArg =
            (Type.ClassType)
                castToNonNull(ASTHelpers.getType(typeArguments.get(i))).cloneWithMetadata(metaData);
        newTypeArgs.add(newArg);
      }
    }
    Type.ClassType finalType =
        new Type.ClassType(
            type.getEnclosingType(), com.sun.tools.javac.util.List.from(newTypeArgs), type.tsym);
    return finalType;
  }
}
