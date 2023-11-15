package com.uber.nullaway.generics;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.common.base.Preconditions;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SimpleTreeVisitor;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeMetadata;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Visitor For getting the preserved Annotation Types for the nested generic type arguments within a
 * ParameterizedTypeTree. This is required primarily since javac does not preserve annotations on
 * generic type arguments in its types for NewClassTrees. We need a visitor since the nested
 * arguments may appear on different kinds of type trees, e.g., ArrayTypeTrees.
 */
public class PreservedAnnotationTreeVisitor extends SimpleTreeVisitor<Type, Void> {

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
    Type nullableType = GenericsChecks.JSPECIFY_NULLABLE_TYPE_SUPPLIER.get(state);
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
