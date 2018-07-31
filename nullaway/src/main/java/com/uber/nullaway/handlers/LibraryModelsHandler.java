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

import static com.uber.nullaway.LibraryModels.MethodRef.methodRef;
import static com.uber.nullaway.Nullness.NONNULL;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Sets;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.LibraryModels;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;

/**
 * This Handler deals with any methods from unannotated packages for which we need a nullability
 * model.
 *
 * <p>It loads the required LibraryModels and inserts their nullability information at the right
 * points through the flow of our analysis.
 */
public class LibraryModelsHandler extends BaseNoOpHandler {

  private final LibraryModels libraryModels;

  public LibraryModelsHandler() {
    super();
    libraryModels = loadLibraryModels();
  }

  @Override
  public ImmutableSet<Integer> onUnannotatedInvocationGetNonNullPositions(
      NullAway analysis,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol,
      List<? extends ExpressionTree> actualParams,
      ImmutableSet<Integer> nonNullPositions) {
    return Sets.union(
            nonNullPositions,
            libraryModels.nonNullParameters().get(LibraryModels.MethodRef.fromSymbol(methodSymbol)))
        .immutableCopy();
  }

  @Override
  public boolean onOverrideMayBeNullExpr(
      NullAway analysis, ExpressionTree expr, VisitorState state, boolean exprMayBeNull) {
    if (expr.getKind() == Tree.Kind.METHOD_INVOCATION
        && LibraryModels.LibraryModelUtil.hasNullableReturn(
            libraryModels, (Symbol.MethodSymbol) ASTHelpers.getSymbol(expr), state.getTypes())) {
      return analysis.nullnessFromDataflow(state, expr) || exprMayBeNull;
    }
    if (expr.getKind() == Tree.Kind.METHOD_INVOCATION
        && LibraryModels.LibraryModelUtil.hasNonNullReturn(
            libraryModels, (Symbol.MethodSymbol) ASTHelpers.getSymbol(expr), state.getTypes())) {
      return false;
    }
    return exprMayBeNull;
  }

  @Override
  public NullnessHint onDataflowVisitMethodInvocation(
      MethodInvocationNode node,
      Types types,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    Symbol.MethodSymbol callee = ASTHelpers.getSymbol(node.getTree());
    Preconditions.checkNotNull(callee);
    setUnconditionalArgumentNullness(bothUpdates, node.getArguments(), callee);
    setConditionalArgumentNullness(thenUpdates, elseUpdates, node.getArguments(), callee);
    if (LibraryModels.LibraryModelUtil.hasNonNullReturn(libraryModels, callee, types)) {
      return NullnessHint.FORCE_NONNULL;
    } else if (LibraryModels.LibraryModelUtil.hasNullableReturn(libraryModels, callee, types)) {
      return NullnessHint.HINT_NULLABLE;
    } else {
      return NullnessHint.UNKNOWN;
    }
  }

  private void setConditionalArgumentNullness(
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      List<Node> arguments,
      Symbol.MethodSymbol callee) {
    Set<Integer> nullImpliesTrueParameters =
        libraryModels.nullImpliesTrueParameters().get(LibraryModels.MethodRef.fromSymbol(callee));
    for (AccessPath accessPath : accessPathsAtIndexes(nullImpliesTrueParameters, arguments)) {
      elseUpdates.set(accessPath, NONNULL);
    }
  }

  private static Iterable<AccessPath> accessPathsAtIndexes(
      Set<Integer> indexes, List<Node> arguments) {
    List<AccessPath> result = new ArrayList<>();
    for (Integer i : indexes) {
      Preconditions.checkArgument(i >= 0 && i < arguments.size(), "Invalid argument index: " + i);
      if (i >= 0 && i < arguments.size()) {
        Node argument = arguments.get(i);
        AccessPath ap = AccessPath.getAccessPathForNodeNoMapGet(argument);
        if (ap != null) {
          result.add(ap);
        }
      }
    }
    return result;
  }

  private void setUnconditionalArgumentNullness(
      AccessPathNullnessPropagation.Updates bothUpdates,
      List<Node> arguments,
      Symbol.MethodSymbol callee) {
    Set<Integer> requiredNonNullParameters =
        libraryModels.failIfNullParameters().get(LibraryModels.MethodRef.fromSymbol(callee));
    for (AccessPath accessPath : accessPathsAtIndexes(requiredNonNullParameters, arguments)) {
      bothUpdates.set(accessPath, NONNULL);
    }
  }

