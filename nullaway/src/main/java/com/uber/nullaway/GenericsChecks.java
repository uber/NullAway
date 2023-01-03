package com.uber.nullaway;

import com.google.errorprone.VisitorState;
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
import com.sun.tools.javac.tree.JCTree;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Methods for performing checks related to generic types and nullability. */
@SuppressWarnings("ClassCanBeStatic")
public final class GenericsChecks {
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

  @SuppressWarnings("UnusedVariable")
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
    if (rhsTree instanceof NewClassTree
        && ((NewClassTree) rhsTree).getIdentifier() instanceof ParameterizedTypeTree) {
      ParameterizedTypeTreeNullableArgIndices typeWrapper =
          new ParameterizedTypeTreeNullableArgIndices();
      typeWrapper.checkAssignmentTypeMatch(
          tree,
          ASTHelpers.getType(lhsTree),
          (ParameterizedTypeTree) ((NewClassTree) rhsTree).getIdentifier());
    } else {
      NormalTypeTreeNullableTypeArgIndices typeWrapper = new NormalTypeTreeNullableTypeArgIndices();
      typeWrapper.checkAssignmentTypeMatch(
          tree, ASTHelpers.getType(lhsTree), ASTHelpers.getType(rhsTree));
    }
  }

  class ParameterizedTypeTreeNullableArgIndices
      implements AnnotatedTypeWrapper<Type, ParameterizedTypeTree> {

    @SuppressWarnings("UnusedVariable")
    @Override
    public HashSet<Integer> getNullableTypeArgIndices(ParameterizedTypeTree tree) {
      List<? extends Tree> typeArguments = tree.getTypeArguments();
      HashSet<Integer> nullableTypeArgIndices = new HashSet<Integer>();
      for (int i = 0; i < typeArguments.size(); i++) {
        if (typeArguments.get(i).getClass().equals(JCTree.JCAnnotatedType.class)) {
          JCTree.JCAnnotatedType annotatedType = (JCTree.JCAnnotatedType) typeArguments.get(i);
          for (JCTree.JCAnnotation annotation : annotatedType.getAnnotations()) {
            Attribute.Compound attribute = annotation.attribute;
            if (attribute.toString().equals("@org.jspecify.annotations.Nullable")) {
              nullableTypeArgIndices.add(i);
              break;
            }
          }
        }
      }
      return nullableTypeArgIndices;
    }

    public void superTypeMatchingRHSParameterizedTypeTree(
        Tree tree, ParameterizedTypeTree rhs, Type lhs) {
      if (lhs instanceof Type.ClassType && ASTHelpers.getType(rhs) instanceof Type.ClassType) {
        Type rhsType =
            GenericsChecks.supertypeMatchingLHS(
                (Type.ClassType) lhs, (Type.ClassType) ASTHelpers.getType(rhs), state);
        NormalTypeTreeNullableTypeArgIndices typeWrapper =
            new NormalTypeTreeNullableTypeArgIndices();
        typeWrapper.checkAssignmentTypeMatch(tree, lhs, rhsType);
      }
    }

    @Override
    public void checkAssignmentTypeMatch(Tree tree, Type lhs, ParameterizedTypeTree rhs) {
      // if types are not same check for the super types
      if (!ASTHelpers.isSameType(lhs, ASTHelpers.getType(rhs), state)) {
        superTypeMatchingRHSParameterizedTypeTree(tree, rhs, lhs);
      }
      NormalTypeTreeNullableTypeArgIndices normalTypeTreeNullableTypeArgIndices =
          new NormalTypeTreeNullableTypeArgIndices();
      HashSet<Integer> lhsNullableArgIndices =
          normalTypeTreeNullableTypeArgIndices.getNullableTypeArgIndices(lhs);
      HashSet<Integer> rhsNullableArgIndices = getNullableTypeArgIndices(rhs);
      if (!lhsNullableArgIndices.equals(rhsNullableArgIndices)) {
        GenericsChecks.invalidInstantiationError(tree, lhs.baseType(), lhs, state, analysis);
        return;
      } else {
        // check for nested types if an error is not already generated
        List<? extends Tree> rhsTypeArguments = rhs.getTypeArguments();
        List<Type> lhsTypeArguments = lhs.getTypeArguments();

        for (int i = 0; i < rhsTypeArguments.size(); i++) {
          if (rhsTypeArguments.get(i).getClass().equals(JCTree.JCTypeApply.class)) {
            ParameterizedTypeTree parameterizedTypeTreeForTypeArgument =
                (ParameterizedTypeTree) rhsTypeArguments.get(i);
            Type argumentType = ASTHelpers.getType(parameterizedTypeTreeForTypeArgument);
            if (argumentType != null
                && argumentType.getTypeArguments() != null
                && argumentType.getTypeArguments().length() > 0) { // Nested generics
              checkAssignmentTypeMatch(
                  tree, lhsTypeArguments.get(i), parameterizedTypeTreeForTypeArgument);
            }
          }
        }
      }
    }
  }

  class NormalTypeTreeNullableTypeArgIndices implements AnnotatedTypeWrapper<Type, Type> {
    @SuppressWarnings("UnusedVariable")
    @Override
    public HashSet<Integer> getNullableTypeArgIndices(Type type) {
      HashSet<Integer> nullableTypeArgIndices = new HashSet<Integer>();
      List<Type> typeArguments = type.getTypeArguments();
      for (int index = 0; index < typeArguments.size(); index++) {
        com.sun.tools.javac.util.List<Attribute.TypeCompound> annotationMirrors =
            typeArguments.get(index).getAnnotationMirrors();
        boolean hasNullableAnnotation =
            Nullness.hasNullableAnnotation(annotationMirrors.stream(), config);
        if (hasNullableAnnotation) {
          nullableTypeArgIndices.add(index);
        }
      }
      return nullableTypeArgIndices;
    }

    @Override
    public void checkAssignmentTypeMatch(Tree tree, Type lhs, Type rhs) {
      // if lhs and rhs are not of the same type check for the super types first
      if (!ASTHelpers.isSameType(lhs, rhs, state) && !rhs.toString().equals("null")) {
        if (lhs instanceof Type.ClassType && rhs instanceof Type.ClassType) {
          rhs =
              GenericsChecks.supertypeMatchingLHS(
                  (Type.ClassType) lhs, (Type.ClassType) rhs, state);
        }
      }
      HashSet<Integer> lhsNullableArgIndices = getNullableTypeArgIndices(lhs);
      HashSet<Integer> rhsNullableArgIndices = getNullableTypeArgIndices(rhs);

      if (!lhsNullableArgIndices.equals(rhsNullableArgIndices)) {
        GenericsChecks.invalidInstantiationError(tree, lhs.baseType(), lhs, state, analysis);
        return;
      } else {
        List<Type> lhsTypeArgs = lhs.getTypeArguments();
        List<Type> rhsTypeArgs = rhs.getTypeArguments();
        // if an error is not already generated check for nested types
        for (int i = 0; i < lhsTypeArgs.size(); i++) {
          // nested generics
          if (lhsTypeArgs.get(i).getTypeArguments().length() > 0) {
            if (rhsTypeArgs.size() > i) {
              checkAssignmentTypeMatch(tree, lhsTypeArgs.get(i), rhsTypeArgs.get(i));
            }
          }
        }
      }
    }
  }
}
