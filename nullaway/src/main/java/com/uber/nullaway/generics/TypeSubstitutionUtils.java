package com.uber.nullaway.generics;

import static com.uber.nullaway.generics.ClassDeclarationNullnessAnnotUtils.getAnnotatedSupertype;
import static com.uber.nullaway.generics.ConstraintSolver.InferredNullability.NULLABLE;
import static com.uber.nullaway.generics.TypeMetadataBuilder.TYPE_METADATA_BUILDER;

import com.google.common.base.Verify;
import com.google.errorprone.VisitorState;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeMetadata;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.uber.nullaway.Config;
import com.uber.nullaway.Nullness;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.type.DeclaredType;
import org.jspecify.annotations.Nullable;

/** Utility method related to substituting type arguments for type variables. */
@SuppressWarnings({"ReferenceEquality", "TypeEquals"}) // deliberate reference equality checks
public class TypeSubstitutionUtils {

  /**
   * Returns a new intersection with the symbol and {@code allInterfaces} flag of {@code original}
   * and the given components. {@code components} must be complete, as {@code getComponents()}
   * returns them: for an interface-only intersection, that starts with the implicit {@code Object}.
   * {@code TypeMetadataBuilder.cloneTypeWithMetadata} cannot be used here: on JDK 17 it throws an
   * {@code AssertionError}, and on JDK 21 and later it can return a plain class type.
   */
  private static Type.IntersectionClassType rebuildIntersection(
      Type.IntersectionClassType original, List<Type> components) {
    return new Type.IntersectionClassType(
        components, (Symbol.ClassSymbol) original.tsym, original.allInterfaces);
  }

  /** Finds an intersection's corresponding component by symbol rather than component position. */
  private static @Nullable Type intersectionComponent(
      Type.IntersectionClassType intersection, Symbol.TypeSymbol symbol) {
    for (Type component : intersection.getComponents()) {
      if (component.tsym.equals(symbol)) {
        return component;
      }
    }
    return null;
  }

  /**
   * Like {@link Types#asSuper(Type, Symbol)}, but restores explicit nullability annotations on type
   * arguments from the subtype's inheritance path to the resulting supertype.
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
    if (subtype.tsym.equals(superTypeSymbol)) {
      return asSuper;
    }
    Type annotatedSupertype =
        subtype instanceof DeclaredType declaredType
            ? getAnnotatedSupertype(declaredType, superTypeSymbol, types, config)
            : null;
    if (annotatedSupertype == null) {
      return asSuper;
    }
    return restoreExplicitNullabilityAnnotations(annotatedSupertype, asSuper, types, config);
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
    Type annotationSource = origType;
    if (t instanceof DeclaredType declaredType
        && sym.owner instanceof Symbol.ClassSymbol owner
        && !t.tsym.equals(owner)) {
      // annotatedOwner is the type of the class containing sym (a supertype of t), capturing any
      // annotations in extends / inherits clauses on the inheritance path from subtype t
      Type annotatedOwner = getAnnotatedSupertype(declaredType, owner, types, config);
      if (annotatedOwner instanceof Type.ClassType annotatedOwnerClassType) {
        Type asMemberOfAnnotatedOwner = types.memberType(annotatedOwnerClassType, sym);
        // this call restores any explicit nullability annotations from the member itself, e.g.,
        // if its return type is declared as @NonNull T
        annotationSource =
            restoreExplicitNullabilityAnnotations(
                origType, asMemberOfAnnotatedOwner, types, config);
      }
    }
    // Here, annotationSource is the type of the member in the superclass with all proper
    // annotations.  But, it might still have generic type variables, which have been properly
    // instantiated in memberType.  So, as a final step, restore the annotations from
    // annotationSource onto memberType.
    Type result =
        restoreExplicitNullabilityAnnotations(annotationSource, memberType, types, config);
    return result;
  }

  /**
   * Restores explicit nullability annotations on type variables in {@code origType} to {@code
   * newType}.
   *
   * @param origType the original type
   * @param newType the new type, a result of applying some substitution to {@code origType}
   * @param types the javac types instance
   * @param config the NullAway config
   * @return the new type with explicit nullability annotations restored
   */
  public static Type restoreExplicitNullabilityAnnotations(
      Type origType, Type newType, Types types, Config config) {
    return new RestoreNullnessAnnotationsVisitor(types, config).visit(newType, origType);
  }

