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

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessAnalysis;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.nullaway.dataflow.cfg.node.Node;

/**
 * Handler to better handle {@code isPresent()} methods in code generated for Optionals. With this
 * handler, we learn appropriate Emptiness facts about the relevant property from these calls.
 */
public class OptionalEmptinessHandler extends BaseNoOpHandler {

  @Nullable private ImmutableSet<Type> optionalTypes;
  private NullAway analysis;

  private final Config config;
  private final MethodNameUtil methodNameUtil;

  public static final VariableElement OPTIONAL_CONTENT = getOptionalContentElement();

  OptionalEmptinessHandler(Config config, MethodNameUtil methodNameUtil) {
    this.config = config;
    this.methodNameUtil = methodNameUtil;
  }

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
      NullAway analysis, ClassTree tree, VisitorState state, Symbol.ClassSymbol classSymbol) {

    this.analysis = analysis;

    optionalTypes =
        config
            .getOptionalClassPaths()
            .stream()
            .map(state::getTypeFromString)
            .filter(Objects::nonNull)
            .map(state.getTypes()::erasure)
            .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public NullnessHint onDataflowVisitMethodInvocation(
      MethodInvocationNode node,
      Types types,
      Context context,
      AccessPath.AccessPathContext apContext,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    Symbol.MethodSymbol symbol = ASTHelpers.getSymbol(node.getTree());

    if (optionalIsPresentCall(symbol, types)) {
      updateNonNullAPsForOptionalContent(thenUpdates, node.getTarget().getReceiver(), apContext);
    } else if (config.handleTestAssertionLibraries() && methodNameUtil.isMethodIsTrue(symbol)) {
      // we check for instance of AssertThat(optionalFoo.isPresent()).isTrue()
      updateIfAssertIsPresentTrueOnOptional(node, types, apContext, bothUpdates);
    }
    return NullnessHint.UNKNOWN;
  }

  @Override
  public Optional<ErrorMessage> onExpressionDereference(
      ExpressionTree expr, ExpressionTree baseExpr, VisitorState state) {

    if (ASTHelpers.getSymbol(expr) instanceof Symbol.MethodSymbol
        && optionalIsGetCall((Symbol.MethodSymbol) ASTHelpers.getSymbol(expr), state.getTypes())
        && isOptionalContentNullable(state, baseExpr, analysis.getNullnessAnalysis(state))) {
      final String message = "Invoking get() on possibly empty Optional " + baseExpr;
      return Optional.of(
          new ErrorMessage(ErrorMessage.MessageTypes.GET_ON_EMPTY_OPTIONAL, message));
    }
    return Optional.empty();
  }

  private boolean isOptionalContentNullable(
      VisitorState state, ExpressionTree baseExpr, AccessPathNullnessAnalysis analysis) {
    return analysis.getNullnessOfExpressionNamedField(
            new TreePath(state.getPath(), baseExpr), state.context, OPTIONAL_CONTENT)
        == Nullness.NULLABLE;
  }

  @Override
  public boolean includeApInfoInSavedContext(AccessPath accessPath, VisitorState state) {

    if (accessPath.getElements().size() == 1) {
      AccessPath.Root root = accessPath.getRoot();
      if (!root.isReceiver()) {
        final Element e = root.getVarElement();
        return e.getKind().equals(ElementKind.LOCAL_VARIABLE)
            && accessPath.getElements().get(0).getJavaElement().equals(OPTIONAL_CONTENT);
      }
    }
    return false;
  }

  private void updateIfAssertIsPresentTrueOnOptional(
      MethodInvocationNode node,
      Types types,
      AccessPath.AccessPathContext apContext,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    Node receiver = node.getTarget().getReceiver();
    if (receiver instanceof MethodInvocationNode) {
      MethodInvocationNode receiverMethod = (MethodInvocationNode) receiver;
      Symbol.MethodSymbol receiverSymbol = ASTHelpers.getSymbol(receiverMethod.getTree());
      if (methodNameUtil.isMethodAssertThat(receiverSymbol)) {
        // assertThat will always have at least one argument, So safe to extract from the arguments
        Node arg = receiverMethod.getArgument(0);
        if (arg instanceof MethodInvocationNode) {
          // Since assertThat(a.isPresent()) changes to
          // Truth.assertThat(Boolean.valueOf(a.isPresent()))
          // need to be unwrapped from Boolean.valueOf
          Node unwrappedArg = ((MethodInvocationNode) arg).getArgument(0);
          if (unwrappedArg instanceof MethodInvocationNode) {
            MethodInvocationNode argMethod = (MethodInvocationNode) unwrappedArg;
            Symbol.MethodSymbol argSymbol = ASTHelpers.getSymbol(argMethod.getTree());
            if (optionalIsPresentCall(argSymbol, types)) {
              updateNonNullAPsForOptionalContent(
                  bothUpdates, argMethod.getTarget().getReceiver(), apContext);
            }
          }
        }
      }
    }
  }

  private void updateNonNullAPsForOptionalContent(
      AccessPathNullnessPropagation.Updates updates,
      Node base,
      AccessPath.AccessPathContext apContext) {
    AccessPath ap = AccessPath.fromBaseAndElement(base, OPTIONAL_CONTENT, apContext);
    if (ap != null && base.getTree() != null) {
      updates.set(ap, Nullness.NONNULL);
    }
  }

  private boolean optionalIsPresentCall(Symbol.MethodSymbol symbol, Types types) {
    for (Type optionalType : optionalTypes) {
      if (symbol.getSimpleName().toString().equals("isPresent")
          && symbol.getParameters().length() == 0
          && types.isSubtype(symbol.owner.type, optionalType)) {
        return true;
      }
    }
    return false;
  }

  private boolean optionalIsGetCall(Symbol.MethodSymbol symbol, Types types) {
    for (Type optionalType : optionalTypes) {
      if (symbol.getSimpleName().toString().equals("get")
          && symbol.getParameters().length() == 0
          && types.isSubtype(symbol.owner.type, optionalType)) {
        return true;
      }
    }
    return false;
  }

  private static VariableElement getOptionalContentElement() {
    return new VariableElement() {
      @Override
      public Object getConstantValue() {
        return null;
      }

      @Override
      public Name getSimpleName() {
        return null;
      }

      @Override
      public Element getEnclosingElement() {
        return null;
      }

      @Override
      public List<? extends Element> getEnclosedElements() {
        return null;
      }

      @Override
      public List<? extends AnnotationMirror> getAnnotationMirrors() {
        return null;
      }

      @Override
      public <A extends Annotation> A getAnnotation(Class<A> aClass) {
        return null;
      }

      @Override
      public <A extends Annotation> A[] getAnnotationsByType(Class<A> aClass) {
        return null;
      }

      @Override
      public <R, P> R accept(ElementVisitor<R, P> elementVisitor, P p) {
        return null;
      }

      @Override
      public TypeMirror asType() {
        return null;
      }

      @Override
      public ElementKind getKind() {
        return null;
      }

      @Override
      public Set<Modifier> getModifiers() {
        return null;
      }

      @Override
      public String toString() {
        return "OPTIONAL_CONTENT";
      }
    };
  }
}
