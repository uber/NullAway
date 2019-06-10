package com.uber.nullaway.jarinfer;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

/** A record describing the annotations associated with a java method and its arguments. */
final class MethodAnnotationsRecord {
  private final ImmutableSet<String> methodAnnotations;
  // 0 means receiver
  private final ImmutableMap<Integer, ImmutableSet<String>> argumentAnnotations;

  MethodAnnotationsRecord(
      ImmutableSet<String> methodAnnotations,
      ImmutableMap<Integer, ImmutableSet<String>> argumentAnnotations) {
    this.methodAnnotations = methodAnnotations;
    this.argumentAnnotations = argumentAnnotations;
  }

  ImmutableSet<String> getMethodAnnotations() {
    return methodAnnotations;
  }

  ImmutableMap<Integer, ImmutableSet<String>> getArgumentAnnotations() {
    return argumentAnnotations;
  }
}
