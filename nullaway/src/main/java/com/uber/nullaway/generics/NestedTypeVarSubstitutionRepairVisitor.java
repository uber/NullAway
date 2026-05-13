package com.uber.nullaway.generics;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;
import static com.uber.nullaway.NullabilityUtil.pathWithLeaf;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.ListBuffer;
import com.uber.nullaway.Config;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.type.TypeKind;

/**
 * Repairs inferred substitutions for method type variables in a call-site type using nested
 * nullability annotations from the corresponding actual argument type.
 */
@SuppressWarnings("ReferenceEquality")
final class NestedTypeVarSubstitutionRepairVisitor
    extends Types.DefaultTypeVisitor<Type, NestedTypeVarSubstitutionRepairVisitor.RepairContext> {

  private final GenericsChecks genericsChecks;
  private final MethodInvocationTree invocationTree;
  private final Type.MethodType origMethodType;
  private final Type.MethodType methodTypeAtCallSite;
  private final Symbol.MethodSymbol methodSymbol;
  private final VisitorState state;
  private final Config config;
  private final boolean calledFromDataflow;
  private final Map<Symbol.TypeVariableSymbol, Type> repairedSubstitutions = new HashMap<>();

  /**
   * Repairs nested nullability annotations in the inferred call-site method type for a generic
   * method invocation. In narrow cases, javac drops or misplaces nested type-use nullability
   * annotations on type variables in its inferred type for a generic method at a call site. See <a
   * href="https://github.com/uber/NullAway/issues/1455">issue 1455</a>. This method repairs those
   * annotations based on the types of actual parameters. It does not attempt to be a very general
   * fix, as we do not fully understand the scenarios where this can arise.
   *
   * @param genericsChecks the owning generics checker, used to compute actual argument types
   * @param invocationTree the method invocation tree for the generic method call
   * @param origMethodType the declared method type for the generic method
   * @param methodTypeAtCallSite the method type inferred by javac at the call site
   * @param state the visitor state
   * @param config the NullAway configuration
   * @param calledFromDataflow true if the repair is being computed as part of dataflow analysis
   * @return a method type based on {@code methodTypeAtCallSite}, with nested nullability
   *     annotations on method type-variable substitutions restored where possible
   */
  static Type.MethodType repairMethodType(
      GenericsChecks genericsChecks,
      MethodInvocationTree invocationTree,
      Type.MethodType origMethodType,
      Type.MethodType methodTypeAtCallSite,
      VisitorState state,
      Config config,
      boolean calledFromDataflow) {
    return new NestedTypeVarSubstitutionRepairVisitor(
            genericsChecks,
            invocationTree,
            origMethodType,
            methodTypeAtCallSite,
            state,
            config,
            calledFromDataflow)
        .repairMethodTypeInternal();
  }

  private NestedTypeVarSubstitutionRepairVisitor(
      GenericsChecks genericsChecks,
      MethodInvocationTree invocationTree,
      Type.MethodType origMethodType,
      Type.MethodType methodTypeAtCallSite,
      VisitorState state,
      Config config,
      boolean calledFromDataflow) {
    this.genericsChecks = genericsChecks;
    this.invocationTree = invocationTree;
    this.origMethodType = origMethodType;
    this.methodTypeAtCallSite = methodTypeAtCallSite;
    this.methodSymbol = ASTHelpers.getSymbol(invocationTree);
    this.state = state;
    this.config = config;
    this.calledFromDataflow = calledFromDataflow;
  }

  private Type.MethodType repairMethodTypeInternal() {
    if (methodSymbol.isVarArgs()) {
      // skip handling of varargs for now
      return methodTypeAtCallSite;
    }
    com.sun.tools.javac.util.List<Type> genericMethodParamTypes =
        origMethodType.getParameterTypes();
    com.sun.tools.javac.util.List<Type> callSiteParamTypes =
        methodTypeAtCallSite.getParameterTypes();
    List<? extends ExpressionTree> actualParams = invocationTree.getArguments();
    TreePath pathToInvocation = pathWithLeaf(state.getPath(), invocationTree);
    ListBuffer<Type> updatedArgTypes = new ListBuffer<>();
    boolean changed = false;
    for (int i = 0; i < genericMethodParamTypes.size(); i++) {
      Type callSiteParamType = callSiteParamTypes.get(i);
      Type genericMethodParamType = genericMethodParamTypes.get(i);
      ExpressionTree actualParam = actualParams.get(i);
      Type actualArgType =
          genericsChecks.getTreeType(
              actualParam,
              state.withPath(pathWithLeaf(pathToInvocation, actualParam)),
              calledFromDataflow);
      if (actualArgType != null) {
        Type repairedType = repairType(genericMethodParamType, actualArgType, callSiteParamType);
        if (repairedType != callSiteParamType) {
          changed = true;
          callSiteParamType = repairedType;
        }
      }
      updatedArgTypes.append(callSiteParamType);
    }
    if (!changed) {
      return methodTypeAtCallSite;
    }
    return new Type.MethodType(
        updatedArgTypes.toList(),
        methodTypeAtCallSite.getReturnType(),
        methodTypeAtCallSite.getThrownTypes(),
        methodTypeAtCallSite.tsym);
  }

  private Type repairType(Type genericMethodType, Type actualArgType, Type callSiteType) {
    return genericMethodType.accept(this, new RepairContext(actualArgType, callSiteType));
  }

  @Override
  public Type visitTypeVar(Type.TypeVar typeVar, RepairContext context) {
    if (typeVar.tsym.owner == methodSymbol) {
      return repairTypeVarSubstitution(typeVar, context.actualArgType(), context.callSiteType());
    }
    return context.callSiteType();
  }

  @Override
  public Type visitClassType(Type.ClassType genericClassType, RepairContext context) {
    if (!(context.actualArgType() instanceof Type.ClassType)
        || !(context.callSiteType() instanceof Type.ClassType callSiteClassType)) {
      return context.callSiteType();
    }
    Type actualAsCallSiteType =
        TypeSubstitutionUtils.asSuper(
            state.getTypes(),
            context.actualArgType(),
            (Symbol.ClassSymbol) context.callSiteType().tsym,
            config);
    if (!(actualAsCallSiteType instanceof Type.ClassType actualClassType)) {
      return context.callSiteType();
    }
    List<Type> genericTypeArgs = genericClassType.getTypeArguments();
    List<Type> actualTypeArgs = actualClassType.getTypeArguments();
    List<Type> callSiteTypeArgs = callSiteClassType.getTypeArguments();
    if (genericTypeArgs.size() != actualTypeArgs.size()
        || genericTypeArgs.size() != callSiteTypeArgs.size()) {
      return context.callSiteType();
    }
    boolean changed = false;
    ListBuffer<Type> updatedTypeArgs = new ListBuffer<>();
    for (int i = 0; i < genericTypeArgs.size(); i++) {
      Type callSiteTypeArg = callSiteTypeArgs.get(i);
      Type repairedTypeArg =
          repairType(genericTypeArgs.get(i), actualTypeArgs.get(i), callSiteTypeArg);
      if (repairedTypeArg != callSiteTypeArg) {
        changed = true;
      }
      updatedTypeArgs.append(repairedTypeArg);
    }
    Type enclosingType = callSiteClassType.getEnclosingType();
    Type repairedEnclosingType =
        repairType(
            genericClassType.getEnclosingType(), actualClassType.getEnclosingType(), enclosingType);
    if (repairedEnclosingType != enclosingType) {
      changed = true;
    }
    return changed
        ? TypeMetadataBuilder.TYPE_METADATA_BUILDER.createClassType(
            callSiteClassType, repairedEnclosingType, updatedTypeArgs.toList())
        : context.callSiteType();
  }

  @Override
  public Type visitArrayType(Type.ArrayType genericArrayType, RepairContext context) {
    if (!(context.actualArgType() instanceof Type.ArrayType actualArrayType)
        || !(context.callSiteType() instanceof Type.ArrayType callSiteArrayType)) {
      return context.callSiteType();
    }
    Type callSiteElemType = callSiteArrayType.getComponentType();
    Type repairedElemType =
        repairType(
            genericArrayType.getComponentType(),
            actualArrayType.getComponentType(),
            callSiteElemType);
    return repairedElemType != callSiteElemType
        ? TypeMetadataBuilder.TYPE_METADATA_BUILDER.createArrayType(
            callSiteArrayType, repairedElemType)
        : context.callSiteType();
  }

  @Override
  public Type visitType(Type type, RepairContext context) {
    return context.callSiteType();
  }

  private Type repairTypeVarSubstitution(
      Type.TypeVar typeVar, Type actualArgType, Type callSiteType) {
    Symbol.TypeVariableSymbol typeVarSymbol = (Symbol.TypeVariableSymbol) typeVar.tsym;
    if (repairedSubstitutions.containsKey(typeVarSymbol)) {
      return castToNonNull(repairedSubstitutions.get(typeVarSymbol));
    }
    Type repairedSubstitution = callSiteType;
    if (!actualArgType.isRaw() && !callSiteType.isRaw()) {
      repairedSubstitution = repairNestedTypeVarSubstitutionFromActual(actualArgType, callSiteType);
    }
    repairedSubstitutions.put(typeVarSymbol, repairedSubstitution);
    return repairedSubstitution;
  }

  /**
   * Repairs nested annotations in {@code callSiteType} using {@code actualArgType}, while
   * preserving direct annotations on {@code callSiteType}.
   */
  private Type repairNestedTypeVarSubstitutionFromActual(Type actualArgType, Type callSiteType) {
    if (!sameErasure(actualArgType, callSiteType)) {
      return callSiteType;
    }
    if (actualArgType instanceof Type.ClassType
        && callSiteType instanceof Type.ClassType callSiteClassType) {
      Type actualAsCallSiteType =
          TypeSubstitutionUtils.asSuper(
              state.getTypes(), actualArgType, (Symbol.ClassSymbol) callSiteType.tsym, config);
      if (!(actualAsCallSiteType instanceof Type.ClassType actualClassType)) {
        return callSiteType;
      }
      List<Type> actualTypeArgs = actualClassType.getTypeArguments();
      if (actualTypeArgs.isEmpty()) {
        return callSiteType;
      }
      return TypeMetadataBuilder.TYPE_METADATA_BUILDER.createClassType(
          callSiteClassType, callSiteClassType.getEnclosingType(), actualTypeArgs);
    }
    if (actualArgType instanceof Type.ArrayType actualArrayType
        && callSiteType instanceof Type.ArrayType callSiteArrayType) {
      return TypeMetadataBuilder.TYPE_METADATA_BUILDER.createArrayType(
          callSiteArrayType, actualArrayType.getComponentType());
    }
    return callSiteType;
  }

  private boolean sameErasure(Type type1, Type type2) {
    return !type1.getKind().equals(TypeKind.NULL)
        && !type2.getKind().equals(TypeKind.NULL)
        && state
            .getTypes()
            .isSameType(state.getTypes().erasure(type1), state.getTypes().erasure(type2));
  }

  /**
   * The two types being compared while recursively walking the declared generic method parameter
   * type. At each recursive step, the visitor uses {@code actualArgType} as the source of nested
   * nullability annotations and applies any repair to the corresponding subtree of {@code
   * callSiteType}.
   *
   * @param actualArgType the subtree of the actual argument type aligned with the current declared
   *     generic method parameter subtree
   * @param callSiteType the subtree of javac's inferred call-site parameter type to repair
   */
  record RepairContext(Type actualArgType, Type callSiteType) {}
}
