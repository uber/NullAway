package com.uber.nullaway.generics;

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
import java.util.Objects;

/**
 * Repairs inferred substitutions for method type variables in a call-site type using nested
 * nullability annotations from the corresponding actual argument type.
 */
final class NestedTypeVarSubstitutionRepairVisitor
    extends Types.DefaultTypeVisitor<Type, NestedTypeVarSubstitutionRepairVisitor.RepairContext> {

  private final GenericsChecks genericsChecks;
  private final MethodInvocationTree invocationTree;

  /** declared method type for generic method */
  private final Type.MethodType origMethodType;

  /** method type inferred by javac at the call site */
  private final Type.MethodType methodTypeAtCallSite;

  /** symbol of the invoked generic method */
  private final Symbol.MethodSymbol methodSymbol;

  private final VisitorState state;
  private final Config config;
  private final boolean calledFromDataflow;

  /**
   * use this map to store repaired substitutions for method type variables, to ensure we use the
   * same repaired substitution for all occurrences of the same type variable
   */
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

  /**
   * repairs all parameter types at the call site, and then returns a new method type if any
   * parameter type was actually repaired. otherwise, returns {@link #methodTypeAtCallSite}.
   */
  // suppress since we want to check for a specific identical Type object to check for changes
  @SuppressWarnings("ReferenceEquality")
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
      // IMPORTANT: actualArgType is the result of getTreeType(), which will apply NullAway's own
      // reasoning about nullability of nested types, e.g., by running generic method inference at
      // nested levels of the expression.  This is how actualArgType ends up having the "ground
      // truth" information about nullability of nested types, which is used to repair the
      // javac-determined call site type.
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
    // only repair type variables on the invoked method
    if (Objects.equals(typeVar.tsym.owner, methodSymbol)) {
      return repairTypeVarSubstitution(typeVar, context.actualArgType(), context.callSiteType());
    }
    return context.callSiteType();
  }

  /**
   * when this method is called, {@code genericClassType} appears within some level a parameter type
   * for the generic method, {@code context.actualArgType()} is the (NullAway-determined) type of
   * the actual parameter at the same nesting level, and {@code context.callSiteType()} is the
   * javac-determined type for the parameter at the same nesting level.
   *
   * <p>This method recurses through the type arguments of {@code genericClassType}, invoking {@link
   * #repairType(Type, Type, Type)} passing the corresponding type arguments from the actual
   * parameter type and javac-determined call site type. If any repair occurs, returns the repaired
   * type as the new type to be used at this level. (The actual repair logic only kicks in when
   * visiting a nested type variable.)
   */
  @SuppressWarnings("ReferenceEquality")
  @Override
  public Type visitClassType(Type.ClassType genericClassType, RepairContext context) {
    if (!(context.actualArgType() instanceof Type.ClassType)
        || !(context.callSiteType() instanceof Type.ClassType callSiteClassType)) {
      return context.callSiteType();
    }
    // the actual type can be a subtype of the javac-inferred call-site type, so convert to the
    // supertype
    Type.ClassType actualClassType =
        (Type.ClassType)
            TypeSubstitutionUtils.asSuper(
                state.getTypes(),
                context.actualArgType(),
                (Symbol.ClassSymbol) callSiteClassType.tsym,
                config);
    if (actualClassType == null) {
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

  /**
   * when this method is called, {@code genericArrayType} appears within some level a parameter type
   * for the generic method, {@code context.actualArgType()} is the (NullAway-determined) type of
   * the actual parameter at the same nesting level, and {@code context.callSiteType()} is the
   * javac-determined type for the parameter at the same nesting level.
   *
   * <p>This method recurses to the component type of {@code genericArrayType}, invoking {@link
   * #repairType(Type, Type, Type)} passing the corresponding component type from the actual
   * parameter type and javac-determined call site type. If any repair occurs, returns the repaired
   * type as the new type to be used at this level. (The actual repair logic only kicks in when
   * visiting a nested type variable.)
   */
  // suppress since we want to check for a specific identical Type object to check for changes
  @SuppressWarnings("ReferenceEquality")
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

  /**
   * For a javac-determined call site type passed in the position of a type variable from the
   * generic method, update nested types in the call site type based on the corresponding nested
   * types from the actual parameter.
   *
   * @param typeVar the type variable from the generic method
   * @param actualArgType the actual parameter type passed in the type variable's position at the
   *     call site
   * @param callSiteType the type javac determined is passed in the type variable's position at the
   *     call site
   * @return updated type to use at the position in the call site, or {@code callSiteType} if no
   *     repair is needed
   */
  private Type repairTypeVarSubstitution(
      Type.TypeVar typeVar, Type actualArgType, Type callSiteType) {
    Symbol.TypeVariableSymbol typeVarSymbol = (Symbol.TypeVariableSymbol) typeVar.tsym;
    return repairedSubstitutions.computeIfAbsent(
        typeVarSymbol,
        (unused) -> {
          Type repairedSubstitution = callSiteType;
          if (!actualArgType.isRaw() && !callSiteType.isRaw()) {
            repairedSubstitution =
                repairNestedTypeVarSubstitutionFromActual(actualArgType, callSiteType);
          }
          return repairedSubstitution;
        });
  }

  /**
   * Repairs nested annotations in {@code callSiteType} using the nested types from {@code
   * actualArgType}, while preserving any direct annotations on {@code callSiteType}.
   *
   * <p>So, for class types, if {@code actualArgType} is {@code Foo<@Nullable Bar>} and {@code
   * callSiteType} is {@code @Nullable Foo<Bar>}, we return {@code @Nullable Foo<@Nullable Bar>},
   * using the top-level type from {@code callSiteType} and the type argument from {@code
   * actualArgType}.
   *
   * <p>Similarly, for array types, if {@code actualArgType} is {@code @Nullable Foo []} and {@code
   * callSiteType} is {@code Foo @Nullable []}, we return {@code @Nullable Foo @Nullable []}.
   */
  private Type repairNestedTypeVarSubstitutionFromActual(Type actualArgType, Type callSiteType) {
    // only handle cases where base types are identical for now
    if (!ASTHelpers.isSameType(actualArgType, callSiteType, state)) {
      return callSiteType;
    }
    if (actualArgType instanceof Type.ClassType actualClassType
        && callSiteType instanceof Type.ClassType callSiteClassType) {
      List<Type> actualTypeArgs = actualClassType.getTypeArguments();
      if (actualTypeArgs.isEmpty()) {
        return callSiteType;
      }
      // use call site type with type arguments from actual
      return TypeMetadataBuilder.TYPE_METADATA_BUILDER.createClassType(
          callSiteClassType, callSiteClassType.getEnclosingType(), actualTypeArgs);
    }
    if (actualArgType instanceof Type.ArrayType actualArrayType
        && callSiteType instanceof Type.ArrayType callSiteArrayType) {
      // use call site type with component type from actual
      return TypeMetadataBuilder.TYPE_METADATA_BUILDER.createArrayType(
          callSiteArrayType, actualArrayType.getComponentType());
    }
    return callSiteType;
  }

  /**
   * The two types being compared while recursively walking the declared generic method parameter
   * type. At each recursive step, the visitor uses {@code actualArgType} as the "ground truth" of
   * nested nullability annotations and applies any repair to the corresponding subtree of {@code
   * callSiteType}.
   *
   * @param actualArgType the subtree of the actual argument type aligned with the current declared
   *     generic method parameter subtree
   * @param callSiteType the subtree of javac's inferred call-site parameter type to repair
   */
  record RepairContext(Type actualArgType, Type callSiteType) {}
}
