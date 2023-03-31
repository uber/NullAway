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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Names;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessAnalysis;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.nullaway.dataflow.cfg.node.Node;

/**
 * Handler to better handle {@code isPresent()} methods in code generated for Optionals. With this
 * handler, we learn appropriate Emptiness facts about the relevant property from these calls.
 */
public class OptionalEmptinessHandler extends BaseNoOpHandler {

  @Nullable private ImmutableSet<Type> optionalTypes;
  private @Nullable NullAway analysis;

  private final Config config;
  private final MethodNameUtil methodNameUtil;

  OptionalEmptinessHandler(Config config, MethodNameUtil methodNameUtil) {
    this.config = config;
    this.methodNameUtil = methodNameUtil;
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
    if (expr.getKind() == Tree.Kind.METHOD_INVOCATION
        && exprSymbol instanceof Symbol.MethodSymbol
        && optionalIsGetCall((Symbol.MethodSymbol) exprSymbol, state.getTypes())) {
      return true;
    }
    return false;
  }

  @Override
  public void onMatchTopLevelClass(
      NullAway analysis, ClassTree tree, VisitorState state, Symbol.ClassSymbol classSymbol) {

    this.analysis = analysis;

    if (optionalTypes == null) {
      optionalTypes =
          config.getOptionalClassPaths().stream()
              .map(state::getTypeFromString)
              .filter(Objects::nonNull)
              .map(state.getTypes()::erasure)
              .collect(ImmutableSet.toImmutableSet());
    }
  }

  @Override
  public NullnessHint onDataflowVisitMethodInvocation(
      MethodInvocationNode node,
      Symbol.MethodSymbol symbol,
      VisitorState state,
      AccessPath.AccessPathContext apContext,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    Types types = state.getTypes();
    if (optionalIsPresentCall(symbol, types)) {
      updateNonNullAPsForOptionalContent(
          state.context, thenUpdates, node.getTarget().getReceiver(), apContext);
    } else if (optionalIsEmptyCall(symbol, types)) {
      updateNonNullAPsForOptionalContent(
          state.context, elseUpdates, node.getTarget().getReceiver(), apContext);
    } else if (config.handleTestAssertionLibraries()) {
      handleTestAssertions(state, apContext, bothUpdates, node, symbol);
    }
    return NullnessHint.UNKNOWN;
  }

  @Override
  public Optional<ErrorMessage> onExpressionDereference(
      ExpressionTree expr, ExpressionTree baseExpr, VisitorState state) {
    Preconditions.checkNotNull(analysis);
    Symbol symbol = ASTHelpers.getSymbol(expr);
    if (symbol instanceof Symbol.MethodSymbol
        && optionalIsGetCall((Symbol.MethodSymbol) symbol, state.getTypes())
        && isOptionalContentNullable(state, baseExpr, analysis.getNullnessAnalysis(state))) {
      final String message = "Invoking get() on possibly empty Optional " + baseExpr;
      return Optional.of(
          new ErrorMessage(ErrorMessage.MessageTypes.GET_ON_EMPTY_OPTIONAL, message));
    }
    return Optional.empty();
  }

  private boolean isOptionalContentNullable(
      VisitorState state,
      ExpressionTree baseExpr,
      AccessPathNullnessAnalysis accessPathNullnessAnalysis) {
    return accessPathNullnessAnalysis.getNullnessOfExpressionNamedField(
            new TreePath(state.getPath(), baseExpr),
            state.context,
            OptionalContentVariableElement.instance(state.context))
        == Nullness.NULLABLE;
  }

  @Override
  public boolean includeApInfoInSavedContext(AccessPath accessPath, VisitorState state) {

    if (accessPath.getElements().size() == 1) {
      final Element e = accessPath.getRoot();
      if (e != null) {
        return e.getKind().equals(ElementKind.LOCAL_VARIABLE)
            && accessPath.getElements().get(0).getJavaElement()
                instanceof OptionalContentVariableElement;
      }
    }
    return false;
  }

