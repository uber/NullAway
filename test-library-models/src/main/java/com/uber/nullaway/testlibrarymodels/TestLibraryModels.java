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

import static com.uber.nullaway.LibraryModels.FieldRef.fieldRef;
import static com.uber.nullaway.LibraryModels.MethodRef.methodRef;
import static com.uber.nullaway.libmodel.NestedAnnotationInfo.TypePathEntry.Kind.ARRAY_ELEMENT;
import static com.uber.nullaway.libmodel.NestedAnnotationInfo.TypePathEntry.Kind.TYPE_ARGUMENT;
import static com.uber.nullaway.libmodel.NestedAnnotationInfo.TypePathEntry.Kind.WILDCARD_BOUND;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.uber.nullaway.LibraryModels;
import com.uber.nullaway.LibraryModels.PolyNullLocation;
import com.uber.nullaway.handlers.stream.StreamModelBuilder;
import com.uber.nullaway.handlers.stream.StreamTypeRecord;
import com.uber.nullaway.libmodel.NestedAnnotationInfo;
import com.uber.nullaway.libmodel.NestedAnnotationInfo.Annotation;
import com.uber.nullaway.libmodel.NestedAnnotationInfo.TypePathEntry;

@AutoService(LibraryModels.class)
public class TestLibraryModels implements LibraryModels {

  // These values contain only strings, integers, and immutable model records. They are safe to
  // share across concurrent javac invocations in the same classloader. Stream models are excluded
  // because their TypePredicates contain invocation-aware memoizing suppliers.
  private static final ImmutableSetMultimap<MethodRef, Integer> EXPLICITLY_NULLABLE_PARAMETERS =
      createExplicitlyNullableParameters();
  private static final ImmutableSetMultimap<MethodRef, Integer> NON_NULL_PARAMETERS =
      createNonNullParameters();
  private static final ImmutableSetMultimap<MethodRef, Integer> NULL_IMPLIES_FALSE_PARAMETERS =
      createNullImpliesFalseParameters();
  private static final ImmutableSetMultimap<MethodRef, MethodRef>
      ENSURES_NON_NULL_IF_TRUE_METHOD_CALLS = createEnsuresNonNullIfTrueMethodCalls();
  private static final ImmutableSet<MethodRef> NULLABLE_RETURNS = createNullableReturns();
  private static final ImmutableSetMultimap<MethodRef, Integer> CAST_TO_NON_NULL_METHODS =
      createCastToNonNullMethods();
  private static final ImmutableSet<FieldRef> NULLABLE_FIELDS = createNullableFields();
  private static final ImmutableSetMultimap<String, Integer>
      TYPE_VARIABLES_WITH_NULLABLE_UPPER_BOUNDS = createTypeVariablesWithNullableUpperBounds();
  private static final ImmutableSet<String> NULL_MARKED_CLASSES = createNullMarkedClasses();
  private static final ImmutableSetMultimap<MethodRef, Integer>
      METHOD_TYPE_VARIABLES_WITH_NULLABLE_UPPER_BOUNDS =
          createMethodTypeVariablesWithNullableUpperBounds();
  private static final ImmutableMap<MethodRef, ImmutableSetMultimap<Integer, NestedAnnotationInfo>>
      NESTED_ANNOTATIONS_FOR_METHODS = createNestedAnnotationsForMethods();
  private static final ImmutableSetMultimap<MethodRef, PolyNullLocation> POLY_NULL_LOCATIONS =
      createPolyNullLocations();

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> failIfNullParameters() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> explicitlyNullableParameters() {
    return EXPLICITLY_NULLABLE_PARAMETERS;
  }

