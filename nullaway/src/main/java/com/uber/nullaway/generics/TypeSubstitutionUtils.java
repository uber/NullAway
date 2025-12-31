package com.uber.nullaway.generics;

import static com.uber.nullaway.generics.ClassDeclarationNullnessAnnotUtils.getAnnotsOnTypeVarsFromSubtypes;
import static com.uber.nullaway.generics.ConstraintSolver.InferredNullability.NULLABLE;
import static com.uber.nullaway.generics.TypeMetadataBuilder.TYPE_METADATA_BUILDER;

import com.google.common.base.Verify;
import com.google.errorprone.VisitorState;
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
import javax.lang.model.element.Element;
import javax.lang.model.type.DeclaredType;
import org.jspecify.annotations.Nullable;

/** Utility method related to substituting type arguments for type variables. */
public class TypeSubstitutionUtils {

  /**
   * Like {@link Types#asSuper(Type, Symbol)}, but restores explicit nullability annotations on type
   * variables from the subtype to the resulting supertype.
   *
   * @param types the {@link Types} instance
   * @param subtype the subtype
   * @param superTypeSymbol the symbol of the supertype
   * @param config the NullAway config
   * @return the type of {@code subtype} viewed as a {@code superTypeSymbol}, or {@code null} if the
   *     view cannot be computed
   */
  public static @Nullable Type asSuper(
      Types types, Type subtype, Symbol.ClassSymbol superTypeSymbol, Config config) {
    Type asSuper = types.asSuper(subtype, superTypeSymbol);
    if (asSuper == null) {
      return null;
    }
    Map<Symbol.TypeVariableSymbol, AnnotationMirror> annotsOnTypeVarsFromSubtypes =
        subtype instanceof DeclaredType declaredType
            ? getAnnotsOnTypeVarsFromSubtypes(declaredType, superTypeSymbol, types, config)
            : Map.of();
    // superTypeSymbol.asType() is the unsubstituted type of the supertype, which has the
    // same type variables as asSuper; we use it to find the positions corresponding to type
    // variables in asSuper to substitute nullability annotations based on
    // annotsOnTypeVarsFromSubtypes.
    return restoreExplicitNullabilityAnnotations(
        superTypeSymbol.asType(), asSuper, config, annotsOnTypeVarsFromSubtypes);
  }

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
        t instanceof DeclaredType declaredType
            ? getAnnotsOnTypeVarsFromSubtypes(
                declaredType, (Symbol.MethodSymbol) sym, types, config)
            : Map.of();
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
   * @param extraTypeVariableAnnotations Additional annotations to consider for type variables. If
   *     there is no explicit nullability annotation on a type variable {@code X} in {@code
   *     origType}, but {@code X} is present as a key in this map, the corresponding annotation will
   *     be used when substituting in {@code newType}. If {@code X} has an explicit nullability
   *     annotation in {@code origType}, that takes precedence over this map.
   * @return the new type with explicit nullability annotations restored
   */
  public static Type restoreExplicitNullabilityAnnotations(
      Type origType,
      Type newType,
      Config config,
      Map<Symbol.TypeVariableSymbol, AnnotationMirror> extraTypeVariableAnnotations) {
    return new RestoreNullnessAnnotationsVisitor(config, extraTypeVariableAnnotations)
        .visit(newType, origType);
  }

  static Type updateWithInferredNullability(
      Type typeToUpdate,
      Type origType,
      @Nullable Map<Element, ConstraintSolver.InferredNullability> typeVarNullability,
      VisitorState state,
      Config config) {
    if (typeVarNullability == null) {
      // no updates to perform
      return typeToUpdate;
    }
    // we get the original generic type with inferred nullability of type variables
    // substituted in.  So, if the original type was List<T>, and we inferred T to be nullable, then
    // methodReturnTypeWithInferredNullability will be List<@Nullable T>.
    Type inferredNullabilitySubstituted =
        substituteInferredNullabilityForTypeVariables(state, origType, typeVarNullability, config);
    // next, we restore any explicit nullability annotations that were present on the original
    // type.  So, continuing the above example, if origType was List<@NonNull T>, then
    // origExplicitAnnotationsRestored will be List<@NonNull T> even if T was inferred to be
    // nullable.
    Type origExplicitAnnotationsRestored =
        restoreExplicitNullabilityAnnotations(
            origType, inferredNullabilitySubstituted, config, Collections.emptyMap());
    // finally, we apply the nullability annotations to the type at the use site.
    // So, if the original type was List<T>, and we inferred T to be nullable, if javac inferred the
    // type at the use to be List<String>, we will return List<@Nullable String>.
    // TODO optimize these steps to avoid doing so many substitutions in the future, if needed
    return restoreExplicitNullabilityAnnotations(
        origExplicitAnnotationsRestored, typeToUpdate, config, Collections.emptyMap());
  }

