package com.uber.nullaway.generics;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;
import static com.uber.nullaway.generics.TypeMetadataBuilder.TYPE_METADATA_BUILDER;

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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.type.NullType;
import org.jspecify.annotations.Nullable;

/**
 * Retains annotated lower and upper bounds to infer nested nullness alongside root nullness.
 *
 * <p>Resolves acyclic dependencies between inference variables at javac's inferred Java types:
 * javac determines the type structure, and bounds supply its nullness annotations. Supports non-raw
 * class types and arrays, including enclosing types.
 *
 * <p>Resolution follows recorded variable edges without deriving new constraints between bounds.
 * Unsupported types (including wildcards, captures, and fixed type variables), recursive nested
 * dependencies, or conflicting nested annotations leave the variable on root-only inference.
 */
final class FullTypeBounds {
  private final Config config;
  private final VisitorState state;
  private final IdentityHashMap<Element, InferenceVariable> variables = new IdentityHashMap<>();
  private final Map<InferenceVariable, Bounds> bounds = new LinkedHashMap<>();

  /** Direct bounds and the nominal shape for one call-specific variable. */
  private static final class Bounds {
    private final Type declaredUpper;
    private final List<Type> lower = new ArrayList<>();
    private final List<Type> upper = new ArrayList<>();
    private @Nullable Type shape;

    Bounds(Type declaredUpper) {
      this.declaredUpper = declaredUpper;
    }
  }

  FullTypeBounds(Config config, VisitorState state) {
    this.config = config;
    this.state = state;
  }

  /** Associates fresh symbols with their call sites and declared upper bounds. */
  void register(Tree site, Map<Element, Type.TypeVar> freshVariables) {
    freshVariables.forEach(
        (declaration, fresh) -> {
          InferenceVariable variable = new InferenceVariable(declaration, site);
          variables.put(fresh.asElement(), variable);
          bounds.put(variable, new Bounds(fresh.getUpperBound()));
        });
  }

  /**
   * Records javac's inferred type structure for registered variables, without using its annotations
   * as nullness evidence.
   */
  void setShapes(Tree site, Map<Element, Type> shapes) {
    shapes.forEach(
        (declaration, shape) -> {
          Bounds variableBounds = bounds.get(new InferenceVariable(declaration, site));
          if (variableBounds != null) {
            variableBounds.shape = shape;
          }
        });
  }