  private void handleTestAssertions(
      VisitorState state,
      AccessPath.AccessPathContext apContext,
      AccessPathNullnessPropagation.Updates bothUpdates,
      MethodInvocationNode node,
      Symbol.MethodSymbol symbol) {

    Consumer<Node> nonNullMarker =
        nonNullNode ->
            updateNonNullAPsForOptionalContent(state.context, bothUpdates, nonNullNode, apContext);

    boolean isAssertTrueMethod = methodNameUtil.isMethodAssertTrue(symbol);
    boolean isAssertFalseMethod = methodNameUtil.isMethodAssertFalse(symbol);
    boolean isTrueMethod = methodNameUtil.isMethodIsTrue(symbol);
    boolean isFalseMethod = methodNameUtil.isMethodIsFalse(symbol);
    if (isAssertTrueMethod || isAssertFalseMethod) {
      // assertTrue(optionalFoo.isPresent())
      // assertFalse("optional was empty", optionalFoo.isEmpty())
      // note: in junit4 the optional string message comes first, but in junit5 it comes last
      Optional<MethodInvocationNode> assertedOnMethod =
          node.getArguments().stream()
              .filter(n -> TypeKind.BOOLEAN.equals(n.getType().getKind()))
              .filter(n -> n instanceof MethodInvocationNode)
              .map(n -> (MethodInvocationNode) n)
              .findFirst();
      if (assertedOnMethod.isPresent()) {
        handleBooleanAssertionOnMethod(
            nonNullMarker,
            state.getTypes(),
            assertedOnMethod.get(),
            isAssertTrueMethod,
            isAssertFalseMethod);
      }
    } else if (isTrueMethod || isFalseMethod) {
      // asertThat(optionalFoo.isPresent()).isTrue()
      // asertThat(optionalFoo.isEmpty()).isFalse()
      Optional<MethodInvocationNode> wrappedMethod =
          getNodeWrappedByAssertThat(node)
              .filter(n -> n instanceof MethodInvocationNode)
              .map(n -> (MethodInvocationNode) n)
              .map(this::maybeUnwrapBooleanValueOf);
      if (wrappedMethod.isPresent()) {
        handleBooleanAssertionOnMethod(
            nonNullMarker, state.getTypes(), wrappedMethod.get(), isTrueMethod, isFalseMethod);
      }
    } else if (methodNameUtil.isMethodThatEnsuresOptionalPresent(symbol)) {
      // assertThat(optionalRef).isPresent()
      // assertThat(methodReturningOptional()).isNotEmpty()
      // assertThat(mapWithOptionalValues.get("key")).isNotEmpty()
      getNodeWrappedByAssertThat(node).ifPresent(nonNullMarker);
    }
  }

  private void handleBooleanAssertionOnMethod(
      Consumer<Node> nonNullMarker,
      Types types,
      MethodInvocationNode node,
      boolean assertsTrue,
      boolean assertsFalse) {
    Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(node.getTree());
    boolean ensuresIsPresent = assertsTrue && optionalIsPresentCall(methodSymbol, types);
    boolean ensuresNotEmpty = assertsFalse && optionalIsEmptyCall(methodSymbol, types);
    if (ensuresIsPresent || ensuresNotEmpty) {
      nonNullMarker.accept(node.getTarget().getReceiver());
    }
  }

  private Optional<Node> getNodeWrappedByAssertThat(MethodInvocationNode node) {
    Node receiver = node.getTarget().getReceiver();
    if (receiver instanceof MethodInvocationNode) {
      MethodInvocationNode receiverMethod = (MethodInvocationNode) receiver;
      if (receiverMethod.getArguments().size() == 1) {
        Symbol.MethodSymbol receiverSymbol = ASTHelpers.getSymbol(receiverMethod.getTree());
        if (methodNameUtil.isMethodAssertThat(receiverSymbol)) {
          return Optional.of(receiverMethod.getArgument(0));
        }
      }
    }
    return Optional.empty();
  }

  private MethodInvocationNode maybeUnwrapBooleanValueOf(MethodInvocationNode node) {
    // Due to autoboxing in the java compiler
    // Truth.assertThat(a.isPresent()) changes to
    // Truth.assertThat(Boolean.valueOf(a.isPresent()))
    // and we need to unwrap Boolean.valueOf here
    if (node.getArguments().size() == 1) {
      Symbol.MethodSymbol symbol = ASTHelpers.getSymbol(node.getTree());
      if (methodNameUtil.isMethodBooleanValueOf(symbol)) {
        Node unwrappedArg = node.getArgument(0);
        if (unwrappedArg instanceof MethodInvocationNode) {
          return (MethodInvocationNode) unwrappedArg;
        }
      }
    }
    return node;
  }