  /**
   * Returns a copy of {@code type} with {@code wildcard} as its backing wildcard.
   *
   * <p>The copy is necessary because javac capture types can be shared across attributed types.
   */
  public static Type.CapturedType replaceCapturedTypeWildcard(
      Type.CapturedType type, Type.WildcardType wildcard) {
    // Do not use cloneTypeWithMetadata here. javac implements metadata "clones" of captured types
    // as wrappers whose upper-bound accessors delegate to the original capture. Returning such a
    // wrapper would allow later javac operations to mutate compiler-owned state through our copy.
    return TYPE_METADATA_BUILDER.createDetachedCapturedType(type, wildcard, type.getUpperBound());
  }

  /**
   * Returns a copy of an unbounded wildcard with a new implicit upper bound.
   *
   * <p>Under JSpecify, an unbounded wildcard uses the upper bound of the formal type variable to
   * which it is passed. javac stores that formal type variable in {@link Type.WildcardType#bound}.
   * This method copies both the wildcard and that type variable before updating the latter's upper
   * bound, so the wildcard remains unbounded and shared javac types are not mutated.
   *
   * @param wildcard the unbounded wildcard to copy
   * @param upperBound the new implicit upper bound
   * @return the copied wildcard
   */
  public static Type.WildcardType replaceUnboundedWildcardUpperBound(
      Type.WildcardType wildcard, Type upperBound) {
    Verify.verify(wildcard.kind == BoundKind.UNBOUND, "wildcard must be unbounded");
    Type.TypeVar formalTypeVariable =
        Verify.verifyNotNull(
            wildcard.bound, "unbounded wildcard has no corresponding formal type variable");
    return replaceUnboundedWildcardUpperBound(wildcard, formalTypeVariable, upperBound);
  }

  /**
   * Returns a copy of an unbounded wildcard with its {@code bound} field set to a copy of {@code
   * typeVariable} with {@code upperBound} as its upper bound.
   *
   * <p>When javac has not recorded the corresponding formal type variable on a captured wildcard,
   * callers can supply the capture itself as {@code typeVariable}.
   */
  public static Type.WildcardType replaceUnboundedWildcardUpperBound(
      Type.WildcardType wildcard, Type.TypeVar typeVariable, Type upperBound) {
    Verify.verify(wildcard.kind == BoundKind.UNBOUND, "wildcard must be unbounded");
    return replaceImplicitWildcardUpperBound(wildcard, typeVariable, upperBound);
  }

  /**
   * Returns a copy of an unbounded or lower-bounded wildcard with its {@code bound} field set to a
   * copy of {@code typeVariable} with {@code upperBound} as its upper bound.
   *
   * <p>The wildcard and type variable are both copied so that shared javac types are not mutated.
   *
   * @param wildcard the unbounded or lower-bounded wildcard to copy
   * @param typeVariable the type variable that supplies the wildcard's implicit upper bound
   * @param upperBound the new implicit upper bound
   * @return the copied wildcard
   */
  public static Type.WildcardType replaceImplicitWildcardUpperBound(
      Type.WildcardType wildcard, Type.TypeVar typeVariable, Type upperBound) {
    Verify.verify(
        wildcard.kind == BoundKind.UNBOUND || wildcard.kind == BoundKind.SUPER,
        "wildcard must have an implicit upper bound");
    // A metadata clone of a javac TypeVar delegates setUpperBound() to the original TypeVar. Build
    // a genuinely detached TypeVar with the desired bound instead of mutating an apparent clone.
    Type.TypeVar updatedFormalTypeVariable =
        TYPE_METADATA_BUILDER.createDetachedTypeVar(typeVariable, upperBound);
    Type.WildcardType updatedWildcard =
        TYPE_METADATA_BUILDER.createWildcardType(wildcard, wildcard.type);
    updatedWildcard.bound = updatedFormalTypeVariable;
    return updatedWildcard;
  }

