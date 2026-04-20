package com.uber.nullaway.dataflow;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;
import static com.uber.nullaway.Nullness.NONNULL;
import static com.uber.nullaway.Nullness.NULLABLE;

import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.CodeAnnotationInfo;
import com.uber.nullaway.Config;
import com.uber.nullaway.MethodParameterNullness;
import com.uber.nullaway.NullabilityUtil;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.generics.GenericsChecks;
import com.uber.nullaway.generics.TypeSubstitutionUtils;
import com.uber.nullaway.handlers.Handler;
import java.util.List;
import java.util.Objects;
import javax.lang.model.element.Element;
import org.checkerframework.nullaway.dataflow.cfg.UnderlyingAST;
import org.checkerframework.nullaway.dataflow.cfg.node.LocalVariableNode;
import org.jspecify.annotations.Nullable;

class CoreNullnessStoreInitializer extends NullnessStoreInitializer {

  private final GenericsChecks genericsChecks;

  public CoreNullnessStoreInitializer(GenericsChecks genericsChecks) {
    this.genericsChecks = genericsChecks;
  }

  @Override
  public NullnessStore getInitialStore(
      UnderlyingAST underlyingAST,
      List<LocalVariableNode> parameters,
      Handler handler,
      Context context,
      Types types,
      Config config) {
    if (underlyingAST.getKind().equals(UnderlyingAST.Kind.ARBITRARY_CODE)) {
      // not a method or a lambda; an initializer expression or block
      UnderlyingAST.CFGStatement ast = (UnderlyingAST.CFGStatement) underlyingAST;
      return getEnvNullnessStoreForClass(ast.getClassTree(), context);
    }
    boolean isLambda = underlyingAST.getKind().equals(UnderlyingAST.Kind.LAMBDA);
    if (isLambda) {
      return lambdaInitialStore(
          (UnderlyingAST.CFGLambda) underlyingAST,
          parameters,
          handler,
          context,
          types,
          config,
          getCodeAnnotationInfo(context));
    } else {
      return methodInitialStore(
          (UnderlyingAST.CFGMethod) underlyingAST, parameters, handler, context, config);
    }
  }

  private static NullnessStore methodInitialStore(
      UnderlyingAST.CFGMethod underlyingAST,
      List<LocalVariableNode> parameters,
      Handler handler,
      Context context,
      Config config) {
    ClassTree classTree = underlyingAST.getClassTree();
    NullnessStore envStore = getEnvNullnessStoreForClass(classTree, context);
    NullnessStore.Builder result = envStore.toBuilder();
    for (LocalVariableNode param : parameters) {
      Symbol paramSymbol = (Symbol) param.getElement();
      Nullness assumed;
      // Using this flag to check for a varargs parameter works since we know paramSymbol represents
      // a parameter defined in source code
      if ((paramSymbol.flags() & Flags.VARARGS) != 0) {
        assumed = Nullness.varargsArrayIsNullable(paramSymbol, config) ? NULLABLE : NONNULL;
      } else {
        assumed = Nullness.hasNullableAnnotation(paramSymbol, config) ? NULLABLE : NONNULL;
      }
      result.setInformation(AccessPath.fromLocal(param), assumed);
    }
    result = handler.onDataflowInitialStore(underlyingAST, parameters, result);
    return result.build();
  }

