package com.uber.nullaway.generics;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;
import static com.uber.nullaway.generics.TypeMetadataBuilder.TYPE_METADATA_BUILDER;

import com.google.common.annotations.VisibleForTesting;
import com.google.errorprone.VisitorState;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.util.ListBuffer;
import com.uber.nullaway.Config;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.generics.ConstraintSolver.InferenceVariable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.type.NoType;
import javax.lang.model.type.NullType;
import org.jspecify.annotations.Nullable;

/**
 * Infers nested nullability from annotated lower and upper bounds.
 *
 * <p>Uses javac's inferred type arguments for Java type structure, but takes nullability
 * annotations from bounds. For example, if javac infers {@code T = List<String>}, a lower bound
 * {@code List<@Nullable String> <: T} supplies the nullable type argument. Supports non-raw class
 * types and arrays, including enclosing types.
 *
 * <p>Uses recorded bounds and variable-to-variable constraints only. For example, given {@code
 * List<@Nullable String> <: T <: List<U>}, it does not infer a bound on {@code U} from these two
 * bounds on {@code T}. Unsupported types (wildcards, captures, and fixed type variables), recursive
 * nested dependencies, or conflicting nested annotations leave the variable without a full
 * substitution; the caller retains its top-level nullability.
 */
final class FullTypeBounds {
  private final Config config;
  private final VisitorState state;
  private final Map<Element, InferenceVariable> inferenceVariables;
  private final Map<InferenceVariable, Bounds> bounds = new LinkedHashMap<>();

  /** Direct bounds and javac's instantiation for one call-specific variable. */
  private static final class Bounds {
    private final Type declaredUpper;
    private final List<Type> lower = new ArrayList<>();
    private final List<Type> upper = new ArrayList<>();
    private @Nullable Type javacInstantiation;

    Bounds(Type declaredUpper) {
      this.declaredUpper = declaredUpper;
    }
  }

  /**
   * Uses the solver's shared fresh-symbol map; registration must populate it before adding bounds.
   */
  FullTypeBounds(
      Config config, VisitorState state, Map<Element, InferenceVariable> inferenceVariables) {
    this.config = config;
    this.state = state;
    this.inferenceVariables = inferenceVariables;
  }

  /**
   * Records declared bounds and javac instantiations for a site's fresh variables. Re-registration
   * preserves bounds and updates only supplied instantiations. The caller populates the shared
   * variable map; instantiations supply Java types, not nullability bounds.
   */
  void register(
      Tree site,
      Map<Element, Type.TypeVar> freshVariables,
      Map<Element, Type> javacInstantiations) {
    freshVariables.forEach(
        (declaration, fresh) -> {
          InferenceVariable variable = new InferenceVariable(declaration, site);
          Bounds variableBounds =
              bounds.computeIfAbsent(variable, unused -> new Bounds(fresh.getUpperBound()));
          Type javacInstantiation = javacInstantiations.get(declaration);
          if (javacInstantiation != null) {
            variableBounds.javacInstantiation = javacInstantiation;
          }
        });
  }

  /**
   * Records {@code subtype} as a lower bound when {@code supertype} is an inference variable, and
   * {@code supertype} as an upper bound when {@code subtype} is an inference variable. Annotated
   * variable uses still contribute nested bounds; top-level nullability propagation in {@link
   * ConstraintSolverImpl} handles their explicit qualifiers.
   */
  void addConstraint(Type subtype, Type supertype) {
    InferenceVariable sub = variable(subtype);
    InferenceVariable sup = variable(supertype);
    if (sub != null) {
      castToNonNull(bounds.get(sub)).upper.add(supertype);
    }
    if (sup != null) {
      castToNonNull(bounds.get(sup)).lower.add(subtype);
    }
  }

  /**
   * Resolves full substitutions, caching successful results for this solve. Failures are not cached
   * because recursion cutoffs depend on the active path. Omits unresolved variables; the caller
   * retains their separately inferred top-level nullability.
   */
  Map<InferenceVariable, Type> solve(Map<InferenceVariable, Nullness> nullability) {
    Map<InferenceVariable, Type> result = new LinkedHashMap<>();
    for (InferenceVariable variable : bounds.keySet()) {
      resolve(variable, nullability, new LinkedHashSet<>(), result);
    }
    return Collections.unmodifiableMap(result);
  }

