/*
 * Copyright (c) 2017 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.nullaway.handlers;

import com.google.errorprone.VisitorState;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.CodeAnnotationInfo;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import javax.annotation.Nullable;
import org.checkerframework.nullaway.dataflow.cfg.node.FieldAccessNode;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;

public class RestrictiveAnnotationHandler extends BaseNoOpHandler {

  private static final String JETBRAINS_NOT_NULL = "org.jetbrains.annotations.NotNull";

  private final Config config;

  RestrictiveAnnotationHandler(Config config) {
    this.config = config;
  }

  /**
   * Returns true iff the symbol is considered unannotated but restrictively annotated
   * {@code @Nullable} under {@code AcknowledgeRestrictiveAnnotations=true} logic.
   *
   * <p>In particular, this means the symbol is explicitly annotated as {@code @Nullable} and, if
   * {@code TreatGeneratedAsUnannotated=true}, it is not within generated code.
   *
   * @param symbol the symbol being checked
   * @param context Javac Context or Error Prone SubContext
   * @return whether this handler would generally override the nullness of {@code symbol} as
   *     nullable.
   */
  private boolean isSymbolRestrictivelyNullable(Symbol symbol, Context context) {
    CodeAnnotationInfo codeAnnotationInfo = getCodeAnnotationInfo(context);
    return (codeAnnotationInfo.isSymbolUnannotated(symbol, config)
        // with the generated-as-unannotated option enabled, we want to ignore annotations in
        // generated code no matter what
        && !(config.treatGeneratedAsUnannotated() && codeAnnotationInfo.isGenerated(symbol, config))
        && Nullness.hasNullableAnnotation(symbol, config));
  }

  @Override
  public boolean onOverrideMayBeNullExpr(
      NullAway analysis,
      ExpressionTree expr,
      @Nullable Symbol exprSymbol,
      VisitorState state,
      boolean exprMayBeNull) {
    if (exprMayBeNull) {
      return true;
    }
    Tree.Kind exprKind = expr.getKind();
    if (exprSymbol != null
        && (exprKind == Tree.Kind.METHOD_INVOCATION || exprKind == Tree.Kind.IDENTIFIER)
        && isSymbolRestrictivelyNullable(exprSymbol, state.context)) {
      return true;
    }
    return false;
  }

  @Nullable private CodeAnnotationInfo codeAnnotationInfo;

  private CodeAnnotationInfo getCodeAnnotationInfo(Context context) {
    if (codeAnnotationInfo == null) {
      codeAnnotationInfo = CodeAnnotationInfo.instance(context);
    }
    return codeAnnotationInfo;
  }

  @Override
  public Nullness[] onOverrideMethodInvocationParametersNullability(
      Context context,
      Symbol.MethodSymbol methodSymbol,
      boolean isAnnotated,
      Nullness[] argumentPositionNullness) {
    if (isAnnotated) {
      // We ignore isAnnotated code here, since annotations in code considered isAnnotated are
      // already handled by NullAway's core algorithm.
      return argumentPositionNullness;
    }
    for (int i = 0; i < methodSymbol.getParameters().size(); ++i) {
      if (Nullness.paramHasNonNullAnnotation(methodSymbol, i, config)) {
        if (methodSymbol.isVarArgs() && i == methodSymbol.getParameters().size() - 1) {
          // Special handling: ignore org.jetbrains.annotations.NotNull on varargs parameters
          // to handle kotlinc generated jars (see #720)
          // We explicitly ignore type-use annotations here, looking for @NotNull used as a
          // declaration annotation, which is why this logic is simpler than e.g.
          // NullabilityUtil.getAllAnnotationsForParameter.
          boolean jetBrainsNotNullAnnotated =
              methodSymbol.getParameters().get(i).getAnnotationMirrors().stream()
                  .map(a -> a.getAnnotationType().toString())
                  .anyMatch(annotName -> annotName.equals(JETBRAINS_NOT_NULL));
          if (jetBrainsNotNullAnnotated) {
            continue;
          }
        }
        argumentPositionNullness[i] = Nullness.NONNULL;
      } else if (Nullness.paramHasNullableAnnotation(methodSymbol, i, config)) {
        argumentPositionNullness[i] = Nullness.NULLABLE;
      }
    }
    return argumentPositionNullness;
  }

  @Override
  public Nullness onOverrideMethodReturnNullability(
      Symbol.MethodSymbol methodSymbol,
      VisitorState state,
      boolean isAnnotated,
      Nullness returnNullness) {
    // Note that, for the purposes of overriding/subtyping, either @Nullable or @NonNull
    // can be considered restrictive annotations, depending on whether the unannotated method
    // is overriding or being overridden.
    if (isAnnotated) {
      return returnNullness;
    }
    if (Nullness.hasNullableAnnotation(methodSymbol, config)) {
      return Nullness.NULLABLE;
    } else if (Nullness.hasNonNullAnnotation(methodSymbol, config)) {
      return Nullness.NONNULL;
    }
    return returnNullness;
  }

  @Override
  public NullnessHint onDataflowVisitMethodInvocation(
      MethodInvocationNode node,
      Symbol.MethodSymbol methodSymbol,
      VisitorState state,
      AccessPath.AccessPathContext apContext,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    return isSymbolRestrictivelyNullable(methodSymbol, state.context)
        ? NullnessHint.HINT_NULLABLE
        : NullnessHint.UNKNOWN;
  }

  @Override
  public NullnessHint onDataflowVisitFieldAccess(
      FieldAccessNode node,
      Symbol symbol,
      Types types,
      Context context,
      AccessPath.AccessPathContext apContext,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates updates) {
    return isSymbolRestrictivelyNullable(symbol, context)
        ? NullnessHint.HINT_NULLABLE
        : NullnessHint.UNKNOWN;
  }
}