  private static LibraryModels loadLibraryModels() {
    Iterable<LibraryModels> externalLibraryModels =
        ServiceLoader.load(LibraryModels.class, LibraryModels.class.getClassLoader());
    ImmutableSet.Builder<LibraryModels> libModelsBuilder = new ImmutableSet.Builder<>();
    libModelsBuilder.add(new DefaultLibraryModels()).addAll(externalLibraryModels);
    return new CombinedLibraryModels(libModelsBuilder.build());
  }

  private static class DefaultLibraryModels implements LibraryModels {

    private static final ImmutableSetMultimap<MethodRef, Integer> FAIL_IF_NULL_PARAMETERS =
        new ImmutableSetMultimap.Builder<MethodRef, Integer>()
            .put(methodRef(Preconditions.class, "<T>checkNotNull(T)"), 0)
            .put(methodRef("java.util.Objects", "<T>requireNonNull(T)"), 0)
            .put(methodRef("org.junit.Assert", "assertNotNull(java.lang.Object)"), 0)
            .put(
                methodRef("org.junit.Assert", "assertNotNull(java.lang.String,java.lang.Object)"),
                1)
            .put(
                methodRef("org.junit.jupiter.api.Assertions", "assertNotNull(java.lang.Object)"), 0)
            .put(
                methodRef(
                    "org.junit.jupiter.api.Assertions",
                    "assertNotNull(java.lang.Object,java.lang.String)"),
                1)
            .put(
                methodRef(
                    "org.junit.jupiter.api.Assertions",
                    "assertNotNull(java.lang.Object,java.util.function.Supplier<String>)"),
                1)
            .build();

    private static final ImmutableSetMultimap<MethodRef, Integer> NON_NULL_PARAMETERS =
        new ImmutableSetMultimap.Builder<MethodRef, Integer>()
            .put(
                methodRef(
                    "com.android.sdklib.build.ApkBuilder",
                    "ApkBuilder(java.io.File,java.io.File,java.io.File,java.lang.String,java.io.PrintStream))"),
                0)
            .put(
                methodRef(
                    "com.android.sdklib.build.ApkBuilder",
                    "ApkBuilder(java.io.File,java.io.File,java.io.File,java.lang.String,java.io.PrintStream))"),
                1)
            .put(methodRef("com.google.common.collect.ImmutableList.Builder", "add(E)"), 0)
            .put(
                methodRef(
                    "com.google.common.collect.ImmutableList.Builder",
                    "addAll(java.lang.Iterable<? extends E>)"),
                0)
            .put(methodRef("com.google.common.collect.ImmutableSet.Builder", "add(E)"), 0)
            .put(
                methodRef(
                    "com.google.common.collect.ImmutableSet.Builder",
                    "addAll(java.lang.Iterable<? extends E>)"),
                0)
            .put(methodRef("com.google.common.collect.ImmutableSortedSet.Builder", "add(E)"), 0)
            .put(
                methodRef(
                    "com.google.common.collect.ImmutableSortedSet.Builder",
                    "addAll(java.lang.Iterable<? extends E>)"),
                0)
            .put(
                methodRef(
                    "com.google.common.collect.Iterables",
                    "<T>getFirst(java.lang.Iterable<? extends T>,T)"),
                0)
            .put(
                methodRef(
                    "com.google.common.util.concurrent.SettableFuture",
                    "setException(java.lang.Throwable)"),
                0)
            .put(methodRef("java.io.File", "File(java.lang.String)"), 0)
            .put(methodRef("java.lang.Class", "getResource(java.lang.String)"), 0)
            .put(methodRef("java.lang.Class", "isAssignableFrom(java.lang.Class<?>)"), 0)
            .put(methodRef("java.lang.System", "getProperty(java.lang.String)"), 0)
            .put(
                methodRef(
                    "java.net.URLClassLoader", "newInstance(java.net.URL[],java.lang.ClassLoader)"),
                0)
            .put(
                methodRef(
                    "javax.lang.model.element.Element", "<A>getAnnotation(java.lang.Class<A>)"),
                0)
            .put(
                methodRef(
                    "javax.lang.model.util.Elements", "getPackageElement(java.lang.CharSequence)"),
                0)
            .put(
                methodRef(
                    "javax.lang.model.util.Elements", "getTypeElement(java.lang.CharSequence)"),
                0)
            .put(
                methodRef(
                    "javax.lang.model.util.Elements",
                    "getDocComment(javax.lang.model.element.Element)"),
                0)
            .put(methodRef("java.util.Deque", "addFirst(E)"), 0)
            .put(methodRef("java.util.Deque", "addLast(E)"), 0)
            .put(methodRef("java.util.Deque", "offerFirst(E)"), 0)
            .put(methodRef("java.util.Deque", "offerLast(E)"), 0)
            .put(methodRef("java.util.Deque", "add(E)"), 0)
            .put(methodRef("java.util.Deque", "offer(E)"), 0)
            .put(methodRef("java.util.Deque", "push(E)"), 0)
            .put(methodRef("java.util.Collection", "<T>toArray(T[])"), 0)
            .put(methodRef("java.util.ArrayDeque", "addFirst(E)"), 0)
            .put(methodRef("java.util.ArrayDeque", "addLast(E)"), 0)
            .put(methodRef("java.util.ArrayDeque", "offerFirst(E)"), 0)
            .put(methodRef("java.util.ArrayDeque", "offerLast(E)"), 0)
            .put(methodRef("java.util.ArrayDeque", "add(E)"), 0)
            .put(methodRef("java.util.ArrayDeque", "offer(E)"), 0)
            .put(methodRef("java.util.ArrayDeque", "push(E)"), 0)
            .put(methodRef("java.util.ArrayDeque", "<T>toArray(T[])"), 0)
            .build();

