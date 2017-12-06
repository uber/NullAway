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

import static com.uber.nullaway.LibraryModels.MemberName.member;
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
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            libraryModels
                .nonNullParameters()
                .get(LibraryModels.MemberName.fromSymbol(methodSymbol)))
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
    return exprMayBeNull;
  }

  @Override
  public Nullness onDataflowVisitMethodInvocation(
      MethodInvocationNode node,
      Types types,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    Symbol.MethodSymbol callee = ASTHelpers.getSymbol(node.getTree());
    Preconditions.checkNotNull(callee);
    setUnconditionalArgumentNullness(bothUpdates, node.getArguments(), callee);
    setConditionalArgumentNullness(thenUpdates, elseUpdates, node.getArguments(), callee);
    return LibraryModels.LibraryModelUtil.hasNullableReturn(libraryModels, callee, types)
        ? Nullness.NULLABLE
        : Nullness.NONNULL;
  }

  private void setConditionalArgumentNullness(
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      List<Node> arguments,
      Symbol.MethodSymbol callee) {
    Set<Integer> nullImpliesTrueParameters =
        libraryModels.nullImpliesTrueParameters().get(LibraryModels.MemberName.fromSymbol(callee));
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
        AccessPath ap = AccessPath.getAccessPathForNode(argument);
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
        libraryModels.failIfNullParameters().get(LibraryModels.MemberName.fromSymbol(callee));
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

    private static final ImmutableSetMultimap<MemberName, Integer> FAIL_IF_NULL_PARAMETERS =
        new ImmutableSetMultimap.Builder<MemberName, Integer>()
            .put(member(Preconditions.class, "checkNotNull"), 0)
            .build();

    private static final ImmutableSetMultimap<MemberName, Integer> NON_NULL_PARAMETERS =
        new ImmutableSetMultimap.Builder<MemberName, Integer>()
            .put(member("com.android.sdklib.build.ApkBuilder", "<init>"), 0)
            .put(member("com.android.sdklib.build.ApkBuilder", "<init>"), 1)
            .put(member("com.android.util.CommandLineParser$Mode", "process"), 0)
            .put(member("com.google.common.base.Objects$ToStringHelper", "add"), 0)
            .put(member("com.google.common.collect.ImmutableList$Builder", "add"), 0)
            .put(member("com.google.common.collect.ImmutableList$Builder", "addAll"), 0)
            .put(member("com.google.common.collect.ImmutableSortedSet$Builder", "add"), 0)
            .put(member("com.google.common.collect.Iterables", "getFirst"), 0)
            .put(member("com.google.common.util.concurrent.SettableFuture", "setException"), 0)
            .put(member("java.io.File", "<init>"), 0)
            .put(member("java.lang.Class", "getResource"), 0)
            .put(member("java.lang.Class", "isAssignableFrom"), 0)
            .put(member("java.lang.System", "getProperty"), 0)
            .put(member("java.net.URLClassLoader", "newInstance"), 0)
            .put(member("javax.lang.model.element.Element", "getAnnotation"), 0)
            .put(member("javax.lang.model.util.Elements", "getPackageElement"), 0)
            .put(member("javax.lang.model.util.Elements", "getTypeElement"), 0)
            .put(member("javax.lang.model.util.Elements", "getDocComment"), 0)
            .put(member("java.util.ArrayDeque", "addFirst"), 0)
            .put(member("java.util.ArrayDeque", "addLast"), 0)
            .put(member("java.util.ArrayDeque", "offerFirst"), 0)
            .put(member("java.util.ArrayDeque", "offerLast"), 0)
            .put(member("java.util.ArrayDeque", "add"), 0)
            .put(member("java.util.ArrayDeque", "offer"), 0)
            .put(member("java.util.ArrayDeque", "push"), 0)
            .put(member("java.util.ArrayDeque", "toArray"), 0)
            .build();

    private static final ImmutableSetMultimap<MemberName, Integer> NULL_IMPLIES_TRUE_PARAMETERS =
        new ImmutableSetMultimap.Builder<MemberName, Integer>()
            .put(member(Strings.class, "isNullOrEmpty"), 0)
            .put(member("android.text.TextUtils", "isEmpty"), 0)
            .build();

    private static final ImmutableSet<MemberName> NULLABLE_RETURNS =
        new ImmutableSet.Builder<MemberName>()
            .add(member("java.lang.ref.Reference", "get"))
            .add(member("java.lang.ref.PhantomReference", "get"))
            .add(member("java.lang.ref.SoftReference", "get"))
            .add(member("java.lang.ref.WeakReference", "get"))
            .add(member("java.util.concurrent.atomic.AtomicReference", "get"))
            .add(member("java.util.Map", "get"))
            .add(member("javax.lang.model.element.Element", "getEnclosingElement"))
            .add(member("javax.lang.model.element.ExecutableElement", "getDefaultValue"))
            .add(member("javax.lang.model.element.PackageElement", "getEnclosingElement"))
            .add(member("javax.lang.model.element.VariableElement", "getConstantValue"))
            .add(member("javax.lang.model.type.WildcardType", "getSuperBound"))
            .add(member("android.app.ActivityManager", "getRunningAppProcesses"))
            .add(member("android.view.View", "getHandler"))
            .add(member("java.lang.Throwable", "getMessage"))
            .build();

    @Override
    public ImmutableSetMultimap<MemberName, Integer> failIfNullParameters() {
      return FAIL_IF_NULL_PARAMETERS;
    }

    @Override
    public ImmutableSetMultimap<MemberName, Integer> nonNullParameters() {
      return NON_NULL_PARAMETERS;
    }

    @Override
    public ImmutableSetMultimap<MemberName, Integer> nullImpliesTrueParameters() {
      return NULL_IMPLIES_TRUE_PARAMETERS;
    }

    @Override
    public ImmutableSet<MemberName> nullableReturns() {
      return NULLABLE_RETURNS;
    }
  }

  private static class CombinedLibraryModels implements LibraryModels {

    private final ImmutableSetMultimap<MemberName, Integer> fail_if_null_parameters;

    private final ImmutableSetMultimap<MemberName, Integer> non_null_parameters;

    private final ImmutableSetMultimap<MemberName, Integer> null_implies_true_parameters;

    private final ImmutableSet<MemberName> nullable_returns;

    public CombinedLibraryModels(Iterable<LibraryModels> models) {
      ImmutableSetMultimap.Builder<MemberName, Integer> failIfNullParametersBuilder =
          new ImmutableSetMultimap.Builder<>();
      ImmutableSetMultimap.Builder<MemberName, Integer> nonNullParametersBuilder =
          new ImmutableSetMultimap.Builder<>();
      ImmutableSetMultimap.Builder<MemberName, Integer> nullImpliesTrueParametersBuilder =
          new ImmutableSetMultimap.Builder<>();
      ImmutableSet.Builder<MemberName> nullableReturnsBuilder = new ImmutableSet.Builder<>();
      for (LibraryModels libraryModels : models) {
        for (Map.Entry<MemberName, Integer> entry :
            libraryModels.failIfNullParameters().entries()) {
          failIfNullParametersBuilder.put(entry);
        }
        for (Map.Entry<MemberName, Integer> entry : libraryModels.nonNullParameters().entries()) {
          nonNullParametersBuilder.put(entry);
        }
        for (Map.Entry<MemberName, Integer> entry :
            libraryModels.nullImpliesTrueParameters().entries()) {
          nullImpliesTrueParametersBuilder.put(entry);
        }
        for (MemberName name : libraryModels.nullableReturns()) {
          nullableReturnsBuilder.add(name);
        }
      }
      fail_if_null_parameters = failIfNullParametersBuilder.build();
      non_null_parameters = nonNullParametersBuilder.build();
      null_implies_true_parameters = nullImpliesTrueParametersBuilder.build();
      nullable_returns = nullableReturnsBuilder.build();
    }

    @Override
    public ImmutableSetMultimap<MemberName, Integer> failIfNullParameters() {
      return fail_if_null_parameters;
    }

    @Override
    public ImmutableSetMultimap<MemberName, Integer> nonNullParameters() {
      return non_null_parameters;
    }

    @Override
    public ImmutableSetMultimap<MemberName, Integer> nullImpliesTrueParameters() {
      return null_implies_true_parameters;
    }

    @Override
    public ImmutableSet<MemberName> nullableReturns() {
      return nullable_returns;
    }
  }
}
