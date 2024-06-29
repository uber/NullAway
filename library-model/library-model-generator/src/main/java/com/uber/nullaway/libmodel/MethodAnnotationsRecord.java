package com.uber.nullaway.libmodel;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

/** A record describing the annotations associated with a java method and its arguments. */
@AutoValue
public abstract class MethodAnnotationsRecord {

  public static MethodAnnotationsRecord create(
      ImmutableSet<String> methodAnnotations,
      ImmutableMap<Integer, ImmutableSet<String>> argumentAnnotations) {
    return new AutoValue_MethodAnnotationsRecord(methodAnnotations, argumentAnnotations);
  }

  abstract ImmutableSet<String> methodAnnotations();

  abstract ImmutableMap<Integer, ImmutableSet<String>> argumentAnnotations();
}
