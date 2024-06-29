/*
 * Copyright (c) 2024 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.uber.nullaway.handlers.stream;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import java.util.function.Function;
import java.util.stream.Collector;

/**
 * An immutable model describing a collect-like method from a stream-based API, such as {@link
 * java.util.stream.Stream#collect(Collector)}. Such methods are distinguished from map-like methods
 * in that they take a {@link Collector} instance as an argument. We match specific factory methods
 * that create a {@link Collector} and track the arguments to those factory methods.
 */
@AutoValue
public abstract class CollectLikeMethodRecord implements MapOrCollectLikeMethodRecord {

  public static CollectLikeMethodRecord create(
      String collectorFactoryMethodClass,
      String collectorFactoryMethodSignature,
      ImmutableSet<Integer> argsToCollectorFactoryMethod,
      String innerMethodName,
      ImmutableSet<Integer> argsFromStream) {
    return new AutoValue_CollectLikeMethodRecord(
        collectorFactoryMethodClass,
        collectorFactoryMethodSignature,
        argsToCollectorFactoryMethod,
        innerMethodName,
        argsFromStream);
  }

  /**
   * The fully qualified name of the class that contains the collector factory method, e.g., {@code
   * java.util.stream.Collectors}.
   */
  public abstract String collectorFactoryMethodClass();

  /**
   * The signature of the factory method that creates the {@link Collector} instance passed to the
   * collect method, e.g., the signature of {@link java.util.stream.Collectors#toMap(Function,
   * Function)}
   */
  public abstract String collectorFactoryMethodSignature();

  /**
   * The indices of the arguments to the collector factory method that are lambdas (or anonymous
   * classes) which get invoked with the elements of the stream
   */
  public abstract ImmutableSet<Integer> argsToCollectorFactoryMethod();

  /**
   * Name of the method that gets passed the elements of the stream, e.g., "apply" for an anonymous
   * class implementing {@link Function}. We assume that all such methods have the same name.
   */
  @Override
  public abstract String innerMethodName();

  /**
   * Argument indices to which stream elements are directly passed. We assume the same indices are
   * used for all methods getting passed elements from the stream.
   */
  @Override
  public abstract ImmutableSet<Integer> argsFromStream();
}
