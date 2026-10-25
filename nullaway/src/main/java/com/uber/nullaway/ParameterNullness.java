package com.uber.nullaway;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;
import static com.uber.nullaway.Nullness.NONNULL;
import static com.uber.nullaway.Nullness.NULLABLE;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.generics.GenericsChecks;
import com.uber.nullaway.generics.TypeSubstitutionUtils;
import com.uber.nullaway.handlers.Handler;
import java.util.ArrayList;
import java.util.List;

/** Computes declared parameter nullness independently of flow-sensitive refinements and stores. */
public final class ParameterNullness {

  private ParameterNullness() {}

  /** Returns a source method or constructor parameter's declared top-level nullness. */
  public static Nullness getMethodParameterNullness(Symbol parameter, Config config) {
    // For varargs, the parameter stores the array, rather than an individual argument.
    boolean nullable =
        (parameter.flags() & Flags.VARARGS) != 0
            ? Nullness.varargsArrayIsNullable(parameter, config)
            : Nullness.hasNullableAnnotation(parameter, config);
    return nullable ? NULLABLE : NONNULL;
  }

  /**
   * Returns parameter nullness before flow refinement, including the functional interface contract
   * for implicitly typed lambda parameters.
   */
  public static Nullness getDeclaredNullness(
      Symbol.VarSymbol parameter,
      VisitorState state,
      GenericsChecks genericsChecks,
      Handler handler,
      Config config) {
    Tree enclosing =
        castToNonNull(NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(state.getPath()))
            .getLeaf();
    if (enclosing instanceof LambdaExpressionTree lambda) {
      int index =
          lambda.getParameters().stream().map(ASTHelpers::getSymbol).toList().indexOf(parameter);
      return getLambdaParameterNullness(
              lambda, genericsChecks, handler, state.context, state.getTypes(), config)
          .get(index);
    }
    return getMethodParameterNullness(parameter, config);
  }

  /** Computes the declared parameter nullness shared by lambda entry and assignment checking. */
  public static List<Nullness> getLambdaParameterNullness(
      LambdaExpressionTree code,
      GenericsChecks genericsChecks,
      Handler handler,
      Context context,
      Types types,
      Config config) {
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
    Type lambdaType = castToNonNull(genericsChecks.getPolyExpressionType(code));

    // This obtains the types of the functional interface method parameters with preserved
    // annotations in case of generic type arguments.  Only used in JSpecify mode.
    List<Type> overridenMethodParamTypeList =
        TypeSubstitutionUtils.memberType(types, lambdaType, fiMethodSymbol, config)
            .getParameterTypes();
    MethodParameterNullness fiArgumentNullness = MethodParameterNullness.create(fiMethodSymbol);
    boolean isFIAnnotated =
        !CodeAnnotationInfo.instance(context).isSymbolUnannotated(fiMethodSymbol, config, handler);
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

    List<Nullness> result = new ArrayList<>();
    for (int i = 0; i < code.getParameters().size(); i++) {
      VariableTree variableTree = code.getParameters().get(i);
      Symbol.VarSymbol element = ASTHelpers.getSymbol(variableTree);
      Nullness assumed;
      // we treat lambda parameters differently; they "inherit" the nullability of the
      // corresponding functional interface parameter, unless they are explicitly annotated.
      //
      // Read declaration and root type-use annotations written on the parameter, skipping
      // element.asType(). In JDK 27+, javac can add an inferred @Nullable to that type; inherited
      // nullness is already handled by fiArgumentNullness.
      if (Nullness.hasNullableAnnotation(
          NullabilityUtil.getExplicitLambdaParameterAnnotations(element), config)) {
        assumed = NULLABLE;
      } else if (!NullabilityUtil.lambdaParamIsImplicitlyTyped(variableTree)) {
        // the parameter has a declared type with no @Nullable annotation
        // treat as non-null
        assumed = NONNULL;
      } else {
        Nullness fiParameterNullness = fiArgumentNullness.getParameterNullness(i);
        assumed = fiParameterNullness == null ? NONNULL : fiParameterNullness;
      }
      result.add(assumed);
    }
    return result;
  }
}
