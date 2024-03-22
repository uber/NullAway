package com.uber.nullaway.handlers.stream;

import com.google.common.collect.ImmutableSet;

/** Common information needed for map-like and collect-like stream methods. */
public interface MapOrCollectLikeMethodRecord {

  /** Name of the method that gets passed the elements of the stream */
  String innerMethodName();

  /** Indices of the arguments to the inner method that are passed the elements of the stream */
  ImmutableSet<Integer> argsFromStream();
}
