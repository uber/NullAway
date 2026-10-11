package com.uber.nullaway.generics;

import static com.uber.nullaway.generics.ClassDeclarationNullnessAnnotUtils.getAnnotatedSupertype;
import static com.uber.nullaway.generics.TypeMetadataBuilder.TYPE_METADATA_BUILDER;

import com.google.common.base.Verify;
import com.google.errorprone.VisitorState;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.TargetType;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeAnnotationPosition.TypePathEntry;
import com.sun.tools.javac.code.TypeAnnotationPosition.TypePathEntryKind;
import com.sun.tools.javac.code.TypeMetadata;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.uber.nullaway.Config;
import com.uber.nullaway.Nullness;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.type.DeclaredType;
import org.jspecify.annotations.Nullable;

/** Utility method related to substituting type arguments for type variables. */
@SuppressWarnings({"ReferenceEquality", "TypeEquals"}) // deliberate reference equality checks
public class TypeSubstitutionUtils {

  /**
   * Reads root nullability, including the structural representation used for intersections. An
   * intersection permits null only when every explicit component permits null; its implicit Object
   * component is not an additional source-level non-null constraint.
   */
  public static boolean hasNullableRoot(Type type, Config config) {
    if (type instanceof Type.IntersectionClassType intersection) {
      for (Type component : intersection.getExplicitComponents()) {
        if (!hasNullableRoot(component, config)) {
          return false;
        }
      }
      return !intersection.getExplicitComponents().isEmpty();
    }
    return Nullness.hasNullableAnnotation(type.getAnnotationMirrors().stream(), config);
  }

  /**
   * Rebuilds a detached intersection with its original nominal symbol and complete component list,
   * including implicit Object for interface-only intersections. Metadata cloning is unsafe here: it
   * asserts on JDK 17 and produces a plain ClassType on newer javac versions.
   */
  private static Type.IntersectionClassType rebuildIntersection(
      Type.IntersectionClassType original, List<Type> components) {
    return new Type.IntersectionClassType(
        components, (Symbol.ClassSymbol) original.tsym, original.allInterfaces);
  }

