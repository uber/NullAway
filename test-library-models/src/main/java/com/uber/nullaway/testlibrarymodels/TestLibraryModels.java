/*
 * Copyright (C) 2017. Uber Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.uber.nullaway.testlibrarymodels;

import static com.uber.nullaway.LibraryModels.MethodRef.methodRef;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.uber.nullaway.LibraryModels;
import com.uber.nullaway.handlers.stream.StreamModelBuilder;
import com.uber.nullaway.handlers.stream.StreamTypeRecord;

@AutoService(LibraryModels.class)
public class TestLibraryModels implements LibraryModels {

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> failIfNullParameters() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> explicitlyNullableParameters() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> nonNullParameters() {
    return new ImmutableSetMultimap.Builder<MethodRef, Integer>()
        .put(
            methodRef(
                "com.uber.lib.unannotated.RestrictivelyAnnotatedFIWithModelOverride",
                "apply(java.lang.Object)"),
            0)
        .build();
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> nullImpliesTrueParameters() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> nullImpliesFalseParameters() {
    return ImmutableSetMultimap.of(
        methodRef("com.uber.lib.unannotated.UnannotatedWithModels", "isNonNull(java.lang.Object)"),
        0);
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> nullImpliesNullParameters() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSet<MethodRef> nullableReturns() {
    return ImmutableSet.of(
        methodRef("com.uber.AnnotatedWithModels", "returnsNullFromModel()"),
        methodRef("com.uber.lib.unannotated.UnannotatedWithModels", "returnsNullUnannotated()"),
        methodRef("com.uber.lib.unannotated.UnannotatedWithModels", "returnsNullUnannotated2()"));
  }

  @Override
  public ImmutableSet<MethodRef> nonNullReturns() {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> castToNonNullMethods() {
    return ImmutableSetMultimap.<MethodRef, Integer>builder()
        .put(
            methodRef("com.uber.nullaway.testdata.Util", "<T>castToNonNull(T,java.lang.String)"), 0)
        .put(
            methodRef(
                "com.uber.nullaway.testdata.Util", "<T>castToNonNull(java.lang.String,T,int)"),
            1)
        .build();
  }

  @Override
  public ImmutableList<StreamTypeRecord> customStreamNullabilitySpecs() {
    // Identical to the default model for java.util.stream.Stream, but with the original type
    // renamed
    return StreamModelBuilder.start()
        .addStreamTypeFromName("com.uber.nullaway.testdata.unannotated.CustomStream")
        .withFilterMethodFromSignature("filter(java.util.function.Predicate<? super T>)")
        .withMapMethodFromSignature(
            "<R>map(java.util.function.Function<? super T,? extends R>)",
            "apply",
            ImmutableSet.of(0))
        .withMapMethodFromSignature(
            "mapToInt(java.util.function.ToIntFunction<? super T>)",
            "applyAsInt",
            ImmutableSet.of(0))
        .withMapMethodFromSignature(
            "mapToLong(java.util.function.ToLongFunction<? super T>)",
            "applyAsLong",
            ImmutableSet.of(0))
        .withMapMethodFromSignature(
            "mapToDouble(java.util.function.ToDoubleFunction<? super T>)",
            "applyAsDouble",
            ImmutableSet.of(0))
        .withMapMethodFromSignature(
            "forEach(java.util.function.Consumer<? super T>)", "accept", ImmutableSet.of(0))
        .withMapMethodFromSignature(
            "forEachOrdered(java.util.function.Consumer<? super T>)", "accept", ImmutableSet.of(0))
        .withMapMethodAllFromName("flatMap", "apply", ImmutableSet.of(0))
        .withPassthroughMethodFromSignature("distinct()")
        .end();
  }
}
