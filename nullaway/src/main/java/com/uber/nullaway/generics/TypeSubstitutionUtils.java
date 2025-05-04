package com.uber.nullaway.generics;

import static com.uber.nullaway.generics.ClassDeclarationNullnessAnnotUtils.getAnnotsOnTypeVarsFromSubtypes;
import static com.uber.nullaway.generics.TypeMetadataBuilder.TYPE_METADATA_BUILDER;

import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeMetadata;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.uber.nullaway.Config;
import com.uber.nullaway.Nullness;
import java.util.Collections;
import java.util.Map;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.type.DeclaredType;
import org.jspecify.annotations.Nullable;

/** Utility method related to substituting type arguments for type variables. */
public class TypeSubstitutionUtils {

  /**
   * Returns the type of {@code sym} as a member of {@code t}.
   *
   * @param types the {@link Types} instance
   * @param t the enclosing type
   * @param sym the symbol
   * @param config the NullAway config
   * @return the type of {@code sym} as a member of {@code t}
   */
  public static Type memberType(Types types, Type t, Symbol sym, Config config) {
    Type origType = sym.type;
    Type memberType = types.memberType(t, sym);
    Map<Symbol.TypeVariableSymbol, AnnotationMirror> annotsOnTypeVarsFromSubtypes =
        getAnnotsOnTypeVarsFromSubtypes((DeclaredType) t, (Symbol.MethodSymbol) sym, types, config);
    return restoreExplicitNullabilityAnnotations(
        origType, memberType, config, annotsOnTypeVarsFromSubtypes);
  }

  /**
   * Restores explicit nullability annotations on type variables in {@code origType} to {@code
   * newType}.
   *
   * @param origType the original type
   * @param newType the new type, a result of applying some substitution to {@code origType}
   * @param config the NullAway config
   * @param nullableTypeVarsViaTypeArgs some stuff
   * @return the new type with explicit nullability annotations restored
   */
  private static Type restoreExplicitNullabilityAnnotations(
      Type origType,
      Type newType,
      Config config,
      Map<Symbol.TypeVariableSymbol, AnnotationMirror> nullableTypeVarsViaTypeArgs) {
    return new RestoreNullnessAnnotationsVisitor(config, nullableTypeVarsViaTypeArgs)
        .visit(newType, origType);
  }

  /**
   * A visitor that restores explicit nullability annotations on type variables from another type to
   * the corresponding positions in the visited type. If no annotations need to be restored, returns
   * the visited type object itself.
   */
  @SuppressWarnings("ReferenceEquality")
  private static class RestoreNullnessAnnotationsVisitor extends Types.MapVisitor<Type> {

    private final Config config;
    private final Map<Symbol.TypeVariableSymbol, AnnotationMirror> nullableTypeVarsViaTypeArgs;

    RestoreNullnessAnnotationsVisitor(
        Config config,
        Map<Symbol.TypeVariableSymbol, AnnotationMirror> nullableTypeVarsViaTypeArgs) {
      this.config = config;
      this.nullableTypeVarsViaTypeArgs = nullableTypeVarsViaTypeArgs;
    }

    @Override
    public Type visitMethodType(Type.MethodType t, Type other) {
      Type.MethodType otherMethodType = (Type.MethodType) other;
      List<Type> argtypes = t.argtypes;
      Type restype = t.restype;
      List<Type> thrown = t.thrown;
      List<Type> argtypes1 = visitTypeLists(argtypes, otherMethodType.argtypes);
      Type restype1 = visit(restype, otherMethodType.restype);
      List<Type> thrown1 = visitTypeLists(thrown, otherMethodType.thrown);
      if (argtypes1 == argtypes && restype1 == restype && thrown1 == thrown) {
        return t;
      } else {
        return new Type.MethodType(argtypes1, restype1, thrown1, t.tsym);
      }
    }

    @Override
    public Type visitClassType(Type.ClassType t, Type other) {
      if (other instanceof Type.TypeVar) {
        Type updated = updateNullabilityAnnotationsForType(t, (Type.TypeVar) other);
        if (updated != null) {
          return updated;
        }
      }
      if (!(other instanceof Type.ClassType)) {
        return t;
      }
      Type outer = t.getEnclosingType();
      Type outer1 = visit(outer, other.getEnclosingType());
      List<Type> typarams = t.getTypeArguments();
      List<Type> typarams1 = visitTypeLists(typarams, other.getTypeArguments());
      if (outer1 == outer && typarams1 == typarams) {
        return t;
      } else {
        return TYPE_METADATA_BUILDER.createClassType(t, outer1, typarams1);
      }
    }

    @Override
    public Type visitWildcardType(Type.WildcardType wt, Type other) {
      if (!(other instanceof Type.WildcardType)) {
        return wt;
      }
      Type t = wt.type;
      if (t != null) {
        t = visit(t, ((Type.WildcardType) other).type);
      }
      if (t == wt.type) {
        return wt;
      } else {
        return TYPE_METADATA_BUILDER.createWildcardType(wt, t);
      }
    }