  @SuppressWarnings("ReferenceEquality")
  static Type.MethodType updateMethodTypeWithInferredNullability(
      Type.MethodType typeToUpdate,
      Type.MethodType origType,
      @Nullable Map<Element, ConstraintSolver.InferredNullability> typeVarNullability,
      VisitorState state,
      Config config) {
    List<Type> argtypes = typeToUpdate.argtypes;
    Type restype = typeToUpdate.restype;
    List<Type> thrown = typeToUpdate.thrown;
    List<Type> argtypes1 =
        updateTypeList(argtypes, origType.argtypes, typeVarNullability, state, config);
    Type restype1 =
        updateWithInferredNullability(restype, origType.restype, typeVarNullability, state, config);
    List<Type> thrown1 = updateTypeList(thrown, origType.thrown, typeVarNullability, state, config);
    if (argtypes1 == argtypes && restype1 == restype && thrown1 == thrown) {
      return typeToUpdate;
    } else {
      return new Type.MethodType(argtypes1, restype1, thrown1, typeToUpdate.tsym);
    }
  }

  @SuppressWarnings("ReferenceEquality")
  private static List<Type> updateTypeList(
      List<Type> typesToUpdate,
      List<Type> origTypes,
      @Nullable Map<Element, ConstraintSolver.InferredNullability> typeVarNullability,
      VisitorState state,
      Config config) {
    ListBuffer<Type> buf = new ListBuffer<>();
    boolean changed = false;
    for (List<Type> l = typesToUpdate, l1 = origTypes; l.nonEmpty(); l = l.tail, l1 = l1.tail) {
      Type toUpdate = l.head;
      Type orig = l1.head;
      Type t2 = updateWithInferredNullability(toUpdate, orig, typeVarNullability, state, config);
      buf.append(t2);
      if (t2 != toUpdate) {
        changed = true;
      }
    }
    return changed ? buf.toList() : typesToUpdate;
  }

  private static Type substituteInferredNullabilityForTypeVariables(
      VisitorState state,
      Type targetType,
      Map<Element, ConstraintSolver.InferredNullability> typeVarNullability,
      Config config) {
    ListBuffer<Type> typeVars = new ListBuffer<>();
    ListBuffer<Type> inferredTypes = new ListBuffer<>();
    for (Map.Entry<Element, ConstraintSolver.InferredNullability> entry :
        typeVarNullability.entrySet()) {
      if (entry.getValue() == NULLABLE) {
        // find all TypeVars occurring in targetType with the same symbol and substitute for those.
        // we can have multiple such TypeVars due to previous substitutions that modified the type
        // in some way, e.g., by changing its bounds
        Element symbol = entry.getKey();
        TypeVarWithSymbolCollector tvc = new TypeVarWithSymbolCollector(symbol);
        targetType.accept(tvc, null);
        for (Type.TypeVar tv : tvc.getMatches()) {
          typeVars.append(tv);
          inferredTypes.append(
              typeWithAnnot(tv, GenericsChecks.getSyntheticNullableAnnotType(state)));
        }
      }
    }
    List<Type> typeVarsToReplace = typeVars.toList();
    if (!typeVarsToReplace.isEmpty()) {
      return subst(state.getTypes(), targetType, typeVarsToReplace, inferredTypes.toList(), config);
    } else {
      return targetType;
    }
  }

  /**
   * A visitor that restores explicit nullability annotations on types nested within another type to
   * the corresponding positions in the visited type. If no annotations need to be restored, returns
   * the visited type object itself.
   */
  @SuppressWarnings("ReferenceEquality")
  private static class RestoreNullnessAnnotationsVisitor extends Types.MapVisitor<Type> {

    private final Config config;

    /**
     * Additional annotations to consider for type variables. If there is no explicit nullability
     * annotation on a type variable {@code X}, but {@code X} is present as a key in this map, the
     * corresponding annotation will be used when substituting in the visited type. If {@code X} has
     * an explicit nullability annotation, that takes precedence over this map.
     */
    private final Map<Symbol.TypeVariableSymbol, AnnotationMirror> extraTypeVariableAnnotations;

    RestoreNullnessAnnotationsVisitor(
        Config config,
        Map<Symbol.TypeVariableSymbol, AnnotationMirror> extraTypeVariableAnnotations) {
      this.config = config;
      this.extraTypeVariableAnnotations = extraTypeVariableAnnotations;
    }

    @Override
    public Type visitMethodType(Type.MethodType t, Type other) {
      // other can be a ForAll whose qtype is a MethodType; asMethodType() safely unwraps it.
      Type.MethodType otherMethodType = other.asMethodType();
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
      Type updated = updateDirectNullabilityAnnotationsForType(t, other);
      if (!(other instanceof Type.ClassType)) {
        return updated;
      }
      Type outer = updated.getEnclosingType();
      Type outer1 = outer.accept(this, other.getEnclosingType());
      List<Type> typarams = updated.getTypeArguments();
      List<Type> typarams1 = visitTypeLists(typarams, other.getTypeArguments());
      if (outer1 == outer && typarams1 == typarams) {
        return updated;
      } else {
        return TYPE_METADATA_BUILDER.createClassType(updated, outer1, typarams1);
      }
    }

