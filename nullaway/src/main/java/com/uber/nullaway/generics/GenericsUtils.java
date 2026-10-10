package com.uber.nullaway.generics;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.common.base.Verify;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.TargetType;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.CapturedType;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.code.Type.WildcardType;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.uber.nullaway.CodeAnnotationInfo;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullabilityUtil;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.handlers.Handler;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeKind;
import org.jspecify.annotations.Nullable;

/** Utility methods for doing generics-related checking */
public class GenericsUtils {

  /** only static methods */
  private GenericsUtils() {}

  enum MethodRefTypeRelationKind {
    RETURN,
    PARAMETER
  }

  /**
   * Returns the effective upper bound of {@code typeArg}. For concrete type arguments, returns the
   * type itself. For wildcards and captured wildcards, returns the wildcard's upper bound,
   * recursing through nested wildcards and captures produced by javac.
   */
  static Type effectiveWildcardUpperBound(
      Type typeArg, VisitorState state, Config config, Handler handler) {
    if (typeArg instanceof CapturedType capturedType) {
      Type.TypeVar formalTypeVariable =
          capturedType.wildcard == null ? null : capturedType.wildcard.bound;
      return resolveEffectiveUpperBound(
          capturedType, formalTypeVariable, Map.of(), state, config, handler);
    }
    if (typeArg instanceof WildcardType wildcardType) {
      return wildcardUpperBound(wildcardType, state, config, handler);
    }
    return typeArg;
  }

  /**
   * Returns the effective upper bound of a wildcard, using the corresponding type variable's upper
   * bound for unbounded wildcards and {@code super} wildcards.
   */
  static Type wildcardUpperBound(
      WildcardType wildcardType, VisitorState state, Config config, Handler handler) {
    return resolveEffectiveUpperBound(
        wildcardType, wildcardType.bound, Map.of(), state, config, handler);
  }

  /**
   * Returns the effective upper bound of each type argument of {@code classType}.
   *
   * <p>For a non-wildcard argument, the effective upper bound is the argument itself. For a
   * wildcard with an implicit upper bound, this method uses capture conversion of the complete
   * containing type to recover the contextual bound. Existing captures likewise use their
   * structural upper bounds, rather than relying on {@link WildcardType#bound}, which javac can
   * mutate while computing an unrelated supertype.
   *
   * @param classType the parameterized type whose arguments are being inspected
   * @param state visitor state
   * @param config NullAway configuration
   * @param handler NullAway extension handler
   * @return effective upper bounds aligned with {@link ClassType#getTypeArguments()}
   */
  static java.util.List<Type> effectiveUpperBoundsForTypeArguments(
      ClassType classType, VisitorState state, Config config, Handler handler) {
    List<Type> typeArguments = classType.getTypeArguments();
    List<Type> correspondingTypeVariables = classType.tsym.type.getTypeArguments();
    Verify.verify(
        typeArguments.size() == correspondingTypeVariables.size(),
        "type argument count does not match declaration for %s",
        classType);
    boolean hasWildcardOrCapture = false;
    boolean hasDirectImplicitWildcard = false;
    for (Type typeArgument : typeArguments) {
      if (typeArgument instanceof CapturedType) {
        hasWildcardOrCapture = true;
      } else if (typeArgument instanceof WildcardType wildcardType) {
        hasWildcardOrCapture = true;
        if (wildcardType.kind != BoundKind.EXTENDS) {
          hasDirectImplicitWildcard = true;
        }
      }
    }
    if (!hasWildcardOrCapture) {
      return typeArguments;
    }
    List<Type> capturedTypeArguments =
        hasDirectImplicitWildcard
            ? state.getTypes().capture(classType).getTypeArguments()
            : typeArguments;
    Verify.verify(
        typeArguments.size() == capturedTypeArguments.size(),
        "capture conversion changed type argument count for %s",
        classType);
    IdentityHashMap<Type, Type.TypeVar> captureToFormalTypeVar = new IdentityHashMap<>();
    for (int i = 0; i < typeArguments.size(); i++) {
      Type typeArgument = typeArguments.get(i);
      if (typeArgument instanceof CapturedType || typeArgument instanceof WildcardType) {
        captureToFormalTypeVar.put(
            capturedTypeArguments.get(i), (Type.TypeVar) correspondingTypeVariables.get(i));
      }
    }

    java.util.List<Type> effectiveUpperBounds = new ArrayList<>(typeArguments.size());
    for (int i = 0; i < typeArguments.size(); i++) {
      Type typeArgument = typeArguments.get(i);
      Type.TypeVar correspondingTypeVariable = (Type.TypeVar) correspondingTypeVariables.get(i);
      Type upperBoundSource;
      if (typeArgument instanceof CapturedType capturedType) {
        // Use the capture's structural bound. Its backing wildcard can have a stale declaration
        // formal in its mutable bound field after javac inspects an unrelated supertype.
        upperBoundSource = capturedType;
      } else if (typeArgument instanceof WildcardType wildcardType) {
        if (wildcardType.kind == BoundKind.EXTENDS) {
          // An explicit extends wildcard stores its upper bound directly.
          upperBoundSource = wildcardType;
        } else {
          // Derive an implicit upper bound by capture-converting the complete containing type so
          // substitutions in dependent formal bounds are included.
          upperBoundSource = capturedTypeArguments.get(i);
        }
      } else {
        // A concrete type argument is its own upper bound.
        effectiveUpperBounds.add(typeArgument);
        continue;
      }
      effectiveUpperBounds.add(
          resolveEffectiveUpperBound(
              upperBoundSource,
              correspondingTypeVariable,
              captureToFormalTypeVar,
              state,
              config,
              handler));
    }
    return effectiveUpperBounds;
  }

