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
import com.sun.tools.javac.util.ListBuffer;
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
      TypeMetadata typeMetadata = tmBuilder.create(nullableAnnotationCompound);
      Type currentTypeArgType = curTypeArg.accept(this, null);
      Type newTypeArgType = tmBuilder.cloneTypeWithMetaData(currentTypeArgType, typeMetadata);
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

  private interface TypeMetadataBuilder {
    TypeMetadata create(com.sun.tools.javac.util.List<Attribute.TypeCompound> attrs);

    Type cloneTypeWithMetaData(Type typeToBeCloned, TypeMetadata metaData);
  }

  private static class JDK17AndEarlierTypeMetadataBuilder implements TypeMetadataBuilder {

    @Override
    public TypeMetadata create(com.sun.tools.javac.util.List<Attribute.TypeCompound> attrs) {
      return new TypeMetadata(new TypeMetadata.Annotations(attrs));
    }

    @Override
    public Type cloneTypeWithMetaData(Type typeToBeCloned, TypeMetadata metaData) {
      return typeToBeCloned.cloneWithMetadata(metaData);
    }
  }

  private static class JDK21TypeMetadataBuilder implements TypeMetadataBuilder {

    private static final MethodHandle typeMetadataHandle = createHandle();
    private static final MethodHandle cloneAddMetadataHandle =
        createVirtualMethodHandle(Type.class, TypeMetadata.class, Type.class, "addMetadata");
    private static final MethodHandle cloneDropMetadataHandle =
        createVirtualMethodHandle(Type.class, Class.class, Type.class, "dropMetadata");

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
        return (TypeMetadata) typeMetadataHandle.invoke(b);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public Type cloneTypeWithMetaData(Type typeToBeCloned, TypeMetadata metaData) {
      try {
        // In Jdk21 addMetadata works if there is no metadata associated with the type, so we create
        // a copy without the existing metadata first and then add it
        Type clonedTypeWithoutMetadata =
            (Type) cloneDropMetadataHandle.invoke(typeToBeCloned, metaData.getClass());
        return (Type) cloneAddMetadataHandle.invoke(clonedTypeWithoutMetadata, metaData);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static final TypeMetadataBuilder tmBuilder =
      getVersion() >= 21
          ? new JDK21TypeMetadataBuilder()
          : new JDK17AndEarlierTypeMetadataBuilder();

  private static int getVersion() {
    String version = System.getProperty("java.version");
    if (version.startsWith("1.")) {
      version = version.substring(2, 3);
    } else {
      int dot = version.indexOf(".");
      if (dot != -1) {
        version = version.substring(0, dot);
      }
    }
    return Integer.parseInt(version);
  }
}
