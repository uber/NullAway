package com.uber.nullaway;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import java.util.HashSet;
import java.util.List;

public class GenericsChecks {

  // check that type is a valid instantiation of its generic type
  public static void checkInstantiatedType(
      Type type, VisitorState state, Tree tree, NullAway analysis, Config config) {
    CodeAnnotationInfo codeAnnotationInfo = CodeAnnotationInfo.instance(state.context);
    if (config.isJSpecifyMode()) {
      // typeArguments used in the instantiated type (like for Foo<String,Integer>, this gets
      // [String,Integer])
      // if base type is unannotated do not check for generics
      // temporary check to handle testMapComputeIfAbsent
      if (codeAnnotationInfo.isSymbolUnannotated(type.tsym, config)) {
        return;
      }

      com.sun.tools.javac.util.List<Type> typeArguments = type.getTypeArguments();
      HashSet<Integer> nullableTypeArguments = new HashSet<Integer>();
      int index = 0;
      for (Type typArgument : typeArguments) {
        com.sun.tools.javac.util.List<Attribute.TypeCompound> annotationMirrors =
            typArgument.getAnnotationMirrors();
        boolean hasNullableAnnotation =
            Nullness.hasNullableAnnotation(annotationMirrors.stream(), config);
        if (hasNullableAnnotation) {
          nullableTypeArguments.add(index);
        }
        index++;
      }

      Type baseType = type.tsym.type;
      com.sun.tools.javac.util.List<Type> baseTypeArguments = baseType.getTypeArguments();
      index = 0;
      for (Type baseTypeArg : baseTypeArguments) {

        // if type argument at current index has @Nullable annotation base type argument at that
        // index
        // should also have a @Nullable annotation on its upper bound.
        if (nullableTypeArguments.contains(index)) {

          Type upperBound = baseTypeArg.getUpperBound();
          com.sun.tools.javac.util.List<Attribute.TypeCompound> annotationMirrors =
              upperBound.getAnnotationMirrors();
          boolean hasNullableAnnotation =
              Nullness.hasNullableAnnotation(annotationMirrors.stream(), config);
          // if base type argument does not have @Nullable annotation then the instantiation is
          // invalid
          if (!hasNullableAnnotation) {
            invalidInstantiationError(tree, state, analysis);
          }
        }
        index++;
      }
      // Generics check for nested type parameters
      for (Type typeArgument : typeArguments) {
        if (typeArgument.getTypeArguments().length() > 0) {
          checkInstantiatedType(typeArgument, state, tree, analysis, config);
        }
      }
    }
  }

  /** Generics checks for parameterized typed trees * */
  public static void checkInstantiationForParameterizedTypedTree(
      ParameterizedTypeTree tree, VisitorState state, NullAway analysis, Config config) {
    if (!config.isJSpecifyMode()) {
      throw new IllegalStateException("should only check type instantiations in JSpecify mode");
    }
    List<? extends Tree> typeArguments = tree.getTypeArguments();
    HashSet<Integer> nullableTypeArguments = new HashSet<Integer>();
    JCTree.JCTypeApply baseTypeTree = (JCTree.JCTypeApply) tree;
    Type t = baseTypeTree.type;
    Type.ClassType classType = (Type.ClassType) t;
    // If none of the arguments are @Nullable annotated MetaData is empty
    if (classType.tsym.getMetadata() != null) {
      List<Attribute.TypeCompound> baseTypeAttributes =
          classType.tsym.getMetadata().getTypeAttributes();
      // Store the arguments in the base type that have @Nullable annotation in the set
      for (int i = 0; i < baseTypeAttributes.size(); i++) {
        // position - index of the parameters in the list of base type attributes that have
        // @Nullable annotations
        boolean hasNullableAnnotation =
            baseTypeAttributes.get(i).toString().equals("@org.jspecify.nullness.Nullable");
        if (hasNullableAnnotation) {
          nullableTypeArguments.add(baseTypeAttributes.get(i).position.parameter_index);
        }
      }
    }
    for (int i = 0; i < typeArguments.size(); i++) {
      boolean hasNullableAnnotation = false;
      if (typeArguments.get(i).getClass().equals(JCTree.JCTypeApply.class)) {
        ParameterizedTypeTree parameterizedTypeTreeForTypeArgument =
            (ParameterizedTypeTree) typeArguments.get(i);
        Type argumentType = ASTHelpers.getType(parameterizedTypeTreeForTypeArgument);
        if (argumentType != null
            && argumentType.getTypeArguments() != null
            && argumentType.getTypeArguments().length() > 0) { // Nested generics
          checkInstantiationForParameterizedTypedTree(
              parameterizedTypeTreeForTypeArgument, state, analysis, config);
        }
      }
      if (typeArguments.get(i).getClass().equals(JCTree.JCAnnotatedType.class)) {
        JCTree.JCAnnotatedType annotatedType = (JCTree.JCAnnotatedType) typeArguments.get(i);
        for (JCTree.JCAnnotation annotation : annotatedType.getAnnotations()) {
          Attribute.Compound attribute = annotation.attribute;
          hasNullableAnnotation =
              hasNullableAnnotation
                  || attribute.toString().equals("@org.jspecify.nullness.Nullable");
          if (hasNullableAnnotation) {
            break;
          }
        }
      }
      if (hasNullableAnnotation) {
        if (!nullableTypeArguments.contains(i)) {
          invalidInstantiationError(typeArguments.get(i), state, analysis);
        }
      }
    }
  }

  private static void invalidInstantiationError(Tree tree, VisitorState state, NullAway analysis) {
    ErrorBuilder errorBuilder = analysis.getErrorBuilder();
    ErrorMessage errorMessage =
        new ErrorMessage(
            ErrorMessage.MessageTypes.TYPE_PARAMETER_CANNOT_BE_NULLABLE,
            "Generic type parameter cannot be @Nullable");
    state.reportMatch(
        errorBuilder.createErrorDescription(
            errorMessage, analysis.buildDescription(tree), state, null));
  }
}