  private NullnessStore lambdaInitialStore(
      UnderlyingAST.CFGLambda underlyingAST,
      List<LocalVariableNode> parameters,
      Handler handler,
      Context context,
      Types types,
      Config config,
      CodeAnnotationInfo codeAnnotationInfo) {
    // include nullness info for locals from enclosing environment
    EnclosingEnvironmentNullness environmentNullness =
        EnclosingEnvironmentNullness.instance(context);
    NullnessStore environmentMapping =
        Objects.requireNonNull(
            environmentNullness.getEnvironmentMapping(underlyingAST.getLambdaTree()),
            "no environment stored for lambda");
    NullnessStore.Builder result = environmentMapping.toBuilder();
    LambdaExpressionTree code = underlyingAST.getLambdaTree();
    // need to check annotation for i'th parameter of functional interface declaration
    Symbol.MethodSymbol fiMethodSymbol = NullabilityUtil.getFunctionalInterfaceMethod(code, types);
    com.sun.tools.javac.util.List<Symbol.VarSymbol> fiMethodParameters =
        fiMethodSymbol.getParameters();

    /*
     * A potential concern here is that this method might return null because we haven't inferred the type
     * of the lambda yet.
     * However, this is not an issue due to the standard AST traversal order used by Error Prone.
     * We currently only infer lambda types when they are passed as a parameter to a generic method.
     * The checker is guaranteed to visit the enclosing generic method call (e.g., {@code wrap(s -> ...)})
     * and perform type inference for it *before* it descends into the lambda's body to begin dataflow
     * analysis. By the time this method is called during the setup for the lambda's analysis, the
     * inferred type for the lambda argument will have already been computed and stored.
     */
    Type lambdaType = castToNonNull(ASTHelpers.getType(code));
    Type inferredType = genericsChecks.getInferredPolyExpressionType(code);
    if (inferredType != null) {
      lambdaType = inferredType;
    }

    // This obtains the types of the functional interface method parameters with preserved
    // annotations in case of generic type arguments.  Only used in JSpecify mode.
    List<Type> overridenMethodParamTypeList =
        TypeSubstitutionUtils.memberType(types, lambdaType, fiMethodSymbol, config)
            .getParameterTypes();
    MethodParameterNullness fiArgumentNullness = MethodParameterNullness.create(fiMethodSymbol);
    boolean isFIAnnotated =
        !codeAnnotationInfo.isSymbolUnannotated(fiMethodSymbol, config, handler);
    if (isFIAnnotated) {
      for (int i = 0; i < fiMethodParameters.size(); i++) {
        if (Nullness.hasNullableAnnotation(fiMethodParameters.get(i), config)) {
          // Get the Nullness if the Annotation is directly written with the parameter
          fiArgumentNullness.setParameterNullness(i, NULLABLE);
        } else if (config.isJSpecifyMode()
            && Nullness.hasNullableAnnotation(
                overridenMethodParamTypeList.get(i).getAnnotationMirrors().stream(), config)) {
          // Get the Nullness if the Annotation is indirectly applied through a generic type if we
          // are in JSpecify mode
          fiArgumentNullness.setParameterNullness(i, NULLABLE);
        } else {
          fiArgumentNullness.setParameterNullness(i, NONNULL);
        }
      }
    }
    fiArgumentNullness =
        handler.onOverrideMethodInvocationParametersNullability(
            context, fiMethodSymbol, isFIAnnotated, fiArgumentNullness);

    for (int i = 0; i < parameters.size(); i++) {
      LocalVariableNode param = parameters.get(i);
      VariableTree variableTree = code.getParameters().get(i);
      Element element = param.getElement();
      Nullness assumed;
      // we treat lambda parameters differently; they "inherit" the nullability of the
      // corresponding functional interface parameter, unless they are explicitly annotated.
      //
      // We look for explicit @Nullable annotations on the symbol.  In JDK 27+, sometimes
      // javac will add a @Nullable annotation to the Type of element based on its own generic
      // inference.  We don't want to consider that here (it is handled in the type stored in
      // fiArgumentNullness), so we only look at the annotations directly on element
      if (Nullness.hasNullableAnnotation(
          ((Symbol) element).getAnnotationMirrors().stream(), config)) {
        assumed = NULLABLE;
      } else if (!NullabilityUtil.lambdaParamIsImplicitlyTyped(variableTree)) {
        // the parameter has a declared type with no @Nullable annotation
        // treat as non-null
        assumed = NONNULL;
      } else {
        Nullness fiParameterNullness = fiArgumentNullness.getParameterNullness(i);
        assumed = fiParameterNullness == null ? NONNULL : fiParameterNullness;
      }
      result.setInformation(AccessPath.fromLocal(param), assumed);
    }
    result = handler.onDataflowInitialStore(underlyingAST, parameters, result);
    return result.build();
  }

  private @Nullable CodeAnnotationInfo codeAnnotationInfo;

  private CodeAnnotationInfo getCodeAnnotationInfo(Context context) {
    if (codeAnnotationInfo == null) {
      codeAnnotationInfo = CodeAnnotationInfo.instance(context);
    }
    return codeAnnotationInfo;
  }
}
