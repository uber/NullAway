package com.uber.nullaway.handlers.stream;

/*
 * Copyright (c) 2017 Uber Technologies, Inc.
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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.predicates.TypePredicate;
import com.google.errorprone.predicates.type.DescendantOf;
import com.google.errorprone.suppliers.Suppliers;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Used to produce a new list of StreamTypeRecord models, where each model represents a class from a
 * stream-based API such as RxJava.
 *
 * <p>This class should be used as:
 *
 * <p>[...] models = StreamModelBuilder.start() // Start the builder .addStreamType(...) // Add a
 * type filter matching a stream type .withX(...) // Model the type methods ... .end();
 */
public class StreamModelBuilder {

  private final List<StreamTypeRecord> typeRecords = new ArrayList<>();
  private @Nullable TypePredicate tp = null;
  private ImmutableSet.Builder<String> filterMethodSigs;
  private ImmutableSet.Builder<String> filterMethodSimpleNames;
  private ImmutableMap.Builder<String, MaplikeMethodRecord> mapMethodSigToRecord;
  private ImmutableMap.Builder<String, MaplikeMethodRecord> mapMethodSimpleNameToRecord;
  private ImmutableSet.Builder<String> passthroughMethodSigs;
  private ImmutableSet.Builder<String> passthroughMethodSimpleNames;

  private StreamModelBuilder() {
    // initialize here to avoid having the fields be @Nullable
    initializeBuilders();
  }

  /**
   * Get an empty StreamModelBuilder.
   *
   * @return An empty StreamModelBuilder.
   */
  public static StreamModelBuilder start() {
    return new StreamModelBuilder();
  }

  private void finalizeOpenStreamTypeRecord() {
    if (this.tp != null) {
      typeRecords.add(
          new StreamTypeRecord(
              this.tp,
              filterMethodSigs.build(),
              filterMethodSimpleNames.build(),
              mapMethodSigToRecord.build(),
              mapMethodSimpleNameToRecord.build(),
              passthroughMethodSigs.build(),
              passthroughMethodSimpleNames.build()));
    }
  }

  /**
   * Add a stream type to our models.
   *
   * @param tp A type predicate matching the class/interface of the type in our stream-based API.
   * @return This builder (for chaining).
   */
  public StreamModelBuilder addStreamType(TypePredicate tp) {
    finalizeOpenStreamTypeRecord();
    this.tp = tp;
    initializeBuilders();
    return this;
  }

  /**
   * Add a stream type to our models based on the type's fully qualified name.
   *
   * @param fullyQualifiedName the FQN of the class/interface in our stream-based API.
   * @return This builder (for chaining).
   */
  public StreamModelBuilder addStreamTypeFromName(String fullyQualifiedName) {
    return this.addStreamType(new DescendantOf(Suppliers.typeFromString(fullyQualifiedName)));
  }

  private void initializeBuilders() {
    this.filterMethodSigs = ImmutableSet.builder();
    this.filterMethodSimpleNames = ImmutableSet.builder();
    this.mapMethodSigToRecord = ImmutableMap.builder();
    this.mapMethodSimpleNameToRecord = ImmutableMap.builder();
    this.passthroughMethodSigs = ImmutableSet.builder();
    this.passthroughMethodSimpleNames = ImmutableSet.builder();
  }

  /**
   * Add a filter method to the last added stream type.
   *
   * @param filterMethodSig The full sub-signature (everything except the receiver type) of the
   *     filter method.
   * @return This builder (for chaining).
   */
  public StreamModelBuilder withFilterMethodFromSignature(String filterMethodSig) {
    this.filterMethodSigs.add(filterMethodSig);
    return this;
  }

  /**
   * Add all methods of the last stream type with the given simple name as filter methods.
   *
   * @param methodSimpleName The method's simple name.
   * @return This builder (for chaining).
   */
  public StreamModelBuilder withFilterMethodAllFromName(String methodSimpleName) {
    this.filterMethodSimpleNames.add(methodSimpleName);
    return this;
  }

  /**
   * Add a model for a map method to the last added stream type.
   *
   * @param methodSig The full sub-signature (everything except the receiver type) of the method.
   * @param innerMethodName The name of the inner "apply" method of the callback or functional
   *     interface that must be passed to this method.
   * @param argsFromStream The indexes (starting at 0, not counting the receiver) of all the
   *     arguments to this method that receive objects from the stream.
   * @return This builder (for chaining).
   */
  public StreamModelBuilder withMapMethodFromSignature(
      String methodSig, String innerMethodName, ImmutableSet<Integer> argsFromStream) {
    this.mapMethodSigToRecord.put(
        methodSig, new MaplikeMethodRecord(innerMethodName, argsFromStream));
    return this;
  }

