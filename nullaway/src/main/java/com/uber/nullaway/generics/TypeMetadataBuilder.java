package com.uber.nullaway.generics;

import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeMetadata;
import com.sun.tools.javac.util.ListBuffer;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

/**
 * Abstracts over the different APIs for building {@link TypeMetadata} objects in different JDK
 * versions.
 */
public interface TypeMetadataBuilder {
  /** The TypeMetadataBuilder to be used for the current JDK version. */
  TypeMetadataBuilder TYPE_METADATA_BUILDER =
      Runtime.version().feature() >= 21
          ? new JDK21TypeMetadataBuilder()
          : new JDK17AndEarlierTypeMetadataBuilder();

  TypeMetadata create(com.sun.tools.javac.util.List<Attribute.TypeCompound> attrs);

  Type cloneTypeWithMetadata(Type typeToBeCloned, TypeMetadata metaData);

  Type.ClassType createClassType(Type baseType, Type enclosingType, List<Type> typeArgs);

  Type.ArrayType createArrayType(Type.ArrayType baseType, Type elementType);

  Type.WildcardType createWildcardType(Type.WildcardType baseType, Type boundType);

  /**
   * Provides implementations for methods under TypeMetadataBuilder compatible with JDK 17 and
   * earlier versions.
   */
  class JDK17AndEarlierTypeMetadataBuilder implements TypeMetadataBuilder {

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
    public Type.ClassType createClassType(Type baseType, Type enclosingType, List<Type> typeArgs) {
      return new Type.ClassType(
          enclosingType,
          com.sun.tools.javac.util.List.from(typeArgs),
          baseType.tsym,
          baseType.getMetadata());
    }

    @Override
    public Type.ArrayType createArrayType(Type.ArrayType baseType, Type elementType) {
      return new Type.ArrayType(elementType, baseType.tsym, baseType.getMetadata());
    }

    @Override
    public Type.WildcardType createWildcardType(Type.WildcardType baseType, Type boundType) {
      return new Type.WildcardType(boundType, baseType.kind, baseType.tsym, baseType.getMetadata());
    }
  }

  /**
   * Provides implementations for methods under TypeMetadataBuilder compatible with the updates made
   * to the library methods for Jdk 21. The implementation calls the logic specific to JDK 21
   * indirectly using MethodHandles since we still need the code to compile on earlier versions.
   */
  class JDK21TypeMetadataBuilder implements TypeMetadataBuilder {

    private static final MethodHandle typeMetadataConstructorHandle = createHandle();
    private static final MethodHandle addMetadataHandle =
        createVirtualMethodHandle(Type.class, TypeMetadata.class, Type.class, "addMetadata");
    private static final MethodHandle dropMetadataHandle =
        createVirtualMethodHandle(Type.class, Class.class, Type.class, "dropMetadata");
    private static final MethodHandle getMetadataHandler = createGetMetadataHandle();
    private static final MethodHandle classTypeConstructorHandle =
        createClassTypeConstructorHandle();
    private static final MethodHandle arrayTypeConstructorHandle =
        createArrayTypeConstructorHandle();
    private static final MethodHandle wildcardTypeConstructorHandle =
        createWildcardTypeConstructorHandle();

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

    private static MethodHandle createArrayTypeConstructorHandle() {
      try {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodType methodType =
            MethodType.methodType(
                void.class, // return type for a constructor is void
                Type.class,
                Symbol.TypeSymbol.class,
                com.sun.tools.javac.util.List.class);
        return lookup.findConstructor(Type.ArrayType.class, methodType);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    private static MethodHandle createWildcardTypeConstructorHandle() {
      try {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodType methodType =
            MethodType.methodType(
                void.class, // return type for a constructor is void
                Type.class,
                BoundKind.class,
                Symbol.TypeSymbol.class,
                com.sun.tools.javac.util.List.class);
        return lookup.findConstructor(Type.WildcardType.class, methodType);
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
    public Type.ClassType createClassType(Type baseType, Type enclosingType, List<Type> typeArgs) {
      try {
        com.sun.tools.javac.util.List<TypeMetadata> metadata =
            (com.sun.tools.javac.util.List<TypeMetadata>) getMetadataHandler.invoke(baseType);
        return (Type.ClassType)
            classTypeConstructorHandle.invoke(
                enclosingType,
                com.sun.tools.javac.util.List.from(typeArgs),
                baseType.tsym,
                metadata);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public Type.ArrayType createArrayType(Type.ArrayType baseType, Type elementType) {
      try {
        com.sun.tools.javac.util.List<TypeMetadata> metadata =
            (com.sun.tools.javac.util.List<TypeMetadata>) getMetadataHandler.invoke(baseType);
        return (Type.ArrayType)
            arrayTypeConstructorHandle.invoke(elementType, baseType.tsym, metadata);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public Type.WildcardType createWildcardType(Type.WildcardType baseType, Type boundType) {
      try {
        com.sun.tools.javac.util.List<TypeMetadata> metadata =
            (com.sun.tools.javac.util.List<TypeMetadata>) getMetadataHandler.invoke(baseType);
        return (Type.WildcardType)
            wildcardTypeConstructorHandle.invoke(boundType, baseType.kind, baseType.tsym, metadata);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }
  }
}
