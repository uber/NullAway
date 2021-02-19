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
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Sets;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import com.uber.nullaway.Config;
import com.uber.nullaway.LibraryModels;
import com.uber.nullaway.LibraryModels.MethodRef;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.NullabilityUtil;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;
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

  private final Config config;
  private final LibraryModels libraryModels;

  @Nullable private OptimizedLibraryModels optLibraryModels;

  public LibraryModelsHandler(Config config) {
    super();
    this.config = config;
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
            nonNullPositions, getOptLibraryModels(state.context).nonNullParameters(methodSymbol))
        .immutableCopy();
  }

  @Override
  public ImmutableSet<Integer> onUnannotatedInvocationGetExplicitlyNullablePositions(
      Context context,
      Symbol.MethodSymbol methodSymbol,
      ImmutableSet<Integer> explicitlyNullablePositions) {
    return Sets.union(
            Sets.difference(
                explicitlyNullablePositions,
                getOptLibraryModels(context).nonNullParameters(methodSymbol)),
            getOptLibraryModels(context).explicitlyNullableParameters(methodSymbol))
        .immutableCopy();
  }

  @Override
  public boolean onOverrideMayBeNullExpr(
      NullAway analysis, ExpressionTree expr, VisitorState state, boolean exprMayBeNull) {
    if (expr.getKind() == Tree.Kind.METHOD_INVOCATION) {
      OptimizedLibraryModels optLibraryModels = getOptLibraryModels(state.context);
      Symbol.MethodSymbol methodSymbol = (Symbol.MethodSymbol) ASTHelpers.getSymbol(expr);
      if (!NullabilityUtil.isUnannotated(methodSymbol, this.config)) {
        // We only look at library models for unannotated (i.e. third-party) code.
        return exprMayBeNull;
      } else if (optLibraryModels.hasNullableReturn(methodSymbol, state.getTypes())
          || !optLibraryModels.nullImpliesNullParameters(methodSymbol).isEmpty()) {
        // These mean the method might be null, depending on dataflow and arguments. We force
        // dataflow to run.
        return analysis.nullnessFromDataflow(state, expr) || exprMayBeNull;
      } else if (optLibraryModels.hasNonNullReturn(methodSymbol, state.getTypes())) {
        // This means the method can't be null, so we return false outright.
        return false;
      }
    }
    return exprMayBeNull;
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
    Symbol.MethodSymbol callee = ASTHelpers.getSymbol(node.getTree());
    Preconditions.checkNotNull(callee);
    if (!NullabilityUtil.isUnannotated(callee, this.config)) {
      // Ignore annotated methods, library models should only apply to "unannotated" code.
      return NullnessHint.UNKNOWN;
    }
    setUnconditionalArgumentNullness(bothUpdates, node.getArguments(), callee, context);
    setConditionalArgumentNullness(thenUpdates, elseUpdates, node.getArguments(), callee, context);
    ImmutableSet<Integer> nullImpliesNullIndexes =
        getOptLibraryModels(context).nullImpliesNullParameters(callee);
    if (!nullImpliesNullIndexes.isEmpty()) {
      // If the method is marked as having argument dependent nullability and any of the
      // corresponding arguments is null, then the return is nullable. If the method is
      // marked as having argument dependent nullability but NONE of the corresponding
      // arguments is null, then the return should be non-null.
      boolean anyNull = false;
      for (int idx : nullImpliesNullIndexes) {
        if (!inputs.valueOfSubNode(node.getArgument(idx)).equals(NONNULL)) {
          anyNull = true;
        }
      }
      return anyNull ? NullnessHint.HINT_NULLABLE : NullnessHint.FORCE_NONNULL;
    }
    if (getOptLibraryModels(context).hasNonNullReturn(callee, types)) {
      return NullnessHint.FORCE_NONNULL;
    } else if (getOptLibraryModels(context).hasNullableReturn(callee, types)) {
      return NullnessHint.HINT_NULLABLE;
    } else {
      return NullnessHint.UNKNOWN;
    }
  }

  private void setConditionalArgumentNullness(
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      List<Node> arguments,
      Symbol.MethodSymbol callee,
      Context context) {
    Set<Integer> nullImpliesTrueParameters =
        getOptLibraryModels(context).nullImpliesTrueParameters(callee);
    Set<Integer> nullImpliesFalseParameters =
        getOptLibraryModels(context).nullImpliesFalseParameters(callee);
    for (AccessPath accessPath : accessPathsAtIndexes(nullImpliesTrueParameters, arguments)) {
      elseUpdates.set(accessPath, NONNULL);
    }
    for (AccessPath accessPath : accessPathsAtIndexes(nullImpliesFalseParameters, arguments)) {
      thenUpdates.set(accessPath, NONNULL);
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

  private OptimizedLibraryModels getOptLibraryModels(Context context) {
    if (optLibraryModels == null) {
      optLibraryModels = new OptimizedLibraryModels(libraryModels, context);
    }
    return optLibraryModels;
  }

  private void setUnconditionalArgumentNullness(
      AccessPathNullnessPropagation.Updates bothUpdates,
      List<Node> arguments,
      Symbol.MethodSymbol callee,
      Context context) {
    Set<Integer> requiredNonNullParameters =
        getOptLibraryModels(context).failIfNullParameters(callee);
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
            .put(methodRef("com.google.common.base.Preconditions", "<T>checkNotNull(T)"), 0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions", "<T>checkNotNull(T,java.lang.Object)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,java.lang.Object...)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,char)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,int)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,long)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,java.lang.Object)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,char,char)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,char,int)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,char,long)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,char,java.lang.Object)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,int,char)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,int,int)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,int,long)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,int,java.lang.Object)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,long,char)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,long,int)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,long,long)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,long,java.lang.Object)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,java.lang.Object,char)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,java.lang.Object,int)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,java.lang.Object,long)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,java.lang.Object,java.lang.Object)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,java.lang.Object,java.lang.Object,java.lang.Object)"),
                0)
            .put(
                methodRef(
                    "com.google.common.base.Preconditions",
                    "<T>checkNotNull(T,java.lang.String,java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object)"),
                0)
            .put(methodRef("java.util.Objects", "<T>requireNonNull(T)"), 0)
            .put(methodRef("java.util.Objects", "<T>requireNonNull(T,java.lang.String)"), 0)
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
                0)
            .put(
                methodRef(
                    "org.junit.jupiter.api.Assertions",
                    "assertNotNull(java.lang.Object,java.util.function.Supplier<java.lang.String>)"),
                0)
            .build();

    private static final ImmutableSetMultimap<MethodRef, Integer> EXPLICITLY_NULLABLE_PARAMETERS =
        new ImmutableSetMultimap.Builder<MethodRef, Integer>()
            .put(
                methodRef("android.app.Service", "onStartCommand(android.content.Intent,int,int)"),
                0)
            .put(
                methodRef(
                    "android.view.GestureDetector.OnGestureListener",
                    "onScroll(android.view.MotionEvent,android.view.MotionEvent,float,float)"),
                0)
            .build();

    private static final ImmutableSetMultimap<MethodRef, Integer> NON_NULL_PARAMETERS =
        new ImmutableSetMultimap.Builder<MethodRef, Integer>()
            .put(
                methodRef(
                    "com.android.sdklib.build.ApkBuilder",
                    "ApkBuilder(java.io.File,java.io.File,java.io.File,java.lang.String,java.io.PrintStream)"),
                0)
            .put(
                methodRef(
                    "com.android.sdklib.build.ApkBuilder",
                    "ApkBuilder(java.io.File,java.io.File,java.io.File,java.lang.String,java.io.PrintStream)"),
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
            .put(methodRef("com.google.common.base.Function", "apply(F)"), 0)
            .put(methodRef("com.google.common.base.Predicate", "apply(T)"), 0)
            .put(methodRef("com.google.common.util.concurrent.AsyncFunction", "apply(I)"), 0)
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
            .put(methodRef("java.util.Optional", "<T>of(T)"), 0)
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
            .put(methodRef("com.google.common.base.Strings", "isNullOrEmpty(java.lang.String)"), 0)
            .put(methodRef("java.util.Objects", "isNull(java.lang.Object)"), 0)
            .put(
                methodRef("org.springframework.util.ObjectUtils", "isEmpty(java.lang.Object[])"), 0)
            .put(
                methodRef(
                    "org.springframework.util.CollectionUtils", "isEmpty(java.util.Collection<?>)"),
                0)
            .put(methodRef("org.springframework.util.StringUtils", "isEmpty(java.lang.Object)"), 0)
            .put(methodRef("org.springframework.util.ObjectUtils", "isEmpty(java.lang.Object)"), 0)
            .put(methodRef("spark.utils.ObjectUtils", "isEmpty(java.lang.Object[])"), 0)
            .put(methodRef("spark.utils.CollectionUtils", "isEmpty(java.util.Collection<?>)"), 0)
            .put(methodRef("spark.utils.StringUtils", "isEmpty(java.lang.Object)"), 0)
            .put(methodRef("spark.utils.StringUtils", "isBlank(java.lang.CharSequence)"), 0)
            .put(methodRef("android.text.TextUtils", "isEmpty(java.lang.CharSequence)"), 0)
            .put(methodRef("org.apache.commons.lang.StringUtils", "isEmpty(java.lang.String)"), 0)
            .put(
                methodRef(
                    "org.apache.commons.lang3.StringUtils", "isEmpty(java.lang.CharSequence)"),
                0)
            .put(methodRef("org.apache.commons.lang.StringUtils", "isBlank(java.lang.String)"), 0)
            .put(
                methodRef(
                    "org.apache.commons.lang3.StringUtils", "isBlank(java.lang.CharSequence)"),
                0)
            .put(methodRef("org.apache.commons.lang3.ObjectUtils", "isEmpty(java.lang.Object)"), 0)
            .build();

    private static final ImmutableSetMultimap<MethodRef, Integer> NULL_IMPLIES_FALSE_PARAMETERS =
        new ImmutableSetMultimap.Builder<MethodRef, Integer>()
            .put(methodRef("java.util.Objects", "nonNull(java.lang.Object)"), 0)
            .put(
                methodRef("org.springframework.util.StringUtils", "hasLength(java.lang.String)"), 0)
            .put(methodRef("org.springframework.util.StringUtils", "hasText(java.lang.String)"), 0)
            .put(
                methodRef(
                    "org.springframework.util.StringUtils", "hasText(java.lang.CharSequence)"),
                0)
            .put(methodRef("spark.utils.CollectionUtils", "isNotEmpty(java.util.Collection<?>)"), 0)
            .put(methodRef("spark.utils.StringUtils", "isNotEmpty(java.lang.String)"), 0)
            .put(methodRef("spark.utils.StringUtils", "isNotBlank(java.lang.CharSequence)"), 0)
            .put(methodRef("spark.utils.StringUtils", "hasLength(java.lang.String)"), 0)
            .put(methodRef("spark.utils.StringUtils", "hasLength(java.lang.CharSequence)"), 0)
            .put(
                methodRef("org.apache.commons.lang.StringUtils", "isNotEmpty(java.lang.String)"), 0)
            .put(
                methodRef(
                    "org.apache.commons.lang3.StringUtils", "isNotEmpty(java.lang.CharSequence)"),
                0)
            .put(
                methodRef("org.apache.commons.lang.StringUtils", "isNotBlank(java.lang.String)"), 0)
            .put(
                methodRef(
                    "org.apache.commons.lang3.StringUtils", "isNotBlank(java.lang.CharSequence)"),
                0)
            .put(
                methodRef("org.apache.commons.lang3.ObjectUtils", "isNotEmpty(java.lang.Object)"),
                0)
            .build();

    private static final ImmutableSetMultimap<MethodRef, Integer> NULL_IMPLIES_NULL_PARAMETERS =
        new ImmutableSetMultimap.Builder<MethodRef, Integer>()
            .put(methodRef("java.util.Optional", "orElse(T)"), 0)
            .build();

    private static final ImmutableSet<MethodRef> NULLABLE_RETURNS =
        new ImmutableSet.Builder<MethodRef>()
            .add(methodRef("com.sun.source.tree.CompilationUnitTree", "getPackageName()"))
            .add(methodRef("java.lang.Throwable", "getMessage()"))
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
            .add(methodRef("android.webkit.WebView", "getUrl()"))
            .add(methodRef("android.widget.TextView", "getLayout()"))
            .build();

    private static final ImmutableSet<MethodRef> NONNULL_RETURNS =
        new ImmutableSet.Builder<MethodRef>()
            .add(methodRef("com.google.gson", "<T>fromJson(String,Class)"))
            .add(methodRef("com.google.common.base.Function", "apply(F)"))
            .add(methodRef("com.google.common.base.Predicate", "apply(T)"))
            .add(methodRef("com.google.common.util.concurrent.AsyncFunction", "apply(I)"))
            .add(methodRef("android.app.Activity", "<T>findViewById(int)"))
            .add(methodRef("android.view.View", "<T>findViewById(int)"))
            .add(methodRef("android.view.View", "getResources()"))
            .add(methodRef("android.view.ViewGroup", "getChildAt(int)"))
            .add(
                methodRef(
                    "android.content.res.Resources",
                    "getDrawable(int,android.content.res.Resources.Theme)"))
            .add(methodRef("android.support.v4.app.Fragment", "getActivity()"))
            .add(methodRef("androidx.fragment.app.Fragment", "getActivity()"))
            .add(methodRef("android.support.v4.app.Fragment", "getArguments()"))
            .add(methodRef("androidx.fragment.app.Fragment", "getArguments()"))
            .add(methodRef("android.support.v4.app.Fragment", "getContext()"))
            .add(methodRef("androidx.fragment.app.Fragment", "getContext()"))
            .add(
                methodRef(
                    "android.support.v4.app.Fragment",
                    "onCreateView(android.view.LayoutInflater,android.view.ViewGroup,android.os.Bundle)"))
            .add(
                methodRef(
                    "androidx.fragment.app.Fragment",
                    "onCreateView(android.view.LayoutInflater,android.view.ViewGroup,android.os.Bundle)"))
            .add(
                methodRef(
                    "android.support.v4.content.ContextCompat",
                    "getDrawable(android.content.Context,int)"))
            .add(
                methodRef(
                    "androidx.core.content.ContextCompat",
                    "getDrawable(android.content.Context,int)"))
            .add(methodRef("android.support.v7.app.AppCompatDialog", "<T>findViewById(int)"))
            .add(methodRef("androidx.appcompat.app.AppCompatDialog", "<T>findViewById(int)"))
            .add(
                methodRef(
                    "android.support.v7.content.res.AppCompatResources",
                    "getDrawable(android.content.Context,int)"))
            .add(
                methodRef(
                    "androidx.appcompat.content.res.AppCompatResources",
                    "getDrawable(android.content.Context,int)"))
            .add(methodRef("android.support.design.widget.TextInputLayout", "getEditText()"))
            .build();

    @Override
    public ImmutableSetMultimap<MethodRef, Integer> failIfNullParameters() {
      return FAIL_IF_NULL_PARAMETERS;
    }

    @Override
    public ImmutableSetMultimap<MethodRef, Integer> explicitlyNullableParameters() {
      return EXPLICITLY_NULLABLE_PARAMETERS;
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
    public ImmutableSetMultimap<MethodRef, Integer> nullImpliesFalseParameters() {
      return NULL_IMPLIES_FALSE_PARAMETERS;
    }

    @Override
    public ImmutableSetMultimap<MethodRef, Integer> nullImpliesNullParameters() {
      return NULL_IMPLIES_NULL_PARAMETERS;
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

    private final ImmutableSetMultimap<MethodRef, Integer> explicitlyNullableParameters;

    private final ImmutableSetMultimap<MethodRef, Integer> nonNullParameters;

    private final ImmutableSetMultimap<MethodRef, Integer> nullImpliesTrueParameters;

    private final ImmutableSetMultimap<MethodRef, Integer> nullImpliesFalseParameters;

    private final ImmutableSetMultimap<MethodRef, Integer> nullImpliesNullParameters;

    private final ImmutableSet<MethodRef> nullableReturns;

    private final ImmutableSet<MethodRef> nonNullReturns;

    public CombinedLibraryModels(Iterable<LibraryModels> models) {
      ImmutableSetMultimap.Builder<MethodRef, Integer> failIfNullParametersBuilder =
          new ImmutableSetMultimap.Builder<>();
      ImmutableSetMultimap.Builder<MethodRef, Integer> explicitlyNullableParametersBuilder =
          new ImmutableSetMultimap.Builder<>();
      ImmutableSetMultimap.Builder<MethodRef, Integer> nonNullParametersBuilder =
          new ImmutableSetMultimap.Builder<>();
      ImmutableSetMultimap.Builder<MethodRef, Integer> nullImpliesTrueParametersBuilder =
          new ImmutableSetMultimap.Builder<>();
      ImmutableSetMultimap.Builder<MethodRef, Integer> nullImpliesFalseParametersBuilder =
          new ImmutableSetMultimap.Builder<>();
      ImmutableSetMultimap.Builder<MethodRef, Integer> nullImpliesNullParametersBuilder =
          new ImmutableSetMultimap.Builder<>();
      ImmutableSet.Builder<MethodRef> nullableReturnsBuilder = new ImmutableSet.Builder<>();
      ImmutableSet.Builder<MethodRef> nonNullReturnsBuilder = new ImmutableSet.Builder<>();
      for (LibraryModels libraryModels : models) {
        for (Map.Entry<MethodRef, Integer> entry : libraryModels.failIfNullParameters().entries()) {
          failIfNullParametersBuilder.put(entry);
        }
        for (Map.Entry<MethodRef, Integer> entry :
            libraryModels.explicitlyNullableParameters().entries()) {
          explicitlyNullableParametersBuilder.put(entry);
        }
        for (Map.Entry<MethodRef, Integer> entry : libraryModels.nonNullParameters().entries()) {
          nonNullParametersBuilder.put(entry);
        }
        for (Map.Entry<MethodRef, Integer> entry :
            libraryModels.nullImpliesTrueParameters().entries()) {
          nullImpliesTrueParametersBuilder.put(entry);
        }
        for (Map.Entry<MethodRef, Integer> entry :
            libraryModels.nullImpliesFalseParameters().entries()) {
          nullImpliesFalseParametersBuilder.put(entry);
        }
        for (Map.Entry<MethodRef, Integer> entry :
            libraryModels.nullImpliesNullParameters().entries()) {
          nullImpliesNullParametersBuilder.put(entry);
        }
        for (MethodRef name : libraryModels.nullableReturns()) {
          nullableReturnsBuilder.add(name);
        }
        for (MethodRef name : libraryModels.nonNullReturns()) {
          nonNullReturnsBuilder.add(name);
        }
      }
      failIfNullParameters = failIfNullParametersBuilder.build();
      explicitlyNullableParameters = explicitlyNullableParametersBuilder.build();
      nonNullParameters = nonNullParametersBuilder.build();
      nullImpliesTrueParameters = nullImpliesTrueParametersBuilder.build();
      nullImpliesFalseParameters = nullImpliesFalseParametersBuilder.build();
      nullImpliesNullParameters = nullImpliesNullParametersBuilder.build();
      nullableReturns = nullableReturnsBuilder.build();
      nonNullReturns = nonNullReturnsBuilder.build();
    }

    @Override
    public ImmutableSetMultimap<MethodRef, Integer> failIfNullParameters() {
      return failIfNullParameters;
    }

    @Override
    public ImmutableSetMultimap<MethodRef, Integer> explicitlyNullableParameters() {
      return explicitlyNullableParameters;
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
    public ImmutableSetMultimap<MethodRef, Integer> nullImpliesFalseParameters() {
      return nullImpliesFalseParameters;
    }

    @Override
    public ImmutableSetMultimap<MethodRef, Integer> nullImpliesNullParameters() {
      return nullImpliesNullParameters;
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

  /**
   * A view of library models optimized to make lookup of {@link
   * com.sun.tools.javac.code.Symbol.MethodSymbol}s fast
   */
  private static class OptimizedLibraryModels {

    /**
     * Mapping from {@link MethodRef} to some state, where lookups first check for a matching method
     * name as an optimization. The {@link Name} data structure is used to avoid unnecessary String
     * conversions when looking up {@link com.sun.tools.javac.code.Symbol.MethodSymbol}s.
     *
     * @param <T> the type of the associated state.
     */
    private static class NameIndexedMap<T> {

      private final Map<Name, Map<MethodRef, T>> state;

      NameIndexedMap(Map<Name, Map<MethodRef, T>> state) {
        this.state = state;
      }

      @Nullable
      public T get(Symbol.MethodSymbol symbol) {
        Map<MethodRef, T> methodRefTMap = state.get(symbol.name);
        if (methodRefTMap == null) {
          return null;
        }
        MethodRef ref = MethodRef.fromSymbol(symbol);
        return methodRefTMap.get(ref);
      }

      public boolean nameNotPresent(Symbol.MethodSymbol symbol) {
        return state.get(symbol.name) == null;
      }
    }

    private final NameIndexedMap<ImmutableSet<Integer>> failIfNullParams;
    private final NameIndexedMap<ImmutableSet<Integer>> explicitlyNullableParams;
    private final NameIndexedMap<ImmutableSet<Integer>> nonNullParams;
    private final NameIndexedMap<ImmutableSet<Integer>> nullImpliesTrueParams;
    private final NameIndexedMap<ImmutableSet<Integer>> nullImpliesFalseParams;
    private final NameIndexedMap<ImmutableSet<Integer>> nullImpliesNullParams;
    private final NameIndexedMap<Boolean> nullableRet;
    private final NameIndexedMap<Boolean> nonNullRet;

    public OptimizedLibraryModels(LibraryModels models, Context context) {
      Names names = Names.instance(context);
      failIfNullParams = makeOptimizedIntSetLookup(names, models.failIfNullParameters());
      explicitlyNullableParams =
          makeOptimizedIntSetLookup(names, models.explicitlyNullableParameters());
      nonNullParams = makeOptimizedIntSetLookup(names, models.nonNullParameters());
      nullImpliesTrueParams = makeOptimizedIntSetLookup(names, models.nullImpliesTrueParameters());
      nullImpliesFalseParams =
          makeOptimizedIntSetLookup(names, models.nullImpliesFalseParameters());
      nullImpliesNullParams = makeOptimizedIntSetLookup(names, models.nullImpliesNullParameters());
      nullableRet = makeOptimizedBoolLookup(names, models.nullableReturns());
      nonNullRet = makeOptimizedBoolLookup(names, models.nonNullReturns());
    }

    public boolean hasNonNullReturn(Symbol.MethodSymbol symbol, Types types) {
      return lookupHandlingOverrides(symbol, types, nonNullRet) != null;
    }

    public boolean hasNullableReturn(Symbol.MethodSymbol symbol, Types types) {
      return lookupHandlingOverrides(symbol, types, nullableRet) != null;
    }

    ImmutableSet<Integer> failIfNullParameters(Symbol.MethodSymbol symbol) {
      return lookupImmutableSet(symbol, failIfNullParams);
    }

    ImmutableSet<Integer> explicitlyNullableParameters(Symbol.MethodSymbol symbol) {
      return lookupImmutableSet(symbol, explicitlyNullableParams);
    }

    ImmutableSet<Integer> nonNullParameters(Symbol.MethodSymbol symbol) {
      return lookupImmutableSet(symbol, nonNullParams);
    }

    ImmutableSet<Integer> nullImpliesTrueParameters(Symbol.MethodSymbol symbol) {
      return lookupImmutableSet(symbol, nullImpliesTrueParams);
    }

    ImmutableSet<Integer> nullImpliesFalseParameters(Symbol.MethodSymbol symbol) {
      return lookupImmutableSet(symbol, nullImpliesFalseParams);
    }

    ImmutableSet<Integer> nullImpliesNullParameters(Symbol.MethodSymbol symbol) {
      return lookupImmutableSet(symbol, nullImpliesNullParams);
    }

    private ImmutableSet<Integer> lookupImmutableSet(
        Symbol.MethodSymbol symbol, NameIndexedMap<ImmutableSet<Integer>> lookup) {
      ImmutableSet<Integer> result = lookup.get(symbol);
      return (result == null) ? ImmutableSet.of() : result;
    }

    private NameIndexedMap<ImmutableSet<Integer>> makeOptimizedIntSetLookup(
        Names names, ImmutableSetMultimap<MethodRef, Integer> ref2Ints) {
      return makeOptimizedLookup(names, ref2Ints.keySet(), ref2Ints::get);
    }

    private NameIndexedMap<Boolean> makeOptimizedBoolLookup(
        Names names, ImmutableSet<MethodRef> refs) {
      return makeOptimizedLookup(names, refs, (ref) -> true);
    }

    private <T> NameIndexedMap<T> makeOptimizedLookup(
        Names names, Set<MethodRef> refs, Function<MethodRef, T> getValForRef) {
      Map<Name, Map<MethodRef, T>> nameMapping = new LinkedHashMap<>();
      for (MethodRef ref : refs) {
        Name methodName = names.fromString(ref.methodName);
        Map<MethodRef, T> mapForName = nameMapping.get(methodName);
        if (mapForName == null) {
          mapForName = new LinkedHashMap<>();
          nameMapping.put(methodName, mapForName);
        }
        mapForName.put(ref, getValForRef.apply(ref));
      }
      return new NameIndexedMap<>(nameMapping);
    }

    /**
     * checks if symbol is present in the NameIndexedMap or if it overrides some method in the
     * NameIndexedMap
     */
    @Nullable
    private static Symbol.MethodSymbol lookupHandlingOverrides(
        Symbol.MethodSymbol symbol, Types types, NameIndexedMap<Boolean> optLookup) {
      if (optLookup.nameNotPresent(symbol)) {
        // no model matching the method name, so we don't need to check for overridden methods
        return null;
      }
      if (optLookup.get(symbol) != null) {
        return symbol;
      }
      for (Symbol.MethodSymbol superSymbol : ASTHelpers.findSuperMethods(symbol, types)) {
        if (optLookup.get(superSymbol) != null) {
          return superSymbol;
        }
      }
      return null;
    }
  }
}