    @Override
    public Type visitWildcardType(Type.WildcardType wt, Type other) {
      if (!(other instanceof Type.WildcardType wildcardType)) {
        return wt;
      }
      Type t = wt.type;
      if (t != null) {
        t = visit(t, wildcardType.type);
      }
      if (t == wt.type) {
        return wt;
      } else {
        return TYPE_METADATA_BUILDER.createWildcardType(wt, t);
      }
    }

    @Override
    public Type visitTypeVar(Type.TypeVar t, Type other) {
      return updateDirectNullabilityAnnotationsForType(t, other);
    }

    @Override
    public Type visitForAll(Type.ForAll t, Type other) {
      Type methodType = t.qtype;
      Type otherMethodType = ((Type.ForAll) other).qtype;
      Type newMethodType = methodType.accept(this, otherMethodType);
      if (methodType == newMethodType) {
        return t;
      } else {
        return new Type.ForAll(t.tvars, newMethodType);
      }
    }

    /**
     * Updates the nullability annotations on a type {@code t} based on the nullability annotations
     * on a type {@code other}. If {@code other} is a type variable, we also check {@code
     * extraTypeVariableAnnotations} for any additional annotations to consider.
     *
     * @param t the type to update
     * @param other the type to update from
     * @return the updated type, or {@code t} if no updates were made
     */
    private Type updateDirectNullabilityAnnotationsForType(Type t, Type other) {
      // first check for annotations directly on the type variable
      for (Attribute.TypeCompound annot : other.getAnnotationMirrors()) {
        if (annot.type.tsym == null) {
          continue;
        }
        String qualifiedName = annot.type.tsym.getQualifiedName().toString();
        if (Nullness.isNullableAnnotation(qualifiedName, config)
            || Nullness.isNonNullAnnotation(qualifiedName, config)) {
          return typeWithAnnot(t, annot);
        }
      }
      // then see if there are any extra annotations to consider
      Attribute.TypeCompound typeArgAnnot =
          (Attribute.TypeCompound) extraTypeVariableAnnotations.get(other.tsym);
      if (typeArgAnnot != null) {
        return typeWithAnnot(t, typeArgAnnot);
      }
      return t;
    }

    private static Type typeWithAnnot(Type t, Attribute.TypeCompound annot) {
      // Construct and return an updated version of t with annotation annot.
      Type annotType = annot.type;
      return TypeSubstitutionUtils.typeWithAnnot(t, annotType);
    }

    @Override
    public Type visitArrayType(Type.ArrayType t, Type other) {
      Type.ArrayType updated = (Type.ArrayType) updateDirectNullabilityAnnotationsForType(t, other);
      if (!(other instanceof Type.ArrayType otherArrayType)) {
        return updated;
      }
      Type elemtype = updated.elemtype;
      Type newElemType = elemtype.accept(this, otherArrayType.elemtype);
      if (newElemType == elemtype) {
        return updated;
      } else {
        return TYPE_METADATA_BUILDER.createArrayType(updated, newElemType);
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

  public static Type typeWithAnnot(Type t, Type annotType) {
    List<Attribute.TypeCompound> annotationCompound =
        List.from(
            Collections.singletonList(new Attribute.TypeCompound(annotType, List.nil(), null)));
    TypeMetadata typeMetadata = TYPE_METADATA_BUILDER.create(annotationCompound);
    return TYPE_METADATA_BUILDER.cloneTypeWithMetadata(t, typeMetadata);
  }

  /**
   * Removes the {@code @Nullable} annotation from the given type.
   *
   * @param argumentType the type from which to remove the {@code @Nullable} annotation (it must be
   *     present)
   * @param config the NullAway config
   * @return the type without the {@code @Nullable} annotation
   */
  public static Type removeNullableAnnotation(Type argumentType, Config config) {
    ListBuffer<Attribute.TypeCompound> updatedAnnotations = new ListBuffer<>();
    boolean removedNullable = false;
    for (Attribute.TypeCompound annot : argumentType.getAnnotationMirrors()) {
      String annotationName = annot.type.toString();
      if (Nullness.isNullableAnnotation(annotationName, config)) {
        removedNullable = true;
        continue;
      }
      updatedAnnotations.append(annot);
    }
    Verify.verify(removedNullable);
    return TYPE_METADATA_BUILDER.cloneTypeWithMetadata(
        argumentType, TYPE_METADATA_BUILDER.create(updatedAnnotations.toList()));
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
