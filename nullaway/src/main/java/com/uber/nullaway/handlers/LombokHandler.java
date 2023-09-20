package com.uber.nullaway.handlers;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import javax.lang.model.element.ElementKind;

/**
 * A general handler for Lombok generated code and its internal semantics.
 *
 * <p>Currently used to propagate @Nullable in cases where the Lombok annotation processor fails to
 * do so consistently.
 */
public class LombokHandler extends BaseNoOpHandler {

  private static String LOMBOK_GENERATED_ANNOTATION_NAME = "lombok.Generated";
  private static String LOMBOK_BUILDER_DEFAULT_METHOD_PREFIX = "$default$";

  private final Config config;

  public LombokHandler(Config config) {
    this.config = config;
  }

  private boolean isLombokMethodWithMissingNullableAnnotation(
      Symbol.MethodSymbol methodSymbol, VisitorState state) {
    if (!ASTHelpers.hasAnnotation(methodSymbol, LOMBOK_GENERATED_ANNOTATION_NAME, state)) {
      return false;
    }
    String methodNameString = methodSymbol.name.toString();
    if (!methodNameString.startsWith(LOMBOK_BUILDER_DEFAULT_METHOD_PREFIX)) {
      return false;
    }
    String originalFieldName =
        methodNameString.substring(LOMBOK_BUILDER_DEFAULT_METHOD_PREFIX.length());
    ImmutableList<Symbol> matchingMembers =
        StreamSupport.stream(methodSymbol.enclClass().members().getSymbols().spliterator(), false)
            .filter(
                sym ->
                    sym.name.contentEquals(originalFieldName)
                        && sym.getKind().equals(ElementKind.FIELD))
            .collect(ImmutableList.toImmutableList());
    Preconditions.checkArgument(
        matchingMembers.size() == 1,
        String.format(
            "Found %d fields matching Lombok generated builder default method %s",
            matchingMembers.size(), methodNameString));
    return Nullness.hasNullableAnnotation(matchingMembers.get(0), config);
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
    if (exprSymbol != null && exprKind == Tree.Kind.METHOD_INVOCATION) {
      Symbol.MethodSymbol methodSymbol = (Symbol.MethodSymbol) exprSymbol;
      return isLombokMethodWithMissingNullableAnnotation(methodSymbol, state);
    }
    return false;
  }

  @Override
  public Nullness onOverrideMethodReturnNullability(
      Symbol.MethodSymbol methodSymbol,
      VisitorState state,
      boolean isAnnotated,
      Nullness returnNullness) {
    if (isLombokMethodWithMissingNullableAnnotation(methodSymbol, state)) {
      return Nullness.NULLABLE;
    }
    return returnNullness;
  }
}
