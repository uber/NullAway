package com.uber.nullaway.libmodel;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;

/** A record describing the annotations associated with a java method and its arguments. */
public record MethodAnnotationsRecord(
    ImmutableSet<String> methodAnnotations,
    ImmutableSet<Integer> typeParamNullableUpperbounds,
    ImmutableMap<Integer, ImmutableSet<String>> argumentAnnotations,
    ImmutableSetMultimap<Integer, NestedAnnotationInfo> nestedAnnotationInfo) {

  public static MethodAnnotationsRecord create(
      ImmutableSet<String> methodAnnotations,
      ImmutableSet<Integer> typeParamNullableUpperbounds,
      ImmutableMap<Integer, ImmutableSet<String>> argumentAnnotations,
      ImmutableSetMultimap<Integer, NestedAnnotationInfo> nestedAnnotationInfo) {
    return new MethodAnnotationsRecord(
        methodAnnotations, typeParamNullableUpperbounds, argumentAnnotations, nestedAnnotationInfo);
  }
}
