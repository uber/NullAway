package com.uber.nullaway.generics;

import com.google.common.base.Verify;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.CapturedType;
import com.sun.tools.javac.code.Type.WildcardType;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree;
import com.uber.nullaway.NullabilityUtil;
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
  static Type effectiveWildcardUpperBound(Type typeArg, VisitorState state) {
    WildcardType wildcardType = asWildcard(typeArg);
    return wildcardType == null ? typeArg : wildcardUpperBound(wildcardType, state);
  }

  /**
   * Returns the effective upper bound of a wildcard, using the corresponding type variable's upper
   * bound for unbounded wildcards and {@code super} wildcards.
   */
  static Type wildcardUpperBound(WildcardType wildcardType, VisitorState state) {
    Type upperBound;
    if (wildcardType.kind == BoundKind.EXTENDS) {
      upperBound = wildcardType.getExtendsBound();
    } else {
      upperBound =
          wildcardType.bound == null
              ? Symtab.instance(state.context).objectType
              : wildcardType.bound.getUpperBound();
    }
    if (upperBound instanceof WildcardType nestedWildcard) {
      return wildcardUpperBound(nestedWildcard, state);
    }
    if (upperBound instanceof CapturedType capturedType && capturedType.wildcard != null) {
      return wildcardUpperBound(capturedType.wildcard, state);
    }
    return upperBound;
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
   * @param state visitor state
   * @param relationHandler handler to invoke for each type relation
   */
  static void processMethodRefTypeRelations(
      GenericsChecks genericsChecks,
      Type targetType,
      MemberReferenceTree memberReferenceTree,
      VisitorState state,
      MethodRefTypeRelationHandler relationHandler) {
    if (targetType.isRaw()) {
      return;
    }
    Types types = state.getTypes();

    // first, figure out the proper method type to use for the member reference
    Symbol.MethodSymbol referencedMethod = ASTHelpers.getSymbol(memberReferenceTree);
    if (referencedMethod == null || referencedMethod.isConstructor()) {
      // TODO handle constructor references like Foo::new;
      //  https://github.com/uber/NullAway/issues/1468
      return;
    }
    Type.MethodType referencedMethodType =
        genericsChecks.getMemberReferenceMethodType(memberReferenceTree, referencedMethod, state);
    if (referencedMethodType == null) {
      return;
    }
    Type qualifierType = null;
    if (!referencedMethod.isStatic()) {
      qualifierType =
          genericsChecks.getTreeType(memberReferenceTree.getQualifierExpression(), state);
    }

    // now, get the type of the corresponding functional interface method, as a member of targetType
    Symbol.MethodSymbol fiMethod =
        NullabilityUtil.getFunctionalInterfaceMethod(memberReferenceTree, types);
    Type.MethodType fiMethodTypeAsMember =
        TypeSubstitutionUtils.memberType(types, targetType, fiMethod, genericsChecks.getConfig())
            .asMethodType();

    // method reference return type <: functional interface return type
    Type fiReturnType = fiMethodTypeAsMember.getReturnType();
    Type referencedReturnType = referencedMethodType.getReturnType();
    if (fiReturnType.getKind() != TypeKind.VOID
        && referencedReturnType.getKind() != TypeKind.VOID) {
      relationHandler.handle(referencedReturnType, fiReturnType, MethodRefTypeRelationKind.RETURN);
    }

    //  i^{th} functional interface parameter type <: i^{th} method reference parameter type,
    //  aligned appropriately in the case of unbound method references
    com.sun.tools.javac.util.List<Type> fiParamTypes = fiMethodTypeAsMember.getParameterTypes();
    com.sun.tools.javac.util.List<Type> referencedParamTypes =
        referencedMethodType.getParameterTypes();
    int fiStartIndex = 0;
    if (((JCTree.JCMemberReference) memberReferenceTree).kind.isUnbound()) {
      Verify.verify(
          !fiParamTypes.isEmpty(),
          "Expected receiver parameter for unbound method ref %s",
          memberReferenceTree);
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
}