  /**
   * Resolves the effective upper bound of a wildcard, capture, or capture-conversion result.
   *
   * <p>Existing captures are resolved from their structural upper bounds. Explicit annotations on
   * an {@code extends} bound are restored before following dependent captures. For an implicit
   * bound, annotations on the capture and its structural bound are handled first. Nullability from
   * the corresponding declaration formal is consulted only when its declared upper bound has the
   * same underlying Java type as the compiler-computed structural bound, including a recursive
   * class bound whose nested type arguments were changed by capture conversion. If capture
   * substitution replaced that declared bound with a different type, the formal no longer supplies
   * nullability for the result.
   *
   * @param type the wildcard, capture, or capture-conversion result to resolve
   * @param formalTypeVariable the corresponding declaration formal, or {@code null} when none is
   *     available
   * @param captureToFormalTypeVar declaration formals corresponding to captures in the containing
   *     type
   * @param state visitor state
   * @param config NullAway configuration
   * @param handler NullAway extension handler
   * @return the resolved effective upper bound
   */
  private static Type resolveEffectiveUpperBound(
      Type type,
      Type.@Nullable TypeVar formalTypeVariable,
      Map<Type, Type.TypeVar> captureToFormalTypeVar,
      VisitorState state,
      Config config,
      Handler handler) {
    Type upperBound;
    if (type instanceof CapturedType capturedType) {
      upperBound = capturedType.getUpperBound();
      if (capturedType.wildcard != null && capturedType.wildcard.kind == BoundKind.EXTENDS) {
        // Explicit annotations can be restored onto the backing extends bound without also being
        // copied to javac's structural bound. The extends bound itself is stable; only the implicit
        // formal stored in WildcardType.bound is mutable.
        upperBound =
            TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
                capturedType.wildcard.getExtendsBound(), upperBound, config);
      } else {
        // An explicit annotation on the capture is a use-site projection and takes precedence over
        // both the capture's structural bound and declaration-level fallback information.
        boolean captureHasDirectNullnessAnnotation = hasNullnessAnnotation(capturedType, config);
        if (captureHasDirectNullnessAnnotation) {
          upperBound =
              TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
                  capturedType, upperBound, config);
        }
        if (formalTypeVariable == null && capturedType.wildcard != null) {
          // Without a containing-type formal, the backing formal is the only available source of
          // declaration-level nullability.
          formalTypeVariable = capturedType.wildcard.bound;
        }
        if (formalTypeVariable != null && !captureHasDirectNullnessAnnotation) {
          upperBound =
              applyDeclarationUpperBoundNullabilityIfApplicable(
                  upperBound, formalTypeVariable, state, config, handler);
        }
      }
    } else if (type instanceof WildcardType wildcardType) {
      if (wildcardType.kind == BoundKind.EXTENDS) {
        upperBound = wildcardType.getExtendsBound();
      } else {
        // For an unbounded or lower-bounded wildcard, use the upper bound of the corresponding
        // declaration formal when available, and otherwise default to Object.
        formalTypeVariable = wildcardType.bound != null ? wildcardType.bound : formalTypeVariable;
        if (formalTypeVariable != null) {
          upperBound =
              applyDeclarationUpperBoundNullabilityIfApplicable(
                  formalTypeVariable.getUpperBound(), formalTypeVariable, state, config, handler);
        } else {
          upperBound = Symtab.instance(state.context).objectType;
        }
      }
    } else {
      // neither a wildcard nor a captured type; just return it
      return type;
    }
    // if the upper bound is either a capture or a wildcard, recurse
    if (upperBound instanceof CapturedType capturedUpperBound) {
      // Captures nested inside an explicit wildcard are not entries in the contextual map. Keep
      // the current formal in that case; older javac versions may also leave the capture's backing
      // wildcard without a formal, so it cannot recover the declaration bound itself.
      Type.TypeVar capturedFormalTypeVariable = captureToFormalTypeVar.get(capturedUpperBound);
      return resolveEffectiveUpperBound(
          capturedUpperBound,
          capturedFormalTypeVariable != null ? capturedFormalTypeVariable : formalTypeVariable,
          captureToFormalTypeVar,
          state,
          config,
          handler);
    }
    if (upperBound instanceof WildcardType wildcardUpperBound) {
      return resolveEffectiveUpperBound(
          wildcardUpperBound, formalTypeVariable, captureToFormalTypeVar, state, config, handler);
    }
    return upperBound;
  }

  /**
   * Applies a declaration formal's upper-bound nullability when that formal still describes the
   * computed bound. An explicit non-null declaration bound may narrow the computed bound. Nullable
   * declaration annotations and defaults are applied only when the computed bound has no nullness
   * annotation, since they permit nullable instantiations but do not widen a known non-null
   * capture.
   *
   * @param upperBound the compiler-computed upper bound
   * @param formalTypeVar the formal type variable supplying the implicit bound
   * @param state visitor state
   * @param config NullAway configuration
   * @param handler NullAway extension handler
   * @return the upper bound with applicable declaration-level nullability applied
   */
  private static Type applyDeclarationUpperBoundNullabilityIfApplicable(
      Type upperBound,
      Type.TypeVar formalTypeVar,
      VisitorState state,
      Config config,
      Handler handler) {
    Type declaredUpperBound = formalTypeVar.getUpperBound();
    // A dependent bound such as U extends T can be replaced entirely by capture conversion, in
    // which case the substituted type supplies its own nullability. A recursive class bound such as
    // S extends Self<S> retains its top-level class while only its nested type arguments change, so
    // it still gets nullability from S's declaration.
    if (!state.getTypes().isSameType(upperBound, declaredUpperBound)
        && (!(upperBound instanceof ClassType)
            || !(declaredUpperBound instanceof ClassType)
            || !upperBound.tsym.equals(declaredUpperBound.tsym))) {
      return upperBound;
    }
    Type declarationUpperBound = formalTypeVar.getUpperBound();
    boolean upperBoundHasExplicitNullnessAnnotation = hasNullnessAnnotation(upperBound, config);
    // An explicit @NonNull declaration bound narrows the computed bound. A nullable declaration
    // bound only permits nullable instantiations, so it is fallback information and must not widen
    // an already-qualified computed bound.
    if (Nullness.hasNonNullAnnotation(declarationUpperBound.getAnnotationMirrors().stream(), config)
        || !upperBoundHasExplicitNullnessAnnotation) {
      upperBound =
          TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
              declarationUpperBound, upperBound, config);
      upperBoundHasExplicitNullnessAnnotation = hasNullnessAnnotation(upperBound, config);
    }
    if (upperBoundIsNullable(formalTypeVar.asElement(), config, handler, state)
        && !upperBoundHasExplicitNullnessAnnotation) {
      return TypeSubstitutionUtils.typeWithAnnot(
          upperBound, GenericsChecks.getSyntheticNullableAnnotType(state));
    }
    return upperBound;
  }

  static boolean hasNullnessAnnotation(Type type, Config config) {
    return Nullness.hasNonNullAnnotation(type.getAnnotationMirrors().stream(), config)
        || Nullness.hasNullableAnnotation(type.getAnnotationMirrors().stream(), config);
  }

  /**
   * Returns true if the upper bound of the given type variable should be treated as nullable.
   *
   * <p>A bound is nullable when the enclosing method or class comes from unannotated code, when a
   * library model overrides the bound nullability for the type variable, or when the declared upper
   * bound has an explicit {@code @Nullable} annotation. An explicit {@code @NonNull} annotation on
   * a type-variable bound takes precedence over nullability inherited from that type variable's
   * upper bound.
   */
  static boolean upperBoundIsNullable(
      Element typeVarElement, Config config, Handler handler, VisitorState state) {
    if (fromUnannotatedMethodOrClass(typeVarElement, config, handler, state)) {
      return true;
    }
    if (libraryModelMakesBoundNullable(typeVarElement, handler, state)) {
      return true;
    }
    Type upperBound = declaredUpperBound((Type.TypeVar) typeVarElement.asType(), state);
    if (Nullness.hasNullableAnnotation(upperBound.getAnnotationMirrors().stream(), config)) {
      return true;
    }
    if (Nullness.hasNonNullAnnotation(upperBound.getAnnotationMirrors().stream(), config)) {
      return false;
    }
    if (upperBound.getKind() == TypeKind.TYPEVAR) {
      return upperBoundIsNullable(upperBound.asElement(), config, handler, state);
    }
    return false;
  }

  /**
   * Returns true if the upper bound of the given type variable is explicitly nullable: the declared
   * bound is annotated {@code @Nullable}, a library model overrides it, or the declared bound is
   * another type variable, carrying no nullness annotation, whose bound is explicitly nullable.
   * Unlike {@link #upperBoundIsNullable}, a type variable declared in unannotated code gets no
   * nullable default, while a {@code @Nullable} written there still counts, as JSpecify gives
   * annotations in null-unmarked code their meaning.
   */
  static boolean boundIsExplicitlyNullable(
      Element typeVarElement, Config config, Handler handler, VisitorState state) {
    Type upperBound = declaredUpperBound((Type.TypeVar) typeVarElement.asType(), state);
    if (Nullness.hasNullableAnnotation(upperBound.getAnnotationMirrors().stream(), config)
        || libraryModelMakesBoundNullable(typeVarElement, handler, state)) {
      return true;
    }
    return !hasNullnessAnnotation(upperBound, config)
        && upperBound.getKind() == TypeKind.TYPEVAR
        && boundIsExplicitlyNullable(upperBound.asElement(), config, handler, state);
  }

  /**
   * Returns the upper bound declared for {@code typeVar}, with the type-use annotations written on
   * it.
   *
   * <p>{@link Type.TypeVar#getUpperBound()} is wrong for a type variable of a constructor once an
   * anonymous class calls that constructor. javac builds the anonymous class's constructor from
   * type-parameter trees that share the superclass constructor's type variables but carry no
   * annotations, and attributing them overwrites the bound of each shared variable. This method
   * reads the bound of a constructor declared in a source file of the current compilation from the
   * declaration tree, and restores the top-level annotations of each bound of a constructor loaded
   * from a class file from the constructor's type annotations; nested annotations of the latter
   * stay lost.
   */
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"}) // deliberate reference equality check
  static Type declaredUpperBound(Type.TypeVar typeVar, VisitorState state) {
    Type upperBound = typeVar.getUpperBound();
    // A copy made by substitution, such as U in <U extends E> for new Foo<String>(...), has a
    // bound of its own that the declaration does not show.
    if (typeVar != typeVar.tsym.type
        || !(typeVar.tsym.owner instanceof Symbol.MethodSymbol owner)
        || !owner.isConstructor()) {
      return upperBound;
    }
    JCTree.JCMethodDecl decl = JavacTrees.instance(state.context).getTree(owner);
    return decl != null
        ? upperBoundFromTree(decl, typeVar, upperBound, state)
        : upperBoundFromTypeAttributes(owner, typeVar, upperBound, state);
  }

  /**
   * Returns the upper bound that {@code decl} declares for {@code typeVar}, or {@code upperBound}
   * where the tree carries none.
   */
  private static Type upperBoundFromTree(
      JCTree.JCMethodDecl decl, Type.TypeVar typeVar, Type upperBound, VisitorState state) {
    for (JCTree.JCTypeParameter typeParameter : decl.typarams) {
      if (typeParameter.type == null || !typeParameter.type.tsym.equals(typeVar.tsym)) {
        continue;
      }
      List<JCTree.JCExpression> bounds = typeParameter.bounds;
      if (bounds.isEmpty() || bounds.stream().anyMatch(bound -> bound.type == null)) {
        return upperBound;
      }
      return bounds.size() == 1
          ? bounds.head.type
          : state.getTypes().makeIntersectionType(bounds.map(bound -> bound.type));
    }
    return upperBound;
  }

  /**
   * Returns {@code upperBound} with the top-level type annotations that {@code constructor} records
   * for each bound of {@code typeVar} put back on the bounds that carry none.
   */
  private static Type upperBoundFromTypeAttributes(
      Symbol.MethodSymbol constructor, Type.TypeVar typeVar, Type upperBound, VisitorState state) {
    int parameterIndex = constructor.getTypeParameters().indexOf(typeVar.tsym);
    if (parameterIndex < 0) {
      return upperBound;
    }
    List<Type> bounds =
        upperBound.isIntersection()
            ? ((Type.IntersectionClassType) upperBound).getExplicitComponents()
            : List.of(upperBound);
    // JVMS 4.7.20.1: bound_index 0 is the class bound, so interface bounds are numbered from 1
    // where the first bound is an interface.
    int boundIndex = bounds.head.isInterface() ? 1 : 0;
    ListBuffer<Type> restored = new ListBuffer<>();
    boolean changed = false;
    for (Type bound : bounds) {
      int index = boundIndex++;
      List<Attribute.TypeCompound> annotations =
          constructor.getRawTypeAttributes().stream()
              .filter(
                  a ->
                      a.position.type == TargetType.METHOD_TYPE_PARAMETER_BOUND
                          && a.position.parameter_index == parameterIndex
                          && a.position.bound_index == index
                          && a.position.location.isEmpty())
              .collect(List.collector());
      if (annotations.isEmpty() || !bound.getAnnotationMirrors().isEmpty()) {
        restored.add(bound);
      } else {
        restored.add(bound.annotatedType(annotations));
        changed = true;
      }
    }
    if (!changed) {
      return upperBound;
    }
    return restored.size() == 1
        ? restored.first()
        : state.getTypes().makeIntersectionType(restored.toList());
  }

  /** Returns true if a library model overrides the upper bound of the given type variable. */
  static boolean libraryModelMakesBoundNullable(
      Element typeVarElement, Handler handler, VisitorState state) {
    Element enclosingElement = typeVarElement.getEnclosingElement();
    if (enclosingElement instanceof Symbol.MethodSymbol methodSymbol
        && typeVarElement instanceof Symbol.TypeVariableSymbol typeVariableSymbol) {
      int typeVarIndex = methodSymbol.getTypeParameters().indexOf(typeVariableSymbol);
      // TODO typeVarIndex is -1 in some cases; see test
      //  com.uber.nullaway.jspecify.GenericMethodTests.instanceGenericMethodWithMethodRefArgument.
      //  Investigate further.
      if (typeVarIndex >= 0
          && handler.onOverrideMethodTypeVariableUpperBound(methodSymbol, typeVarIndex, state)) {
        return true;
      }
    } else if (enclosingElement instanceof Symbol.ClassSymbol classSymbol
        && typeVarElement instanceof Symbol.TypeVariableSymbol typeVariableSymbol) {
      int typeVarIndex = classSymbol.getTypeParameters().indexOf(typeVariableSymbol);
      if (typeVarIndex >= 0
          && handler.onOverrideClassTypeVariableUpperBound(classSymbol.toString(), typeVarIndex)) {
        return true;
      }
    }
    return false;
  }

  static boolean fromUnannotatedMethodOrClass(
      Element typeVarElement, Config config, Handler handler, VisitorState state) {
    Element enclosingElement = typeVarElement.getEnclosingElement();
    if (!(enclosingElement instanceof Symbol.MethodSymbol)
        && !(enclosingElement instanceof Symbol.ClassSymbol)) {
      return false;
    }
    return CodeAnnotationInfo.instance(state.context)
        .isSymbolUnannotated((Symbol) enclosingElement, config, handler);
  }

  static @Nullable WildcardType asWildcard(Type typeArg) {
    if (typeArg instanceof WildcardType wildcardType) {
      return wildcardType;
    }
    if (typeArg instanceof CapturedType capturedType) {
      return capturedType.wildcard;
    }
    return null;
  }

  /**
   * Returns a non-wildcard functional interface parameterization for lambda and method-reference
   * checking. For immediate wildcard type arguments, use the bound that determines the functional
   * interface descriptor, preserving wildcards in nested type positions.
   *
   * <p>This implements the ground target type behavior used for lambda and method-reference target
   * typing; see JLS <a
   * href="https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html#jls-15.27.3">15.27.3</a>,
   * JLS <a
   * href="https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html#jls-15.13.2">15.13.2</a>,
   * and the non-wildcard parameterization rules in JLS <a
   * href="https://docs.oracle.com/javase/specs/jls/se21/html/jls-9.html#jls-9.9">9.9</a>.
   */
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"}) // deliberate reference equality checks
  static Type groundTargetType(
      Type targetType, VisitorState state, Config config, Handler handler) {
    if (!config.handleWildcardGenerics()) {
      return targetType;
    }
    if (!(targetType instanceof ClassType classType) || targetType.isRaw()) {
      return targetType;
    }
    List<Type> typeArguments = classType.getTypeArguments();
    if (typeArguments.isEmpty()) {
      return targetType;
    }
    ListBuffer<Type> groundedTypeArguments = new ListBuffer<>();
    boolean changed = false;
    for (Type typeArgument : typeArguments) {
      Type groundedTypeArgument = groundTypeArgument(typeArgument, state, config, handler);
      groundedTypeArguments.append(groundedTypeArgument);
      changed |= groundedTypeArgument != typeArgument;
    }
    return changed
        ? TypeMetadataBuilder.TYPE_METADATA_BUILDER.createClassType(
            targetType, classType.getEnclosingType(), groundedTypeArguments.toList())
        : targetType;
  }

  /**
   * Grounds one immediate wildcard type argument according to the non-wildcard parameterization
   * rules for functional interface target types in JLS <a
   * href="https://docs.oracle.com/javase/specs/jls/se21/html/jls-9.html#jls-9.9">9.9</a>.
   */
  private static Type groundTypeArgument(
      Type typeArgument, VisitorState state, Config config, Handler handler) {
    if (typeArgument instanceof CapturedType capturedType) {
      if (capturedType.wildcard == null) {
        return typeArgument;
      }
      if (capturedType.wildcard.kind == BoundKind.SUPER) {
        return castToNonNull(capturedType.wildcard.getSuperBound());
      }
      return effectiveWildcardUpperBound(capturedType, state, config, handler);
    }
    if (typeArgument instanceof WildcardType wildcardType) {
      if (wildcardType.kind == BoundKind.SUPER) {
        return castToNonNull(wildcardType.getSuperBound());
      }
      return wildcardUpperBound(wildcardType, state, config, handler);
    }
    return typeArgument;
  }

  /**
   * Handler for method reference type relations, used by {{@link
   * #processMethodRefTypeRelations(GenericsChecks, Type, MemberReferenceTree, VisitorState,
   * MethodRefTypeRelationHandler)}}
   */
  @FunctionalInterface
  interface MethodRefTypeRelationHandler {
    void handle(Type subtype, Type supertype, MethodRefTypeRelationKind relationKind);
  }

  /**
   * Utility method to process relationships between return types and corresponding parameter types
   * for a method reference and the functional interface method it is being assigned to. Handles
   * unbound method references and varargs.
   *
   * @param genericsChecks generics checks object
   * @param targetType type to which method reference is being assigned
   * @param memberReferenceTree the method reference tree
   * @param state visitor state whose current path ends at {@code memberReferenceTree}
   * @param relationHandler handler to invoke for each type relation
   */
  @SuppressWarnings("ReferenceEquality") // deliberate reference equality check
  static void processMethodRefTypeRelations(
      GenericsChecks genericsChecks,
      Type targetType,
      MemberReferenceTree memberReferenceTree,
      VisitorState state,
      MethodRefTypeRelationHandler relationHandler) {
    Verify.verify(
        state.getPath().getLeaf() == memberReferenceTree,
        "Expected current path to end at member reference %s, but found %s",
        memberReferenceTree,
        state.getPath().getLeaf());
    if (targetType.isRaw()) {
      return;
    }
    Types types = state.getTypes();

    // First, resolve the referenced method and its qualifier type.
    Symbol.MethodSymbol referencedMethod = ASTHelpers.getSymbol(memberReferenceTree);
    if (referencedMethod == null || referencedMethod.isConstructor()) {
      // TODO handle constructor references like Foo::new;
      //  https://github.com/uber/NullAway/issues/1468
      return;
    }
    GenericsChecks.ResolvedMethodReference resolvedMethodReference =
        genericsChecks.resolveMemberReference(
            memberReferenceTree, referencedMethod, targetType, state);
    if (resolvedMethodReference == null) {
      return;
    }
    Type qualifierType = resolvedMethodReference.qualifierType();
    Type.MethodType referencedMethodType = resolvedMethodReference.methodType();

    // Get the type of the corresponding functional interface method as a member of targetType.
    Symbol.MethodSymbol fiMethod =
        NullabilityUtil.getFunctionalInterfaceMethod(memberReferenceTree, types);
    Type.MethodType fiMethodTypeAsMember =
        TypeSubstitutionUtils.memberType(types, targetType, fiMethod, genericsChecks.getConfig())
            .asMethodType();
    com.sun.tools.javac.util.List<Type> fiParamTypes = fiMethodTypeAsMember.getParameterTypes();
    boolean unbound = ((JCTree.JCMemberReference) memberReferenceTree).kind.isUnbound();

    // method reference return type <: functional interface return type
    Type fiReturnType = fiMethodTypeAsMember.getReturnType();
    Type referencedReturnType = referencedMethodType.getReturnType();
    if (fiReturnType.getKind() != TypeKind.VOID
        && referencedReturnType.getKind() != TypeKind.VOID) {
      relationHandler.handle(referencedReturnType, fiReturnType, MethodRefTypeRelationKind.RETURN);
    }

    //  i^{th} functional interface parameter type <: i^{th} method reference parameter type,
    //  aligned appropriately in the case of unbound method references
    com.sun.tools.javac.util.List<Type> referencedParamTypes =
        referencedMethodType.getParameterTypes();
    int fiStartIndex = 0;
    if (unbound) {
      if (qualifierType != null) {
        relationHandler.handle(
            fiParamTypes.get(0), qualifierType, MethodRefTypeRelationKind.PARAMETER);
      }
      fiStartIndex = 1;
    }

    // first, handle the non-varargs case
    int fiParamCount = fiParamTypes.size() - fiStartIndex;
    int nonVarargsParamCount =
        referencedMethod.isVarArgs()
            ? Math.min(fiParamCount, referencedParamTypes.size() - 1)
            : referencedParamTypes.size();
    for (int i = 0; i < nonVarargsParamCount; i++) {
      relationHandler.handle(
          fiParamTypes.get(fiStartIndex + i),
          referencedParamTypes.get(i),
          MethodRefTypeRelationKind.PARAMETER);
    }
    if (!referencedMethod.isVarArgs()) {
      return;
    }

    // For varargs references, the functional interface can map to fixed-arity form (single array
    // argument at the varargs position) or variable-arity form (zero or more element arguments).
    int varargsParamPosition = referencedParamTypes.size() - 1;
    if (fiParamCount == varargsParamPosition) {
      // No varargs arguments; this is the variable-arity case, passing zero arguments
      return;
    }
    Type varargsArrayType = referencedParamTypes.get(varargsParamPosition);
    Verify.verify(
        varargsArrayType.getKind() == TypeKind.ARRAY,
        "Expected array type for varargs parameter in %s, got %s",
        memberReferenceTree,
        varargsArrayType);
    JCTree.JCMemberReference javacMemberRef = (JCTree.JCMemberReference) memberReferenceTree;
    int firstVarargsFiParamIndex = fiStartIndex + varargsParamPosition;
    if (javacMemberRef.varargsElement == null) {
      // javac resolved this member reference using non-varargs (fixed-arity) adaptation.
      relationHandler.handle(
          fiParamTypes.get(firstVarargsFiParamIndex),
          varargsArrayType,
          MethodRefTypeRelationKind.PARAMETER);
    } else {
      // javac resolved this member reference using varargs (variable-arity) adaptation.
      // Use the element type from the referenced varargs array type
      Type varargsElementType = types.elemtype(varargsArrayType);
      for (int i = varargsParamPosition; i < fiParamCount; i++) {
        relationHandler.handle(
            fiParamTypes.get(fiStartIndex + i),
            varargsElementType,
            MethodRefTypeRelationKind.PARAMETER);
      }
    }
  }

  /**
   * Instantiates unresolved class type variables in an unbound method reference's qualifier from
   * the functional interface receiver type.
   *
   * <p>For example, javac represents the qualifier in {@code Entry::getKey} as {@code Entry<K,V>}.
   * If the functional interface receives {@code Entry<String, @Nullable String>}, this method
   * substitutes those arguments for {@code K} and {@code V}. Explicit qualifier arguments are
   * preserved because they do not contain the declaration's type-variable symbols.
   */
  static Type instantiateUnboundQualifierType(
      ClassType qualifierType, Type receiverType, Types types, Config config) {
    Symbol.ClassSymbol qualifierSymbol = (Symbol.ClassSymbol) qualifierType.tsym;
    ClassType declarationType = (ClassType) qualifierSymbol.type;
    Type receiverAsQualifier =
        TypeSubstitutionUtils.asSuper(types, receiverType, qualifierSymbol, config);
    if (!(receiverAsQualifier instanceof ClassType receiverClassType)
        || receiverAsQualifier.isRaw()
        || declarationType.allparams().size() != receiverClassType.allparams().size()) {
      return qualifierType;
    }
    return TypeSubstitutionUtils.subst(
        types, qualifierType, declarationType.allparams(), receiverClassType.allparams(), config);
  }
}
