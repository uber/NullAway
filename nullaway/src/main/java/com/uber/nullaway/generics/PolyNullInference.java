package com.uber.nullaway.generics;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.VisitorState;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.util.ListBuffer;
import com.uber.nullaway.LibraryModels.PolyNullLocation;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.librarymodel.NestedTypePathUpdater;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.lang.model.element.Element;
import org.jspecify.annotations.Nullable;

/** Constructs and applies polymorphic-nullness constraints for modeled method locations. */
final class PolyNullInference {

  /** Diagnostic for incompatible constraints on modeled PolyNull locations. */
  static final String INFERENCE_FAILURE_MESSAGE =
      "inference failure: polymorphic nullness constrained to both @NonNull and @Nullable";

  /** The result of resolving PolyNull for one invocation in a generic inference session. */
  record PolyNullInferenceResult(@Nullable Nullness nullness) {}

  /** The modeled input overlay and shared PolyNull variable for one call. */
  record PolyNullInferenceContext(
      Type.MethodType inferenceMethodType,
      ImmutableList<PolyNullLocation> inputs,
      Type.TypeVar inferenceVariable) {}

  private PolyNullInference() {}

  /** Applies a resolved PolyNull annotation to every modeled parameter and return location. */
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"}) // deliberate reference equality checks
  static Type.MethodType applyToMethodType(
      Type.MethodType methodType, ImmutableSet<PolyNullLocation> locations, Type annotationType) {
    boolean changed = false;
    ListBuffer<Type> updatedParameterTypes = new ListBuffer<>();
    int parameterIndex = 0;
    for (com.sun.tools.javac.util.List<Type> remaining = methodType.argtypes;
        remaining.nonEmpty();
        remaining = remaining.tail, parameterIndex++) {
      Type parameterType = remaining.head;
      Type updatedParameterType =
          applyToType(parameterType, parameterIndex, locations, annotationType);
      updatedParameterTypes.append(updatedParameterType);
      changed |= updatedParameterType != parameterType;
    }
    Type returnType = methodType.restype;
    Type updatedReturnType = applyToReturnType(returnType, locations, annotationType);
    changed |= updatedReturnType != returnType;
    return changed
        ? new Type.MethodType(
            updatedParameterTypes.toList(), updatedReturnType, methodType.thrown, methodType.tsym)
        : methodType;
  }

  /** Applies a resolved PolyNull annotation to every modeled location within a return type. */
  static Type applyToReturnType(
      Type returnType, ImmutableSet<PolyNullLocation> locations, Type annotationType) {
    return applyToType(returnType, -1, locations, annotationType);
  }

  /** Resolves all PolyNull contexts after a shared generic-inference solver run. */
  static IdentityHashMap<MethodInvocationTree, PolyNullInferenceResult> resolveContexts(
      IdentityHashMap<MethodInvocationTree, PolyNullInferenceContext> contexts,
      Map<Element, ConstraintSolver.InferredNullability> solution) {
    IdentityHashMap<MethodInvocationTree, PolyNullInferenceResult> results =
        new IdentityHashMap<>();
    for (Map.Entry<MethodInvocationTree, PolyNullInferenceContext> entry : contexts.entrySet()) {
      results.put(
          entry.getKey(), new PolyNullInferenceResult(resolveContext(entry.getValue(), solution)));
    }
    return results;
  }

  /** Resolves the shared PolyNull variable for one invocation. */
  static Nullness resolveContext(
      PolyNullInferenceContext inferenceContext,
      Map<Element, ConstraintSolver.InferredNullability> solution) {
    ConstraintSolver.InferredNullability inferred =
        solution.getOrDefault(
            inferenceContext.inferenceVariable().asElement(),
            ConstraintSolver.InferredNullability.NONNULL);
    return inferred == ConstraintSolver.InferredNullability.NULLABLE
        ? Nullness.NULLABLE
        : Nullness.NONNULL;
  }

