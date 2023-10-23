package com.uber.nullaway.dataflow;

import static com.uber.nullaway.Nullness.NONNULL;
import static com.uber.nullaway.Nullness.NULLABLE;

import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.CodeAnnotationInfo;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullabilityUtil;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.handlers.Handler;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import javax.lang.model.element.Element;
import org.checkerframework.nullaway.dataflow.cfg.UnderlyingAST;
import org.checkerframework.nullaway.dataflow.cfg.node.LocalVariableNode;

class CoreNullnessStoreInitializer extends NullnessStoreInitializer {

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
      Element element = param.getElement();
      Nullness assumed =
          Nullness.hasNullableAnnotation((Symbol) element, config) ? NULLABLE : NONNULL;
      result.setInformation(AccessPath.fromLocal(param), assumed);
    }
    result = handler.onDataflowInitialStore(underlyingAST, parameters, result);
    return result.build();
  }

  private static NullnessStore lambdaInitialStore(
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
    // This obtains the types of the functional interface method parameters with preserved
    // annotations in case of generic type arguments.  Only used in JSpecify mode.
    List<Type> overridenMethodParamTypeList =
        types.memberType(ASTHelpers.getType(code), fiMethodSymbol).getParameterTypes();
    // If fiArgumentPositionNullness[i] == null, parameter position i is unannotated
    Nullness[] fiArgumentPositionNullness = new Nullness[fiMethodParameters.size()];
    final boolean isFIAnnotated = !codeAnnotationInfo.isSymbolUnannotated(fiMethodSymbol, config);
    if (isFIAnnotated) {
      for (int i = 0; i < fiMethodParameters.size(); i++) {
        if (Nullness.hasNullableAnnotation(fiMethodParameters.get(i), config)) {
          // Get the Nullness if the Annotation is directly written with the parameter
          fiArgumentPositionNullness[i] = NULLABLE;
        } else if (config.isJSpecifyMode()
            && Nullness.hasNullableAnnotation(
                overridenMethodParamTypeList.get(i).getAnnotationMirrors().stream(), config)) {
          // Get the Nullness if the Annotation is indirectly applied through a generic type if we
          // are in JSpecify mode
          fiArgumentPositionNullness[i] = NULLABLE;
        } else {
          fiArgumentPositionNullness[i] = NONNULL;
        }
      }
    }
    fiArgumentPositionNullness =
        handler.onOverrideMethodInvocationParametersNullability(
            context, fiMethodSymbol, isFIAnnotated, fiArgumentPositionNullness);

    for (int i = 0; i < parameters.size(); i++) {
      LocalVariableNode param = parameters.get(i);
      VariableTree variableTree = code.getParameters().get(i);
      Element element = param.getElement();
      Nullness assumed;
      // we treat lambda parameters differently; they "inherit" the nullability of the
      // corresponding functional interface parameter, unless they are explicitly annotated
      if (Nullness.hasNullableAnnotation((Symbol) element, config)) {
        assumed = NULLABLE;
      } else if (!NullabilityUtil.lambdaParamIsImplicitlyTyped(variableTree)) {
        // the parameter has a declared type with no @Nullable annotation
        // treat as non-null
        assumed = NONNULL;
      } else {
        assumed = fiArgumentPositionNullness[i] == null ? NONNULL : fiArgumentPositionNullness[i];
      }
      result.setInformation(AccessPath.fromLocal(param), assumed);
    }
    result = handler.onDataflowInitialStore(underlyingAST, parameters, result);
    return result.build();
  }

  @Nullable private CodeAnnotationInfo codeAnnotationInfo;

  private CodeAnnotationInfo getCodeAnnotationInfo(Context context) {
    if (codeAnnotationInfo == null) {
      codeAnnotationInfo = CodeAnnotationInfo.instance(context);
    }
    return codeAnnotationInfo;
  }
}