    private static final ImmutableSetMultimap<MethodRef, Integer> NULL_IMPLIES_TRUE_PARAMETERS =
        new ImmutableSetMultimap.Builder<MethodRef, Integer>()
            .put(methodRef(Strings.class, "isNullOrEmpty(java.lang.String)"), 0)
            .put(methodRef(Objects.class, "isNull(java.lang.Object)"), 0)
            .put(methodRef("android.text.TextUtils", "isEmpty(java.lang.CharSequence)"), 0)
            .build();

    private static final ImmutableSet<MethodRef> NULLABLE_RETURNS =
        new ImmutableSet.Builder<MethodRef>()
            .add(methodRef("java.lang.ref.Reference", "get()"))
            .add(methodRef("java.lang.ref.PhantomReference", "get()"))
            .add(methodRef("java.lang.ref.SoftReference", "get()"))
            .add(methodRef("java.lang.ref.WeakReference", "get()"))
            .add(methodRef("java.util.concurrent.atomic.AtomicReference", "get()"))
            .add(methodRef("java.util.Map", "get(java.lang.Object)"))
            .add(methodRef("javax.lang.model.element.Element", "getEnclosingElement()"))
            .add(methodRef("javax.lang.model.element.ExecutableElement", "getDefaultValue()"))
            .add(methodRef("javax.lang.model.element.PackageElement", "getEnclosingElement()"))
            .add(methodRef("javax.lang.model.element.VariableElement", "getConstantValue()"))
            .add(methodRef("javax.lang.model.type.WildcardType", "getSuperBound()"))
            .add(methodRef("android.app.ActivityManager", "getRunningAppProcesses()"))
            .add(methodRef("android.view.View", "getHandler()"))
            .add(methodRef("java.lang.Throwable", "getMessage()"))
            .add(methodRef("android.webkit.WebView", "getUrl()"))
            .build();

    private static final ImmutableSet<MethodRef> NONNULL_RETURNS =
        new ImmutableSet.Builder<MethodRef>()
            .add(methodRef("android.view.View", "<T>findViewById(int)"))
            .add(methodRef("android.app.Activity", "<T>findViewById(int)"))
            .add(
                methodRef(
                    "android.content.res.Resources",
                    "getDrawable(int,android.content.res.Resources.Theme)"))
            .add(methodRef("android.support.v4.app.Fragment", "getActivity()"))
            .add(methodRef("android.support.v4.app.Fragment", "getArguments()"))
            .add(methodRef("android.support.v4.app.Fragment", "getContext()"))
            .add(
                methodRef(
                    "android.support.v4.app.Fragment",
                    "onCreateView(android.view.LayoutInflater,android.view.ViewGroup,android.os.Bundle)"))
            .add(
                methodRef(
                    "android.support.v4.content.ContextCompat",
                    "getDrawable(android.content.Context,int)"))
            .add(methodRef("android.support.v7.app.AppCompatDialog", "<T>findViewById(int)"))
            .add(methodRef("android.support.design.widget.TextInputLayout", "getEditText()"))
            .build();

