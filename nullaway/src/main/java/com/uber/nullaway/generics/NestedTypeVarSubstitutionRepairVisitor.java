package com.uber.nullaway.generics;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.errorprone.VisitorState;
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

  private final Symbol.MethodSymbol methodSymbol;
  private final VisitorState state;
  private final Config config;
  private final Map<Symbol.TypeVariableSymbol, Type> repairedSubstitutions = new HashMap<>();

  NestedTypeVarSubstitutionRepairVisitor(
      Symbol.MethodSymbol methodSymbol, VisitorState state, Config config) {
    this.methodSymbol = methodSymbol;
    this.state = state;
    this.config = config;
  }

  Type repair(Type genericMethodType, Type actualArgType, Type callSiteType) {
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
      Type repairedTypeArg = repair(genericTypeArgs.get(i), actualTypeArgs.get(i), callSiteTypeArg);
      if (repairedTypeArg != callSiteTypeArg) {
        changed = true;
      }
      updatedTypeArgs.append(repairedTypeArg);
    }
    Type enclosingType = callSiteClassType.getEnclosingType();
    Type repairedEnclosingType =
        repair(
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
        repair(
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

  record RepairContext(Type actualArgType, Type callSiteType) {}
}