  /** Creates a method-type overlay with one shared variable at every modeled PolyNull input. */
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"}) // deliberate reference equality checks
  static PolyNullInferenceContext createContext(
      Symbol.MethodSymbol methodSymbol,
      Type.MethodType methodType,
      ImmutableSet<PolyNullLocation> locations,
      Type nullableAnnotationType,
      VisitorState state) {
    Map<Integer, java.util.List<PolyNullLocation>> inputsByParameter = new LinkedHashMap<>();
    ImmutableList.Builder<PolyNullLocation> allInputs = ImmutableList.builder();
    Type.TypeVar inferenceVariable =
        createInferenceVariable(methodSymbol, 0, nullableAnnotationType, state);
    for (PolyNullLocation location : locations) {
      int parameterIndex = location.parameterIndex();
      if (parameterIndex < 0 || parameterIndex >= methodType.argtypes.size()) {
        continue;
      }
      inputsByParameter.computeIfAbsent(parameterIndex, unused -> new ArrayList<>()).add(location);
    }
    ListBuffer<Type> updatedParameterTypes = new ListBuffer<>();
    int parameterIndex = 0;
    for (com.sun.tools.javac.util.List<Type> remaining = methodType.argtypes;
        remaining.nonEmpty();
        remaining = remaining.tail, parameterIndex++) {
      Type updated = remaining.head;
      for (PolyNullLocation location :
          inputsByParameter.getOrDefault(parameterIndex, java.util.List.of())) {
        Type replaced =
            NestedTypePathUpdater.replaceType(updated, location.typePath(), inferenceVariable);
        if (replaced != updated) {
          updated = replaced;
          allInputs.add(location);
        }
      }
      updatedParameterTypes.append(updated);
    }
    return new PolyNullInferenceContext(
        new Type.MethodType(
            updatedParameterTypes.toList(), methodType.restype, methodType.thrown, methodType.tsym),
        allInputs.build(),
        inferenceVariable);
  }

  /** Adds a call-result subtype constraint using the invocation's shared PolyNull variable. */
  static void addResultConstraints(
      ConstraintSolver solver,
      Type returnType,
      ImmutableSet<PolyNullLocation> locations,
      PolyNullInferenceContext inferenceContext,
      Type targetType,
      boolean assignedToLocal) {
    if (locations.stream().noneMatch(location -> location.parameterIndex() == -1)) {
      return;
    }
    Type inferenceReturnType = returnType;
    for (PolyNullLocation location : locations) {
      if (location.parameterIndex() == -1) {
        inferenceReturnType =
            NestedTypePathUpdater.replaceType(
                inferenceReturnType, location.typePath(), inferenceContext.inferenceVariable());
      }
    }
    solver.addSubtypeConstraint(inferenceReturnType, targetType, assignedToLocal);
  }

  /** Returns whether {@code typeVariable} is a PolyNull variable from one of {@code contexts}. */
  static boolean containsInferenceVariable(
      IdentityHashMap<MethodInvocationTree, PolyNullInferenceContext> contexts,
      Element typeVariable) {
    return contexts.values().stream()
        .anyMatch(context -> Objects.equals(context.inferenceVariable().asElement(), typeVariable));
  }

  /** Creates the nullable-bounded synthetic type variable for one PolyNull invocation. */
  private static Type.TypeVar createInferenceVariable(
      Symbol.MethodSymbol methodSymbol,
      int group,
      Type nullableAnnotationType,
      VisitorState state) {
    Symbol.TypeVariableSymbol symbol =
        new Symbol.TypeVariableSymbol(
            0, state.getName("$PolyNull$" + group), Type.noType, methodSymbol);
    Type nullableObject =
        TypeSubstitutionUtils.typeWithAnnot(state.getSymtab().objectType, nullableAnnotationType);
    Type.TypeVar variable = new Type.TypeVar(symbol, nullableObject, state.getSymtab().botType);
    symbol.type = variable;
    return variable;
  }

  /** Applies {@code annotationType} at modeled locations within one method-type component. */
  private static Type applyToType(
      Type type,
      int parameterIndex,
      ImmutableSet<PolyNullLocation> locations,
      Type annotationType) {
    Type updated = type;
    for (PolyNullLocation location : locations) {
      if (location.parameterIndex() == parameterIndex) {
        updated = NestedTypePathUpdater.addAnnotation(updated, location.typePath(), annotationType);
      }
    }
    return updated;
  }
}