  /**
   * Add all methods of the last stream type with the given simple name as map methods.
   *
   * @param methodSimpleName The method's simple name.
   * @param innerMethodName The name of the inner "apply" method of the callback or functional
   *     interface that must be passed to this method.
   * @param argsFromStream The indexes (starting at 0, not counting the receiver) of all the
   *     arguments to this method that receive objects from the stream. Must be the same for all
   *     methods with this name (else use withMapMethodFromSignature).
   * @return This builder (for chaining).
   */
  public StreamModelBuilder withMapMethodAllFromName(
      String methodSimpleName, String innerMethodName, ImmutableSet<Integer> argsFromStream) {
    this.mapMethodSimpleNameToRecord.put(
        methodSimpleName, new MaplikeMethodRecord(innerMethodName, argsFromStream));
    return this;
  }

  /**
   * Add a passthrough method to the last added stream type.
   *
   * <p>A passthrough method is a method that affects the stream but doesn't change the nullability
   * information of the elements inside the stream (e.g. in o.filter(...).sync().map(...), sync() is
   * a passthrough method if the exact same objects that are added to the stream at the end of
   * filter are those that will be consumed by map(...).
   *
   * @param passthroughMethodSig The full sub-signature (everything except the receiver type) of the
   *     method.
   * @return This builder (for chaining).
   */
  public StreamModelBuilder withPassthroughMethodFromSignature(String passthroughMethodSig) {
    this.passthroughMethodSigs.add(passthroughMethodSig);
    return this;
  }

  /**
   * Add all methods of the last stream type with the given simple name as passthrough methods.
   *
   * @param methodSimpleName The method's simple name.
   * @return This builder (for chaining).
   */
  public StreamModelBuilder withPassthroughMethodAllFromName(String methodSimpleName) {
    this.passthroughMethodSimpleNames.add(methodSimpleName);
    return this;
  }

  /**
   * Add a passthrough method that uses the value to the last added stream type.
   *
   * <p>Like a normal passthrough method, but it takes a callback which inspects but doesn't change
   * the elements flowing through the stream.
   *
   * @param passthroughMethodSig The full sub-signature (everything except the receiver type) of the
   *     method.
   * @param innerMethodName The name of the inner method of the callback or functional interface
   *     that must be passed to this method.
   * @param argsFromStream The indexes (starting at 0, not counting the receiver) of all the
   *     arguments to this method that receive objects from the stream.
   * @return This builder (for chaining).
   */
  public StreamModelBuilder withUseAndPassthroughMethodFromSignature(
      String passthroughMethodSig, String innerMethodName, ImmutableSet<Integer> argsFromStream) {
    this.mapMethodSigToRecord.put(
        passthroughMethodSig, new MaplikeMethodRecord(innerMethodName, argsFromStream));
    this.passthroughMethodSigs.add(passthroughMethodSig);
    return this;
  }

  /**
   * Add all methods of the last stream type with the given simple name as use-and-passthrough
   * methods.
   *
   * @param methodSimpleName The method's simple name.
   * @param innerMethodName The name of the inner method of the callback or functional interface
   *     that must be passed to this method.
   * @param argsFromStream The indexes (starting at 0, not counting the receiver) of all the
   *     arguments to this method that receive objects from the stream. Must be the same for all
   *     methods with this name (else use withUseAndPassthroughMethodFromSignature).
   * @return This builder (for chaining).
   */
  public StreamModelBuilder withUseAndPassthroughMethodAllFromName(
      String methodSimpleName, String innerMethodName, ImmutableSet<Integer> argsFromStream) {
    this.mapMethodSimpleNameToRecord.put(
        methodSimpleName, new MaplikeMethodRecord(innerMethodName, argsFromStream));
    this.passthroughMethodSimpleNames.add(methodSimpleName);
    return this;
  }

  /**
   * Turn the models added to this builder into a list of StreamTypeRecord objects.
   *
   * @return The finalized (immutable) models.
   */
  public ImmutableList<StreamTypeRecord> end() {
    finalizeOpenStreamTypeRecord();
    return ImmutableList.copyOf(typeRecords);
  }
}
