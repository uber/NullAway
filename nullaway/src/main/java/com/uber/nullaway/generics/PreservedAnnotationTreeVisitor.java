package com.uber.nullaway.generics;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;

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
import com.sun.tools.javac.util.ListBuffer;
import com.uber.nullaway.Config;
import com.uber.nullaway.Nullness;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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

  /**
   * Abstracts over the different APIs for building {@link TypeMetadata} objects in different JDK
   * versions.
   */
  private interface TypeMetadataBuilder {
    TypeMetadata create(com.sun.tools.javac.util.List<Attribute.TypeCompound> attrs);

    Type cloneTypeWithMetadata(Type typeToBeCloned, TypeMetadata metaData);

    Type createWithBaseTypeAndTypeArgs(Type baseType, List<Type> typeArgs);
  }

  /**
   * Provides implementations for methods under TypeMetadataBuilder compatible with JDK 17 and
   * earlier versions.
   */
  private static class JDK17AndEarlierTypeMetadataBuilder implements TypeMetadataBuilder {

    @Override
    public TypeMetadata create(com.sun.tools.javac.util.List<Attribute.TypeCompound> attrs) {
      return new TypeMetadata(new TypeMetadata.Annotations(attrs));
    }

    /**
     * Clones the given type with the specified Metadata for getting the right nullability
     * annotations.
     *
     * @param typeToBeCloned The Type we want to clone with the required Nullability Metadata
     * @param metadata The required Nullability metadata which is lost from the type
     * @return Type after it has been cloned by applying the required Nullability metadata
     */
    @Override
    public Type cloneTypeWithMetadata(Type typeToBeCloned, TypeMetadata metadata) {
      return typeToBeCloned.cloneWithMetadata(metadata);
    }

    @Override
    public Type createWithBaseTypeAndTypeArgs(Type baseType, List<Type> typeArgs) {
      return new Type.ClassType(
          baseType.getEnclosingType(),
          com.sun.tools.javac.util.List.from(typeArgs),
          baseType.tsym,
          baseType.getMetadata());
    }
  }

  /**
   * Provides implementations for methods under TypeMetadataBuilder compatible with the updates made
   * to the library methods for Jdk 21. The implementation calls the logic specific to JDK 21
   * indirectly using MethodHandles since we still need the code to compile on earlier versions.
   */
  private static class JDK21TypeMetadataBuilder implements TypeMetadataBuilder {

    private static final MethodHandle typeMetadataConstructorHandle = createHandle();
    private static final MethodHandle addMetadataHandle =
        createVirtualMethodHandle(Type.class, TypeMetadata.class, Type.class, "addMetadata");
    private static final MethodHandle dropMetadataHandle =
        createVirtualMethodHandle(Type.class, Class.class, Type.class, "dropMetadata");
    private static final MethodHandle getMetadataHandler = createGetMetadataHandle();
    private static final MethodHandle classTypeConstructorHandle =
        createClassTypeConstructorHandle();

    private static MethodHandle createHandle() {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      MethodType mt = MethodType.methodType(void.class, com.sun.tools.javac.util.ListBuffer.class);
      try {
        return lookup.findConstructor(TypeMetadata.Annotations.class, mt);
      } catch (NoSuchMethodException e) {
        throw new RuntimeException(e);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    private static MethodHandle createGetMetadataHandle() {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      MethodType mt = MethodType.methodType(com.sun.tools.javac.util.List.class);
      try {
        return lookup.findVirtual(Type.class, "getMetadata", mt);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    private static MethodHandle createClassTypeConstructorHandle() {
      try {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodType methodType =
            MethodType.methodType(
                void.class, // return type for a constructor is void
                Type.class,
                com.sun.tools.javac.util.List.class,
                Symbol.TypeSymbol.class,
                com.sun.tools.javac.util.List.class);
        return lookup.findConstructor(Type.ClassType.class, methodType);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    /**
     * Used to get a MethodHandle for a virtual method from the specified class
     *
     * @param retTypeClass Class to indicate the return type of the desired method
     * @param paramTypeClass Class to indicate the parameter type of the desired method
     * @param refClass Class within which the desired method is contained
     * @param methodName Name of the desired method
     * @return The appropriate MethodHandle for the virtual method
     */
    private static MethodHandle createVirtualMethodHandle(
        Class<?> retTypeClass, Class<?> paramTypeClass, Class<?> refClass, String methodName) {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      MethodType mt = MethodType.methodType(retTypeClass, paramTypeClass);
      try {
        return lookup.findVirtual(refClass, methodName, mt);
      } catch (NoSuchMethodException e) {
        throw new RuntimeException(e);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public TypeMetadata create(com.sun.tools.javac.util.List<Attribute.TypeCompound> attrs) {
      ListBuffer<Attribute.TypeCompound> b = new ListBuffer<>();
      b.appendList(attrs);
      try {
        return (TypeMetadata) typeMetadataConstructorHandle.invoke(b);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    /**
     * Calls dropMetadata and addMetadata using MethodHandles for JDK 21, which removed the previous
     * cloneWithMetadata method.
     *
     * @param typeToBeCloned The Type we want to clone with the required Nullability metadata
     * @param metadata The required Nullability metadata
     * @return Cloned Type with the necessary Nullability metadata
     */
    @Override
    public Type cloneTypeWithMetadata(Type typeToBeCloned, TypeMetadata metadata) {
      try {
        // In JDK 21 addMetadata works if there is no metadata associated with the type, so we
        // create a copy without the existing metadata first and then add it
        Type clonedTypeWithoutMetadata =
            (Type) dropMetadataHandle.invoke(typeToBeCloned, metadata.getClass());
        return (Type) addMetadataHandle.invoke(clonedTypeWithoutMetadata, metadata);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public Type createWithBaseTypeAndTypeArgs(Type baseType, List<Type> typeArgs) {
      try {
        com.sun.tools.javac.util.List<TypeMetadata> metadata =
            (com.sun.tools.javac.util.List<TypeMetadata>) getMetadataHandler.invoke(baseType);
        return (Type)
            classTypeConstructorHandle.invoke(
                baseType.getEnclosingType(),
                com.sun.tools.javac.util.List.from(typeArgs),
                baseType.tsym,
                metadata);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }
  }

  /** The TypeMetadataBuilder to be used for the current JDK version. */
  private static final TypeMetadataBuilder TYPE_METADATA_BUILDER =
      Runtime.version().feature() >= 21
          ? new JDK21TypeMetadataBuilder()
          : new JDK17AndEarlierTypeMetadataBuilder();
}
