package com.uber.nullaway.libmodel;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

/** A record describing the annotations associated with a java method and its arguments. */
@AutoValue
public abstract class MethodAnnotationsRecord {

  public static MethodAnnotationsRecord create(
      ImmutableSet<String> methodAnnotations,
      ImmutableSet<Integer> typeParamNullableUpperbounds,
      ImmutableMap<Integer, ImmutableSet<String>> argumentAnnotations) {
    return new AutoValue_MethodAnnotationsRecord(
        methodAnnotations, typeParamNullableUpperbounds, argumentAnnotations);
  }

  abstract ImmutableSet<String> methodAnnotations();

  abstract ImmutableSet<Integer> typeParamNullableUpperbounds();

  abstract ImmutableMap<Integer, ImmutableSet<String>> argumentAnnotations();
}
