package com.uber.nullaway.handlers.stream;

import java.util.Set;

/** An immutable model describing a map-like method from a stream-based API such as RxJava. */
public class MaplikeMethodRecord {

  private final String innerMethodName;

  public String getInnerMethodName() {
    return innerMethodName;
  }

  private final Set<Integer> argsFromStream;

  public Set<Integer> getArgsFromStream() {
    return argsFromStream;
  }

  public MaplikeMethodRecord(String innerMethodName, Set<Integer> argsFromStream) {
    this.innerMethodName = innerMethodName;
    this.argsFromStream = argsFromStream;
  }
}
