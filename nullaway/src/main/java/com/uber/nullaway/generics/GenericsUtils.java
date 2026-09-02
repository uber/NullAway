package com.uber.nullaway.generics;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.common.base.Verify;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
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
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeVariable;
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
    WildcardType wildcardType = asWildcard(typeArg);
    return wildcardType == null
        ? typeArg
        : wildcardUpperBound(wildcardType, state, config, handler);
  }

  /**
   * Returns the effective upper bound of a wildcard, using the corresponding type variable's upper
   * bound for unbounded wildcards and {@code super} wildcards.
   */
  static Type wildcardUpperBound(
      WildcardType wildcardType, VisitorState state, Config config, Handler handler) {
    return wildcardUpperBound(wildcardType, wildcardType.bound, state, config, handler);
  }

  /**
   * Returns the effective upper bound of a wildcard, using {@code correspondingTypeVariable} when
   * javac has not stored one on the wildcard itself.
   *
   * <p>Before JDK 23, javac does not associate wildcard type arguments read from classfiles with
   * their corresponding formal type variables. The {@code correspondingTypeVariable} parameter
   * allows the caller to provide that information, when available (see <a
   * href="https://github.com/uber/NullAway/issues/1732">#1732</a>).
   */
  static Type wildcardUpperBound(
      WildcardType wildcardType,
      Type.@Nullable TypeVar correspondingTypeVariable,
      VisitorState state,
      Config config,
      Handler handler) {
    Type upperBound;
    if (wildcardType.kind == BoundKind.EXTENDS) {
      upperBound = wildcardType.getExtendsBound();
    } else {
      // We have an unbound wildcard or a wildcard with just a lower bound.  In such cases, if
      // present, we use the upper bound of the formal type variable to which the wildcard is being
      // passed (confusingly stored in the `bound` field).  E.g., if we have class Foo<T extends
      // @Nullable Object>, and then see Foo<? super String>, we use @Nullable Object as the upper
      // bound.  If not present, default to Object.
      Type.TypeVar formalTypeVar =
          wildcardType.bound != null ? wildcardType.bound : correspondingTypeVariable;
      upperBound =
          formalTypeVar == null
              ? Symtab.instance(state.context).objectType
              : formalTypeVar.getUpperBound();
      // check if the upper bound should be treated as @Nullable, e.g., due to a library model or a
      // type variable in @NullUnmarked code
      if (formalTypeVar != null
          && upperBoundIsNullable(formalTypeVar.asElement(), config, handler, state)
          && !Nullness.hasNullableAnnotation(upperBound.getAnnotationMirrors().stream(), config)) {
        upperBound =
            TypeSubstitutionUtils.typeWithAnnot(
                upperBound, GenericsChecks.getSyntheticNullableAnnotType(state));
      }
    }
    if (upperBound instanceof WildcardType nestedWildcard) {
      return wildcardUpperBound(nestedWildcard, state, config, handler);
    }
    if (upperBound instanceof CapturedType capturedType && capturedType.wildcard != null) {
      return wildcardUpperBound(capturedType.wildcard, state, config, handler);
    }
    return upperBound;
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
    // First, check if library model overrides the upper bound nullability.
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
    Type upperBound = (Type) ((TypeVariable) typeVarElement.asType()).getUpperBound();
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

  private static boolean fromUnannotatedMethodOrClass(
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
    WildcardType wildcardType = asWildcard(typeArgument);
    if (wildcardType == null) {
      return typeArgument;
    }
    if (wildcardType.kind == BoundKind.SUPER) {
      return castToNonNull(wildcardType.getSuperBound());
    }
    return wildcardUpperBound(wildcardType, state, config, handler);
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
    Type qualifierType = null;
    if (!referencedMethod.isStatic()) {
      ExpressionTree qualifierExpression = memberReferenceTree.getQualifierExpression();
      qualifierType =
          genericsChecks.getTreeType(
              qualifierExpression,
              state.withPath(new TreePath(state.getPath(), qualifierExpression)));
    }

    // Get the type of the corresponding functional interface method as a member of targetType.
    Symbol.MethodSymbol fiMethod =
        NullabilityUtil.getFunctionalInterfaceMethod(memberReferenceTree, types);
    Type.MethodType fiMethodTypeAsMember =
        TypeSubstitutionUtils.memberType(types, targetType, fiMethod, genericsChecks.getConfig())
            .asMethodType();
    com.sun.tools.javac.util.List<Type> fiParamTypes = fiMethodTypeAsMember.getParameterTypes();
    boolean unbound = ((JCTree.JCMemberReference) memberReferenceTree).kind.isUnbound();
    if (unbound) {
      Verify.verify(
          !fiParamTypes.isEmpty(),
          "Expected receiver parameter for unbound method ref %s",
          memberReferenceTree);
      if (qualifierType instanceof ClassType qualifierClassType) {
        qualifierType =
            instantiateUnboundQualifierType(
                qualifierClassType, fiParamTypes.get(0), types, genericsChecks.getConfig());
      }
    }

    Type.MethodType referencedMethodType =
        genericsChecks.getMemberReferenceMethodType(
            memberReferenceTree, referencedMethod, unbound ? qualifierType : null, state);
    if (referencedMethodType == null) {
      return;
    }

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
  private static Type instantiateUnboundQualifierType(
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