    @Override
    public Type visitTypeVar(Type.TypeVar t, Type other) {
      Type updated = updateNullabilityAnnotationsForType(t, (Type.TypeVar) other);
      return updated != null ? updated : t;
    }

    @Override
    public Type visitForAll(Type.ForAll t, Type other) {
      Type methodType = t.qtype;
      Type otherMethodType = ((Type.ForAll) other).qtype;
      Type newMethodType = visit(methodType, otherMethodType);
      if (methodType == newMethodType) {
        return t;
      } else {
        return new Type.ForAll(t.tvars, newMethodType);
      }
    }

    /**
     * Updates the nullability annotations on a type {@code t} based on the nullability annotations
     * on a type variable {@code other}.
     *
     * @param t the type to update
     * @param other the type variable to update from
     * @return the updated type, or {@code null} if no updates were made
     */
    private @Nullable Type updateNullabilityAnnotationsForType(Type t, Type.TypeVar other) {
      Attribute.TypeCompound typeArgAnnot =
          (Attribute.TypeCompound) nullableTypeVarsViaTypeArgs.get(other.tsym);
      if (typeArgAnnot != null) {
        // TODO should probably detect @NonNull also??
        // Construct and return an updated version of t with annotation annot.
        List<Attribute.TypeCompound> annotationCompound =
            List.from(
                Collections.singletonList(
                    new Attribute.TypeCompound(typeArgAnnot.type, List.nil(), null)));
        TypeMetadata typeMetadata = TYPE_METADATA_BUILDER.create(annotationCompound);
        return TYPE_METADATA_BUILDER.cloneTypeWithMetadata(t, typeMetadata);
      }
      for (Attribute.TypeCompound annot : other.getAnnotationMirrors()) {
        if (annot.type.tsym == null) {
          continue;
        }
        String qualifiedName = annot.type.tsym.getQualifiedName().toString();
        if (Nullness.isNullableAnnotation(qualifiedName, config)
            || Nullness.isNonNullAnnotation(qualifiedName, config)) {
          // Construct and return an updated version of t with annotation annot.
          List<Attribute.TypeCompound> annotationCompound =
              List.from(
                  Collections.singletonList(
                      new Attribute.TypeCompound(annot.type, List.nil(), null)));
          TypeMetadata typeMetadata = TYPE_METADATA_BUILDER.create(annotationCompound);
          return TYPE_METADATA_BUILDER.cloneTypeWithMetadata(t, typeMetadata);
        }
      }
      return null;
    }

    @Override
    public Type visitArrayType(Type.ArrayType t, Type other) {
      if (other instanceof Type.TypeVar) {
        Type updated = updateNullabilityAnnotationsForType(t, (Type.TypeVar) other);
        if (updated != null) {
          return updated;
        }
      }
      if (!(other instanceof Type.ArrayType)) {
        return t;
      }
      Type.ArrayType otherArrayType = (Type.ArrayType) other;
      Type elemtype = t.elemtype;
      Type newElemType = visit(elemtype, otherArrayType.elemtype);
      if (newElemType == elemtype) {
        return t;
      } else {
        return TYPE_METADATA_BUILDER.createArrayType(t, newElemType);
      }
    }

    /**
     * Visits each corresponding pair in two lists of types. Returns a list of the updated types, or
     * {@code newtypes} itself if no updates were made.
     *
     * @param newtypes list of new types to be updated
     * @param origtypes list of original types to update from
     * @return the updated list of types, or {@code newtypes} itself if no updates were made
     */
    private List<Type> visitTypeLists(List<Type> newtypes, List<Type> origtypes) {
      ListBuffer<Type> buf = new ListBuffer<>();
      boolean changed = false;
      for (List<Type> l = newtypes, l1 = origtypes; l.nonEmpty(); l = l.tail, l1 = l1.tail) {
        Type t = l.head;
        Type t1 = l1.head;
        Type t2 = visit(t, t1);
        buf.append(t2);
        if (t2 != t) {
          changed = true;
        }
      }
      return changed ? buf.toList() : newtypes;
    }
  }

  /**
   * Substitutes the types in {@code to} for the types in {@code from} in {@code t}.
   *
   * @param types the {@link Types} instance
   * @param t the type to which to perform the substitution
   * @param from the types that will be substituted out
   * @param to the types that will be substituted in
   * @param config the NullAway config
   * @return the type resulting from the substitution
   */
  public static Type subst(Types types, Type t, List<Type> from, List<Type> to, Config config) {
    Type substResult = types.subst(t, from, to);
    return restoreExplicitNullabilityAnnotations(t, substResult, config, Collections.emptyMap());
  }
}