  /**
   * Updates a type {@code typeToUpdate} by applying inferred nullability for type variables. The
   * update proceeds in three steps:
   *
   * <p>1. Substitute inferred nullability for type variables in the original type {@code origType}.
   * So, if the {@code origType} is {@code List<T>}, and we inferred T to be nullable, the result
   * will be {@code List<@Nullable T>}.
   *
   * <p>2. Restore any explicit nullability annotations that were present on {@code origType} to the
   * result of 1. So, if {@code origType} was {@code List<@NonNull T>}, the result will be {@code
   * List<@NonNull T>}, even if T was inferred to be nullable.
   *
   * <p>3 . Apply the nullability annotations from the result of 2 to {@code typeToUpdate}. So, if
   * {@code typeToUpdate} is {@code List<String>}, and the result of 2 is {@code List<@Nullable T>},
   * the final result will be {@code List<@Nullable String>}.
   *
   * @param typeToUpdate the type to update
   * @param origType the original type with type variables and possibly explicit nullability
   *     annotations
   * @param typeVarNullability a map from type variable elements to their inferred nullability
   * @param state the visitor state
   * @param config the NullAway config
   * @return the updated type with inferred nullability applied
   */
  static Type updateTypeWithInferredNullability(
      Type typeToUpdate,
      Type origType,
      @Nullable Map<Element, ConstraintSolver.InferredNullability> typeVarNullability,
      VisitorState state,
      Config config) {
    if (typeVarNullability == null) {
      // no updates to perform
      return typeToUpdate;
    }
    // step 1
    Type inferredNullabilitySubstituted =
        substituteInferredNullabilityForTypeVariables(origType, typeVarNullability, state, config);
    // step 2
    Type origExplicitAnnotationsRestored =
        restoreExplicitNullabilityAnnotations(
            origType, inferredNullabilitySubstituted, state.getTypes(), config);
    // step 3
    // TODO optimize these steps to avoid doing so many substitutions in the future, if needed
    return restoreExplicitNullabilityAnnotations(
        origExplicitAnnotationsRestored, typeToUpdate, state.getTypes(), config);
  }

  /**
   * Updates a method type {@code typeToUpdate} by applying inferred nullability for type variables.
   * The update is applied to the argument types, return type, and thrown types of the method type,
   * using {@link #updateMethodTypeWithInferredNullability(Type.MethodType, Type.MethodType, Map,
   * VisitorState, Config)}
   *
   * @param methodTypeToUpdate method type to update
   * @param origMethodType original method type, with type variables and possibly explicit
   *     nullability annotations
   * @param typeVarNullability a map from type variable elements to their inferred nullability
   * @param state the visitor state
   * @param config the NullAway config
   * @return the updated method type with inferred nullability applied
   */
  @SuppressWarnings("ReferenceEquality")
  public static Type.MethodType updateMethodTypeWithInferredNullability(
      Type.MethodType methodTypeToUpdate,
      Type.MethodType origMethodType,
      @Nullable Map<Element, ConstraintSolver.InferredNullability> typeVarNullability,
      VisitorState state,
      Config config) {
    List<Type> argtypes = methodTypeToUpdate.argtypes;
    Type restype = methodTypeToUpdate.restype;
    List<Type> thrown = methodTypeToUpdate.thrown;
    List<Type> argtypes1 =
        updateTypeListNullability(
            argtypes, origMethodType.argtypes, typeVarNullability, state, config);
    Type restype1 =
        updateTypeWithInferredNullability(
            restype, origMethodType.restype, typeVarNullability, state, config);
    List<Type> thrown1 =
        updateTypeListNullability(thrown, origMethodType.thrown, typeVarNullability, state, config);
    if (argtypes1 == argtypes && restype1 == restype && thrown1 == thrown) {
      return methodTypeToUpdate;
    } else {
      return new Type.MethodType(argtypes1, restype1, thrown1, methodTypeToUpdate.tsym);
    }
  }

