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

import com.google.common.collect.ImmutableMap;
import com.sun.tools.javac.code.Types;

import org.checkerframework.dataflow.analysis.AbstractValue;
import org.checkerframework.dataflow.analysis.FlowExpressions;
import org.checkerframework.dataflow.analysis.Store;
import org.checkerframework.dataflow.cfg.CFGVisualizer;
import org.checkerframework.dataflow.cfg.node.FieldAccessNode;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.lang.model.element.Element;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Sets.intersection;

/**
 * Highly based on {@link com.google.errorprone.dataflow.LocalStore}, but
 * for {@link AccessPath}s.
 *
 * @param <V>
 */
public class NullnessStore<V extends AbstractValue<V>>
        implements Store<NullnessStore<V>> {

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final NullnessStore<?> EMPTY = new NullnessStore(ImmutableMap.of());

    private final ImmutableMap<AccessPath, V> contents;

    private NullnessStore(Map<AccessPath, V> contents) {
        this.contents = ImmutableMap.copyOf(contents);
    }
    /**
     *
     * @param <V> type of facts
     * @return an empty store
     */
    @SuppressWarnings("unchecked")
    public static <V extends AbstractValue<V>> NullnessStore<V> empty() {
        return (NullnessStore<V>) EMPTY;
    }

    /**
     *
     * @param node node representing local variable
     * @param defaultValue default value if we have no fact
     * @return fact associated with local
     */
    public V valueOfLocalVariable(LocalVariableNode node, V defaultValue) {
        V result = contents.get(AccessPath.fromLocal(node));
        return result != null ? result : defaultValue;
    }

    /**
     *
     * @param node node representing field access
     * @param defaultValue default value if we have no fact
     * @return fact associated with field access
     */
    public V valueOfField(FieldAccessNode node, V defaultValue) {
        AccessPath path = AccessPath.fromFieldAccess(node);
        if (path == null) {
            return defaultValue;
        }
        V result = contents.get(path);
        return result != null ? result : defaultValue;
    }

    /**
     *
     * @param node node representing method invocation
     * @param defaultValue default value if we have no fact
     * @return fact associated with method invocation
     */
    public V valueOfMethodCall(MethodInvocationNode node, Types types, V defaultValue) {
        AccessPath accessPath = AccessPath.fromMethodCall(node, types);
        if (accessPath == null) {
            return defaultValue;
        }
        V result = contents.get(accessPath);
        return result != null ? result : defaultValue;
    }

    /**
     *
     * @param value a nullness value
     * @return all access paths in this store that have the given nullness value
     */
    public Set<AccessPath> getAccessPathsWithValue(V value) {
        Set<AccessPath> result = new LinkedHashSet<>();
        for (Map.Entry<AccessPath, V> entry : contents.entrySet()) {
            if (value.equals(entry.getValue())) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    Builder<V> toBuilder() {
        return new Builder<V>(this);
    }

    @Override
    public NullnessStore<V> copy() {
        return this;
    }

    @Override
    public NullnessStore<V> leastUpperBound(NullnessStore<V> other) {
        NullnessStore.Builder<V> result = NullnessStore.<V>empty().toBuilder();
        for (AccessPath ap : intersection(contents.keySet(), other.contents.keySet())) {
            V apContents = contents.get(ap);
            if (apContents == null) {
                throw new RuntimeException("null contents for " + ap);
            }
            V otherAPContents = other.contents.get(ap);
            if (otherAPContents == null) {
                throw new RuntimeException("null other contents for " + ap);
            }
            result.contents.put(ap, apContents.leastUpperBound(otherAPContents));
        }
        return result.build();
    }

    @Override
    public NullnessStore<V> widenedUpperBound(NullnessStore<V> vNullnessStore) {
        return leastUpperBound(vNullnessStore);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof NullnessStore)) {
            return false;
        }
        NullnessStore<?> other = (NullnessStore<?>) o;
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
    public boolean canAlias(
            FlowExpressions.Receiver receiver, FlowExpressions.Receiver receiver1) {
        return true;
    }

    @Override
    public void visualize(CFGVisualizer<?, NullnessStore<V>, ?> cfgVisualizer) {
        throw new UnsupportedOperationException();
    }

    /**
     * Takes the Access Paths rooted at specific locals in this NullnessStore and translates them to paths rooted at
     * different/renamed local variables.
     *
     * This method is used to patch-around the paths inter-procedurally when handling certain libraries. For example,
     * by {@code handlers.RxNullabilityPropagator} to translate access paths relative to the argument of a filter(...)
     * method, to paths relative to the argument of a map(...) method in a filter(...).map(...) chain pattern.
     *
     * @param localVarTranslations A map from local variable nodes to local variable nodes, indicating the desired
     * re-rooting / re-naming.
     * @return A store containing only those access paths in {@code this} which are relative to variables in the domain
     * of {@code localVarTranslations}, with each access path re-rooted to be relative to the corresponding local
     * variable in the co-domain of the map.
     */
    public NullnessStore<V> uprootAccessPaths(Map<LocalVariableNode, LocalVariableNode> localVarTranslations) {
        NullnessStore.Builder<V> nullnessBuilder = NullnessStore.<V>empty().toBuilder();
        for (AccessPath ap : contents.keySet()) {
            if (ap.getRoot().isReceiver()) {
                continue;
            }
            Element varElement = ap.getRoot().getVarElement();
            for (LocalVariableNode fromVar : localVarTranslations.keySet()) {
                if (varElement.equals(fromVar.getElement())) {
                    LocalVariableNode toVar = localVarTranslations.get(fromVar);
                    AccessPath newAP = new AccessPath(new AccessPath.Root(toVar.getElement()), ap.getElements());
                    nullnessBuilder.setInformation(newAP, contents.get(ap));
                }
            }
        }
        return nullnessBuilder.build();
    }

    /**
     * class for building up instances of the store.
     * @param <V> the type of fact
     */
    public static final class Builder<V extends AbstractValue<V>> {
        private final Map<AccessPath, V> contents;

        Builder(NullnessStore<V> prototype) {

            contents = new HashMap<>(prototype.contents);
        }

        /**
         * Sets the value for the given variable. {@code element} must come from a call to {@link
         * LocalVariableNode#getElement()} or {@link
         * org.checkerframework.javacutil.TreeUtils#elementFromDeclaration} ({@link
         * org.checkerframework.dataflow.cfg.node.VariableDeclarationNode#getTree()}).
         *
         * @param ap relevant access path
         * @param value fact for access path
         * @return the new builder
         */
        public NullnessStore.Builder<V> setInformation(AccessPath ap, V value) {
            contents.put(checkNotNull(ap), checkNotNull(value));
            return this;
        }

        /**
         *
         * @return a store constructed from everything added to the builder
         */
        public NullnessStore<V> build() {
            return new NullnessStore<>(contents);
        }
    }

}
