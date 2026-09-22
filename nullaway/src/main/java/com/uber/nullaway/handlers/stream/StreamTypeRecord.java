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
import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.VisitorState;
import com.google.errorprone.predicates.TypePredicate;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;

/** An immutable model describing a class from a stream-based API such as RxJava. */
public class StreamTypeRecord {

  private final TypePredicate typePredicate;

  // Names of all the methods of this type that behave like .filter(...) (must take exactly 1
  // argument)
  private final ImmutableSet<String> filterMethodSigs;
  private final ImmutableSet<String> filterMethodSimpleNames;

  // Names and relevant arguments of all the methods of this type that behave like .map(...) for
  // the purposes of this checker (the listed arguments are those that take the potentially
  // filtered objects from the stream)
  private final ImmutableMap<String, MapLikeMethodRecord> mapMethodSigToRecord;
  private final ImmutableMap<String, MapLikeMethodRecord> mapMethodSimpleNameToRecord;

  private final ImmutableMultimap<String, CollectLikeMethodRecord> collectMethodSigToRecords;

  // List of methods of java.util.stream.Stream through which we just propagate the nullability
  // information of the last call, e.g. m() in Observable.filter(...).m().map(...) means the
  // nullability information from filter(...) should still be propagated to map(...), ignoring the
  // interleaving call to m().
  // We assume that if m() is a pass-through method for Observable, so are m(a1), m(a1,a2), etc.
  // If that is ever not the case, we can use a more complex method subsignature her.
  private final ImmutableSet<String> passthroughMethodSigs;
  private final ImmutableSet<String> passthroughMethodSimpleNames;

  // Simple names of all methods modeled by this record. This supports rejecting unrelated method
  // invocations before running the more expensive receiver-type predicate.
  private final ImmutableSet<String> modeledMethodSimpleNames;

  public StreamTypeRecord(
      TypePredicate typePredicate,
      ImmutableSet<String> filterMethodSigs,
      ImmutableSet<String> filterMethodSimpleNames,
      ImmutableMap<String, MapLikeMethodRecord> mapMethodSigToRecord,
      ImmutableMap<String, MapLikeMethodRecord> mapMethodSimpleNameToRecord,
      ImmutableMultimap<String, CollectLikeMethodRecord> collectMethodSigToRecords,
      ImmutableSet<String> passthroughMethodSigs,
      ImmutableSet<String> passthroughMethodSimpleNames) {
    this.typePredicate = typePredicate;
    this.filterMethodSigs = filterMethodSigs;
    this.filterMethodSimpleNames = filterMethodSimpleNames;
    this.mapMethodSigToRecord = mapMethodSigToRecord;
    this.mapMethodSimpleNameToRecord = mapMethodSimpleNameToRecord;
    this.collectMethodSigToRecords = collectMethodSigToRecords;
    this.passthroughMethodSigs = passthroughMethodSigs;
    this.passthroughMethodSimpleNames = passthroughMethodSimpleNames;

    ImmutableSet.Builder<String> modeledMethodSimpleNamesBuilder = ImmutableSet.builder();
    modeledMethodSimpleNamesBuilder.addAll(filterMethodSimpleNames);
    modeledMethodSimpleNamesBuilder.addAll(mapMethodSimpleNameToRecord.keySet());
    modeledMethodSimpleNamesBuilder.addAll(passthroughMethodSimpleNames);
    filterMethodSigs.forEach(
        signature -> modeledMethodSimpleNamesBuilder.add(methodNameFromSignature(signature)));
    mapMethodSigToRecord
        .keySet()
        .forEach(
            signature -> modeledMethodSimpleNamesBuilder.add(methodNameFromSignature(signature)));
    collectMethodSigToRecords
        .keySet()
        .forEach(
            signature -> modeledMethodSimpleNamesBuilder.add(methodNameFromSignature(signature)));
    passthroughMethodSigs.forEach(
        signature -> modeledMethodSimpleNamesBuilder.add(methodNameFromSignature(signature)));
    this.modeledMethodSimpleNames = modeledMethodSimpleNamesBuilder.build();
  }

  /**
   * Extracts the simple method name from a modeled method signature.
   *
   * @param signature a method signature such as {@code map(Function)} or {@code <R>map(Function)}
   * @return the method's simple name
   */
  private static String methodNameFromSignature(String signature) {
    int parameterListStart = signature.indexOf('(');
    if (parameterListStart < 0) {
      throw new IllegalArgumentException("Malformed method signature: " + signature);
    }
    int typeParametersEnd = signature.lastIndexOf('>', parameterListStart);
    return signature.substring(typeParametersEnd + 1, parameterListStart);
  }

  /**
   * Returns whether this record models any overload of the method's simple name.
   *
   * <p>This is a deliberately coarse but cheap prefilter. Callers must still perform the exact
   * signature check after checking the receiver type.
   *
   * @param methodSymbol the invoked method
   * @return whether this record might model the method
   */
  public boolean hasModelForMethod(Symbol.MethodSymbol methodSymbol) {
    return modeledMethodSimpleNames.contains(methodSymbol.getSimpleName().toString());
  }

  public boolean matchesType(Type type, VisitorState state) {
    return typePredicate.apply(type, state);
  }

  public boolean isFilterMethod(Symbol.MethodSymbol methodSymbol) {
    return filterMethodSigs.contains(methodSymbol.toString())
        || filterMethodSimpleNames.contains(methodSymbol.getQualifiedName().toString());
  }

  public boolean isMapMethod(Symbol.MethodSymbol methodSymbol) {
    return mapMethodSigToRecord.containsKey(methodSymbol.toString())
        || mapMethodSimpleNameToRecord.containsKey(methodSymbol.getQualifiedName().toString());
  }

  public MapLikeMethodRecord getMaplikeMethodRecord(Symbol.MethodSymbol methodSymbol) {
    MapLikeMethodRecord record = mapMethodSigToRecord.get(methodSymbol.toString());
    if (record == null) {
      record =
          castToNonNull(
              mapMethodSimpleNameToRecord.get(methodSymbol.getQualifiedName().toString()));
    }
    return record;
  }

  public ImmutableCollection<CollectLikeMethodRecord> getCollectlikeMethodRecords(
      Symbol.MethodSymbol methodSymbol) {
    return collectMethodSigToRecords.get(methodSymbol.toString());
  }

  public boolean isPassthroughMethod(Symbol.MethodSymbol methodSymbol) {
    return passthroughMethodSigs.contains(methodSymbol.toString())
        || passthroughMethodSimpleNames.contains(methodSymbol.getQualifiedName().toString());
  }
}
