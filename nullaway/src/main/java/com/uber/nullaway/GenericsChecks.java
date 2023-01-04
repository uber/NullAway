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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Methods for performing checks related to generic types and nullability. */
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

    AnnotatedTypeWrapper lhsTypeWrapper = getAnnotatedTypeWrapper(lhsTree);
    AnnotatedTypeWrapper rhsTypeWrapper = getAnnotatedTypeWrapper(rhsTree);

    checkIdenticalWrappers(tree, lhsTypeWrapper, rhsTypeWrapper);
  }

  private AnnotatedTypeWrapper getAnnotatedTypeWrapper(Tree tree) {
    if (tree instanceof NewClassTree
        && ((NewClassTree) tree).getIdentifier() instanceof ParameterizedTypeTree) {
      return getParameterizedTypeAnnotatedWrapper(
          (ParameterizedTypeTree) ((NewClassTree) tree).getIdentifier());
    } else {
      return getNormalTypeAnnotatedWrapper(ASTHelpers.getType(tree));
    }
  }

  private AnnotatedTypeWrapper getParameterizedTypeAnnotatedWrapper(ParameterizedTypeTree tree) {
    return new AnnotatedTypeWrapper() {
      Type wrappedObject = ASTHelpers.getType(tree);

      @Override
      public Type getWrapped() {
        return wrappedObject;
      }

      @Override
      public HashSet<Integer> getNullableTypeArgIndices() {
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

      @Override
      public List<AnnotatedTypeWrapper> getWrappersForNestedTypes() {
        List<AnnotatedTypeWrapper> wrappersForNestedTypes = new ArrayList<AnnotatedTypeWrapper>();
        List<? extends Tree> typeArguments = tree.getTypeArguments();

        for (int i = 0; i < typeArguments.size(); i++) {
          if (typeArguments.get(i).getClass().equals(JCTree.JCTypeApply.class)) {
            ParameterizedTypeTree parameterizedTypeTreeForTypeArgument =
                (ParameterizedTypeTree) typeArguments.get(i);
            Type argumentType = ASTHelpers.getType(parameterizedTypeTreeForTypeArgument);
            if (argumentType != null
                && argumentType.getTypeArguments() != null
                && argumentType.getTypeArguments().length() > 0) { // Nested generics
              wrappersForNestedTypes.add(
                  getParameterizedTypeAnnotatedWrapper(
                      (ParameterizedTypeTree) typeArguments.get(i)));
            } else {
              wrappersForNestedTypes.add(null);
            }
          }
        }
        return wrappersForNestedTypes;
      }

      @Override
      public boolean isParameterizedTypedWrapper() {
        return true;
      }
    };
  }

  private AnnotatedTypeWrapper getNormalTypeAnnotatedWrapper(Type type) {
    return new AnnotatedTypeWrapper() {
      Type wrappedObject = type;

      @Override
      public Type getWrapped() {
        return wrappedObject;
      }

      @Override
      public HashSet<Integer> getNullableTypeArgIndices() {
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
      public List<AnnotatedTypeWrapper> getWrappersForNestedTypes() {
        List<AnnotatedTypeWrapper> wrappersForNestedTypes = new ArrayList<AnnotatedTypeWrapper>();
        List<Type> typeArguments = wrappedObject.getTypeArguments();

        for (int i = 0; i < typeArguments.size(); i++) {
          // nested generics
          if (typeArguments.get(i).getTypeArguments().length() > 0) {
            wrappersForNestedTypes.add(getNormalTypeAnnotatedWrapper(typeArguments.get(i)));
          } else {
            wrappersForNestedTypes.add(null);
          }
        }
        return wrappersForNestedTypes;
      }

      @Override
      public boolean isParameterizedTypedWrapper() {
        return false;
      }
    };
  }

  public void checkIdenticalWrappers(
      Tree tree, AnnotatedTypeWrapper lhsWrapper, AnnotatedTypeWrapper rhsWrapper) {
    // non-nested typed wrappers
    if (lhsWrapper == null || rhsWrapper == null) {
      return;
    }
    Type lhs = lhsWrapper.getWrapped();
    Type rhs = rhsWrapper.getWrapped();

    AnnotatedTypeWrapper prevOriginalRHSWrapper = rhsWrapper;
    // if types don't match check for super types matching the lhs type
    if (!ASTHelpers.isSameType(lhsWrapper.getWrapped(), rhsWrapper.getWrapped(), state)) {
      if (lhs instanceof Type.ClassType && rhs instanceof Type.ClassType) {
        Type rhsSuperTypeMatchingLHSType =
            supertypeMatchingLHS(
                (Type.ClassType) lhsWrapper.getWrapped(),
                (Type.ClassType) rhsWrapper.getWrapped(),
                state);
        rhsWrapper = getNormalTypeAnnotatedWrapper(rhsSuperTypeMatchingLHSType);
      }
    }

    // check for the same nullable type argument indices
    HashSet<Integer> lhsNullableTypeArgIndices = lhsWrapper.getNullableTypeArgIndices();
    HashSet<Integer> rhsNullableTypeArgIndices = rhsWrapper.getNullableTypeArgIndices();

    if (!lhsNullableTypeArgIndices.equals(rhsNullableTypeArgIndices)) {
      // generate an error
      invalidInstantiationError(tree, lhs, rhs, state, analysis);
      return;
    } else {
      // check for the nested types if and only if the error has not already been generated
      List<AnnotatedTypeWrapper> lhsNestedTypeWrappers = lhsWrapper.getWrappersForNestedTypes();
      List<AnnotatedTypeWrapper> rhsNestedTypeWrappers = rhsWrapper.getWrappersForNestedTypes();
      if (lhsNestedTypeWrappers.size() != rhsNestedTypeWrappers.size()) {
        return;
      }
      for (int i = 0; i < lhsNestedTypeWrappers.size(); i++) {
        checkIdenticalWrappers(tree, lhsNestedTypeWrappers.get(i), rhsNestedTypeWrappers.get(i));
      }
    }

    // for new class trees need to check everything with original rhsType wrapper. Need to find a
    // better way to do this
    if (prevOriginalRHSWrapper.isParameterizedTypedWrapper()) {
      List<AnnotatedTypeWrapper> lhsNestedTypeWrappers = lhsWrapper.getWrappersForNestedTypes();
      List<AnnotatedTypeWrapper> rhsNestedTypeWrappers =
          prevOriginalRHSWrapper.getWrappersForNestedTypes();
      if (lhsNestedTypeWrappers.size() != rhsNestedTypeWrappers.size()) {
        return;
      }
      for (int i = 0; i < lhsNestedTypeWrappers.size(); i++) {
        checkIdenticalWrappers(tree, lhsNestedTypeWrappers.get(i), rhsNestedTypeWrappers.get(i));
      }
    }
  }
}