  /**
   * Builds a candidate from lower bounds, or from upper bounds when no lower bounds exist.
   * Covariant array components can combine nullability; invariant positions must agree. Checks
   * nested annotations against upper and declared bounds, returning null to retain only top-level
   * nullability if a type is unsupported or incompatible. For example, lower bounds {@code
   * String[]} and {@code @Nullable String[]} join to {@code @Nullable String[]}; the same upper
   * bounds meet at {@code String[]}. Lower bounds {@code List<String>} and {@code List<@Nullable
   * String>} disagree in an invariant position and leave only top-level nullability available.
   */
  private @Nullable Type resolve(
      InferenceVariable variable,
      Map<InferenceVariable, Nullness> nullability,
      Set<InferenceVariable> resolving,
      Map<InferenceVariable, Type> completed) {
    Type previous = completed.get(variable);
    if (previous != null) {
      return previous;
    }
    Bounds variableBounds = castToNonNull(bounds.get(variable));
    Type javacInstantiation = variableBounds.javacInstantiation;
    if (javacInstantiation == null || !resolving.add(variable)) {
      return null;
    }
    try {
      List<Type> collectedLowerBounds = new ArrayList<>();
      List<Type> collectedUpperBounds = new ArrayList<>();
      List<Type> collectedDeclared = new ArrayList<>();
      collect(variable, true, collectedLowerBounds, new ArrayList<>(), new LinkedHashSet<>());
      collect(variable, false, collectedUpperBounds, collectedDeclared, new LinkedHashSet<>());
      for (Type declarationBound : collectedDeclared) {
        // For <T, U extends T>, top-level nullability inference does not propagate U <: T.
        // Retain only top-level nullability rather than assume this dependent bound was checked.
        if (variable(declarationBound) != null) {
          return null;
        }
      }
      // prefer to resolve lower bounds for the candidates since they capture actual data flow
      // coming in, potentially yielding a more precise type.  This matters for array types, e.g.,
      // if you have String[] <: T <: @Nullable String[]
      boolean fromLower = !collectedLowerBounds.isEmpty();
      List<Type> resolvedBounds =
          resolveBounds(
              fromLower ? collectedLowerBounds : collectedUpperBounds,
              nullability,
              resolving,
              completed);
      if (resolvedBounds == null) {
        return null;
      }
      Type candidate = null;
      for (Type resolved : resolvedBounds) {
        Type projected = project(javacInstantiation, resolved, fromLower);
        if (projected == null) {
          return null;
        }
        candidate = candidate == null ? projected : merge(candidate, projected, fromLower, true);
        if (candidate == null) {
          return null;
        }
      }
      if (candidate == null) {
        // No lower or upper bound is available to infer nested nullability.
        return null;
      }
      // Projection and merging already make each lower bound compatible with the candidate:
      // covariant nullability is joined, and invariant annotations must agree.
      List<Type> resolvedUpper =
          fromLower
              ? resolveBounds(collectedUpperBounds, nullability, resolving, completed)
              : resolvedBounds;
      if (resolvedUpper == null) {
        return null;
      }
      // Check compatibility of candidate with resolved upper bounds.  We can only have an
      // incompatibility if resolvedBounds were computed from lower bounds
      if (fromLower) {
        for (Type resolved : resolvedUpper) {
          if (!compatible(candidate, resolved, true, false)) {
            return null;
          }
        }
      }
      for (Type bound : collectedDeclared) {
        Type resolved = resolveType(bound, nullability, resolving, completed);
        if (resolved == null || !compatible(candidate, resolved, true, false)) {
          return null;
        }
      }
      // The top-level nullability solver handles modeled declaration-bound overrides.
      Type inferred = annotate(candidate, nullability.get(variable) == Nullness.NULLABLE);
      completed.put(variable, inferred);
      return inferred;
    } finally {
      resolving.remove(variable);
    }
  }

