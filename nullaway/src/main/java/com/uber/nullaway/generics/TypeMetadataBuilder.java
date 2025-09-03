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
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
   * earlier versions. Uses reflection (MethodHandles) to avoid directly referencing JDK 17-only
   * APIs so the code compiles on newer JDKs.
   */
  class JDK17AndEarlierTypeMetadataBuilder implements TypeMetadataBuilder {
    // Lazily initialized handles, each resolved on first use only
    private static volatile @Nullable MethodHandle cloneWithMetadataHandle;
    private static volatile @Nullable MethodHandle getMetadataHandleV17;
    private static volatile @Nullable MethodHandle classTypeCtorHandleV17;
    private static volatile @Nullable MethodHandle arrayTypeCtorHandleV17;
    private static volatile @Nullable MethodHandle wildcardTypeCtorHandleV17;

    private static MethodHandles.Lookup privLookup(Class<?> cls) {
      try {
        return MethodHandles.privateLookupIn(cls, MethodHandles.lookup());
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    private static MethodHandle createAnnotationsCtorHandle() {
      try {
        MethodHandles.Lookup lookup = privLookup(TypeMetadata.Annotations.class);
        MethodType mt =
            MethodType.methodType(
                void.class, // constructor
                com.sun.tools.javac.util.List.class);
        return lookup.findConstructor(TypeMetadata.Annotations.class, mt);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    // Intentionally deleted: older JDKs may not expose this constructor uniformly

    private static MethodHandle createGetMetadataHandleV17() {
      try {
        MethodHandles.Lookup lookup = privLookup(Type.class);
        MethodType mt = MethodType.methodType(TypeMetadata.class);
        return lookup.findVirtual(Type.class, "getMetadata", mt);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    private static MethodHandle createClassTypeCtorHandleV17() {
      try {
        MethodHandles.Lookup lookup = privLookup(Type.ClassType.class);
        MethodType mt =
            MethodType.methodType(
                void.class,
                Type.class,
                com.sun.tools.javac.util.List.class,
                Symbol.TypeSymbol.class,
                TypeMetadata.class);
        return lookup.findConstructor(Type.ClassType.class, mt);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    private static MethodHandle createArrayTypeCtorHandleV17() {
      try {
        MethodHandles.Lookup lookup = privLookup(Type.ArrayType.class);
        MethodType mt =
            MethodType.methodType(
                void.class, Type.class, Symbol.TypeSymbol.class, TypeMetadata.class);
        return lookup.findConstructor(Type.ArrayType.class, mt);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    private static MethodHandle createWildcardTypeCtorHandleV17() {
      try {
        MethodHandles.Lookup lookup = privLookup(Type.WildcardType.class);
        MethodType mt =
            MethodType.methodType(
                void.class,
                Type.class,
                BoundKind.class,
                Symbol.TypeSymbol.class,
                TypeMetadata.class);
        return lookup.findConstructor(Type.WildcardType.class, mt);
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
      MethodHandles.Lookup lookup = privLookup(refClass);
      MethodType mt = MethodType.methodType(retTypeClass, paramTypeClass);
      try {
        return lookup.findVirtual(refClass, methodName, mt);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public TypeMetadata create(com.sun.tools.javac.util.List<Attribute.TypeCompound> attrs) {
      // Resolve constructors lazily here to avoid initializing them when not needed
      try {
        MethodHandle annCtor;
        try {
          annCtor = createAnnotationsCtorHandle();
        } catch (RuntimeException ex) {
          // Fallback: some JDKs require ListBuffer for Annotations ctor
          MethodHandles.Lookup lookup = privLookup(TypeMetadata.Annotations.class);
          MethodType mt = MethodType.methodType(void.class, ListBuffer.class);
          annCtor = lookup.findConstructor(TypeMetadata.Annotations.class, mt);
        }

        Object annotations = annCtor.invoke(attrs);
        if (annotations instanceof TypeMetadata) {
          return (TypeMetadata) annotations;
        }
        // As a last resort, try to find any matching constructor on TypeMetadata
        Constructor<?> match =
            Arrays.stream(TypeMetadata.class.getDeclaredConstructors())
                .filter(
                    c -> {
                      Class<?>[] p = c.getParameterTypes();
                      return p.length == 1 && p[0].isAssignableFrom(annotations.getClass());
                    })
                .findFirst()
                .orElse(null);
        if (match != null) {
          match.setAccessible(true);
          MethodHandle tmCtor = MethodHandles.lookup().unreflectConstructor(match);
          return (TypeMetadata) tmCtor.invoke(annotations);
        }
        throw new NoSuchMethodException(
            "No TypeMetadata constructor compatible with " + annotations.getClass());
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public Type cloneTypeWithMetadata(Type typeToBeCloned, TypeMetadata metadata) {
      try {
        MethodHandle mh = cloneWithMetadataHandle;
        if (mh == null) {
          mh =
              createVirtualMethodHandle(
                  Type.class, TypeMetadata.class, Type.class, "cloneWithMetadata");
          cloneWithMetadataHandle = mh;
        }
        return (Type) mh.invoke(typeToBeCloned, metadata);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public Type.ClassType createClassType(Type baseType, Type enclosingType, List<Type> typeArgs) {
      try {
        MethodHandle getMd = getMetadataHandleV17;
        if (getMd == null) {
          getMd = createGetMetadataHandleV17();
          getMetadataHandleV17 = getMd;
        }
        TypeMetadata metadata = (TypeMetadata) getMd.invoke(baseType);
        MethodHandle ctor = classTypeCtorHandleV17;
        if (ctor == null) {
          ctor = createClassTypeCtorHandleV17();
          classTypeCtorHandleV17 = ctor;
        }
        return (Type.ClassType)
            ctor.invoke(
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
        MethodHandle getMd = getMetadataHandleV17;
        if (getMd == null) {
          getMd = createGetMetadataHandleV17();
          getMetadataHandleV17 = getMd;
        }
        TypeMetadata metadata = (TypeMetadata) getMd.invoke(baseType);
        MethodHandle ctor = arrayTypeCtorHandleV17;
        if (ctor == null) {
          ctor = createArrayTypeCtorHandleV17();
          arrayTypeCtorHandleV17 = ctor;
        }
        return (Type.ArrayType) ctor.invoke(elementType, baseType.tsym, metadata);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public Type.WildcardType createWildcardType(Type.WildcardType baseType, Type boundType) {
      try {
        MethodHandle getMd = getMetadataHandleV17;
        if (getMd == null) {
          getMd = createGetMetadataHandleV17();
          getMetadataHandleV17 = getMd;
        }
        TypeMetadata metadata = (TypeMetadata) getMd.invoke(baseType);
        MethodHandle ctor = wildcardTypeCtorHandleV17;
        if (ctor == null) {
          ctor = createWildcardTypeCtorHandleV17();
          wildcardTypeCtorHandleV17 = ctor;
        }
        return (Type.WildcardType) ctor.invoke(boundType, baseType.kind, baseType.tsym, metadata);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Provides implementations for methods under TypeMetadataBuilder compatible with the updates made
   * to the library methods for JDK 21+. Calls the APIs directly since we compile on a recent JDK.
   */
  class JDK21TypeMetadataBuilder implements TypeMetadataBuilder {

    @Override
    public TypeMetadata create(com.sun.tools.javac.util.List<Attribute.TypeCompound> attrs) {
      ListBuffer<Attribute.TypeCompound> b = new ListBuffer<>();
      b.appendList(attrs);
      return new TypeMetadata.Annotations(b);
    }

    /** In JDK 21+ cloneWithMetadata was removed; use dropMetadata/addMetadata. */
    @Override
    public Type cloneTypeWithMetadata(Type typeToBeCloned, TypeMetadata metadata) {
      Type without = typeToBeCloned.dropMetadata(metadata.getClass());
      return without.addMetadata(metadata);
    }

    @Override
    public Type.ClassType createClassType(Type baseType, Type enclosingType, List<Type> typeArgs) {
      com.sun.tools.javac.util.List<TypeMetadata> metadata = baseType.getMetadata();
      return new Type.ClassType(
          enclosingType, com.sun.tools.javac.util.List.from(typeArgs), baseType.tsym, metadata);
    }

    @Override
    public Type.ArrayType createArrayType(Type.ArrayType baseType, Type elementType) {
      com.sun.tools.javac.util.List<TypeMetadata> metadata = baseType.getMetadata();
      return new Type.ArrayType(elementType, baseType.tsym, metadata);
    }

    @Override
    public Type.WildcardType createWildcardType(Type.WildcardType baseType, Type boundType) {
      com.sun.tools.javac.util.List<TypeMetadata> metadata = baseType.getMetadata();
      return new Type.WildcardType(boundType, baseType.kind, baseType.tsym, metadata);
    }
  }
}