    @Override
    public ImmutableSetMultimap<MethodRef, Integer> failIfNullParameters() {
      return FAIL_IF_NULL_PARAMETERS;
    }

    @Override
    public ImmutableSetMultimap<MethodRef, Integer> nonNullParameters() {
      return NON_NULL_PARAMETERS;
    }

    @Override
    public ImmutableSetMultimap<MethodRef, Integer> nullImpliesTrueParameters() {
      return NULL_IMPLIES_TRUE_PARAMETERS;
    }

    @Override
    public ImmutableSet<MethodRef> nullableReturns() {
      return NULLABLE_RETURNS;
    }

    @Override
    public ImmutableSet<MethodRef> nonNullReturns() {
      return NONNULL_RETURNS;
    }
  }

  private static class CombinedLibraryModels implements LibraryModels {

    private final ImmutableSetMultimap<MethodRef, Integer> failIfNullParameters;

    private final ImmutableSetMultimap<MethodRef, Integer> nonNullParameters;

    private final ImmutableSetMultimap<MethodRef, Integer> nullImpliesTrueParameters;

    private final ImmutableSet<MethodRef> nullableReturns;

    private final ImmutableSet<MethodRef> nonNullReturns;

    public CombinedLibraryModels(Iterable<LibraryModels> models) {
      ImmutableSetMultimap.Builder<MethodRef, Integer> failIfNullParametersBuilder =
          new ImmutableSetMultimap.Builder<>();
      ImmutableSetMultimap.Builder<MethodRef, Integer> nonNullParametersBuilder =
          new ImmutableSetMultimap.Builder<>();
      ImmutableSetMultimap.Builder<MethodRef, Integer> nullImpliesTrueParametersBuilder =
          new ImmutableSetMultimap.Builder<>();
      ImmutableSet.Builder<MethodRef> nullableReturnsBuilder = new ImmutableSet.Builder<>();
      ImmutableSet.Builder<MethodRef> nonNullReturnsBuilder = new ImmutableSet.Builder<>();
      for (LibraryModels libraryModels : models) {
        for (Map.Entry<MethodRef, Integer> entry : libraryModels.failIfNullParameters().entries()) {
          failIfNullParametersBuilder.put(entry);
        }
        for (Map.Entry<MethodRef, Integer> entry : libraryModels.nonNullParameters().entries()) {
          nonNullParametersBuilder.put(entry);
        }
        for (Map.Entry<MethodRef, Integer> entry :
            libraryModels.nullImpliesTrueParameters().entries()) {
          nullImpliesTrueParametersBuilder.put(entry);
        }
        for (MethodRef name : libraryModels.nullableReturns()) {
          nullableReturnsBuilder.add(name);
        }
        for (MethodRef name : libraryModels.nonNullReturns()) {
          nonNullReturnsBuilder.add(name);
        }
      }
      failIfNullParameters = failIfNullParametersBuilder.build();
      nonNullParameters = nonNullParametersBuilder.build();
      nullImpliesTrueParameters = nullImpliesTrueParametersBuilder.build();
      nullableReturns = nullableReturnsBuilder.build();
      nonNullReturns = nonNullReturnsBuilder.build();
    }

    @Override
    public ImmutableSetMultimap<MethodRef, Integer> failIfNullParameters() {
      return failIfNullParameters;
    }

    @Override
    public ImmutableSetMultimap<MethodRef, Integer> nonNullParameters() {
      return nonNullParameters;
    }

    @Override
    public ImmutableSetMultimap<MethodRef, Integer> nullImpliesTrueParameters() {
      return nullImpliesTrueParameters;
    }

    @Override
    public ImmutableSet<MethodRef> nullableReturns() {
      return nullableReturns;
    }

    @Override
    public ImmutableSet<MethodRef> nonNullReturns() {
      return nonNullReturns;
    }
  }
}