  /** Materializes each bound once so candidate construction and validation share the same types. */
  private @Nullable List<Type> resolveBounds(
      List<Type> inputs,
      Map<InferenceVariable, Nullness> nullability,
      Set<InferenceVariable> resolving,
      Map<InferenceVariable, Type> completed) {
    List<Type> result = new ArrayList<>();
    for (Type input : inputs) {
      Type resolved = resolveType(input, nullability, resolving, completed);
      if (resolved == null) {
        return null;
      }
      result.add(resolved);
    }
    return result;
  }

  /**
   * Collects bounds reachable through variable-to-variable edges in one direction. The visited set
   * stops edge cycles; dependencies nested inside a bound are resolved separately by {@link
   * #resolveType}.
   */
  private void collect(
      InferenceVariable variable,
      boolean lower,
      List<Type> result,
      List<Type> declared,
      Set<InferenceVariable> visited) {
    if (!visited.add(variable)) {
      return;
    }
    Bounds variableBounds = castToNonNull(bounds.get(variable));
    if (!lower) {
      declared.add(variableBounds.declaredUpper);
    }
    List<Type> relevantBounds = lower ? variableBounds.lower : variableBounds.upper;
    for (Type bound : relevantBounds) {
      InferenceVariable inferenceVariableForBound = variable(bound);
      if (inferenceVariableForBound != null) {
        collect(inferenceVariableForBound, lower, result, declared, visited);
      } else if (!(bound instanceof NullType)) {
        result.add(bound);
      }
    }
  }

