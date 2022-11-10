package com.uber.nullaway;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import java.util.HashMap;
import java.util.List;

/** Methods for performing checks related to generic types and nullability. */
public class GenericsChecks {

  private GenericsChecks() {
    // just utility methods
  }

  /**
   * Checks if the type arguments with a {@code @Nullable} annotation in an instantiated type have a
   * {@code @Nullable} upper bound in the declaration, and reports an error otherwise.
   *
   * @param state the visitor state
   * @param analysis the analysis object
   * @param config the analysis config
   * @param nullableTypeArguments indices of {@code Nullable} type arguments
   * @param baseTypeArguments list of type variables (with bounds) in the declared type
   */
  private static void checkNullableTypeArgsAgainstUpperBounds(
      VisitorState state,
      NullAway analysis,
      Config config,
      HashMap<Integer, Tree> nullableTypeArguments,
      com.sun.tools.javac.util.List<Type> baseTypeArguments) {
    for (int i = 0; i < baseTypeArguments.size(); i++) {
      if (nullableTypeArguments.containsKey(i)) {

        Type upperBound = baseTypeArguments.get(i).getUpperBound();
        com.sun.tools.javac.util.List<Attribute.TypeCompound> annotationMirrors =
            upperBound.getAnnotationMirrors();
        boolean hasNullableAnnotation =
            Nullness.hasNullableAnnotation(annotationMirrors.stream(), config);
        // if base type argument does not have @Nullable annotation then the instantiation is
        // invalid
        if (!hasNullableAnnotation) {
          invalidInstantiationError(nullableTypeArguments.get(i), state, analysis);
        }
      }
    }
  }

  /**
   * Checks that for an instantiated generic type, {@code @Nullable} types are only used for type
   * variables that have a {@code @Nullable} upper bound. Similar to {@link
   * #checkInstantiationForParameterizedTypedTree(ParameterizedTypeTree, VisitorState, NullAway,
   * Config)} but specialized for when the instantiated type is represented as a {@link
   * ParameterizedTypeTree}.
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
    HashMap<Integer, Tree> nullableTypeArguments = new HashMap<Integer, Tree>();
    for (int i = 0; i < typeArguments.size(); i++) {
      if (typeArguments.get(i).getClass().equals(JCTree.JCAnnotatedType.class)) {
        JCTree.JCAnnotatedType annotatedType = (JCTree.JCAnnotatedType) typeArguments.get(i);
        for (JCTree.JCAnnotation annotation : annotatedType.getAnnotations()) {
          Attribute.Compound attribute = annotation.attribute;
          if (attribute.toString().equals("@org.jspecify.nullness.Nullable")) {
            nullableTypeArguments.put(i, typeArguments.get(i));
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
    checkNullableTypeArgsAgainstUpperBounds(
        state, analysis, config, nullableTypeArguments, baseTypeArgs);
    // recursive check for nested parameterized types
    for (int i = 0; i < typeArguments.size(); i++) {
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
