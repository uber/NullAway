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
import org.jspecify.annotations.Nullable;

/**
 * Visitor For getting the preserved Annotation Types for the nested generic type arguments within a
 * ParameterizedTypeTree. This is required primarily since javac does not preserve annotations on
 * generic type arguments in its types for NewClassTrees. We need a visitor since the nested
 * arguments may appear on different kinds of type trees, e.g., ArrayTypeTrees.
 */
public class PreservedAnnotationTreeVisitor extends SimpleTreeVisitor<Type, @Nullable Void> {

  private final Config config;

  PreservedAnnotationTreeVisitor(Config config) {
    this.config = config;
  }

  /**
   * Computes the type of an array creation expression, preserving nullability annotations on the
   * element type.
   *
   * <p>{@link NewArrayTree#getType()} yields the element type of the innermost dimension only,
   * e.g., {@code @Nullable Integer} for {@code new @Nullable Integer[3][4]}. So the rank of the
   * created array is taken from the type javac computed for the whole expression, and the element
   * type is wrapped in one array level for each dimension it does not already have. Taking the rank
   * from javac's type rather than from {@link NewArrayTree#getDimensions()} also handles the
   * array-initializer form {@code new @Nullable Integer[]{null}}, which has no explicit dimension
   * expressions but still creates a one-dimensional array.
   *
   * @param tree the array creation expression
   * @return the type of {@code tree}, with nullability annotations preserved on the element type
   */
  @Override
  public Type visitNewArray(NewArrayTree tree, @Nullable Void p) {
    Type elemType = tree.getType().accept(this, null);
    Type javacArrayType = castToNonNull(ASTHelpers.getType(tree));
    Type result = elemType;
    for (int i = arrayDimensionCount(elemType); i < arrayDimensionCount(javacArrayType); i++) {
      result = new Type.ArrayType(result, javacArrayType.tsym);
    }
    return result;
  }

  /**
   * Computes the number of array dimensions of a type, e.g., 2 for {@code String[][]}.
   *
   * @param type the type to inspect
   * @return the number of array dimensions of {@code type}, or 0 if it is not an array type
   */
  private static int arrayDimensionCount(Type type) {
    int count = 0;
    Type current = type;
    while (current instanceof Type.ArrayType arrayType) {
      count++;
      current = arrayType.getComponentType();
    }
    return count;
  }

  @Override
  public Type visitArrayType(ArrayTypeTree tree, @Nullable Void p) {
    Type elemType = tree.getType().accept(this, null);
    return new Type.ArrayType(elemType, castToNonNull(ASTHelpers.getType(tree)).tsym);
  }

  @Override
  public Type visitParameterizedType(ParameterizedTypeTree tree, @Nullable Void p) {
    Type.ClassType baseType = (Type.ClassType) tree.getType().accept(this, null);
    List<? extends Tree> typeArguments = tree.getTypeArguments();
    List<Type> newTypeArgs = new ArrayList<>();
    for (int i = 0; i < typeArguments.size(); i++) {
      newTypeArgs.add(typeArguments.get(i).accept(this, null));
    }
    Type finalType =
        TYPE_METADATA_BUILDER.createClassType(baseType, baseType.getEnclosingType(), newTypeArgs);
    return finalType;
  }

  @Override
  public Type visitAnnotatedType(AnnotatedTypeTree annotatedType, @Nullable Void unused) {
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
  protected Type defaultAction(Tree node, @Nullable Void unused) {
    return castToNonNull(ASTHelpers.getType(node));
  }
}