  private void updateNonNullAPsForOptionalContent(
      Context context,
      AccessPathNullnessPropagation.Updates updates,
      Node base,
      AccessPath.AccessPathContext apContext) {
    AccessPath ap =
        AccessPath.fromBaseAndElement(
            base, OptionalContentVariableElement.instance(context), apContext);
    if (ap != null && base.getTree() != null) {
      updates.set(ap, Nullness.NONNULL);
    }
  }

  private boolean optionalIsPresentCall(Symbol.MethodSymbol symbol, Types types) {
    return isZeroArgOptionalMethod("isPresent", symbol, types);
  }

  private boolean optionalIsEmptyCall(Symbol.MethodSymbol symbol, Types types) {
    return isZeroArgOptionalMethod("isEmpty", symbol, types);
  }

  private boolean isZeroArgOptionalMethod(
      String methodName, Symbol.MethodSymbol symbol, Types types) {
    Preconditions.checkNotNull(optionalTypes);
    if (!(symbol.getSimpleName().toString().equals(methodName)
        && symbol.getParameters().length() == 0)) {
      return false;
    }
    for (Type optionalType : optionalTypes) {
      if (types.isSubtype(symbol.owner.type, optionalType)) {
        return true;
      }
    }
    return false;
  }

  private boolean optionalIsGetCall(Symbol.MethodSymbol symbol, Types types) {
    return isZeroArgOptionalMethod("get", symbol, types);
  }

  /**
   * A {@link VariableElement} for a dummy "field" holding the contents of an Optional object, used
   * in dataflow analysis to track whether the Optional content is present.
   *
   * <p>Instances of this type should be accessed using {@link #instance(Context)}, not instantiated
   * directly.
   */
  private static final class OptionalContentVariableElement implements VariableElement {
    public static final Context.Key<OptionalContentVariableElement> contextKey =
        new Context.Key<>();

    private static final Set<Modifier> MODIFIERS = ImmutableSet.of(Modifier.PUBLIC, Modifier.FINAL);
    private final Name name;
    private final TypeMirror asType;

    static synchronized VariableElement instance(Context context) {
      OptionalContentVariableElement instance = context.get(contextKey);
      if (instance == null) {
        instance =
            new OptionalContentVariableElement(
                Names.instance(context).fromString("value"), Symtab.instance(context).objectType);
        context.put(contextKey, instance);
      }
      return instance;
    }

    private OptionalContentVariableElement(Name name, TypeMirror asType) {
      this.name = name;
      this.asType = asType;
    }

    @Override
    @Nullable
    public Object getConstantValue() {
      return null;
    }

    @Override
    public Name getSimpleName() {
      return name;
    }

    @Override
    @Nullable
    public Element getEnclosingElement() {
      // A field would have an enclosing element, however this method isn't guaranteed to
      // return non-null in all cases. It may be beneficial to implement this in a future
      // improvement, but that will require tracking an instance per supported optional
      // type (e.g. java.util.Optional and guava Optional).
      return null;
    }

    @Override
    public List<? extends Element> getEnclosedElements() {
      return Collections.emptyList();
    }

    @Override
    public List<? extends AnnotationMirror> getAnnotationMirrors() {
      return Collections.emptyList();
    }

    @Override
    @Nullable
    public <A extends Annotation> A getAnnotation(Class<A> aClass) {
      return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends Annotation> A[] getAnnotationsByType(Class<A> aClass) {
      return (A[]) Array.newInstance(aClass, 0);
    }

    @Override
    public <R, P> R accept(ElementVisitor<R, P> elementVisitor, P p) {
      return elementVisitor.visitVariable(this, p);
    }

    @Override
    public TypeMirror asType() {
      return asType;
    }

    @Override
    public ElementKind getKind() {
      return ElementKind.FIELD;
    }

    @Override
    public Set<Modifier> getModifiers() {
      return MODIFIERS;
    }

    @Override
    public String toString() {
      return "OPTIONAL_CONTENT";
    }
  }
}
