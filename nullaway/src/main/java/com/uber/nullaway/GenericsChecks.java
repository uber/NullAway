package com.uber.nullaway;

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
import com.sun.tools.javac.tree.JCTree;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Methods for performing checks related to generic types and nullability. */
public final class GenericsChecks {

  private static final String NULLABLE_NAME = "org.jspecify.annotations.Nullable";

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

  @SuppressWarnings("UnusedVariable")
  public static Type supertypeMatchingLHS(
      Type.ClassType lhsType, Type.ClassType rhsType, VisitorState state) {
    // all supertypes including classes as well as interfaces
    List<Type> listOfDirectSuperTypes = state.getTypes().closure(rhsType);
    if (listOfDirectSuperTypes != null) {
      for (int i = 0; i < listOfDirectSuperTypes.size(); i++) {
        if (ASTHelpers.isSameType(listOfDirectSuperTypes.get(i), lhsType, state)) {
          return listOfDirectSuperTypes.get(i);
        }
      }
    }

    return rhsType.baseType();
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

  static void invalidInstantiationError(
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

  public void checkInstantiationForAssignments(Tree tree) {
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
    if (rhsTree == null) {
      // Possible for VariableTrees with no initializer
      return;
    }
    Type lhsType = ASTHelpers.getType(lhsTree);
    Type rhsType = ASTHelpers.getType(rhsTree);
    if (rhsTree instanceof NewClassTree
        && ((NewClassTree) rhsTree).getIdentifier() instanceof ParameterizedTypeTree) {
      // for the parameterized typed tree ASTHelpers.getType() returns a type that does not have
      // annotations preserved
      rhsType =
          typeWithPreservedAnnotations(
              (ParameterizedTypeTree) ((NewClassTree) rhsTree).getIdentifier());

      ParameterizedTypeTree paramTypedTree =
          (ParameterizedTypeTree) ((NewClassTree) rhsTree).getIdentifier();
      // not generic
      if (paramTypedTree.getTypeArguments().size() <= 0) {
        return;
      }
    }
    compareAnnotations(lhsType, rhsType, tree);
  }

  private void compareAnnotations(Type lhsType, Type rhsType, Tree tree) {
    if (!ASTHelpers.isSameType(lhsType, rhsType, state)) {
      if (lhsType instanceof Type.ClassType && rhsType instanceof Type.ClassType) {
        rhsType = supertypeMatchingLHS((Type.ClassType) lhsType, (Type.ClassType) rhsType, state);
      }
    }

    List<Type> lhsTypeArguments = lhsType.getTypeArguments();
    List<Type> rhsTypeArguments = rhsType.getTypeArguments();

    // this error should already be generated by some other part of NullAway still adding a check to
    // be safe
    if (lhsTypeArguments.size() != rhsTypeArguments.size()) {
      return;
    }

    for (int i = 0; i < lhsTypeArguments.size(); i++) {
      com.sun.tools.javac.util.List<Attribute.TypeCompound> annotationMirrorsLHS =
          lhsTypeArguments.get(i).getAnnotationMirrors();
      com.sun.tools.javac.util.List<Attribute.TypeCompound> annotationMirrorsRHS =
          rhsTypeArguments.get(i).getAnnotationMirrors();
      boolean isLHSNullableAnnotated =
          Nullness.hasNullableAnnotation(annotationMirrorsLHS.stream(), config);
      boolean isRHSNullableAnnotated =
          Nullness.hasNullableAnnotation(annotationMirrorsRHS.stream(), config);
      if (isLHSNullableAnnotated != isRHSNullableAnnotated) {
        invalidInstantiationError(tree, lhsType, lhsType.baseType(), state, analysis);
        return;
      }
      // nested generics
      if (lhsTypeArguments.get(i).getTypeArguments().length() > 0) {
        compareAnnotations(lhsTypeArguments.get(i), rhsTypeArguments.get(i), tree);
      }
    }
  }

  private Type.ClassType typeWithPreservedAnnotations(ParameterizedTypeTree rhsTree) {
    Type.ClassType type = (Type.ClassType) ASTHelpers.getType(rhsTree);
    Type nullableType = NULLABLE_TYPE_SUPPLIER.get(state);
    List<? extends Tree> typeArguments = rhsTree.getTypeArguments();
    List<Type> newTypeArgs = new ArrayList<>();
    for (int i = 0; i < typeArguments.size(); i++) {
      List<Attribute.TypeCompound> myAnnos = new ArrayList<>();
      if (typeArguments.get(i).getClass().equals(JCTree.JCAnnotatedType.class)) {
        JCTree.JCAnnotatedType annotatedType = (JCTree.JCAnnotatedType) typeArguments.get(i);
        for (JCTree.JCAnnotation annotation : annotatedType.getAnnotations()) {
          Attribute.Compound attribute = annotation.attribute;
          if (attribute.toString().equals("@org.jspecify.annotations.Nullable")) {
            myAnnos.add(
                new Attribute.TypeCompound(
                    nullableType, com.sun.tools.javac.util.List.nil(), null));
          }
        }
      }
      // nested generics checks
      Type currentArgType = ASTHelpers.getType(typeArguments.get(i));
      if (currentArgType.getTypeArguments().size() > 0) {
        Type.ClassType nestedTyp =
            typeWithPreservedAnnotations((ParameterizedTypeTree) typeArguments.get(i));
        newTypeArgs.add(nestedTyp);
      } else {
        com.sun.tools.javac.util.List<Attribute.TypeCompound> annos =
            com.sun.tools.javac.util.List.from(myAnnos);
        TypeMetadata md = new TypeMetadata(new TypeMetadata.Annotations(annos));
        Type arg = ASTHelpers.getType(typeArguments.get(i));
        Type.ClassType newArg = (Type.ClassType) arg.cloneWithMetadata(md);
        newTypeArgs.add(newArg);
      }
    }
    Type.ClassType finalType =
        new Type.ClassType(
            type.getEnclosingType(), com.sun.tools.javac.util.List.from(newTypeArgs), type.tsym);
    return finalType;
  }
}
