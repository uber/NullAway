/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.uber.nullaway.dataflow;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Sets.intersection;

import com.google.common.collect.ImmutableMap;
import com.google.errorprone.VisitorState;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath.IteratorContentsKey;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.lang.model.element.Element;
import org.checkerframework.nullaway.dataflow.analysis.Store;
import org.checkerframework.nullaway.dataflow.cfg.node.FieldAccessNode;
import org.checkerframework.nullaway.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.nullaway.dataflow.cfg.visualize.CFGVisualizer;
import org.checkerframework.nullaway.dataflow.expression.JavaExpression;

/**
 * Highly based on {@link com.google.errorprone.dataflow.LocalStore}, but for {@link AccessPath}s.
 */
public class NullnessStore implements Store<NullnessStore> {

  private static final NullnessStore EMPTY = new NullnessStore(ImmutableMap.of());

  private final ImmutableMap<AccessPath, Nullness> contents;

  private NullnessStore(Map<AccessPath, Nullness> contents) {
    this.contents = ImmutableMap.copyOf(contents);
  }

  /**
   * Produce an empty store.
   *
   * @return an empty store
   */
  public static NullnessStore empty() {
    return EMPTY;
  }

  /**
   * Get the nullness for a local variable.
   *
   * @param node node representing local variable
   * @param defaultValue default value if we have no fact
   * @return fact associated with local
   */
  public Nullness valueOfLocalVariable(LocalVariableNode node, Nullness defaultValue) {
    return contents.getOrDefault(AccessPath.fromLocal(node), defaultValue);
  }

  /**
   * Get the nullness of a field.
   *
   * @param node node representing field access
   * @param defaultValue default value if we have no fact
   * @return fact associated with field access
   */
  public Nullness valueOfField(
      FieldAccessNode node, Nullness defaultValue, AccessPath.AccessPathContext apContext) {
    AccessPath path = AccessPath.fromFieldAccess(node, apContext);
    if (path == null) {
      return defaultValue;
    }
    return contents.getOrDefault(path, defaultValue);
  }

  /**
   * Get the nullness of a method call.
   *
   * @param node node representing method invocation
   * @param defaultValue default value if we have no fact
   * @return fact associated with method invocation
   */
  public Nullness valueOfMethodCall(
      MethodInvocationNode node,
      VisitorState state,
      Nullness defaultValue,
      AccessPath.AccessPathContext apContext) {
    AccessPath accessPath = AccessPath.fromMethodCall(node, state, apContext);
    if (accessPath == null) {
      return defaultValue;
    }
    return contents.getOrDefault(accessPath, defaultValue);
  }

  /**
   * Get all access paths in this store with a particular nullness value.
   *
   * @param value a nullness value
   * @return all access paths in this store that have the given nullness value
   */
  public Set<AccessPath> getAccessPathsWithValue(Nullness value) {
    Set<AccessPath> result = new LinkedHashSet<>();
    for (Map.Entry<AccessPath, Nullness> entry : contents.entrySet()) {
      if (value.equals(entry.getValue())) {
        result.add(entry.getKey());
      }
    }
    return result;
  }

  /**
   * If this store maps an access path {@code p} whose map-get argument is an {@link
   * IteratorContentsKey} whose variable is {@code iteratorVar}, returns {@code p}. Otherwise,
   * returns {@code null}.
   */
  @Nullable
  public AccessPath getMapGetIteratorContentsAccessPath(LocalVariableNode iteratorVar) {
    for (AccessPath accessPath : contents.keySet()) {
      MapKey mapGetArg = accessPath.getMapGetArg();
      if (mapGetArg instanceof IteratorContentsKey) {
        IteratorContentsKey iteratorContentsKey = (IteratorContentsKey) mapGetArg;
        if (iteratorContentsKey.getIteratorVarElement().equals(iteratorVar.getElement())) {
          return accessPath;
        }
      }
    }
    return null;
  }