  @SuppressWarnings("ReferenceEquality")
  private static List<Type> updateTypeListNullability(
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
      Type t2 =
          updateTypeWithInferredNullability(toUpdate, orig, typeVarNullability, state, config);
      buf.append(t2);
      if (t2 != toUpdate) {
        changed = true;
      }
    }
    return changed ? buf.toList() : typesToUpdate;
  }

  /**
   * Substitutes inferred nullability for type variables in the given target type.
   *
   * @param targetType type to which to apply substitutions
   * @param typeVarNullability a map from type variable elements to their inferred nullability
   * @param state the visitor state
   * @param config the NullAway config
   * @return the type resulting from applying inferred nullability substitutions
   */
  private static Type substituteInferredNullabilityForTypeVariables(
      Type targetType,
      Map<Element, ConstraintSolver.InferredNullability> typeVarNullability,
      VisitorState state,
      Config config) {
    ListBuffer<Type> typeVars = new ListBuffer<>();
    ListBuffer<Type> inferredTypes = new ListBuffer<>();
    for (Map.Entry<Element, ConstraintSolver.InferredNullability> entry :
        typeVarNullability.entrySet()) {
      // find all TypeVars occurring in targetType with the same symbol and substitute for those.
      // we can have multiple such TypeVars due to previous substitutions that modified the type
      // in some way, e.g., by changing its bounds
      Element symbol = entry.getKey();
      TypeVarWithSymbolCollector tvc = new TypeVarWithSymbolCollector(symbol);
      targetType.accept(tvc, null);
      for (Type.TypeVar tv : tvc.getMatches()) {
        typeVars.append(tv);
        inferredTypes.append(
            typeWithAnnot(
                tv,
                entry.getValue() == NULLABLE
                    ? GenericsChecks.getSyntheticNullableAnnotType(state)
                    : GenericsChecks.getSyntheticNonNullAnnotType(state)));
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
   * Replaces every occurrence of the given type variables in {@code targetType}, preserving
   * explicit nullability annotations on the replaced occurrences. So, if {@code targetType} is
   * {@code List<@Nullable T>} and {@code T} is replaced with {@code S}, the result is {@code
   * List<@Nullable S>}.
   *
   * <p>Occurrences are found by symbol, since a type can contain several {@link Type.TypeVar}
   * objects for the same symbol, e.g., due to annotations on type variable uses.
   *
   * @param targetType type in which to replace type variables
   * @param replacements map from type variable symbols to their replacement types
   * @param types the javac types instance
   * @param config the NullAway config
   * @return the type with replacements applied, or {@code targetType} itself if it contains none of
   *     the type variables
   */
  static Type substituteTypeVariables(
      Type targetType,
      Map<? extends Element, ? extends Type> replacements,
      Types types,
      Config config) {
    ListBuffer<Type> typeVars = new ListBuffer<>();
    ListBuffer<Type> replacementTypes = new ListBuffer<>();
    for (Map.Entry<? extends Element, ? extends Type> entry : replacements.entrySet()) {
      TypeVarWithSymbolCollector tvc = new TypeVarWithSymbolCollector(entry.getKey());
      targetType.accept(tvc, null);
      for (Type.TypeVar tv : tvc.getMatches()) {
        typeVars.append(tv);
        replacementTypes.append(entry.getValue());
      }
    }
    List<Type> typeVarsToReplace = typeVars.toList();
    if (typeVarsToReplace.isEmpty()) {
      return targetType;
    }
    return subst(types, targetType, typeVarsToReplace, replacementTypes.toList(), config);
  }

  /**
   * A visitor that restores explicit nullability annotations on types nested within another type to
   * the corresponding positions in the visited type. If no annotations need to be restored, returns
   * the visited type object itself.
   */
  @SuppressWarnings("ReferenceEquality")
  private static class RestoreNullnessAnnotationsVisitor extends Types.MapVisitor<Type> {

    private final Types types;
    private final Config config;

    /** Formal target/source pairs, scoped to this restoration operation and allocated lazily. */
    private @Nullable IdentityHashMap<Type.TypeVar, IdentityHashMap<Type.TypeVar, BoundPair>>
        implicitWildcardBounds;

    /** The current implicit-bound traversal, excluding ordinary structural visitor calls. */
    private @Nullable BoundFrame activeImplicitWildcardBoundFrame;

    /**
     * Identity-keyed state shared by active traversals and completed results for one formal pair.
     */
    private static final class BoundPair {
      private @Nullable BoundFrame activeFrame;
      private @Nullable BoundResult completedResult;
    }

    /** A bound traversal's cycle summary; sets stay absent on entirely acyclic paths. */
    private static final class BoundFrame {
      private final @Nullable BoundFrame parent;
      private final int depth;
      private final BoundPair pair;
      private boolean cyclic;
      private @Nullable Set<BoundPair> cyclePairs;
      private @Nullable Set<BoundPair> cutoffs;

      private BoundFrame(@Nullable BoundFrame parent, BoundPair pair) {
        this.parent = parent;
        this.depth = parent == null ? 0 : parent.depth + 1;
        this.pair = pair;
      }

      /** Records a cutoff and marks exactly the active path participating in its cycle. */
      private void recordCutoff(BoundFrame cutoff) {
        for (BoundFrame frame = this; ; frame = Verify.verifyNotNull(frame.parent)) {
          frame.cyclic = true;
          if (frame == cutoff) {
            break;
          }
        }
        if (cyclePairs == null) {
          cyclePairs = Collections.newSetFromMap(new IdentityHashMap<>());
        }
        cyclePairs.add(cutoff.pair);
        if (cutoffs == null) {
          cutoffs = Collections.newSetFromMap(new IdentityHashMap<>());
        }
        cutoffs.add(cutoff.pair);
      }

      /** Propagates a child's summary without sharing mutable sets with its cached result. */
      private void merge(BoundResult child) {
        if (child.cyclePairs != null) {
          if (cyclePairs == null) {
            cyclePairs = Collections.newSetFromMap(new IdentityHashMap<>());
          }
          cyclePairs.addAll(child.cyclePairs);
        }
        if (child.externalCutoffs != null) {
          if (cutoffs == null) {
            cutoffs = Collections.newSetFromMap(new IdentityHashMap<>());
          }
          cutoffs.addAll(child.externalCutoffs);
        }
      }
    }

    /** A restored bound and the active-path conditions under which it can be reused. */
    private static final class BoundResult {
      private final Type targetBound;
      private final Type sourceBound;
      private final Type resultType;
      private final @Nullable Set<BoundPair> cyclePairs;
      private final @Nullable Set<BoundPair> externalCutoffs;

      /** Closes internal cycles, retaining only cutoff dependencies on strict ancestors. */
      private BoundResult(Type targetBound, Type sourceBound, Type resultType, BoundFrame frame) {
        this.targetBound = targetBound;
        this.sourceBound = sourceBound;
        this.resultType = resultType;
        if (frame.cyclic) {
          if (frame.cyclePairs == null) {
            frame.cyclePairs = Collections.newSetFromMap(new IdentityHashMap<>());
          }
          frame.cyclePairs.add(frame.pair);
        }
        this.cyclePairs = frame.cyclePairs;
        Set<BoundPair> external = null;
        if (frame.cutoffs != null) {
          for (BoundPair cutoff : frame.cutoffs) {
            BoundFrame active = cutoff.activeFrame;
            if (active != null && active.depth < frame.depth) {
              if (external == null) {
                external = Collections.newSetFromMap(new IdentityHashMap<>());
              }
              external.add(cutoff);
            }
          }
        }
        this.externalCutoffs = external;
      }

      /** Requires the same external cutoffs and forbids new cutoffs within the cached cycles. */
      private boolean canReuse(Type targetBound, Type sourceBound) {
        if (this.targetBound != targetBound || this.sourceBound != sourceBound) {
          return false;
        }
        if (externalCutoffs != null) {
          for (BoundPair cutoff : externalCutoffs) {
            if (cutoff.activeFrame == null) {
              return false;
            }
          }
        }
        if (cyclePairs != null) {
          for (BoundPair cyclePair : cyclePairs) {
            if (cyclePair.activeFrame != null
                && (externalCutoffs == null || !externalCutoffs.contains(cyclePair))) {
              return false;
            }
          }
        }
        return true;
      }
    }

    /** Gets state by formal-pair identity, not by possibly shared upper-bound objects. */
    private BoundPair getImplicitWildcardBoundPair(Type.TypeVar target, Type.TypeVar source) {
      if (implicitWildcardBounds == null) {
        implicitWildcardBounds = new IdentityHashMap<>();
      }
      return implicitWildcardBounds
          .computeIfAbsent(target, unused -> new IdentityHashMap<>())
          .computeIfAbsent(source, unused -> new BoundPair());
    }

    /**
     * Restores a non-active pair's upper bound, reusing results only in compatible cycle contexts.
     * Cached external cutoffs also mark the active path, just as an actual traversal would.
     */
    private Type restoreImplicitWildcardBound(BoundPair pair, Type targetBound, Type sourceBound) {
      BoundFrame parent = activeImplicitWildcardBoundFrame;
      BoundResult completed = pair.completedResult;
      // Check before making this pair active: an internal self-cycle is safe to reuse here.
      if (completed != null && completed.canReuse(targetBound, sourceBound)) {
        if (parent != null) {
          if (completed.externalCutoffs != null) {
            for (BoundPair cutoff : completed.externalCutoffs) {
              parent.recordCutoff(Verify.verifyNotNull(cutoff.activeFrame));
            }
          }
          parent.merge(completed);
        }
        return completed.resultType;
      }
      BoundFrame frame = new BoundFrame(parent, pair);
      pair.activeFrame = frame;
      activeImplicitWildcardBoundFrame = frame;
      try {
        Type restored = visit(targetBound, sourceBound);
        completed = new BoundResult(targetBound, sourceBound, restored, frame);
        pair.completedResult = completed;
      } finally {
        activeImplicitWildcardBoundFrame = parent;
        pair.activeFrame = null;
      }
      if (parent != null) {
        parent.merge(completed);
      }
      return completed.resultType;
    }

    RestoreNullnessAnnotationsVisitor(Types types, Config config) {
      this.types = types;
      this.config = config;
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
      if (other instanceof Type.WildcardType wt) {
        // When the other type is a wildcard, restore nullability annotations from the bound that
        // determines the functional interface type.
        if (wt.kind == BoundKind.EXTENDS) {
          return visit(t, wt.getExtendsBound());
        } else if (wt.kind == BoundKind.SUPER) {
          return visit(t, wt.getSuperBound());
        }
      }
      if (t instanceof Type.IntersectionClassType) {
        Type.IntersectionClassType updated =
            (Type.IntersectionClassType) updateDirectNullabilityAnnotationsForType(t, other);
        Map<Symbol.TypeSymbol, Type> sourceComponents = null;
        if (other instanceof Type.IntersectionClassType source) {
          sourceComponents = new HashMap<>();
          for (Type sourceComponent : source.getComponents()) {
            sourceComponents.putIfAbsent(sourceComponent.tsym, sourceComponent);
          }
        }
        ListBuffer<Type> components = new ListBuffer<>();
        boolean changed = false;
        for (Type component : updated.getComponents()) {
          Type corresponding;
          if (sourceComponents != null) {
            corresponding = sourceComponents.get(component.tsym);
          } else if (component.tsym.equals(other.tsym)) {
            corresponding = other;
          } else {
            corresponding = null;
          }
          Type restored = corresponding == null ? component : visit(component, corresponding);
          components.append(restored);
          changed |= restored != component;
        }
        return changed ? rebuildIntersection(updated, components.toList()) : updated;
      }
      if (other instanceof Type.IntersectionClassType source) {
        Type corresponding = intersectionComponent(source, t.tsym);
        Type restored = corresponding == null ? t : restoreClassTypeArguments(t, corresponding);
        return updateDirectNullabilityAnnotationsForType(restored, other);
      }
      Type updated = updateDirectNullabilityAnnotationsForType(t, other);
      return restoreClassTypeArguments(updated, other);
    }

    /**
     * Restores enclosing-type and type-argument annotations without copying the source's top-level
     * annotation. A matching intersection component may have different top-level nullability from
     * the complete intersection.
     */
    private Type restoreClassTypeArguments(Type updated, Type other) {
      // A raw source type has no type arguments from which to restore nested annotations.
      if (!(other instanceof Type.ClassType) || other.isRaw()) {
        return updated;
      }
      if (!updated.tsym.equals(other.tsym)) {
        // Capture conversion can generalize a wildcard's bound to a supertype. Argument positions
        // only correspond after viewing the annotation source as that same supertype.
        other = asSuper(types, other, (Symbol.ClassSymbol) updated.tsym, config);
        if (other == null || other.isRaw()) {
          return updated;
        }
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
        return restoreWildcardUpperBoundAnnotation(wt, wt.bound, other);
      }
      // Unbounded and super wildcards have an implicit upper bound on the formal type variable.
      if (wt.kind != BoundKind.EXTENDS
          && wildcardType.kind == wt.kind
          && wt.bound != null
          && wildcardType.bound != null) {
        Type.TypeVar formalTypeVariable = wt.bound;
        Type.TypeVar otherFormalTypeVariable = wildcardType.bound;
        BoundPair pair = getImplicitWildcardBoundPair(formalTypeVariable, otherFormalTypeVariable);
        BoundFrame active = pair.activeFrame;
        if (active != null) {
          // Preserve the original wildcard, including its lower bound, at an active-path cutoff.
          Verify.verifyNotNull(activeImplicitWildcardBoundFrame).recordCutoff(active);
          return wt;
        }
        Type upperBound = formalTypeVariable.getUpperBound();
        Type updatedUpperBound =
            restoreImplicitWildcardBound(pair, upperBound, otherFormalTypeVariable.getUpperBound());
        if (updatedUpperBound != upperBound) {
          wt = replaceImplicitWildcardUpperBound(wt, formalTypeVariable, updatedUpperBound);
        }
        // return here for unbounded wildcards.  For lower-bounded wildcards we need to fall through
        // to restore annotations to the lower bound
        if (wt.kind == BoundKind.UNBOUND) {
          return wt;
        }
      }
      Type otherBound = wildcardType.type;
      if (wt.kind == BoundKind.EXTENDS && wildcardType.kind == BoundKind.UNBOUND) {
        // Substitution can introduce an explicit extends wildcard while the corresponding
        // declared wildcard remains unbounded. Its `type` field is just an Object placeholder;
        // annotations must be restored from the implicit upper bound of its formal type variable
        // instead. This also applies to wildcards nested within a captured type's upper bound.
        Type.TypeVar formalTypeVariable = wildcardType.bound;
        if (formalTypeVariable == null) {
          return wt;
        }
        otherBound = formalTypeVariable.getUpperBound();
      }
      Type t = wt.type;
      if (t != null) {
        t = visit(t, otherBound);
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

    /**
     * Restores annotations from another type onto a wildcard's upper bound.
     *
     * @param wildcard the wildcard type whose upper bound should be updated
     * @param implicitUpperBoundTypeVariable for unbounded or lower bounded wildcard types, the type
     *     variable from which to obtain an upper bound, or null if not available
     * @param other the other type from which to restore annotations
     */
    private Type.WildcardType restoreWildcardUpperBoundAnnotation(
        Type.WildcardType wildcard,
        Type.@Nullable TypeVar implicitUpperBoundTypeVariable,
        Type other) {
      Type upperBound =
          wildcard.kind == BoundKind.EXTENDS
              ? wildcard.type
              : implicitUpperBoundTypeVariable == null
                  ? null
                  : implicitUpperBoundTypeVariable.getUpperBound();
      if (upperBound == null) {
        return wildcard;
      }
      Type updatedBound = updateDirectNullabilityAnnotationsForType(upperBound, other);
      if (updatedBound == upperBound) {
        return wildcard;
      }
      if (wildcard.kind == BoundKind.EXTENDS) {
        return TYPE_METADATA_BUILDER.createWildcardType(wildcard, updatedBound);
      } else { // unbounded or lower-bounded wildcard
        return replaceImplicitWildcardUpperBound(
            wildcard, Verify.verifyNotNull(implicitUpperBoundTypeVariable), updatedBound);
      }
    }

    /**
     * Restores nullability information on the captured type {@code t} and its backing wildcard.
     * Explicit annotations on a type-variable use remain direct annotations on the capture.
     * Synthetic annotations representing inferred nullability instead annotate the capture's
     * structural upper bound.
     *
     * <p>The corresponding type {@code other} may be an ordinary wildcard because javac can
     * capture-convert {@code t} without capture-converting {@code other}. In such cases, the
     * annotation on the bound of {@code other} should be restored to the bound of the wildcard
     * corresponding to {@code t}. Alternatively, nested capture conversion can make {@code t} a
     * captured {@code extends} wildcard while the same position in {@code other} is a non-wildcard
     * type. In that case, annotations from {@code other} must be restored to the backing wildcard's
     * upper bound.
     *
     * <p>For an unbounded or super wildcard, the relevant upper bound is its implicit upper bound
     * from the corresponding formal type variable. Wildcard-aware checks use these bounds rather
     * than annotations directly on the captured type.
     */
    @Override
    public Type visitCapturedType(Type.CapturedType t, Type other) {
      Attribute.TypeCompound syntheticNullnessAnnotation =
          config.handleWildcardGenerics() ? getDirectSyntheticNullnessAnnotation(other) : null;
      Type updated;
      if (syntheticNullnessAnnotation != null) {
        // A synthetic annotation records NullAway's inferred nullability for the type variable that
        // javac instantiated as this capture. It constrains the capture itself, so represent it on
        // the capture's structural upper bound rather than as a use-site projection of the capture.
        Type updatedUpperBound = typeWithAnnot(t.getUpperBound(), syntheticNullnessAnnotation);
        updated =
            TYPE_METADATA_BUILDER.createDetachedCapturedType(t, t.wildcard, updatedUpperBound);
      } else {
        // An explicit annotation on a type-variable use remains a use-site annotation after the
        // type variable is instantiated as a capture.
        updated = updateDirectNullabilityAnnotationsForType(t, other);
      }
      Type.WildcardType otherWildcard = GenericsUtils.asWildcard(other);
      Type.WildcardType updatedWildcard;
      if (otherWildcard != null) {
        updatedWildcard = (Type.WildcardType) t.wildcard.accept(this, otherWildcard);
      } else if (t.wildcard.kind == BoundKind.EXTENDS) {
        Type updatedBound = t.wildcard.type.accept(this, other);
        if (updatedBound == t.wildcard.type) {
          return updated;
        }
        updatedWildcard = TYPE_METADATA_BUILDER.createWildcardType(t.wildcard, updatedBound);
      } else {
        Verify.verify(t.wildcard.kind == BoundKind.UNBOUND || t.wildcard.kind == BoundKind.SUPER);
        // t.wildcard is either unbounded or lower bounded (with super).  We want to find the
        // corresponding type variable X for t (the type variable for which t.wildcard was passed
        // as a type argument), in order to obtain the upper bound of X later on.
        // Normally, X is stored in t.wildcard.bound.  If it is unavailable, we fall back on using
        // the captured type t itself, as its own upper bound (t.getUpperBound()) could provide
        // useful information.
        Type.TypeVar implicitUpperBoundTypeVariable =
            t.wildcard.bound != null ? t.wildcard.bound : t;
        Type upperBound = implicitUpperBoundTypeVariable.getUpperBound();
        Type updatedUpperBound = upperBound.accept(this, other);
        if (updatedUpperBound == upperBound) {
          return updated;
        }
        updatedWildcard =
            replaceImplicitWildcardUpperBound(
                t.wildcard, implicitUpperBoundTypeVariable, updatedUpperBound);
      }
      if (updatedWildcard == t.wildcard) {
        return updated;
      }
      return replaceCapturedTypeWildcard((Type.CapturedType) updated, updatedWildcard);
    }

    /** Returns a synthetic top-level nullness annotation for {@code type}, if one is present. */
    private Attribute.@Nullable TypeCompound getDirectSyntheticNullnessAnnotation(Type type) {
      if (type instanceof Type.IntersectionClassType source) {
        for (Type component : GenericsUtils.nullabilityMatchingComponents(source, config)) {
          Attribute.TypeCompound annotation = getDirectSyntheticNullnessAnnotation(component);
          if (annotation != null) {
            return annotation;
          }
        }
        return null;
      }
      for (Attribute.TypeCompound annotation : type.getAnnotationMirrors()) {
        if (annotation.type.tsym == null) {
          continue;
        }
        if (GenericsChecks.isSyntheticNullnessAnnotation(annotation.type)) {
          return annotation;
        }
      }
      return null;
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
     * on a type {@code other}. When {@code other} is an intersection, copies the nullness
     * annotation of the first explicit component that carries one and whose nullability matches the
     * intersection's. If no such component exists, returns {@code t} unchanged.
     *
     * @param t the type to update
     * @param other the type to update from
     * @return the updated type, or {@code t} if no updates were made
     */
    private Type updateDirectNullabilityAnnotationsForType(Type t, Type other) {
      if (other instanceof Type.IntersectionClassType source) {
        for (Type component : GenericsUtils.nullabilityMatchingComponents(source, config)) {
          Type updated = updateDirectNullabilityAnnotationsForType(t, component);
          if (updated != t) {
            return updated;
          }
        }
        return t;
      }
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
      return t;
    }

    /**
     * Replaces the type's top-level annotations with an annotation of type {@code annot.type} via
     * {@link TypeSubstitutionUtils#typeWithAnnot(Type, Type)}.
     */
    private static Type typeWithAnnot(Type t, Attribute.TypeCompound annot) {
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

  /**
   * Returns {@code t} with its top-level annotations replaced by one annotation of type {@code
   * annotType}. For an intersection, returns a new intersection in which every component, including
   * an implicit {@code Object}, carries that annotation.
   */
  public static Type typeWithAnnot(Type t, Type annotType) {
    if (t instanceof Type.IntersectionClassType intersection) {
      ListBuffer<Type> components = new ListBuffer<>();
      for (Type component : intersection.getComponents()) {
        components.append(typeWithAnnot(component, annotType));
      }
      return rebuildIntersection(intersection, components.toList());
    }
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
    Verify.verify(GenericsUtils.isAnnotatedAsNullable(argumentType, config));
    return withoutTopLevelNullableAnnotations(argumentType, config);
  }

  /**
   * Returns {@code type} without its top-level nullable annotations. For an intersection, removes
   * them from every component.
   */
  private static Type withoutTopLevelNullableAnnotations(Type type, Config config) {
    if (type instanceof Type.IntersectionClassType intersection) {
      ListBuffer<Type> components = new ListBuffer<>();
      for (Type component : intersection.getComponents()) {
        components.append(withoutTopLevelNullableAnnotations(component, config));
      }
      return rebuildIntersection(intersection, components.toList());
    }
    ListBuffer<Attribute.TypeCompound> updatedAnnotations = new ListBuffer<>();
    boolean removedNullable = false;
    for (Attribute.TypeCompound annot : type.getAnnotationMirrors()) {
      String annotationName = annot.type.toString();
      if (Nullness.isNullableAnnotation(annotationName, config)) {
        removedNullable = true;
        continue;
      }
      updatedAnnotations.append(annot);
    }
    return removedNullable
        ? TYPE_METADATA_BUILDER.cloneTypeWithMetadata(
            type, TYPE_METADATA_BUILDER.create(updatedAnnotations.toList()))
        : type;
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
    return restoreExplicitNullabilityAnnotations(t, substResult, types, config);
  }
}
