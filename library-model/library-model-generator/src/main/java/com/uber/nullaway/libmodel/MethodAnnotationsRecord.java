package com.uber.nullaway.libmodel;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

/** A record describing the annotations associated with a java method and its arguments.
 * Generates by Record
 */

public record MethodAnnotationsRecord(ImmutableSet<String> methodAnnotations,
                                      ImmutableSet<Integer> typeParamNullableUpperbounds,
                                      ImmutableMap<Integer, ImmutableSet<String>> argumentAnnotations) {

  public static MethodAnnotationsRecord create(
      ImmutableSet<String> methodAnnotations,
      ImmutableSet<Integer> typeParamNullableUpperbounds,
      ImmutableMap<Integer, ImmutableSet<String>> argumentAnnotations) {
    return new MethodAnnotationsRecord(
        methodAnnotations, typeParamNullableUpperbounds, argumentAnnotations);
  }

}
