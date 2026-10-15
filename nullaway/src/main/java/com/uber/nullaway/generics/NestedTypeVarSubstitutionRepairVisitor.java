package com.uber.nullaway.generics;

import static com.uber.nullaway.NullabilityUtil.pathWithLeaf;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.ListBuffer;
import com.uber.nullaway.Config;
import com.uber.nullaway.Nullness;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Repairs inferred substitutions for method type variables in a call-site type using nested
 * nullability annotations from the corresponding actual argument type.
 */
final class NestedTypeVarSubstitutionRepairVisitor
    extends Types.DefaultTypeVisitor<Type, NestedTypeVarSubstitutionRepairVisitor.RepairContext> {

  private final GenericsChecks genericsChecks;

  /** the generic method invocation or constructor call */
  private final ExpressionTree invocationTree;

  /** declared method type for generic method */
  private final Type.MethodType origMethodType;

  /** method type inferred by javac at the call site */
  private final Type.MethodType methodTypeAtCallSite;

  /** symbols of the type variables of the invoked generic method or constructor */
  private final Set<Symbol.TypeSymbol> methodTypeVariables = new HashSet<>();

  /** visitor state whose path points to {@link #invocationTree} */
  private final VisitorState state;

  private final Config config;
  private final boolean calledFromDataflow;

  /**
   * repaired substitutions for method type variables, so that later occurrences of a type variable
   * take the substitution an earlier argument repaired. Only a substitution that an argument
   * actually repaired is kept, and one whose array components are {@code @Nullable} replaces an
   * earlier one whose components are not.
   */
  private final Map<Symbol.TypeVariableSymbol, Type> repairedSubstitutions = new HashMap<>();

  /**
   * true while the return type is rebuilt from {@link #repairedSubstitutions}, where a type
   * variable takes its repaired substitution and is not repaired again
   */
  private boolean substituteRepairedOnly = false;

  /**
   * Repairs nested nullability annotations in the inferred call-site method type for a generic
   * method invocation or constructor call. In narrow cases, javac drops or misplaces nested
   * type-use nullability annotations on type variables in its inferred type for a generic method at
   * a call site. See <a href="https://github.com/uber/NullAway/issues/1455">issue 1455</a>. This
   * method repairs those annotations based on the types of actual parameters. It does not attempt
   * to be a very general fix, as we do not fully understand the scenarios where this can arise.
   *
   * @param genericsChecks the owning generics checker, used to compute actual argument types
   * @param invocationTree the method invocation or constructor call tree for the generic call
   * @param origMethodType the declared method type for the generic method
   * @param methodTypeVariables the type variables of the generic method, as they appear in {@code
   *     origMethodType}
   * @param methodTypeAtCallSite the method type inferred by javac at the call site
   * @param invocationPath the path to the invocation tree, or null if not available
   * @param state the visitor state
   * @param config the NullAway configuration
   * @param calledFromDataflow true if the repair is being computed as part of dataflow analysis
   * @return a method type based on {@code methodTypeAtCallSite}, with nested nullability
   *     annotations on method type-variable substitutions restored where possible
   */
  static Type.MethodType repairMethodType(
      GenericsChecks genericsChecks,
      ExpressionTree invocationTree,
      Type.MethodType origMethodType,
      List<Type> methodTypeVariables,
      Type.MethodType methodTypeAtCallSite,
      @Nullable TreePath invocationPath,
      VisitorState state,
      Config config,
      boolean calledFromDataflow) {
    return new NestedTypeVarSubstitutionRepairVisitor(
            genericsChecks,
            invocationTree,
            origMethodType,
            methodTypeVariables,
            methodTypeAtCallSite,
            invocationPath,
            state,
            config,
            calledFromDataflow)
        .repairMethodTypeInternal();
  }

  /**
   * Repairs nested nullability annotations in javac's instantiation of a generic method at a use
   * whose argument types are known as types rather than as trees, such as a method reference whose
   * arguments are the parameters of its functional-interface method. The return type takes the
   * substitutions repaired from the parameters.
   *
   * @param genericsChecks the owning generics checker
   * @param useTree the tree of the use, such as the method reference
   * @param origMethodType the declared method type for the generic method
   * @param methodTypeVariables the type variables of the generic method, as they appear in {@code
   *     origMethodType}
   * @param methodTypeAtUse the method type javac inferred at the use
   * @param actualParamTypes the types passed for the parameters, one for each parameter, or, in
   *     varargs form, one for each parameter before the last and one for each varargs element
   * @param varargsForm whether the use passes its last arguments as elements of the varargs
   *     parameter
   * @param state the visitor state
   * @param config the NullAway configuration
   * @return a method type based on {@code methodTypeAtUse}, with nested nullability annotations on
   *     method type-variable substitutions restored where possible
   */
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"}) // deliberate reference equality checks
  static Type.MethodType repairMethodTypeFromParameterTypes(
      GenericsChecks genericsChecks,
      ExpressionTree useTree,
      Type.MethodType origMethodType,
      List<Type> methodTypeVariables,
      Type.MethodType methodTypeAtUse,
      List<Type> actualParamTypes,
      boolean varargsForm,
      VisitorState state,
      Config config) {
    NestedTypeVarSubstitutionRepairVisitor visitor =
        new NestedTypeVarSubstitutionRepairVisitor(
            genericsChecks,
            useTree,
            origMethodType,
            methodTypeVariables,
            methodTypeAtUse,
            null,
            state,
            config,
            false);
    List<Type> genericParamTypes = origMethodType.getParameterTypes();
    List<Type> paramTypesAtUse = methodTypeAtUse.getParameterTypes();
    int lastParamIndex = genericParamTypes.size() - 1;
    if (paramTypesAtUse.size() != genericParamTypes.size()
        || (varargsForm
            ? lastParamIndex < 0
                || actualParamTypes.size() < lastParamIndex
                || !(genericParamTypes.get(lastParamIndex) instanceof Type.ArrayType)
                || !(paramTypesAtUse.get(lastParamIndex) instanceof Type.ArrayType)
            : actualParamTypes.size() != genericParamTypes.size())) {
      return methodTypeAtUse;
    }
    ListBuffer<Type> updatedParamTypes = new ListBuffer<>();
    boolean changed = false;
    for (int i = 0; i < genericParamTypes.size(); i++) {
      Type paramTypeAtUse = paramTypesAtUse.get(i);
      Type repairedType;
      if (varargsForm && i == lastParamIndex) {
        Type.ArrayType arrayTypeAtUse = (Type.ArrayType) paramTypeAtUse;
        Type elemTypeAtUse = arrayTypeAtUse.getComponentType();
        Type repairedElemType = elemTypeAtUse;
        for (int j = i; j < actualParamTypes.size(); j++) {
          repairedElemType =
              visitor.repairType(
                  ((Type.ArrayType) genericParamTypes.get(i)).getComponentType(),
                  actualParamTypes.get(j),
                  repairedElemType);
        }
        repairedType =
            repairedElemType != elemTypeAtUse
                ? TypeMetadataBuilder.TYPE_METADATA_BUILDER.createArrayType(
                    arrayTypeAtUse, repairedElemType)
                : paramTypeAtUse;
      } else {
        repairedType =
            visitor.repairType(genericParamTypes.get(i), actualParamTypes.get(i), paramTypeAtUse);
      }
      changed |= repairedType != paramTypeAtUse;
      updatedParamTypes.append(repairedType);
    }
    Type returnTypeAtUse = methodTypeAtUse.getReturnType();
    Type repairedReturnType = visitor.repairReturnType(origMethodType, returnTypeAtUse);
    changed |= repairedReturnType != returnTypeAtUse;
    return changed
        ? new Type.MethodType(
            updatedParamTypes.toList(),
            repairedReturnType,
            methodTypeAtUse.getThrownTypes(),
            methodTypeAtUse.tsym)
        : methodTypeAtUse;
  }

  private NestedTypeVarSubstitutionRepairVisitor(
      GenericsChecks genericsChecks,
      ExpressionTree invocationTree,
      Type.MethodType origMethodType,
      List<Type> methodTypeVariables,
      Type.MethodType methodTypeAtCallSite,
      @Nullable TreePath invocationPath,
      VisitorState state,
      Config config,
      boolean calledFromDataflow) {
    this.genericsChecks = genericsChecks;
    this.invocationTree = invocationTree;
    this.origMethodType = origMethodType;
    this.methodTypeAtCallSite = methodTypeAtCallSite;
    for (Type typeVariable : methodTypeVariables) {
      this.methodTypeVariables.add(typeVariable.tsym);
    }
    this.state =
        state.withPath(
            pathWithLeaf(
                invocationPath != null ? invocationPath : state.getPath(), invocationTree));
    this.config = config;
    this.calledFromDataflow = calledFromDataflow;
  }

  /**
   * repairs all parameter types at the call site, and the return type from the substitutions
   * repaired for them, and then returns a new method type if any type was actually repaired.
   * otherwise, returns {@link #methodTypeAtCallSite}.
   */
  // suppress since we want to check for a specific identical Type object to check for changes
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"}) // deliberate reference equality checks
  private Type.MethodType repairMethodTypeInternal() {
    com.sun.tools.javac.util.List<Type> genericMethodParamTypes =
        origMethodType.getParameterTypes();
    com.sun.tools.javac.util.List<Type> callSiteParamTypes =
        methodTypeAtCallSite.getParameterTypes();
    List<? extends ExpressionTree> actualParams;
    Type varargsElement;
    if (invocationTree instanceof JCTree.JCMethodInvocation methodInvocation) {
      actualParams = methodInvocation.getArguments();
      varargsElement = methodInvocation.varargsElement;
    } else {
      JCTree.JCNewClass newClass = (JCTree.JCNewClass) invocationTree;
      actualParams = newClass.getArguments();
      varargsElement = newClass.varargsElement;
    }
    // in a call in varargs form, each argument from the last parameter's position on is an element
    // of that parameter's array
    int lastParamIndex = genericMethodParamTypes.size() - 1;
    boolean varargsForm =
        varargsElement != null
            && lastParamIndex >= 0
            && genericMethodParamTypes.get(lastParamIndex) instanceof Type.ArrayType
            && callSiteParamTypes.get(lastParamIndex) instanceof Type.ArrayType;
    ListBuffer<Type> updatedArgTypes = new ListBuffer<>();
    boolean changed = false;
    for (int i = 0; i < genericMethodParamTypes.size(); i++) {
      Type callSiteParamType = callSiteParamTypes.get(i);
      Type genericMethodParamType = genericMethodParamTypes.get(i);
      Type repairedType;
      if (varargsForm && i == lastParamIndex) {
        Type.ArrayType callSiteArrayType = (Type.ArrayType) callSiteParamType;
        Type callSiteElemType = callSiteArrayType.getComponentType();
        Type repairedElemType = callSiteElemType;
        for (int j = i; j < actualParams.size(); j++) {
          repairedElemType =
              repairActual(
                  ((Type.ArrayType) genericMethodParamType).getComponentType(),
                  actualParams.get(j),
                  repairedElemType);
        }
        repairedType =
            repairedElemType != callSiteElemType
                ? TypeMetadataBuilder.TYPE_METADATA_BUILDER.createArrayType(
                    callSiteArrayType, repairedElemType)
                : callSiteParamType;
      } else {
        repairedType = repairActual(genericMethodParamType, actualParams.get(i), callSiteParamType);
      }
      if (repairedType != callSiteParamType) {
        changed = true;
      }
      updatedArgTypes.append(repairedType);
    }
    Type returnTypeAtCallSite = methodTypeAtCallSite.getReturnType();
    Type repairedReturnType = repairReturnType(origMethodType, returnTypeAtCallSite);
    changed |= repairedReturnType != returnTypeAtCallSite;
    if (!changed) {
      return methodTypeAtCallSite;
    }
    return new Type.MethodType(
        updatedArgTypes.toList(),
        repairedReturnType,
        methodTypeAtCallSite.getThrownTypes(),
        methodTypeAtCallSite.tsym);
  }

  /**
   * Rebuilds the return type at the use with the substitutions repaired for the parameters, so that
   * the result {@code U} of {@code <U> U id(U u)} in the repaired method type carries the nested
   * nullability its argument does. Must be called after the parameters are repaired.
   */
  private Type repairReturnType(Type.MethodType origMethodType, Type returnTypeAtCallSite) {
    substituteRepairedOnly = true;
    try {
      return repairType(origMethodType.getReturnType(), returnTypeAtCallSite, returnTypeAtCallSite);
    } finally {
      substituteRepairedOnly = false;
    }
  }

  /**
   * Repairs {@code callSiteType}, the javac-determined type of a parameter or of a varargs element,
   * from the type NullAway determines for {@code actualParam}. Returns {@code callSiteType} where
   * NullAway determines no type for the argument.
   */
  private Type repairActual(Type genericMethodType, ExpressionTree actualParam, Type callSiteType) {
    // IMPORTANT: actualArgType is the result of getTreeType(), which will apply NullAway's own
    // reasoning about nullability of nested types, e.g., by running generic method inference at
    // nested levels of the expression.  This is how actualArgType ends up having the "ground
    // truth" information about nullability of nested types, which is used to repair the
    // javac-determined call site type.
    Type actualArgType =
        genericsChecks.getTreeType(
            actualParam,
            state.withPath(pathWithLeaf(state.getPath(), actualParam)),
            calledFromDataflow);
    return actualArgType == null
        ? callSiteType
        : repairType(genericMethodType, actualArgType, callSiteType);
  }

  private Type repairType(Type genericMethodType, Type actualArgType, Type callSiteType) {
    return genericMethodType.accept(this, new RepairContext(actualArgType, callSiteType));
  }

  @Override
  public Type visitTypeVar(Type.TypeVar typeVar, RepairContext context) {
    // only repair type variables on the invoked method
    if (methodTypeVariables.contains(typeVar.tsym)) {
      if (substituteRepairedOnly) {
        return repairedSubstitutions.getOrDefault(
            (Symbol.TypeVariableSymbol) typeVar.tsym, context.callSiteType());
      }
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
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"}) // deliberate reference equality checks
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
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"}) // deliberate reference equality checks
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

  /**
   * when this method is called, {@code genericWildcardType} is a type argument within some level of
   * a parameter type for the generic method, and {@code context.callSiteType()} is the
   * javac-determined wildcard at the same position. Recurses into the bound, against the bound of
   * {@code context.actualArgType()} if it is a wildcard and against the type itself otherwise, as
   * the type argument of the actual parameter is contained in the wildcard.
   */
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"}) // deliberate reference equality checks
  @Override
  public Type visitWildcardType(Type.WildcardType genericWildcardType, RepairContext context) {
    if (!(context.callSiteType() instanceof Type.WildcardType callSiteWildcardType)
        || genericWildcardType.type == null
        || callSiteWildcardType.type == null
        || genericWildcardType.kind != callSiteWildcardType.kind) {
      return context.callSiteType();
    }
    Type actualBound = context.actualArgType();
    if (actualBound instanceof Type.WildcardType actualWildcardType) {
      if (actualWildcardType.kind != callSiteWildcardType.kind || actualWildcardType.type == null) {
        return context.callSiteType();
      }
      actualBound = actualWildcardType.type;
    }
    Type callSiteBound = callSiteWildcardType.type;
    Type repairedBound = repairType(genericWildcardType.type, actualBound, callSiteBound);
    return repairedBound != callSiteBound
        ? TypeMetadataBuilder.TYPE_METADATA_BUILDER.createWildcardType(
            callSiteWildcardType, repairedBound)
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
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"}) // deliberate reference equality checks
  private Type repairTypeVarSubstitution(
      Type.TypeVar typeVar, Type actualArgType, Type callSiteType) {
    Symbol.TypeVariableSymbol typeVarSymbol = (Symbol.TypeVariableSymbol) typeVar.tsym;
    Type previousSubstitution = repairedSubstitutions.get(typeVarSymbol);
    if (previousSubstitution != null
        && !(previousSubstitution instanceof Type.ArrayType previousArrayType
            && !hasNullableComponents(previousArrayType))) {
      return previousSubstitution;
    }
    Type repairedSubstitution = callSiteType;
    if (!actualArgType.isRaw() && !callSiteType.isRaw()) {
      repairedSubstitution = repairNestedTypeVarSubstitutionFromActual(actualArgType, callSiteType);
    }
    if (previousSubstitution != null) {
      // arrays are covariant, so an array whose components admit null also takes the arrays of
      // non-null components that another argument passed; a later argument with @Nullable
      // components replaces an earlier one without
      if (!(repairedSubstitution instanceof Type.ArrayType repairedArrayType)
          || !hasNullableComponents(repairedArrayType)) {
        return previousSubstitution;
      }
      repairedSubstitutions.put(typeVarSymbol, repairedSubstitution);
      return repairedSubstitution;
    }
    // only a repair is kept for the other occurrences of the type variable; an argument that
    // repairs nothing leaves a later argument at another occurrence free to repair it
    if (repairedSubstitution != callSiteType) {
      repairedSubstitutions.put(typeVarSymbol, repairedSubstitution);
    }
    return repairedSubstitution;
  }

  /** Returns whether the components of {@code arrayType} are annotated {@code @Nullable}. */
  private boolean hasNullableComponents(Type.ArrayType arrayType) {
    return Nullness.hasNullableAnnotation(
        arrayType.getComponentType().getAnnotationMirrors().stream(), config);
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
    // the actual type can be a subtype of the javac-inferred call-site type, so convert to the
    // supertype
    if (actualArgType instanceof Type.ClassType
        && callSiteType instanceof Type.ClassType
        && callSiteType.tsym instanceof Symbol.ClassSymbol callSiteClassSymbol) {
      Type actualAsSuper =
          TypeSubstitutionUtils.asSuper(
              state.getTypes(), actualArgType, callSiteClassSymbol, config);
      if (actualAsSuper != null) {
        actualArgType = actualAsSuper;
      }
    }
    // only handle cases where base types are identical for now
    if (!ASTHelpers.isSameType(actualArgType, callSiteType, state)) {
      return callSiteType;
    }
    if (actualArgType instanceof Type.ClassType actualClassType
        && callSiteType instanceof Type.ClassType callSiteClassType) {
      List<Type> actualTypeArgs = actualClassType.getTypeArguments();
      Type actualEnclosingType = actualClassType.getEnclosingType();
      boolean hasGenericEnclosingType =
          actualEnclosingType instanceof Type.ClassType
              && !actualEnclosingType.getTypeArguments().isEmpty();
      if (actualTypeArgs.isEmpty() && !hasGenericEnclosingType) {
        return callSiteType;
      }
      // use call site type with type arguments, and those of an enclosing type such as Outer<E>
      // in Outer<E>.Inner, from actual
      return TypeMetadataBuilder.TYPE_METADATA_BUILDER.createClassType(
          callSiteClassType,
          hasGenericEnclosingType ? actualEnclosingType : callSiteClassType.getEnclosingType(),
          actualTypeArgs);
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