  /**
   * Replaces nested inference variables with their resolved substitutions, reusing types whose
   * components are unchanged. Returns null if a variable cannot be resolved or any part of the type
   * is unsupported: a wildcard, capture, fixed type variable, raw type, or intersection.
   */
  @SuppressWarnings({
    "ReferenceEquality",
    "TypeEquals"
  }) // Equal Java types can have different annotations.
  @VisibleForTesting
  @Nullable Type resolveType(
      Type type,
      Map<InferenceVariable, Nullness> nullability,
      Set<InferenceVariable> resolving,
      Map<InferenceVariable, Type> completed) {
    InferenceVariable variable = variable(type);
    if (variable != null) {
      Type inferred = resolve(variable, nullability, resolving, completed);
      return inferred == null
          ? null
          : TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
              type, inferred, state.getTypes(), config);
    }
    if (type instanceof Type.ArrayType array) {
      Type element = resolveType(array.elemtype, nullability, resolving, completed);
      if (element == null) {
        return null;
      }
      return element == array.elemtype
          ? type
          : TYPE_METADATA_BUILDER.createArrayType(array, element);
    }
    if (type instanceof Type.ClassType clazz && !clazz.isRaw() && !clazz.isIntersection()) {
      Type originalEnclosing = clazz.getEnclosingType();
      Type enclosing = resolveType(originalEnclosing, nullability, resolving, completed);
      if (enclosing == null) {
        return null;
      }
      boolean changed = enclosing != originalEnclosing;
      ListBuffer<Type> arguments = new ListBuffer<>();
      for (Type argument : clazz.getTypeArguments()) {
        Type resolved = resolveType(argument, nullability, resolving, completed);
        if (resolved == null) {
          return null;
        }
        changed |= resolved != argument;
        arguments.append(resolved);
      }
      return changed
          ? TYPE_METADATA_BUILDER.createClassType(clazz, enclosing, arguments.toList())
          : type;
    }
    return type.isPrimitive() || type.hasTag(TypeTag.NONE) ? type : null;
  }

  /**
   * Accepts non-raw class types and arrays with supported arguments and enclosing types, plus
   * primitives and the enclosing-type sentinel {@code NONE}. Rejects wildcards, captures, fixed
   * type variables, intersections, and erroneous types.
   */
  private boolean isSupportedForFullSubstitution(Type type) {
    if (type.isErroneous()) {
      return false;
    }
    if (type instanceof Type.ArrayType array) {
      return isSupportedForFullSubstitution(array.elemtype);
    }
    if (type instanceof Type.ClassType clazz && !clazz.isRaw() && !clazz.isIntersection()) {
      if (!isSupportedForFullSubstitution(clazz.getEnclosingType())) {
        return false;
      }
      for (Type argument : clazz.getTypeArguments()) {
        if (!isSupportedForFullSubstitution(argument)) {
          return false;
        }
      }
      return true;
    }
    return type.isPrimitive() || type.hasTag(TypeTag.NONE);
  }

  /**
   * Projects bound annotations onto javac's instantiation, or returns null if projection is
   * unsupported. In covariant positions, {@code asSuper} can widen a lower bound to match javac's
   * inferred type. An upper bound must already have the same Java type structure. Class arguments
   * and enclosing types are invariant and must also match. For example, {@code ArrayList<@Nullable
   * String>} can supply annotations for {@code List<String>}, but {@code List<ArrayList<String>>}
   * cannot supply them for {@code List<List<String>>}. Replaces nullability annotations on javac's
   * inferred type with those from the bound.
   */
  private @Nullable Type project(Type javacInstantiation, Type bound, boolean covariant) {
    if (javacInstantiation instanceof NoType) {
      return javacInstantiation;
    }
    if (javacInstantiation instanceof Type.ArrayType array
        && bound instanceof Type.ArrayType actual) {
      Type element = project(array.elemtype, actual.elemtype, covariant);
      return element == null
          ? null
          : annotate(TYPE_METADATA_BUILDER.createArrayType(array, element), nullable(bound));
    }
    if (javacInstantiation instanceof Type.ClassType clazz && bound instanceof Type.ClassType) {
      Type aligned = bound;
      if (!clazz.tsym.equals(bound.tsym)) {
        if (!covariant) {
          return null;
        }
        aligned =
            TypeSubstitutionUtils.asSuper(
                state.getTypes(), bound, (Symbol.ClassSymbol) clazz.tsym, config);
      }
      if (!(aligned instanceof Type.ClassType actual)
          || actual.isRaw()
          || !isSupportedForFullSubstitution(actual)
          || clazz.getTypeArguments().size() != actual.getTypeArguments().size()) {
        return null;
      }
      Type enclosing = project(clazz.getEnclosingType(), actual.getEnclosingType(), false);
      if (enclosing == null) {
        return null;
      }
      ListBuffer<Type> arguments = new ListBuffer<>();
      for (int i = 0; i < clazz.getTypeArguments().size(); i++) {
        Type argument =
            project(clazz.getTypeArguments().get(i), actual.getTypeArguments().get(i), false);
        if (argument == null) {
          return null;
        }
        arguments.append(argument);
      }
      return annotate(
          TYPE_METADATA_BUILDER.createClassType(clazz, enclosing, arguments.toList()),
          nullable(bound));
    }
    return state.getTypes().isSameType(javacInstantiation, bound)
            && isSupportedForFullSubstitution(bound)
        ? javacInstantiation
        : null;
  }

  /**
   * Joins lower or meets upper nullability only at covariant positions; invariant positions must
   * agree. Inputs have already been projected onto the same javac instantiation; returns null if
   * invariant annotations conflict.
   */
  private @Nullable Type merge(Type first, Type second, boolean lower, boolean covariant) {
    if (!covariant && nullable(first) != nullable(second)) {
      return null;
    }
    boolean nullable =
        lower ? nullable(first) || nullable(second) : nullable(first) && nullable(second);
    if (first instanceof Type.ArrayType array && second instanceof Type.ArrayType other) {
      Type element = merge(array.elemtype, other.elemtype, lower, covariant);
      return element == null
          ? null
          : annotate(TYPE_METADATA_BUILDER.createArrayType(array, element), nullable);
    }
    if (first instanceof Type.ClassType clazz && second instanceof Type.ClassType other) {
      Type enclosing = merge(clazz.getEnclosingType(), other.getEnclosingType(), lower, false);
      if (enclosing == null) {
        return null;
      }
      ListBuffer<Type> arguments = new ListBuffer<>();
      for (int i = 0; i < clazz.getTypeArguments().size(); i++) {
        Type argument =
            merge(clazz.getTypeArguments().get(i), other.getTypeArguments().get(i), lower, false);
        if (argument == null) {
          return null;
        }
        arguments.append(argument);
      }
      return annotate(
          TYPE_METADATA_BUILDER.createClassType(clazz, enclosing, arguments.toList()), nullable);
    }
    return first;
  }

  /**
   * Checks existing bound compatibility without adding constraints or changing Java structure. The
   * caller excludes unsupported types before comparing; top-level nullability is checked only on
   * recursive calls, since it is inferred separately for the variable itself.
   *
   * <p>This is stricter than {@link GenericsChecks#subtypeParameterNullability(Type, Type,
   * VisitorState)} (whose arguments are supertype first). That check assumes javac has already
   * validated Java subtyping: it accepts missing supertype alignments and raw types, and can widen
   * class types inside invariant arguments. For example, it accepts the nested nullability of
   * {@code List<ArrayList<String>>} against {@code List<List<String>>}; this comparison rejects the
   * mismatch between those class types. This method also checks nullability on enclosing types
   * themselves, while the shared visitor compares only their type arguments. Those differences
   * matter for bounds assembled during inference rather than a javac-validated assignment.
   */
  private boolean compatible(Type subtype, Type supertype, boolean covariant, boolean compareRoot) {
    if (compareRoot
        && (covariant
            ? nullable(subtype) && !nullable(supertype)
            : nullable(subtype) != nullable(supertype))) {
      return false;
    }
    if (subtype instanceof Type.ArrayType sub && supertype instanceof Type.ArrayType sup) {
      return compatible(sub.elemtype, sup.elemtype, covariant, true);
    }
    if (subtype instanceof Type.ClassType && supertype instanceof Type.ClassType sup) {
      Type aligned = subtype;
      if (!subtype.tsym.equals(sup.tsym)) {
        if (!covariant) {
          return false;
        }
        aligned =
            TypeSubstitutionUtils.asSuper(
                state.getTypes(), subtype, (Symbol.ClassSymbol) sup.tsym, config);
      }
      if (!(aligned instanceof Type.ClassType sub)
          || !isSupportedForFullSubstitution(sub)
          || sub.getTypeArguments().size() != sup.getTypeArguments().size()
          || !compatible(sub.getEnclosingType(), sup.getEnclosingType(), false, true)) {
        return false;
      }
      for (int i = 0; i < sub.getTypeArguments().size(); i++) {
        if (!compatible(
            sub.getTypeArguments().get(i), sup.getTypeArguments().get(i), false, true)) {
          return false;
        }
      }
      return true;
    }
    return covariant
        ? state.getTypes().isSubtype(subtype, supertype)
        : state.getTypes().isSameType(subtype, supertype);
  }

  /** Returns the inference variable associated with a registered fresh type-variable symbol. */
  private @Nullable InferenceVariable variable(Type type) {
    return type instanceof Type.TypeVar variable && !(type instanceof Type.CapturedType)
        ? inferenceVariables.get(variable.asElement())
        : null;
  }

  /** Returns the bound's top-level nullability in the supported full-substitution domain. */
  private boolean nullable(Type type) {
    return GenericsUtils.isAnnotatedAsNullable(type, config);
  }

  /** Replaces top-level nullability without mutating javac types or dropping unrelated metadata. */
  private Type annotate(Type type, boolean nullable) {
    if (type.isPrimitive() || type.hasTag(TypeTag.NONE)) {
      return type;
    }
    ListBuffer<Attribute.TypeCompound> annotations = new ListBuffer<>();
    for (Attribute.TypeCompound annotation : type.getAnnotationMirrors()) {
      String name = annotation.type.tsym.getQualifiedName().toString();
      if (!Nullness.isNullableAnnotation(name, config)
          && !Nullness.isNonNullAnnotation(name, config)) {
        annotations.append(annotation);
      }
    }
    Type annotationType =
        nullable
            ? GenericsChecks.getSyntheticNullableAnnotType(state)
            : GenericsChecks.getSyntheticNonNullAnnotType(state);
    annotations.append(
        new Attribute.TypeCompound(annotationType, com.sun.tools.javac.util.List.nil(), null));
    return TYPE_METADATA_BUILDER.cloneTypeWithMetadata(
        type, TYPE_METADATA_BUILDER.create(annotations.toList()));
  }
}