  /** Finds an intersection's corresponding nominal component without positional slot copying. */
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
    if (subtype instanceof Type.IntersectionClassType intersection) {
      if (intersection.tsym.equals(superTypeSymbol)) {
        return intersection;
      }
      for (Type component : intersection.getComponents()) {
        Type view = asSuper(types, component, superTypeSymbol, config);
        if (view != null) {
          return copyDirectNullabilityAnnotations(intersection, view, config);
        }
      }
      return null;
    }
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
    return restoreExplicitNullabilityAnnotations(annotatedSupertype, asSuper, config);
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
    Type origType = restoreMemberDeclarationBounds(types, sym, config);
    Type memberType = types.memberType(t, sym);
    Type annotationSource = origType;
    if (sym.owner instanceof Symbol.ClassSymbol owner) {
      // Find an annotated nominal owner even when the receiver is an intersection rather than a
      // DeclaredType. The synthetic intersection symbol has no usable generic member slots.
      Type annotatedOwner =
          t instanceof Type.IntersectionClassType
              ? asSuper(types, t, owner, config)
              : t instanceof DeclaredType declaredType && !t.tsym.equals(owner)
                  ? getAnnotatedSupertype(declaredType, owner, types, config)
                  : null;
      if (annotatedOwner instanceof Type.ClassType annotatedOwnerClassType) {
        Type asMemberOfAnnotatedOwner = types.memberType(annotatedOwnerClassType, sym);
        // this call restores any explicit nullability annotations from the member itself, e.g.,
        // if its return type is declared as @NonNull T
        annotationSource =
            restoreExplicitNullabilityAnnotations(origType, asMemberOfAnnotatedOwner, config);
      }
    }
    // Here, annotationSource is the type of the member in the superclass with all proper
    // annotations.  But, it might still have generic type variables, which have been properly
    // instantiated in memberType.  So, as a final step, restore the annotations from
    // annotationSource onto memberType.
    Type result = restoreExplicitNullabilityAnnotations(annotationSource, memberType, config);
    return restoreReceiverInstantiatedMethodBounds(types, t, sym, origType, result, config);
  }

  /**
   * Restores missing declaration-bound nullness from a method's authoritative raw type attributes.
   * Anonymous-class attribution can strip annotations from constructor type-variable bounds before
   * inference starts. Raw bound indices and type paths identify the exact annotated positions; a
   * nested nullable annotation must not make the entire bound nullable. Changed variables, their
   * peer occurrences, and the executable type are detached without modifying the member symbol.
   *
   * @return the member's declaration type, with detached variables if bound annotations were lost
   */
  public static Type restoreMemberDeclarationBounds(Types types, Symbol member, Config config) {
    if (!(member instanceof Symbol.MethodSymbol)
        || !(member.type instanceof Type.ForAll declaration)) {
      return member.type;
    }
    java.util.List<Type> bounds = new java.util.ArrayList<>();
    for (Type variable : declaration.tvars) {
      bounds.add(((Type.TypeVar) variable).getUpperBound());
    }
    boolean changed = false;
    for (Attribute.TypeCompound annotation : member.getRawTypeAttributes()) {

      if (annotation.position.type != TargetType.METHOD_TYPE_PARAMETER_BOUND
          || annotation.type.tsym == null) {
        continue;
      }
      String name = annotation.type.tsym.getQualifiedName().toString();
      if (!Nullness.isNullableAnnotation(name, config)
          && !Nullness.isNonNullAnnotation(name, config)) {
        continue;
      }
      int parameterIndex = annotation.position.parameter_index;
      if (parameterIndex < 0 || parameterIndex >= bounds.size()) {
        continue;
      }
      Type previous = bounds.get(parameterIndex);
      Type restored = restoreDeclarationBoundAnnotation(previous, annotation, config);
      bounds.set(parameterIndex, restored);

      changed |= restored != previous;
    }
    if (!changed) {
      return declaration;
    }
    Map<Element, Type> replacements = new LinkedHashMap<>();
    Map<Element, Type.TypeVar> methodVariables = new LinkedHashMap<>();
    ListBuffer<Type> variables = new ListBuffer<>();
    for (Type variable : declaration.tvars) {
      Type.TypeVar detached =
          TYPE_METADATA_BUILDER.createDetachedTypeVar(
              (Type.TypeVar) variable, ((Type.TypeVar) variable).getUpperBound());
      variables.append(detached);
      replacements.put(variable.asElement(), detached);
      methodVariables.put(variable.asElement(), detached);
    }
    List<Type> detached = variables.toList();
    for (int i = 0; i < detached.size(); i++) {
      ((Type.TypeVar) detached.get(i))
          .setUpperBound(substituteTypeVariables(bounds.get(i), replacements, types, config));
    }
    Type executable = substituteTypeVariables(declaration.qtype, replacements, types, config);
    Set<Type> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Type variable : detached) {
      synchronizeReceiverInstantiatedBounds(
          ((Type.TypeVar) variable).getUpperBound(), methodVariables, visited);
    }
    synchronizeReceiverInstantiatedBounds(executable, methodVariables, visited);
    return new Type.ForAll(detached, executable);
  }

  /**
   * Selects a JVMS bound index, including index zero's implicit Object slot for interface-only
   * bounds. Invalid or unavailable positions are left untouched rather than creating Java shapes.
   */
  private static Type restoreDeclarationBoundAnnotation(
      Type bound, Attribute.TypeCompound annotation, Config config) {
    if (!(bound instanceof Type.IntersectionClassType)
        && bound.baseType() instanceof Type.IntersectionClassType intersection) {
      // Recover a genuine Java shape retained by a metadata wrapper, never one inferred from attrs.
      bound = intersection;
    }
    int boundIndex = annotation.position.bound_index;
    if (bound instanceof Type.IntersectionClassType intersection) {
      List<Type> original = intersection.getComponents();
      if (boundIndex < 0 || boundIndex >= original.size()) {
        return bound;
      }
      ListBuffer<Type> components = new ListBuffer<>();
      boolean changed = false;
      for (int i = 0; i < original.size(); i++) {
        Type component = original.get(i);
        Type restored =
            i == boundIndex
                ? restoreBoundAnnotationAtPath(
                    component, annotation.position.location, annotation, config)
                : component;
        components.append(restored);
        changed |= restored != component;
      }
      return changed ? rebuildIntersection(intersection, components.toList()) : bound;
    }
    // A sole interface occupies bound_index 1 despite having no IntersectionClassType wrapper.
    int expectedIndex = bound.isInterface() ? 1 : 0;
    return boundIndex == expectedIndex
        ? restoreBoundAnnotationAtPath(bound, annotation.position.location, annotation, config)
        : bound;
  }

  /**
   * Restores a raw annotation along its type path. Class paths start at the outermost class
   * segment, not at javac's innermost ClassType object, so enclosing arguments and INNER_TYPE
   * annotations cannot be confused with the inner class's root or arguments.
   */
  private static Type restoreBoundAnnotationAtPath(
      Type type, List<TypePathEntry> path, Attribute.TypeCompound annotation, Config config) {
    if (type instanceof Type.ClassType classType && !(type instanceof Type.IntersectionClassType)) {
      List<Type.ClassType> segments = List.of(classType);
      Type enclosing = classType.getEnclosingType();
      while (enclosing instanceof Type.ClassType outer) {
        segments = segments.prepend(outer);
        enclosing = outer.getEnclosingType();
      }
      int segmentIndex = 0;
      while (path.nonEmpty() && path.head.tag == TypePathEntryKind.INNER_TYPE) {
        segmentIndex++;
        path = path.tail;
      }
      if (segmentIndex >= segments.size()) {
        return type;
      }
      Type restored =
          restoreBoundAnnotationAtNode(segments.get(segmentIndex), path, annotation, config);
      if (restored == segments.get(segmentIndex)) {
        return type;
      }
      for (int i = segmentIndex + 1; i < segments.size(); i++) {
        Type.ClassType inner = segments.get(i);
        restored = TYPE_METADATA_BUILDER.createClassType(inner, restored, inner.getTypeArguments());
      }
      return restored;
    }
    return restoreBoundAnnotationAtNode(type, path, annotation, config);
  }

  /** Applies an annotation only at an existing root, array, wildcard, or type-argument position. */
  private static Type restoreBoundAnnotationAtNode(
      Type type, List<TypePathEntry> path, Attribute.TypeCompound annotation, Config config) {
    if (path.isEmpty()) {
      if (type instanceof Type.IntersectionClassType
          || type.isErroneous()
          || (type.tsym != null && type.tsym.type instanceof Type.IntersectionClassType)) {
        // Raw bounds select a nominal component, never a synthetic intersection root.
        return type;
      }
      if (hasNullableRoot(type, config)
          || Nullness.hasNonNullAnnotation(type.getAnnotationMirrors().stream(), config)) {
        return type;
      }
      ListBuffer<Attribute.TypeCompound> annotations = new ListBuffer<>();
      annotations.appendList(type.getAnnotationMirrors());
      annotations.append(annotation);
      return TYPE_METADATA_BUILDER.cloneTypeWithMetadata(
          type, TYPE_METADATA_BUILDER.create(annotations.toList()));
    }
    if (path.head.tag == TypePathEntryKind.ARRAY && type instanceof Type.ArrayType array) {
      Type component = restoreBoundAnnotationAtPath(array.elemtype, path.tail, annotation, config);
      return component == array.elemtype
          ? type
          : TYPE_METADATA_BUILDER.createArrayType(array, component);
    }
    if (path.head.tag == TypePathEntryKind.WILDCARD
        && type instanceof Type.WildcardType wildcard
        && !wildcard.isUnbound()) {
      Type bound = restoreBoundAnnotationAtPath(wildcard.type, path.tail, annotation, config);
      if (bound == wildcard.type) {
        return type;
      }
      Type.WildcardType updated = TYPE_METADATA_BUILDER.createWildcardType(wildcard, bound);
      updated.bound = wildcard.bound;
      return updated;
    }
    if (path.head.tag == TypePathEntryKind.TYPE_ARGUMENT
        && type instanceof Type.ClassType classType
        && !(type instanceof Type.IntersectionClassType)) {
      List<Type> arguments = classType.getTypeArguments();
      int argumentIndex = path.head.arg;
      if (classType.isRaw() || argumentIndex < 0 || argumentIndex >= arguments.size()) {
        return type;
      }
      Type previous = arguments.get(argumentIndex);
      Type restored = restoreBoundAnnotationAtPath(previous, path.tail, annotation, config);
      if (restored == previous) {
        return type;
      }
      ListBuffer<Type> updated = new ListBuffer<>();
      for (int i = 0; i < arguments.size(); i++) {
        updated.append(i == argumentIndex ? restored : arguments.get(i));
      }
      return TYPE_METADATA_BUILDER.createClassType(
          classType, classType.getEnclosingType(), updated.toList());
    }
    return type;
  }

  /**
   * Restores receiver argument annotations in a generic member's dependent bounds. javac's member
   * substitution can erase them in bounds such as {@code U extends E}. Detach the method variables
   * before restoring bounds and remap their occurrences without mutating compiler-owned types.
   */
  private static Type restoreReceiverInstantiatedMethodBounds(
      Types types,
      Type receiver,
      Symbol member,
      Type declaration,
      Type instantiated,
      Config config) {
    if (!(declaration instanceof Type.ForAll original)
        || !(instantiated instanceof Type.ForAll resolved)
        || !(member.owner instanceof Symbol.ClassSymbol owner)
        || original.tvars.size() != resolved.tvars.size()) {
      return instantiated;
    }
    Type ownerView = asSuper(types, receiver, owner, config);
    Map<Element, Type> replacements = new LinkedHashMap<>();
    if (ownerView instanceof Type.ClassType) {
      List<Type> parameters = owner.type.allparams();
      List<Type> arguments = ownerView.allparams();
      if (parameters.size() == arguments.size()) {
        for (int i = 0; i < parameters.size(); i++) {
          replacements.put(parameters.get(i).asElement(), arguments.get(i));
        }
      }
    }
    // Declaration-bound restoration is independent of receiver parameters, including constructors
    // of non-generic classes whose bounds were stripped during anonymous-class attribution.
    ListBuffer<Type> detachedVariables = new ListBuffer<>();
    Map<Element, Type.TypeVar> methodVariables = new LinkedHashMap<>();
    for (Type variable : resolved.tvars) {
      Type.TypeVar detached =
          TYPE_METADATA_BUILDER.createDetachedTypeVar(
              (Type.TypeVar) variable, ((Type.TypeVar) variable).getUpperBound());
      detachedVariables.append(detached);
      methodVariables.put(variable.asElement(), detached);
      replacements.put(variable.asElement(), detached);
    }
    List<Type> variables = detachedVariables.toList();
    for (int i = 0; i < original.tvars.size(); i++) {
      ((Type.TypeVar) variables.get(i))
          .setUpperBound(
              restoreReceiverInstantiatedBound(
                  ((Type.TypeVar) original.tvars.get(i)).getUpperBound(),
                  ((Type.TypeVar) resolved.tvars.get(i)).getUpperBound(),
                  replacements,
                  types,
                  config));
    }
    Type methodType = substituteTypeVariables(resolved.qtype, replacements, types, config);
    // Annotated peer occurrences are detached copies whose bounds may have been copied before
    // the peer's bound was restored. Synchronize them only after all canonical bounds are complete.
    Set<Type> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Type variable : variables) {
      synchronizeReceiverInstantiatedBounds(
          ((Type.TypeVar) variable).getUpperBound(), methodVariables, visited);
    }
    synchronizeReceiverInstantiatedBounds(methodType, methodVariables, visited);
    return new Type.ForAll(variables, methodType);
  }

  /**
   * Restores a dependent bound, pairing intersection components before substituting annotations.
   *
   * <p>Intersection types have no ordinary class type arguments. Restore their constituent bounds
   * explicitly and rebuild the intersection, preserving javac's implicit Object representation
   * rather than treating the synthetic class as an annotation source or mutating shared bounds.
   */
  private static Type restoreReceiverInstantiatedBound(
      Type declaration,
      Type instantiated,
      Map<Element, Type> replacements,
      Types types,
      Config config) {
    if (declaration instanceof Type.IntersectionClassType originalIntersection
        && instantiated instanceof Type.IntersectionClassType resolvedIntersection) {
      // javac substitution may expose an originally implicit Object bound as an explicit one.
      List<Type> originals = originalIntersection.getComponents();
      List<Type> resolved = resolvedIntersection.getComponents();
      Verify.verify(originals.size() == resolved.size(), "intersection component arity changed");
      ListBuffer<Type> components = new ListBuffer<>();
      for (; originals.nonEmpty(); originals = originals.tail, resolved = resolved.tail) {
        components.append(
            restoreReceiverInstantiatedBound(
                originals.head, resolved.head, replacements, types, config));
      }
      List<Type> restored = components.toList();
      return rebuildIntersection(resolvedIntersection, restored);
    }
    Type bound = substituteTypeVariables(declaration, replacements, types, config);
    Type restored = restoreExplicitNullabilityAnnotations(bound, instantiated, config);
    return substituteTypeVariables(restored, replacements, types, config);
  }

  /**
   * Gives detached method-variable occurrences their completed receiver-instantiated bounds.
   *
   * <p>The roots must have already been substituted with the detached method variables, so every
   * syntactic occurrence of a listed variable is safe to update. Do not follow variable bounds:
   * those may still reference compiler-owned types, and each completed bound is visited separately.
   */
  private static void synchronizeReceiverInstantiatedBounds(
      Type type, Map<Element, Type.TypeVar> variables, Set<Type> visited) {
    if (!visited.add(type)) {
      return;
    }
    if (type instanceof Type.TypeVar variable) {
      Type.TypeVar canonical = variables.get(variable.asElement());
      if (canonical != null) {
        variable.setUpperBound(canonical.getUpperBound());
      }
    } else if (type instanceof Type.MethodType method) {
      for (Type parameter : method.getParameterTypes()) {
        synchronizeReceiverInstantiatedBounds(parameter, variables, visited);
      }
      synchronizeReceiverInstantiatedBounds(method.getReturnType(), variables, visited);
      for (Type thrown : method.getThrownTypes()) {
        synchronizeReceiverInstantiatedBounds(thrown, variables, visited);
      }
    } else if (type instanceof Type.ArrayType array) {
      synchronizeReceiverInstantiatedBounds(array.getComponentType(), variables, visited);
    } else if (type instanceof Type.WildcardType wildcard) {
      if (!wildcard.isUnbound()) {
        synchronizeReceiverInstantiatedBounds(wildcard.type, variables, visited);
      }
    } else if (type instanceof Type.IntersectionClassType intersection) {
      for (Type component : intersection.getExplicitComponents()) {
        synchronizeReceiverInstantiatedBounds(component, variables, visited);
      }
    } else if (type instanceof Type.ClassType clazz) {
      synchronizeReceiverInstantiatedBounds(clazz.getEnclosingType(), variables, visited);
      for (Type argument : clazz.getTypeArguments()) {
        synchronizeReceiverInstantiatedBounds(argument, variables, visited);
      }
    }
  }

  /**
   * Restores explicit nullability annotations on type variables in {@code origType} to {@code
   * newType}.
   *
   * @param origType the original type
   * @param newType the new type, a result of applying some substitution to {@code origType}
   * @param config the NullAway config
   * @return the new type with explicit nullability annotations restored
   */
  public static Type restoreExplicitNullabilityAnnotations(
      Type origType, Type newType, Config config) {
    return new RestoreNullnessAnnotationsVisitor(config).visit(newType, origType);
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
  private static Type.WildcardType replaceImplicitWildcardUpperBound(
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
   * Updates a type {@code typeToUpdate} by applying inferred types for type variables. The update
   * proceeds in four steps:
   *
   * <p>1. Substitute inferred top-level nullability for type variables in the original type {@code
   * origType}. So, if the {@code origType} is {@code List<T>}, and we inferred T to be nullable,
   * the result will be {@code List<@Nullable T>}.
   *
   * <p>2. Restore any explicit nullability annotations that were present on {@code origType} to the
   * result of 1. So, if {@code origType} was {@code List<@NonNull T>}, the result will be {@code
   * List<@NonNull T>}, even if T was inferred to be nullable.
   *
   * <p>3 . Apply the nullability annotations from the result of 2 to {@code typeToUpdate}. So, if
   * {@code typeToUpdate} is {@code List<String>}, and the result of 2 is {@code List<@Nullable T>},
   * the final result will be {@code List<@Nullable String>}.
   *
   * <p>4. For type variables whose inferred type has known structure, apply the nested nullability
   * annotations of the inferred type to the corresponding position in the result of 3. So, if
   * {@code origType} is {@code Box<R>}, we inferred R to be {@code Box<@Nullable String>}, and the
   * result of 3 is {@code Box<Box<String>>}, the final result is {@code Box<Box<@Nullable
   * String>>}. This step corrects nested annotations that javac drops or misplaces in its inferred
   * type arguments.
   *
   * @param typeToUpdate the type to update
   * @param origType the original type with type variables and possibly explicit nullability
   *     annotations
   * @param inferredTypes a map from type variable elements to their inferred types, as described in
   *     {@link ConstraintSolver#solve()}
   * @param state the visitor state
   * @param config the NullAway config
   * @return the updated type with inferred nullability applied
   */
  static Type updateTypeWithInferredNullability(
      Type typeToUpdate,
      Type origType,
      @Nullable Map<Element, Type> inferredTypes,
      VisitorState state,
      Config config) {
    if (inferredTypes == null) {
      // no updates to perform
      return typeToUpdate;
    }
    // step 1
    Type inferredNullabilitySubstituted =
        substituteInferredNullabilityForTypeVariables(origType, inferredTypes, state, config);
    // step 2
    Type origExplicitAnnotationsRestored =
        restoreExplicitNullabilityAnnotations(origType, inferredNullabilitySubstituted, config);
    // step 3
    // TODO optimize these steps to avoid doing so many substitutions in the future, if needed
    Type updated =
        restoreExplicitNullabilityAnnotations(
            origExplicitAnnotationsRestored, typeToUpdate, config);
    // step 4
    return applyNestedAnnotationsOfInferredTypes(
        origType, updated, inferredTypes, state.getTypes(), config);
  }

  /**
   * Updates a method type {@code typeToUpdate} by applying inferred types for type variables. The
   * update is applied to the argument types, return type, and thrown types of the method type,
   * using {@link #updateTypeWithInferredNullability(Type, Type, Map, VisitorState, Config)}
   *
   * @param methodTypeToUpdate method type to update
   * @param origMethodType original method type, with type variables and possibly explicit
   *     nullability annotations
   * @param inferredTypes a map from type variable elements to their inferred types
   * @param state the visitor state
   * @param config the NullAway config
   * @return the updated method type with inferred nullability applied
   */
  @SuppressWarnings("ReferenceEquality")
  public static Type.MethodType updateMethodTypeWithInferredNullability(
      Type.MethodType methodTypeToUpdate,
      Type.MethodType origMethodType,
      @Nullable Map<Element, Type> inferredTypes,
      VisitorState state,
      Config config) {
    List<Type> argtypes = methodTypeToUpdate.argtypes;
    Type restype = methodTypeToUpdate.restype;
    List<Type> thrown = methodTypeToUpdate.thrown;
    List<Type> argtypes1 =
        updateTypeListNullability(argtypes, origMethodType.argtypes, inferredTypes, state, config);
    Type restype1 =
        updateTypeWithInferredNullability(
            restype, origMethodType.restype, inferredTypes, state, config);
    List<Type> thrown1 =
        updateTypeListNullability(thrown, origMethodType.thrown, inferredTypes, state, config);
    if (argtypes1 == argtypes && restype1 == restype && thrown1 == thrown) {
      return methodTypeToUpdate;
    } else {
      return new Type.MethodType(argtypes1, restype1, thrown1, methodTypeToUpdate.tsym);
    }
  }

  /**
   * Substitutes complete inferred types into a generic method reference's still-symbolic method
   * type, including nested type arguments and array components.
   *
   * <p>Unlike {@link #updateMethodTypeWithInferredNullability}, this replaces type-variable
   * occurrences with the inferred types themselves rather than only overlaying their annotations.
   * Explicit nullability annotations on each original occurrence take precedence over the inferred
   * root annotation; nested annotations of the replacement are retained. Solver fallback values
   * that are annotated declared type variables remain symbolic: their upper bounds are not used as
   * replacement types. Variables absent from the inference map are left unchanged.
   *
   * @param methodType the original method-reference type, which may still contain type variables
   * @param inferredTypes complete inferred substitutions for this method-reference site
   * @param state the visitor state
   * @param config the NullAway config
   * @return the method type with complete inferred substitutions applied
   */
  static Type.MethodType substituteInferredTypesForGenericMethodReference(
      Type.MethodType methodType,
      Map<Element, Type> inferredTypes,
      VisitorState state,
      Config config) {
    return (Type.MethodType)
        substituteTypeVariables(methodType, inferredTypes, state.getTypes(), config);
  }

  @SuppressWarnings("ReferenceEquality")
  private static List<Type> updateTypeListNullability(
      List<Type> typesToUpdate,
      List<Type> origTypes,
      @Nullable Map<Element, Type> inferredTypes,
      VisitorState state,
      Config config) {
    ListBuffer<Type> buf = new ListBuffer<>();
    boolean changed = false;
    for (List<Type> l = typesToUpdate, l1 = origTypes; l.nonEmpty(); l = l.tail, l1 = l1.tail) {
      Type toUpdate = l.head;
      Type orig = l1.head;
      Type t2 = updateTypeWithInferredNullability(toUpdate, orig, inferredTypes, state, config);
      buf.append(t2);
      if (t2 != toUpdate) {
        changed = true;
      }
    }
    return changed ? buf.toList() : typesToUpdate;
  }

  /**
   * Substitutes inferred top-level nullability for type variables in the given target type.
   *
   * @param targetType type to which to apply substitutions
   * @param inferredTypes a map from type variable elements to their inferred types
   * @param state the visitor state
   * @param config the NullAway config
   * @return the type resulting from applying inferred nullability substitutions
   */
  private static Type substituteInferredNullabilityForTypeVariables(
      Type targetType, Map<Element, Type> inferredTypes, VisitorState state, Config config) {
    if (inferredTypes.isEmpty()) {
      return targetType;
    }
    if (targetType instanceof Type.IntersectionClassType intersection) {
      ListBuffer<Type> components = new ListBuffer<>();
      boolean changed = false;
      for (Type component : intersection.getComponents()) {
        Type substituted =
            substituteInferredNullabilityForTypeVariables(component, inferredTypes, state, config);
        components.append(substituted);
        changed |= substituted != component;
      }
      return changed ? rebuildIntersection(intersection, components.toList()) : targetType;
    }
    ListBuffer<Type> typeVars = new ListBuffer<>();
    ListBuffer<Type> inferredNullabilityTypes = new ListBuffer<>();
    TypeVarWithSymbolCollector collector = new TypeVarWithSymbolCollector(inferredTypes.keySet());
    targetType.accept(collector, null);
    for (Type.TypeVar occurrence : collector.getMatches()) {
      Type inferred = Verify.verifyNotNull(inferredTypes.get(occurrence.asElement()));
      Type nullnessAnnotType =
          hasNullableRoot(inferred, config)
              ? GenericsChecks.getSyntheticNullableAnnotType(state)
              : GenericsChecks.getSyntheticNonNullAnnotType(state);
      typeVars.append(occurrence);
      inferredNullabilityTypes.append(typeWithAnnot(occurrence, nullnessAnnotType));
    }
    List<Type> typeVarsToReplace = typeVars.toList();
    if (!typeVarsToReplace.isEmpty()) {
      return subst(
          state.getTypes(),
          targetType,
          typeVarsToReplace,
          inferredNullabilityTypes.toList(),
          config);
    } else {
      return targetType;
    }
  }

  /**
   * Walks {@code origType} and {@code target} in parallel. At each position where {@code origType}
   * has a type variable whose inferred type has known structure (i.e., is not just the type
   * variable itself; see {@link ConstraintSolver#solve()}), applies the nested nullability
   * annotations of the inferred type to the corresponding position in {@code target}, keeping the
   * top-level annotations of that position. Positions where the two types do not line up are left
   * unchanged.
   *
   * @param origType the original type with type variables
   * @param target the type to update, with the same shape as {@code origType} after substitution
   * @param inferredTypes a map from type variable elements to their inferred types
   * @param types the javac types instance
   * @param config the NullAway config
   * @return the updated type, or {@code target} itself if no updates were made
   */
  @SuppressWarnings("ReferenceEquality")
  private static Type applyNestedAnnotationsOfInferredTypes(
      Type origType, Type target, Map<Element, Type> inferredTypes, Types types, Config config) {
    if (origType instanceof Type.TypeVar origTypeVar && !(origType instanceof Type.CapturedType)) {
      Type inferredType = inferredTypes.get(origTypeVar.tsym);
      if (inferredType == null
          || (inferredType instanceof Type.TypeVar && inferredType.tsym == origTypeVar.tsym)) {
        // no structure inferred for this type variable
        return target;
      }
      return applyNestedAnnotations(inferredType, target, types, config);
    }
    if (origType instanceof Type.IntersectionClassType original
        && target instanceof Type.IntersectionClassType intersection) {
      ListBuffer<Type> components = new ListBuffer<>();
      boolean changed = false;
      for (Type component : intersection.getComponents()) {
        Type corresponding = intersectionComponent(original, component.tsym);
        Type updated =
            corresponding == null
                ? component
                : applyNestedAnnotationsOfInferredTypes(
                    corresponding, component, inferredTypes, types, config);
        components.append(updated);
        changed |= updated != component;
      }
      return changed ? rebuildIntersection(intersection, components.toList()) : target;
    }
    if (origType instanceof Type.IntersectionClassType
        || target instanceof Type.IntersectionClassType) {
      return target;
    }
    if (origType instanceof Type.ClassType origClassType
        && target instanceof Type.ClassType targetClassType) {
      if (origClassType.tsym != targetClassType.tsym
          || origClassType.isRaw()
          || targetClassType.isRaw()
          || origClassType.getTypeArguments().size() != targetClassType.getTypeArguments().size()) {
        return target;
      }
      ListBuffer<Type> newTypeArgs = new ListBuffer<>();
      boolean changed = false;
      for (List<Type> o = origClassType.getTypeArguments(), t = targetClassType.getTypeArguments();
          t.nonEmpty();
          o = o.tail, t = t.tail) {
        Type newTypeArg =
            applyNestedAnnotationsOfInferredTypes(o.head, t.head, inferredTypes, types, config);
        changed |= newTypeArg != t.head;
        newTypeArgs.append(newTypeArg);
      }
      Type enclosingType = targetClassType.getEnclosingType();
      Type newEnclosingType =
          applyNestedAnnotationsOfInferredTypes(
              origClassType.getEnclosingType(), enclosingType, inferredTypes, types, config);
      changed |= newEnclosingType != enclosingType;
      return changed
          ? TYPE_METADATA_BUILDER.createClassType(
              targetClassType, newEnclosingType, newTypeArgs.toList())
          : target;
    }
    if (origType instanceof Type.ArrayType origArrayType
        && target instanceof Type.ArrayType targetArrayType) {
      Type elemType = targetArrayType.getComponentType();
      Type newElemType =
          applyNestedAnnotationsOfInferredTypes(
              origArrayType.getComponentType(), elemType, inferredTypes, types, config);
      return newElemType == elemType
          ? target
          : TYPE_METADATA_BUILDER.createArrayType(targetArrayType, newElemType);
    }
    if (origType instanceof Type.WildcardType origWildcard && origWildcard.type != null) {
      if (target instanceof Type.WildcardType targetWildcard) {
        if (origWildcard.kind != targetWildcard.kind || targetWildcard.type == null) {
          return target;
        }
        Type newBound =
            applyNestedAnnotationsOfInferredTypes(
                origWildcard.type, targetWildcard.type, inferredTypes, types, config);
        return newBound == targetWildcard.type
            ? target
            : TYPE_METADATA_BUILDER.createWildcardType(targetWildcard, newBound);
      }
      if (origWildcard.kind == BoundKind.EXTENDS && !(target instanceof Type.CapturedType)) {
        // e.g., the ground type of a functional interface type replaces ? extends S with S
        return applyNestedAnnotationsOfInferredTypes(
            origWildcard.type, target, inferredTypes, types, config);
      }
    }
    return target;
  }

  /**
   * Applies the nested nullability annotations of {@code inferredType} (annotations on its type
   * arguments, enclosing type, or array component type, recursively) to {@code target}, keeping the
   * top-level annotations of {@code target}. If {@code inferredType} is a class type for a
   * different class than {@code target} (e.g., a subtype), it is first viewed as an instance of the
   * class of {@code target}. If the two types cannot be aligned, returns {@code target}.
   *
   * @param inferredType the inferred type, whose nested annotations to apply
   * @param target the type to update
   * @param types the javac types instance
   * @param config the NullAway config
   * @return the updated type, or {@code target} itself if no updates were made
   */
  private static Type applyNestedAnnotations(
      Type inferredType, Type target, Types types, Config config) {
    return overlayInferredTypeAnnotations(inferredType, target, types, config, true);
  }

  /**
   * Overlays inferred nullability only at recursively aligned positions. Class types must name the
   * same class after viewing the source as the target's supertype; equal argument counts alone do
   * not establish alignment. Arrays and wildcard bounds are checked recursively rather than passed
   * to the positional annotation-restoration visitor.
   *
   * @param inferredType the annotation source
   * @param target the type whose structure and unrelated metadata are preserved
   * @param types the javac types instance
   * @param config the NullAway config
   * @param preserveRoot whether to preserve the target's root annotations, including explicit
   *     occurrence overrides already restored before applying nested inferred annotations
   * @return the updated target, or the original target if no aligned annotations changed
   */
  private static Type overlayInferredTypeAnnotations(
      Type inferredType, Type target, Types types, Config config, boolean preserveRoot) {
    if (inferredType instanceof Type.CapturedType || target instanceof Type.CapturedType) {
      return target;
    }
    if (target instanceof Type.IntersectionClassType) {
      Type.IntersectionClassType updated =
          (Type.IntersectionClassType)
              (preserveRoot
                  ? target
                  : copyDirectNullabilityAnnotations(inferredType, target, config));
      ListBuffer<Type> components = new ListBuffer<>();
      boolean changed = false;
      for (Type component : updated.getComponents()) {
        // Component roots encode the intersection root, already chosen above. Overlay only the
        // nested annotations from the source's corresponding nominal view.
        Type overlaid =
            overlayInferredTypeAnnotations(inferredType, component, types, config, true);
        components.append(overlaid);
        changed |= overlaid != component;
      }
      return changed ? rebuildIntersection(updated, components.toList()) : updated;
    }
    if (inferredType instanceof Type.ArrayType inferredArrayType
        && target instanceof Type.ArrayType targetArrayType) {
      Type.ArrayType updated =
          preserveRoot
              ? targetArrayType
              : (Type.ArrayType) copyDirectNullabilityAnnotations(inferredType, target, config);
      Type elemType = updated.getComponentType();
      Type newElemType =
          overlayInferredTypeAnnotations(
              inferredArrayType.getComponentType(), elemType, types, config, false);
      return newElemType == elemType
          ? updated
          : TYPE_METADATA_BUILDER.createArrayType(updated, newElemType);
    }
    if (inferredType instanceof Type.ClassType
        && target instanceof Type.ClassType targetClassType) {
      if (inferredType.isRaw() || targetClassType.isRaw()) {
        return target;
      }
      Type aligned = inferredType;
      if (inferredType.tsym != targetClassType.tsym) {
        aligned = asSuper(types, inferredType, (Symbol.ClassSymbol) targetClassType.tsym, config);
      }
      if (!(aligned instanceof Type.ClassType alignedClassType)
          || alignedClassType.isRaw()
          || alignedClassType.tsym != targetClassType.tsym
          || alignedClassType.getTypeArguments().size()
              != targetClassType.getTypeArguments().size()) {
        return target;
      }
      // A supertype view supplies argument positions, not the nullness of the original value.
      Type updated =
          preserveRoot ? target : copyDirectNullabilityAnnotations(inferredType, target, config);
      ListBuffer<Type> newTypeArgs = new ListBuffer<>();
      boolean changed = false;
      for (List<Type> a = alignedClassType.getTypeArguments(),
              t = targetClassType.getTypeArguments();
          t.nonEmpty();
          a = a.tail, t = t.tail) {
        Type newTypeArg = overlayInferredTypeAnnotations(a.head, t.head, types, config, false);
        changed |= newTypeArg != t.head;
        newTypeArgs.append(newTypeArg);
      }
      Type enclosingType = targetClassType.getEnclosingType();
      Type newEnclosingType =
          overlayInferredTypeAnnotations(
              alignedClassType.getEnclosingType(), enclosingType, types, config, false);
      changed |= newEnclosingType != enclosingType;
      return changed
          ? TYPE_METADATA_BUILDER.createClassType(updated, newEnclosingType, newTypeArgs.toList())
          : updated;
    }
    if (inferredType instanceof Type.WildcardType inferredWildcard
        && target instanceof Type.WildcardType targetWildcard) {
      if (inferredWildcard.kind != targetWildcard.kind) {
        return target;
      }
      Type.WildcardType updated =
          preserveRoot
              ? targetWildcard
              : (Type.WildcardType) copyDirectNullabilityAnnotations(inferredType, target, config);
      if (inferredWildcard.type == null || targetWildcard.type == null) {
        return updated;
      }
      Type newBound =
          overlayInferredTypeAnnotations(
              inferredWildcard.type, targetWildcard.type, types, config, false);
      if (newBound == targetWildcard.type) {
        return updated;
      }
      Type.WildcardType result = TYPE_METADATA_BUILDER.createWildcardType(updated, newBound);
      result.bound = updated.bound;
      return result;
    }
    if (!preserveRoot
        && inferredType.tsym != null
        && inferredType.tsym == target.tsym
        && inferredType.getTag() == target.getTag()) {
      return copyDirectNullabilityAnnotations(inferredType, target, config);
    }
    return target;
  }

  /**
   * Copies a source's root nullability, retaining the target's other annotations without mutating
   * shared javac types. Ordinary unannotated sources leave the target unchanged. Intersection roots
   * are read and written structurally. Absent component annotations are not explicit overrides and
   * must not erase root evidence already restored from authoritative declaration attributes.
   */
  private static Type copyDirectNullabilityAnnotations(Type source, Type target, Config config) {
    if (target instanceof Type.IntersectionClassType intersection) {
      ListBuffer<Type> components = new ListBuffer<>();
      boolean changed = false;
      for (Type component : intersection.getComponents()) {
        Type updated = copyDirectNullabilityAnnotations(source, component, config);
        components.append(updated);
        changed |= updated != component;
      }
      return changed ? rebuildIntersection(intersection, components.toList()) : target;
    }
    if (source instanceof Type.IntersectionClassType intersection) {
      boolean nullable = hasNullableRoot(intersection, config);
      for (Type component : intersection.getExplicitComponents()) {
        if (hasNullableRoot(component, config) == nullable) {
          Type updated = copyDirectNullabilityAnnotations(component, target, config);
          if (nullable || updated != target) {
            return updated;
          }
          // A default non-null source root carries no annotation to restore. In particular, a
          // stripped declaration must not overwrite explicit markers in an instantiated bound.
        }
      }
      return target;
    }
    for (Attribute.TypeCompound annotation : source.getAnnotationMirrors()) {
      if (annotation.type.tsym == null) {
        continue;
      }
      String name = annotation.type.tsym.getQualifiedName().toString();
      if (!Nullness.isNullableAnnotation(name, config)
          && !Nullness.isNonNullAnnotation(name, config)) {
        continue;
      }
      ListBuffer<Attribute.TypeCompound> annotations = new ListBuffer<>();
      for (Attribute.TypeCompound targetAnnotation : target.getAnnotationMirrors()) {
        if (targetAnnotation.type.tsym != null) {
          String targetName = targetAnnotation.type.tsym.getQualifiedName().toString();
          if (Nullness.isNullableAnnotation(targetName, config)
              || Nullness.isNonNullAnnotation(targetName, config)) {
            continue;
          }
        }
        annotations.append(targetAnnotation);
      }
      annotations.append(annotation);
      return TYPE_METADATA_BUILDER.cloneTypeWithMetadata(
          target, TYPE_METADATA_BUILDER.create(annotations.toList()));
    }
    return target;
  }

  /**
   * Replaces every occurrence of the given type variables in {@code targetType}, preserving
   * explicit nullability annotations on the replaced occurrences. So, if {@code targetType} is
   * {@code List<@Nullable T>} and {@code T} is replaced with {@code S}, the result is {@code
   * List<@Nullable S>}.
   *
   * <p>Occurrences are found by symbol, since a type can contain several {@link Type.TypeVar}
   * objects for the same symbol, e.g., due to annotations on type variable uses. Intersection
   * bounds are handled component-wise because their synthetic class has no type arguments for the
   * collector to traverse.
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
    if (replacements.isEmpty()) {
      return targetType;
    }
    if (targetType instanceof Type.IntersectionClassType intersection) {
      ListBuffer<Type> components = new ListBuffer<>();
      boolean changed = false;
      for (Type component : intersection.getComponents()) {
        Type substituted = substituteTypeVariables(component, replacements, types, config);
        components.append(substituted);
        changed |= substituted != component;
      }
      return changed ? rebuildIntersection(intersection, components.toList()) : targetType;
    }
    ListBuffer<Type> typeVars = new ListBuffer<>();
    ListBuffer<Type> replacementTypes = new ListBuffer<>();
    TypeVarWithSymbolCollector collector = new TypeVarWithSymbolCollector(replacements.keySet());
    targetType.accept(collector, null);
    for (Type.TypeVar occurrence : collector.getMatches()) {
      // Keep actual, annotation-distinct occurrences, not the symbol's declaration type or any
      // caller's private canonical variable. Each matched symbol belongs to the supplied map.
      typeVars.append(occurrence);
      replacementTypes.append(Verify.verifyNotNull(replacements.get(occurrence.asElement())));
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

    private final Config config;

    /**
     * Pairs of implicit wildcard bounds currently being traversed. Allocated lazily, as the map is
     * only needed for types involving wildcards.
     */
    private @Nullable IdentityHashMap<Type.TypeVar, Set<Type.TypeVar>> activeImplicitWildcardBounds;

    private IdentityHashMap<Type.TypeVar, Set<Type.TypeVar>> getActiveImplicitWildcardBounds() {
      if (activeImplicitWildcardBounds == null) {
        activeImplicitWildcardBounds = new IdentityHashMap<>();
      }
      return activeImplicitWildcardBounds;
    }

    RestoreNullnessAnnotationsVisitor(Config config) {
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
        ListBuffer<Type> components = new ListBuffer<>();
        boolean changed = false;
        for (Type component : updated.getComponents()) {
          Type corresponding =
              other instanceof Type.IntersectionClassType source
                  ? intersectionComponent(source, component.tsym)
                  : null;
          Type restored = corresponding == null ? component : visit(component, corresponding);
          components.append(restored);
          changed |= restored != component;
        }
        return changed ? rebuildIntersection(updated, components.toList()) : updated;
      }
      if (other instanceof Type.IntersectionClassType intersection) {
        Type corresponding = intersectionComponent(intersection, t.tsym);
        Type restored = corresponding == null ? t : visit(t, corresponding);
        return copyDirectNullabilityAnnotations(other, restored, config);
      }
      Type updated = updateDirectNullabilityAnnotationsForType(t, other);
      // A raw source type has no type arguments from which to restore nested annotations.
      if (!(other instanceof Type.ClassType) || other.isRaw()) {
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
        return restoreWildcardUpperBoundAnnotation(wt, wt.bound, other);
      }
      // Unbounded and super wildcards have an implicit upper bound on the formal type variable.
      if (wt.kind != BoundKind.EXTENDS
          && wildcardType.kind == wt.kind
          && wt.bound != null
          && wildcardType.bound != null) {
        Type.TypeVar formalTypeVariable = wt.bound;
        Type.TypeVar otherFormalTypeVariable = wildcardType.bound;
        IdentityHashMap<Type.TypeVar, Set<Type.TypeVar>> activeBounds =
            getActiveImplicitWildcardBounds();
        Set<Type.TypeVar> activeOtherBounds = activeBounds.get(formalTypeVariable);
        if (activeOtherBounds == null) {
          activeOtherBounds = Collections.newSetFromMap(new IdentityHashMap<>());
          activeBounds.put(formalTypeVariable, activeOtherBounds);
        } else if (activeOtherBounds.contains(otherFormalTypeVariable)) {
          // F-bounded type variables make the implicit upper-bound graph cyclic. Re-entering the
          // same pair cannot reveal any annotations that were not handled on the first visit.
          return wt;
        }
        activeOtherBounds.add(otherFormalTypeVariable);
        Type upperBound = formalTypeVariable.getUpperBound();
        Type updatedUpperBound;
        try {
          updatedUpperBound = visit(upperBound, otherFormalTypeVariable.getUpperBound());
        } finally {
          activeOtherBounds.remove(otherFormalTypeVariable);
          if (activeOtherBounds.isEmpty()) {
            activeBounds.remove(formalTypeVariable);
          }
        }
        if (updatedUpperBound != upperBound) {
          wt = replaceImplicitWildcardUpperBound(wt, formalTypeVariable, updatedUpperBound);
        }
        // return here for unbounded wildcards.  For lower-bounded wildcards we need to fall through
        // to restore annotations to the lower bound
        if (wt.kind == BoundKind.UNBOUND) {
          return wt;
        }
      }
      Type t = wt.type;
      if (t != null) {
        t = visit(t, wildcardType.type);
      }
      if (t == wt.type) {
        return wt;
      } else {
        Type.WildcardType restored = TYPE_METADATA_BUILDER.createWildcardType(wt, t);
        restored.bound = wt.bound;
        return restored;
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
        if (t.wildcard.kind == BoundKind.EXTENDS && otherWildcard.kind == BoundKind.UNBOUND) {
          // Substitution can turn the capture's backing wildcard into an explicit extends
          // wildcard while the corresponding declared wildcard remains unbounded. Its `type`
          // field is just an Object placeholder; annotations must be restored from the implicit
          // upper bound of its formal type variable instead.
          Type.TypeVar formalTypeVariable = otherWildcard.bound;
          if (formalTypeVariable == null) {
            return updated;
          }
          Type updatedBound = t.wildcard.type.accept(this, formalTypeVariable.getUpperBound());
          if (updatedBound == t.wildcard.type) {
            return updated;
          }
          updatedWildcard = TYPE_METADATA_BUILDER.createWildcardType(t.wildcard, updatedBound);
        } else {
          updatedWildcard = (Type.WildcardType) t.wildcard.accept(this, otherWildcard);
        }
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

    /** Returns a synthetic root nullness annotation, including structural intersection roots. */
    private Attribute.@Nullable TypeCompound getDirectSyntheticNullnessAnnotation(Type type) {
      if (type instanceof Type.IntersectionClassType intersection) {
        boolean nullable = hasNullableRoot(intersection, config);
        for (Type component : intersection.getExplicitComponents()) {
          if (hasNullableRoot(component, config) == nullable) {
            Attribute.TypeCompound annotation = getDirectSyntheticNullnessAnnotation(component);
            if (annotation != null) {
              return annotation;
            }
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
     * on a type {@code other}.
     *
     * @param t the type to update
     * @param other the type to update from
     * @return the updated type, or {@code t} if no updates were made
     */
    private Type updateDirectNullabilityAnnotationsForType(Type t, Type other) {
      if (t instanceof Type.IntersectionClassType || other instanceof Type.IntersectionClassType) {
        return copyDirectNullabilityAnnotations(other, t, config);
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

  /**
   * Replaces root annotations without changing a type's Java shape. Intersection root annotations
   * are represented on all detached components, never on a metadata clone of the intersection.
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
    Verify.verify(hasNullableRoot(argumentType, config));
    return withoutNullableRootAnnotations(argumentType, config);
  }

  /**
   * Removes nullable root annotations if present, preserving structure and unrelated annotations.
   */
  private static Type withoutNullableRootAnnotations(Type type, Config config) {
    if (type instanceof Type.IntersectionClassType intersection) {
      ListBuffer<Type> components = new ListBuffer<>();
      boolean changed = false;
      for (Type component : intersection.getComponents()) {
        Type updated = withoutNullableRootAnnotations(component, config);
        components.append(updated);
        changed |= updated != component;
      }
      return changed ? rebuildIntersection(intersection, components.toList()) : type;
    }
    ListBuffer<Attribute.TypeCompound> annotations = new ListBuffer<>();
    boolean changed = false;
    for (Attribute.TypeCompound annotation : type.getAnnotationMirrors()) {
      if (annotation.type.tsym != null
          && Nullness.isNullableAnnotation(
              annotation.type.tsym.getQualifiedName().toString(), config)) {
        changed = true;
      } else {
        annotations.append(annotation);
      }
    }
    return changed
        ? TYPE_METADATA_BUILDER.cloneTypeWithMetadata(
            type, TYPE_METADATA_BUILDER.create(annotations.toList()))
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
    if (t instanceof Type.IntersectionClassType intersection) {
      ListBuffer<Type> components = new ListBuffer<>();
      boolean changed = false;
      for (Type component : intersection.getComponents()) {
        Type substituted = subst(types, component, from, to, config);
        components.append(substituted);
        changed |= substituted != component;
      }
      return changed ? rebuildIntersection(intersection, components.toList()) : t;
    }
    Type substResult = types.subst(t, from, to);
    return restoreExplicitNullabilityAnnotations(t, substResult, config);
  }
}
