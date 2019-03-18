/*
 * Copyright (c) 2018 Uber Technologies, Inc.
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
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;

/**
 * Handler to better handle {@code isPresent()} methods in code generated for Optionals. With this
 * handler, we learn appropriate Emptiness facts about the relevant property from these calls.
 */
public class OptionalEmptinessHandler extends BaseNoOpHandler {

  @Nullable private Set<Optional<Type>> optionalTypes;

  @Override
  public boolean onOverrideMayBeNullExpr(
      NullAway analysis, ExpressionTree expr, VisitorState state, boolean exprMayBeNull) {
    if (expr.getKind() == Tree.Kind.METHOD_INVOCATION
        && optionalIsGetCall((Symbol.MethodSymbol) ASTHelpers.getSymbol(expr), state.getTypes())) {
      return true;
    }
    return exprMayBeNull;
  }

  @Override
  public void onMatchTopLevelClass(
      NullAway analysis,
      ClassTree tree,
      VisitorState state,
      Symbol.ClassSymbol classSymbol,
      Config config) {
    optionalTypes =
        config
            .getOptionalClassPaths()
            .stream()
            .map(
                type ->
                    Optional.ofNullable(state.getTypeFromString(type))
                        .map(state.getTypes()::erasure))
            .collect(Collectors.toSet());
  }

  @Override
  public NullnessHint onDataflowVisitMethodInvocation(
      MethodInvocationNode node,
      Types types,
      Context context,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    Symbol.MethodSymbol symbol = ASTHelpers.getSymbol(node.getTree());

    if (optionalIsGetCall(symbol, types)) {
      return NullnessHint.HINT_NULLABLE;
    } else if (optionalIsPresentCall(symbol, types)) {
      Element getter = null;
      Node base = node.getTarget().getReceiver();
      for (Symbol elem : symbol.owner.getEnclosedElements()) {
        if (elem.getKind().equals(ElementKind.METHOD)
            && elem.getSimpleName().toString().equals("get")) {
          getter = elem;
        }
      }
      updateNonNullAPsForElement(thenUpdates, getter, base);
    }
    return NullnessHint.UNKNOWN;
  }

  @Override
  public void onPrepareErrorMessage(
      ExpressionTree expr, VisitorState state, ErrorMessage errorMessage) {
    if (expr.getKind() == Tree.Kind.METHOD_INVOCATION
        && optionalIsGetCall((Symbol.MethodSymbol) ASTHelpers.getSymbol(expr), state.getTypes())) {
      final int exprStringSize = expr.toString().length();
      // Name of the optional is extracted from the expression
      final String message =
          "Optional "
              + expr.toString().substring(0, exprStringSize - 6)
              + " can be empty, dereferenced get() call on it";
      errorMessage.updateErrorMessage(ErrorMessage.MessageTypes.GET_ON_EMPTY_OPTIONAL, message);
    }
  }

  @Override
  public boolean includeApInfoInSavedContext(AccessPath accessPath, VisitorState state) {

    if (accessPath.getElements().size() == 1) {
      AccessPath.Root root = accessPath.getRoot();
      if (!root.isReceiver() && (accessPath.getElements().get(0) instanceof Symbol.MethodSymbol)) {
        final Element e = root.getVarElement();
        final Symbol.MethodSymbol g = (Symbol.MethodSymbol) accessPath.getElements().get(0);
        return e.getKind().equals(ElementKind.LOCAL_VARIABLE)
            && optionalIsGetCall(g, state.getTypes());
      }
    }
    return false;
  }

  private void updateNonNullAPsForElement(
      AccessPathNullnessPropagation.Updates updates, @Nullable Element elem, Node base) {
    if (elem != null) {
      AccessPath ap = AccessPath.fromBaseAndElement(base, elem);
      if (ap != null) {
        updates.set(ap, Nullness.NONNULL);
      }
    }
  }

  private boolean optionalIsPresentCall(Symbol.MethodSymbol symbol, Types types) {
    for (Optional<Type> optionalType : optionalTypes) {
      if (optionalType.isPresent()
          && symbol.getSimpleName().toString().equals("isPresent")
          && symbol.getParameters().length() == 0
          && types.isSubtype(symbol.owner.type, optionalType.get())) return true;
    }
    return false;
  }

  private boolean optionalIsGetCall(Symbol.MethodSymbol symbol, Types types) {
    for (Optional<Type> optionalType : optionalTypes) {
      if (optionalType.isPresent()
          && symbol.getSimpleName().toString().equals("get")
          && symbol.getParameters().length() == 0
          && types.isSubtype(symbol.owner.type, optionalType.get())) return true;
    }
    return false;
  }
}