  /**
   * Retains full types even on annotated variable uses. Such uses fix the root nullness, but not
   * the variable's nested structure. The scalar solver remains responsible for those root
   * overrides.
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
   * Resolves full substitutions, reusing successful results within this solve to avoid repeatedly
   * resolving shared nested variables. Failed resolutions are not cached, since a recursion cutoff
   * depends on the active resolution path.
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
   * Covariant array components can combine nullness; invariant positions must agree. Checks nested
   * annotations against all collected bounds, returning null for root-only fallback if a type is
   * unsupported or incompatible.
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
    Type shape = variableBounds.shape;
    if (shape == null || !isProper(shape) || !resolving.add(variable)) {
      return null;
    }
    try {
      List<Type> lower = new ArrayList<>();
      List<Type> upper = new ArrayList<>();
      List<Type> declared = new ArrayList<>();
      collect(variable, true, lower, new ArrayList<>(), new LinkedHashSet<>());
      collect(variable, false, upper, declared, new LinkedHashSet<>());
      for (Type declarationBound : declared) {
        // For <T, U extends T>, root inference does not propagate the declaration edge U <: T.
        // Keep root-only fallback rather than assume that this dependent bound was checked.
        if (variable(declarationBound) != null) {
          return null;
        }
      }
      boolean fromLower = !lower.isEmpty();
      List<Type> candidates =
          resolveBounds(fromLower ? lower : upper, nullability, resolving, completed);
      if (candidates == null) {
        return null;
      }
      Type candidate = null;
      for (Type resolved : candidates) {
        // An upper bound can supply annotations only when its full nominal shape matches.
        Type projected = project(shape, resolved, fromLower);
        if (projected == null) {
          return null;
        }
        candidate = candidate == null ? projected : merge(candidate, projected, fromLower, true);
        if (candidate == null) {
          return null;
        }
      }
      if (candidate == null) {
        // Neither javac's provisional annotations nor a declaration bound is expression evidence.
        return null;
      }
      if (fromLower) {
        for (Type resolved : candidates) {
          if (!compatible(resolved, candidate, true, false)) {
            return null;
          }
        }
      }
      List<Type> resolvedUpper =
          fromLower ? resolveBounds(upper, nullability, resolving, completed) : candidates;
      if (resolvedUpper == null) {
        return null;
      }
      for (Type resolved : resolvedUpper) {
        if (!compatible(candidate, resolved, true, false)) {
          return null;
        }
      }
      for (Type bound : declared) {
        Type resolved = resolveType(bound, nullability, resolving, completed);
        if (resolved == null || !compatible(candidate, resolved, true, false)) {
          return null;
        }
      }
      // Root nullness, including modeled declaration-bound overrides, belongs to the scalar solver.
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
    for (Type bound : lower ? variableBounds.lower : variableBounds.upper) {
      InferenceVariable peer = variable(bound);
      if (peer != null) {
        collect(peer, lower, result, declared, visited);
      } else if (!(bound instanceof NullType)) {
        result.add(bound);
      }
    }
  }

  /**
   * Substitutes only supported nested inference variables, without unfolding fixed-variable bounds.
   */
  private @Nullable Type resolveType(
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
      return element == null ? null : TYPE_METADATA_BUILDER.createArrayType(array, element);
    }
    if (type instanceof Type.ClassType clazz && !clazz.isRaw() && !clazz.isIntersection()) {
      Type enclosing = resolveType(clazz.getEnclosingType(), nullability, resolving, completed);
      if (enclosing == null) {
        return null;
      }
      ListBuffer<Type> arguments = new ListBuffer<>();
      for (Type argument : clazz.getTypeArguments()) {
        Type resolved = resolveType(argument, nullability, resolving, completed);
        if (resolved == null) {
          return null;
        }
        arguments.append(resolved);
      }
      return TYPE_METADATA_BUILDER.createClassType(clazz, enclosing, arguments.toList());
    }
    return type.isPrimitive() || type.hasTag(TypeTag.NONE) ? type : null;
  }

  /** Checks for supported type structure with no remaining type variables or wildcards. */
  private boolean isProper(Type type) {
    if (type.isErroneous()) {
      return false;
    }
    if (type instanceof Type.ArrayType array) {
      return isProper(array.elemtype);
    }
    if (type instanceof Type.ClassType clazz && !clazz.isRaw() && !clazz.isIntersection()) {
      if (!isProper(clazz.getEnclosingType())) {
        return false;
      }
      for (Type argument : clazz.getTypeArguments()) {
        if (!isProper(argument)) {
          return false;
        }
      }
      return true;
    }
    return type.isPrimitive() || type.hasTag(TypeTag.NONE);
  }

  /**
   * Projects bound annotations onto javac's shape. Nominal widening is allowed only in covariant
   * positions; class arguments and enclosing types are invariant. Every projected reference node
   * gets explicit nullness so provisional javac annotations cannot leak into the substitution.
   */
  private @Nullable Type project(Type shape, Type evidence, boolean covariant) {
    if (shape instanceof Type.ArrayType array && evidence instanceof Type.ArrayType actual) {
      Type element = project(array.elemtype, actual.elemtype, covariant);
      return element == null
          ? null
          : annotate(TYPE_METADATA_BUILDER.createArrayType(array, element), nullable(evidence));
    }
    if (shape instanceof Type.ClassType clazz && evidence instanceof Type.ClassType) {
      Type aligned = evidence;
      if (!clazz.tsym.equals(evidence.tsym)) {
        if (!covariant) {
          return null;
        }
        aligned =
            TypeSubstitutionUtils.asSuper(
                state.getTypes(), evidence, (Symbol.ClassSymbol) clazz.tsym, config);
      }
      if (!(aligned instanceof Type.ClassType actual)
          || actual.isRaw()
          || !isProper(actual)
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
          nullable(evidence));
    }
    return state.getTypes().isSameType(shape, evidence) && isProper(evidence) ? shape : null;
  }

  /**
   * Joins lower or meets upper nullness only at covariant positions; invariant positions must
   * agree.
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

  /** Checks existing bound compatibility without adding constraints or changing Java structure. */
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
          || !isProper(sub)
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

  /** Identifies a registered variable even when its use has an explicit nullness annotation. */
  private @Nullable InferenceVariable variable(Type type) {
    return type instanceof Type.TypeVar variable && !(type instanceof Type.CapturedType)
        ? variables.get(variable.asElement())
        : null;
  }

  /** Returns the bound's effective nullness in the supported proper-type domain. */
  private boolean nullable(Type type) {
    return GenericsUtils.isAnnotatedAsNullable(type, config);
  }

  /** Replaces direct nullness without mutating javac-owned types or dropping unrelated metadata. */
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