  /** Creates the immutable explicitly-nullable parameter models used by this test provider. */
  private static ImmutableSetMultimap<MethodRef, Integer> createExplicitlyNullableParameters() {
    return ImmutableSetMultimap.of(
        methodRef(
            "com.uber.lib.unannotated.NullMarkedVarargsWithModel",
            "nullableArray(java.lang.String...)"),
        0,
        methodRef(
            "com.uber.lib.unannotated.NullMarkedVarargsWithModel",
            "bothNullable(java.lang.String...)"),
        0,
        methodRef("com.uber.lib.unannotated.UnannotatedWithModels", "isNonNull(java.lang.Object)"),
        0,
        methodRef("com.uber.lib.unannotated.Box", "orElse(T)"),
        0);
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> nonNullParameters() {
    return NON_NULL_PARAMETERS;
  }

  /** Creates the immutable non-null parameter models used by this test provider. */
  private static ImmutableSetMultimap<MethodRef, Integer> createNonNullParameters() {
    return new ImmutableSetMultimap.Builder<MethodRef, Integer>()
        .put(
            methodRef(
                "com.uber.lib.unannotated.RestrictivelyAnnotatedFIWithModelOverride",
                "apply(java.lang.Object)"),
            0)
        .put(
            methodRef(
                "com.uber.lib.unannotated.NullUnmarkedVarargsWithModel",
                "nonNullArray(java.lang.String...)"),
            0)
        .put(
            methodRef(
                "com.uber.lib.unannotated.NullUnmarkedVarargsWithModel",
                "bothNonNull(java.lang.String...)"),
            0)
        .build();
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> nullImpliesTrueParameters() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> nullImpliesFalseParameters() {
    return NULL_IMPLIES_FALSE_PARAMETERS;
  }

  /** Creates the immutable null-implies-false models used by this test provider. */
  private static ImmutableSetMultimap<MethodRef, Integer> createNullImpliesFalseParameters() {
    return ImmutableSetMultimap.of(
        methodRef("com.uber.lib.unannotated.UnannotatedWithModels", "isNonNull(java.lang.Object)"),
        0);
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> nullImpliesNullParameters() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSetMultimap<MethodRef, MethodRef> ensuresNonNullIfTrueMethodCalls() {
    return ENSURES_NON_NULL_IF_TRUE_METHOD_CALLS;
  }

  /** Creates the immutable ensures-non-null models used by this test provider. */
  private static ImmutableSetMultimap<MethodRef, MethodRef>
      createEnsuresNonNullIfTrueMethodCalls() {
    return ImmutableSetMultimap.of(
        methodRef("com.uber.lib.unannotated.CustomInterface", "hasContent()"),
        methodRef("com.uber.lib.unannotated.CustomInterface", "getContent()"));
  }

  @Override
  public ImmutableSet<MethodRef> nullableReturns() {
    return NULLABLE_RETURNS;
  }

  /** Creates the immutable nullable-return models used by this test provider. */
  private static ImmutableSet<MethodRef> createNullableReturns() {
    return ImmutableSet.of(
        methodRef("com.uber.AnnotatedWithModels", "returnsNullFromModel()"),
        methodRef("com.uber.lib.unannotated.UnannotatedWithModels", "returnsNullUnannotated()"),
        methodRef("com.uber.lib.unannotated.UnannotatedWithModels", "returnsNullUnannotated2()"),
        methodRef("com.uber.lib.unannotated.Box", "orElse(T)"),
        methodRef("com.uber.lib.unannotated.CustomInterface", "getContent()"));
  }

  @Override
  public ImmutableSet<MethodRef> nonNullReturns() {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> castToNonNullMethods() {
    return CAST_TO_NON_NULL_METHODS;
  }

  /** Creates the immutable cast-to-non-null models used by this test provider. */
  private static ImmutableSetMultimap<MethodRef, Integer> createCastToNonNullMethods() {
    return ImmutableSetMultimap.<MethodRef, Integer>builder()
        .put(
            methodRef("com.uber.nullaway.testdata.Util", "<T>castToNonNull(T,java.lang.String)"), 0)
        .put(
            methodRef(
                "com.uber.nullaway.testdata.Util", "<T>castToNonNull(java.lang.String,T,int)"),
            1)
        .put(methodRef("com.uber.Test", "<T>castToNonNull(java.lang.String,T,int)"), 1)
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

  @Override
  public ImmutableSet<FieldRef> nullableFields() {
    return NULLABLE_FIELDS;
  }

  /** Creates the immutable nullable-field models used by this test provider. */
  private static ImmutableSet<FieldRef> createNullableFields() {
    return ImmutableSet.<FieldRef>builder()
        .add(
            fieldRef("com.uber.lib.unannotated.UnannotatedWithModels", "nullableFieldUnannotated1"),
            fieldRef("com.uber.lib.unannotated.UnannotatedWithModels", "nullableFieldUnannotated2"))
        .build();
  }

  @Override
  public ImmutableSetMultimap<String, Integer> typeVariablesWithNullableUpperBounds() {
    return TYPE_VARIABLES_WITH_NULLABLE_UPPER_BOUNDS;
  }

  /** Creates the immutable class type-variable models used by this test provider. */
  private static ImmutableSetMultimap<String, Integer>
      createTypeVariablesWithNullableUpperBounds() {
    return ImmutableSetMultimap.of(
        "com.uber.lib.unannotated.ProviderNullMarkedViaModel",
        0,
        "com.uber.lib.unannotated.NestedAnnots",
        0,
        "com.uber.lib.unannotated.UnboundWildcards",
        0);
  }

  @Override
  public ImmutableSet<String> nullMarkedClasses() {
    return NULL_MARKED_CLASSES;
  }

  /** Creates the immutable null-marked class models used by this test provider. */
  private static ImmutableSet<String> createNullMarkedClasses() {
    return ImmutableSet.of(
        "com.uber.lib.unannotated.ProviderNullMarkedViaModel",
        "com.uber.lib.unannotated.LambdaBox",
        "com.uber.lib.unannotated.LambdaConsumer",
        "com.uber.lib.unannotated.LambdaModel",
        "com.uber.lib.unannotated.NestedAnnots",
        "com.uber.lib.unannotated.NullMarkedVarargsWithModel",
        "com.uber.lib.unannotated.PolyNullMethods",
        "com.uber.lib.unannotated.UnboundWildcards");
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> methodTypeVariablesWithNullableUpperBounds() {
    return METHOD_TYPE_VARIABLES_WITH_NULLABLE_UPPER_BOUNDS;
  }

  /** Creates the immutable method type-variable models used by this test provider. */
  private static ImmutableSetMultimap<MethodRef, Integer>
      createMethodTypeVariablesWithNullableUpperBounds() {
    return new ImmutableSetMultimap.Builder<MethodRef, Integer>()
        .put(methodRef("com.uber.lib.unannotated.ProviderNullMarkedViaModel", "<U>of(U)"), 0)
        .put(
            methodRef(
                "com.uber.lib.unannotated.NestedAnnots", "<T>genericMethod(java.lang.Class<T>)"),
            0)
        .put(methodRef("com.uber.lib.unannotated.PolyNullMethods", "<T,U>twoTypeVariables(T,U)"), 0)
        .put(methodRef("com.uber.lib.unannotated.PolyNullMethods", "<T,U>twoTypeVariables(T,U)"), 1)
        .put(methodRef("com.uber.lib.unannotated.PolyNullMethods", "<T,U>genericFirst(T,U)"), 0)
        .put(methodRef("com.uber.lib.unannotated.PolyNullMethods", "<T,U>genericFirst(T,U)"), 1)
        .put(methodRef("com.uber.lib.unannotated.PolyNullMethods", "<T,U>genericObject(T,U)"), 0)
        .put(methodRef("com.uber.lib.unannotated.PolyNullMethods", "<T,U>genericObject(T,U)"), 1)
        .put(
            methodRef(
                "com.uber.lib.unannotated.PolyNullMethods",
                "<T,U>genericFromSuppliers(java.util.function.Supplier<? extends T>,java.util.function.Supplier<? extends U>)"),
            0)
        .put(
            methodRef(
                "com.uber.lib.unannotated.PolyNullMethods",
                "<T,U>genericFromSuppliers(java.util.function.Supplier<? extends T>,java.util.function.Supplier<? extends U>)"),
            1)
        .build();
  }

  @Override
  public ImmutableMap<MethodRef, ImmutableSetMultimap<Integer, NestedAnnotationInfo>>
      nestedAnnotationsForMethods() {
    return NESTED_ANNOTATIONS_FOR_METHODS;
  }

  @Override
  public ImmutableSetMultimap<MethodRef, PolyNullLocation> polyNullLocations() {
    return POLY_NULL_LOCATIONS;
  }

  /** Creates polymorphic-nullness models used to test custom library-model providers. */
  private static ImmutableSetMultimap<MethodRef, PolyNullLocation> createPolyNullLocations() {
    MethodRef method =
        methodRef(
            "com.uber.lib.unannotated.PolyNullMethods",
            "first(java.util.List<java.lang.Object>,java.util.List<java.lang.Object>)");
    return new ImmutableSetMultimap.Builder<MethodRef, PolyNullLocation>()
        .put(method, new PolyNullLocation(0, ImmutableList.of(new TypePathEntry(TYPE_ARGUMENT, 0))))
        .put(method, new PolyNullLocation(1, ImmutableList.of(new TypePathEntry(TYPE_ARGUMENT, 0))))
        .put(method, new PolyNullLocation(-1, ImmutableList.of()))
        .put(
            methodRef("com.uber.lib.unannotated.PolyNullMethods", "<T,U>twoTypeVariables(T,U)"),
            new PolyNullLocation(0, ImmutableList.of()))
        .put(
            methodRef("com.uber.lib.unannotated.PolyNullMethods", "<T,U>twoTypeVariables(T,U)"),
            new PolyNullLocation(1, ImmutableList.of()))
        .put(
            methodRef("com.uber.lib.unannotated.PolyNullMethods", "<T,U>genericFirst(T,U)"),
            new PolyNullLocation(0, ImmutableList.of()))
        .put(
            methodRef("com.uber.lib.unannotated.PolyNullMethods", "<T,U>genericFirst(T,U)"),
            new PolyNullLocation(1, ImmutableList.of()))
        .put(
            methodRef("com.uber.lib.unannotated.PolyNullMethods", "<T,U>genericFirst(T,U)"),
            new PolyNullLocation(-1, ImmutableList.of()))
        .put(
            methodRef("com.uber.lib.unannotated.PolyNullMethods", "<T,U>genericObject(T,U)"),
            new PolyNullLocation(0, ImmutableList.of()))
        .put(
            methodRef("com.uber.lib.unannotated.PolyNullMethods", "<T,U>genericObject(T,U)"),
            new PolyNullLocation(1, ImmutableList.of()))
        .put(
            methodRef("com.uber.lib.unannotated.PolyNullMethods", "<T,U>genericObject(T,U)"),
            new PolyNullLocation(-1, ImmutableList.of()))
        .put(
            methodRef(
                "com.uber.lib.unannotated.PolyNullMethods",
                "<T,U>genericFromSuppliers(java.util.function.Supplier<? extends T>,java.util.function.Supplier<? extends U>)"),
            new PolyNullLocation(
                0,
                ImmutableList.of(
                    new TypePathEntry(TYPE_ARGUMENT, 0), new TypePathEntry(WILDCARD_BOUND, 0))))
        .put(
            methodRef(
                "com.uber.lib.unannotated.PolyNullMethods",
                "<T,U>genericFromSuppliers(java.util.function.Supplier<? extends T>,java.util.function.Supplier<? extends U>)"),
            new PolyNullLocation(
                1,
                ImmutableList.of(
                    new TypePathEntry(TYPE_ARGUMENT, 0), new TypePathEntry(WILDCARD_BOUND, 0))))
        .put(
            methodRef(
                "com.uber.lib.unannotated.PolyNullMethods",
                "<T,U>genericFromSuppliers(java.util.function.Supplier<? extends T>,java.util.function.Supplier<? extends U>)"),
            new PolyNullLocation(-1, ImmutableList.of()))
        .build();
  }

  /** Creates the immutable nested-annotation models used by this test provider. */
  private static ImmutableMap<MethodRef, ImmutableSetMultimap<Integer, NestedAnnotationInfo>>
      createNestedAnnotationsForMethods() {
    return new ImmutableMap.Builder<
            MethodRef, ImmutableSetMultimap<Integer, NestedAnnotationInfo>>()
        .put(
            methodRef(
                "com.uber.lib.unannotated.NestedAnnots", "<T>genericMethod(java.lang.Class<T>)"),
            ImmutableSetMultimap.of(
                0,
                new NestedAnnotationInfo(
                    Annotation.NONNULL, ImmutableList.of(new TypePathEntry(TYPE_ARGUMENT, 0)))))
        .put(
            methodRef(
                "com.uber.lib.unannotated.NestedAnnots",
                "deeplyNested(com.uber.lib.unannotated.NestedAnnots<com.uber.lib.unannotated.NestedAnnots<java.lang.String>>)"),
            ImmutableSetMultimap.of(
                0,
                new NestedAnnotationInfo(
                    Annotation.NULLABLE,
                    ImmutableList.of(
                        new TypePathEntry(TYPE_ARGUMENT, 0), new TypePathEntry(TYPE_ARGUMENT, 0)))))
        .put(
            methodRef("com.uber.lib.unannotated.NestedAnnots", "nestedArray1()"),
            ImmutableSetMultimap.of(
                -1,
                new NestedAnnotationInfo(
                    Annotation.NULLABLE,
                    ImmutableList.of(
                        new TypePathEntry(TYPE_ARGUMENT, 0),
                        new TypePathEntry(ARRAY_ELEMENT, -1),
                        new TypePathEntry(TYPE_ARGUMENT, 0)))))
        .put(
            methodRef("com.uber.lib.unannotated.NestedAnnots", "nestedArray2()"),
            ImmutableSetMultimap.of(
                -1,
                new NestedAnnotationInfo(
                    Annotation.NULLABLE, ImmutableList.of(new TypePathEntry(TYPE_ARGUMENT, 0)))))
        .put(
            methodRef(
                "com.uber.lib.unannotated.NestedAnnots",
                "wildcardUpper(com.uber.lib.unannotated.NestedAnnots<? extends java.lang.String>)"),
            ImmutableSetMultimap.of(
                0,
                new NestedAnnotationInfo(
                    Annotation.NONNULL,
                    ImmutableList.of(
                        new TypePathEntry(TYPE_ARGUMENT, 0),
                        new TypePathEntry(WILDCARD_BOUND, 0)))))
        .put(
            methodRef(
                "com.uber.lib.unannotated.NestedAnnots",
                "wildcardLower(com.uber.lib.unannotated.NestedAnnots<? super java.lang.String>)"),
            ImmutableSetMultimap.of(
                0,
                new NestedAnnotationInfo(
                    Annotation.NULLABLE,
                    ImmutableList.of(
                        new TypePathEntry(TYPE_ARGUMENT, 0),
                        new TypePathEntry(WILDCARD_BOUND, 1)))))
        .put(
            methodRef("com.uber.lib.unannotated.NestedAnnots", "wildcardUpperTypeVariable()"),
            ImmutableSetMultimap.of(
                -1,
                new NestedAnnotationInfo(
                    Annotation.NULLABLE,
                    ImmutableList.of(
                        new TypePathEntry(TYPE_ARGUMENT, 0),
                        new TypePathEntry(WILDCARD_BOUND, 0)))))
        .put(
            methodRef("com.uber.lib.unannotated.UnboundWildcards", "literalWildcard()"),
            ImmutableSetMultimap.of(
                -1,
                new NestedAnnotationInfo(
                    Annotation.NULLABLE,
                    ImmutableList.of(
                        new TypePathEntry(TYPE_ARGUMENT, 0),
                        new TypePathEntry(WILDCARD_BOUND, 0)))))
        .put(
            methodRef("com.uber.lib.unannotated.UnboundWildcards", "typeVariable()"),
            ImmutableSetMultimap.of(
                -1,
                new NestedAnnotationInfo(
                    Annotation.NULLABLE, ImmutableList.of(new TypePathEntry(TYPE_ARGUMENT, 0)))))
        .put(
            methodRef(
                "com.uber.lib.unannotated.NestedAnnots",
                "multipleArgs(com.uber.lib.unannotated.NestedAnnots<java.lang.String>,com.uber.lib.unannotated.NestedAnnots<java.lang.Integer>)"),
            ImmutableSetMultimap.of(
                1,
                new NestedAnnotationInfo(
                    Annotation.NULLABLE, ImmutableList.of(new TypePathEntry(TYPE_ARGUMENT, 0)))))
        .put(
            methodRef(
                "com.uber.lib.unannotated.LambdaModel",
                "<U>map(java.util.function.Function<java.lang.String,? extends U>)"),
            ImmutableSetMultimap.of(
                0,
                new NestedAnnotationInfo(
                    Annotation.NULLABLE,
                    ImmutableList.of(
                        new TypePathEntry(TYPE_ARGUMENT, 1),
                        new TypePathEntry(WILDCARD_BOUND, 0)))))
        .put(
            methodRef(
                "com.uber.lib.unannotated.LambdaModel",
                "apply(java.util.function.Function<java.lang.String,java.lang.String>)"),
            ImmutableSetMultimap.of(
                0,
                new NestedAnnotationInfo(
                    Annotation.NULLABLE, ImmutableList.of(new TypePathEntry(TYPE_ARGUMENT, 1)))))
        .put(
            methodRef(
                "com.uber.lib.unannotated.LambdaModel",
                "consume(com.uber.lib.unannotated.LambdaConsumer<? super java.lang.String>)"),
            ImmutableSetMultimap.of(
                0,
                new NestedAnnotationInfo(
                    Annotation.NULLABLE,
                    ImmutableList.of(
                        new TypePathEntry(TYPE_ARGUMENT, 0),
                        new TypePathEntry(WILDCARD_BOUND, 1)))))
        .put(
            methodRef(
                "com.uber.lib.unannotated.NullMarkedVarargsWithModel",
                "nullableContents(java.lang.String...)"),
            ImmutableSetMultimap.of(
                0,
                new NestedAnnotationInfo(
                    Annotation.NULLABLE, ImmutableList.of(new TypePathEntry(ARRAY_ELEMENT, -1)))))
        .put(
            methodRef(
                "com.uber.lib.unannotated.NullMarkedVarargsWithModel",
                "bothNullable(java.lang.String...)"),
            ImmutableSetMultimap.of(
                0,
                new NestedAnnotationInfo(
                    Annotation.NULLABLE, ImmutableList.of(new TypePathEntry(ARRAY_ELEMENT, -1)))))
        .put(
            methodRef(
                "com.uber.lib.unannotated.NullUnmarkedVarargsWithModel",
                "nonNullContents(java.lang.String...)"),
            ImmutableSetMultimap.of(
                0,
                new NestedAnnotationInfo(
                    Annotation.NONNULL, ImmutableList.of(new TypePathEntry(ARRAY_ELEMENT, -1)))))
        .put(
            methodRef(
                "com.uber.lib.unannotated.NullUnmarkedVarargsWithModel",
                "bothNonNull(java.lang.String...)"),
            ImmutableSetMultimap.of(
                0,
                new NestedAnnotationInfo(
                    Annotation.NONNULL, ImmutableList.of(new TypePathEntry(ARRAY_ELEMENT, -1)))))
        .build();
  }
}
