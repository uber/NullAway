package com.uber.nullaway.generics;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;
import static com.uber.nullaway.generics.TypeMetadataBuilder.TYPE_METADATA_BUILDER;

import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SimpleTreeVisitor;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeMetadata;
import com.uber.nullaway.Config;
import com.uber.nullaway.Nullness;
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

  private final Config config;

  PreservedAnnotationTreeVisitor(Config config) {
    this.config = config;
  }

  @Override
  public Type visitNewArray(NewArrayTree tree, Void p) {
    Type elemType = tree.getType().accept(this, null);
    return new Type.ArrayType(elemType, castToNonNull(ASTHelpers.getType(tree)).tsym);
  }

  @Override
  public Type visitArrayType(ArrayTypeTree tree, Void p) {
    Type elemType = tree.getType().accept(this, null);
    return new Type.ArrayType(elemType, castToNonNull(ASTHelpers.getType(tree)).tsym);
  }

  @Override
  public Type visitParameterizedType(ParameterizedTypeTree tree, Void p) {
    Type.ClassType baseType = (Type.ClassType) tree.getType().accept(this, null);
    List<? extends Tree> typeArguments = tree.getTypeArguments();
    List<Type> newTypeArgs = new ArrayList<>();
    for (int i = 0; i < typeArguments.size(); i++) {
      newTypeArgs.add(typeArguments.get(i).accept(this, null));
    }
    Type finalType = TYPE_METADATA_BUILDER.createWithBaseTypeAndTypeArgs(baseType, newTypeArgs);
    return finalType;
  }

  @Override
  public Type visitAnnotatedType(AnnotatedTypeTree annotatedType, Void unused) {
    List<? extends AnnotationTree> annotations = annotatedType.getAnnotations();
    boolean hasNullableAnnotation = false;
    Type nullableType = null;
    for (AnnotationTree annotation : annotations) {
      Symbol annotSymbol = ASTHelpers.getSymbol(annotation.getAnnotationType());
      if (annotSymbol != null
          && Nullness.isNullableAnnotation(annotSymbol.getQualifiedName().toString(), config)) {
        hasNullableAnnotation = true;
        // save the type of the nullable annotation, so that we can use it when constructing the
        // TypeMetadata object below
        nullableType = castToNonNull(ASTHelpers.getType(annotation));
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
    TypeMetadata typeMetadata = TYPE_METADATA_BUILDER.create(nullableAnnotationCompound);
    Type underlyingType = annotatedType.getUnderlyingType().accept(this, null);
    Type newType = TYPE_METADATA_BUILDER.cloneTypeWithMetadata(underlyingType, typeMetadata);
    return newType;
  }

  /** By default, just use the type computed by javac */
  @Override
  protected Type defaultAction(Tree node, Void unused) {
    return castToNonNull(ASTHelpers.getType(node));
  }
}