  /**
   * Gets the {@link Nullness} value of an access path.
   *
   * @param accessPath The access path.
   * @return The {@link Nullness} value of the access path.
   */
  public Nullness getNullnessOfAccessPath(AccessPath accessPath) {
    if (contents == null) {
      return Nullness.NULLABLE;
    }
    return contents.getOrDefault(accessPath, Nullness.NULLABLE);
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public NullnessStore copy() {
    return this;
  }

  @Override
  public NullnessStore leastUpperBound(NullnessStore other) {
    NullnessStore.Builder result = NullnessStore.empty().toBuilder();
    for (AccessPath ap : intersection(contents.keySet(), other.contents.keySet())) {
      Nullness apContents = contents.get(ap);
      if (apContents == null) {
        throw new RuntimeException("null contents for " + ap);
      }
      Nullness otherAPContents = other.contents.get(ap);
      if (otherAPContents == null) {
        throw new RuntimeException("null other contents for " + ap);
      }
      result.contents.put(ap, apContents.leastUpperBound(otherAPContents));
    }
    return result.build();
  }

  @Override
  public NullnessStore widenedUpperBound(NullnessStore vNullnessStore) {
    return leastUpperBound(vNullnessStore);
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (!(o instanceof NullnessStore)) {
      return false;
    }
    NullnessStore other = (NullnessStore) o;
    return contents.equals(other.contents);
  }

  @Override
  public int hashCode() {
    return contents.hashCode();
  }

  @Override
  public String toString() {
    return contents.toString();
  }

  @Override
  public boolean canAlias(JavaExpression a, JavaExpression b) {
    return true;
  }

  @Override
  public String visualize(CFGVisualizer<?, NullnessStore, ?> viz) {
    throw new UnsupportedOperationException();
  }

  /**
   * Takes the Access Paths rooted at specific locals in this NullnessStore and translates them to
   * paths rooted at different/renamed local variables.
   *
   * <p>This method is used to patch-around the paths inter-procedurally when handling certain
   * libraries. For example, by {@code handlers.RxNullabilityPropagator} to translate access paths
   * relative to the argument of a filter(...) method, to paths relative to the argument of a
   * map(...) method in a filter(...).map(...) chain pattern.
   *
   * @param localVarTranslations A map from local variable nodes to local variable nodes, indicating
   *     the desired re-rooting / re-naming.
   * @return A store containing only those access paths in {@code this} which are relative to
   *     variables in the domain of {@code localVarTranslations}, with each access path re-rooted to
   *     be relative to the corresponding local variable in the co-domain of the map.
   */
  public NullnessStore uprootAccessPaths(
      Map<LocalVariableNode, LocalVariableNode> localVarTranslations) {
    NullnessStore.Builder nullnessBuilder = NullnessStore.empty().toBuilder();
    for (AccessPath ap : contents.keySet()) {
      Element element = ap.getRoot();
      if (element == null) {
        // Access path is rooted at the receiver, so we don't need to uproot it
        continue;
      }
      for (LocalVariableNode fromVar : localVarTranslations.keySet()) {
        if (element.equals(fromVar.getElement())) {
          LocalVariableNode toVar = localVarTranslations.get(fromVar);
          AccessPath newAP = AccessPath.switchRoot(ap, toVar.getElement());
          nullnessBuilder.setInformation(newAP, contents.get(ap));
        }
      }
    }
    return nullnessBuilder.build();
  }

  /**
   * Get access paths matching a predicate.
   *
   * @param pred predicate over {@link AccessPath}s
   * @return NullnessStore containing only AccessPaths that pass the predicate
   */
  public NullnessStore filterAccessPaths(Predicate<AccessPath> pred) {
    return new NullnessStore(
        contents.entrySet().stream()
            .filter(e -> pred.test(e.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
  }

  /** class for building up instances of the store. */
  public static final class Builder {
    private final Map<AccessPath, Nullness> contents;

    Builder(NullnessStore prototype) {

      contents = new HashMap<>(prototype.contents);
    }

    /**
     * Sets the value for the given variable. {@code element} must come from a call to {@link
     * LocalVariableNode#getElement()} or {@link
     * org.checkerframework.nullaway.javacutil.TreeUtils#elementFromDeclaration} ({@link
     * org.checkerframework.nullaway.dataflow.cfg.node.VariableDeclarationNode#getTree()}).
     *
     * @param ap relevant access path
     * @param value fact for access path
     * @return the new builder
     */
    public NullnessStore.Builder setInformation(AccessPath ap, Nullness value) {
      contents.put(checkNotNull(ap), checkNotNull(value));
      return this;
    }

    /**
     * Construct the immutable NullnessStore instance.
     *
     * @return a store constructed from everything added to the builder
     */
    public NullnessStore build() {
      return new NullnessStore(contents);
    }
  }
}
