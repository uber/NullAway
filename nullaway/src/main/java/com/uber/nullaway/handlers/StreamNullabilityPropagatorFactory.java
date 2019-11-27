package com.uber.nullaway.handlers;
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
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.predicates.type.DescendantOf;
import com.google.errorprone.suppliers.Suppliers;
import com.uber.nullaway.handlers.stream.StreamModelBuilder;
import com.uber.nullaway.handlers.stream.StreamTypeRecord;

public class StreamNullabilityPropagatorFactory {
  public static StreamNullabilityPropagator getJavaStreamNullabilityPropagator() {
    ImmutableList<StreamTypeRecord> streamModels =
        StreamModelBuilder.start()
            .addStreamType(new DescendantOf(Suppliers.typeFromString("java.util.stream.Stream")))
            // Names of all the methods of java.util.stream.Stream that behave like .filter(...)
            // (must take exactly 1 argument)
            .withFilterMethodFromSignature("filter(java.util.function.Predicate<? super T>)")
            // Names and relevant arguments of all the methods of java.util.stream.Stream that
            // behave
            // like .map(...) for the purposes of this checker (the listed arguments are those that
            // take the potentially filtered objects from the stream)
            .withMapMethodFromSignature(
                "<R>map(java.util.function.Function<? super T,? extends R>)",
                "apply",
                ImmutableSet.of(0))
            .withMapMethodFromSignature(
                "mapToInt(java.util.function.ToIntFunction<? super T>)",
                "applyAsInt",
                ImmutableSet.of(0))
            .withMapMethodFromSignature(
                "mapToLong(java.util.function.ToLongFunction<? super T>)",
                "applyAsLong",
                ImmutableSet.of(0))
            .withMapMethodFromSignature(
                "mapToDouble(java.util.function.ToDoubleFunction<? super T>)",
                "applyAsDouble",
                ImmutableSet.of(0))
            .withMapMethodFromSignature(
                "forEach(java.util.function.Consumer<? super T>)", "accept", ImmutableSet.of(0))
            .withMapMethodFromSignature(
                "forEachOrdered(java.util.function.Consumer<? super T>)",
                "accept",
                ImmutableSet.of(0))
            .withMapMethodAllFromName("flatMap", "apply", ImmutableSet.of(0))
            // List of methods of java.util.stream.Stream through which we just propagate the
            // nullability information of the last call, e.g. m() in
            // Observable.filter(...).m().map(...) means the
            // nullability information from filter(...) should still be propagated to map(...),
            // ignoring the interleaving call to m().
            .withPassthroughMethodFromSignature("distinct()")
            // List of methods of java.util.stream.Stream that both use the nullability information
            // internally (like map does), but also don't change the values flowing through the
            // stream
            // and thus propagate
            // the nullability information of the last call.
            .end();
    return new StreamNullabilityPropagator(streamModels);
  }

  public static StreamNullabilityPropagator getRxStreamNullabilityPropagator() {
    ImmutableList<StreamTypeRecord> rxModels =
        StreamModelBuilder.start()
            .addStreamType(new DescendantOf(Suppliers.typeFromString("io.reactivex.Observable")))
            // Names of all the methods of io.reactivex.Observable that behave like .filter(...)
            // (must take exactly 1 argument)
            .withFilterMethodFromSignature("filter(io.reactivex.functions.Predicate<? super T>)")
            // Names and relevant arguments of all the methods of io.reactivex.Observable that
            // behave
            // like .map(...) for the purposes of this checker (the listed arguments are those that
            // take the potentially filtered objects from the stream)
            .withMapMethodFromSignature(
                "<R>map(io.reactivex.functions.Function<? super T,? extends R>)",
                "apply",
                ImmutableSet.of(0))
            .withMapMethodAllFromName("flatMap", "apply", ImmutableSet.of(0))
            .withMapMethodAllFromName("flatMapSingle", "apply", ImmutableSet.of(0))
            .withMapMethodFromSignature(
                "distinctUntilChanged(io.reactivex.functions.BiPredicate<? super T,? super T>)",
                "test",
                ImmutableSet.of(0, 1))
            // List of methods of io.reactivex.Observable through which we just propagate the
            // nullability information of the last call, e.g. m() in
            // Observable.filter(...).m().map(...) means the
            // nullability information from filter(...) should still be propagated to map(...),
            // ignoring the interleaving call to m().
            .withPassthroughMethodFromSignature("distinct()")
            .withPassthroughMethodFromSignature("distinctUntilChanged()")
            .withPassthroughMethodAllFromName("observeOn")
            // List of methods of io.reactivex.Observable that both use the nullability information
            // internally (like map does), but also don't change the values flowing through the
            // stream
            // and thus propagate
            // the nullability information of the last call.
            .withUseAndPassthroughMethodAllFromName("doOnNext", "accept", ImmutableSet.of(0))
            .addStreamType(new DescendantOf(Suppliers.typeFromString("io.reactivex.Maybe")))
            .withFilterMethodFromSignature("filter(io.reactivex.functions.Predicate<? super T>)")
            .withMapMethodFromSignature(
                "<R>map(io.reactivex.functions.Function<? super T,? extends R>)",
                "apply",
                ImmutableSet.of(0))
            .withMapMethodAllFromName("flatMap", "apply", ImmutableSet.of(0))
            .withMapMethodAllFromName("flatMapSingle", "apply", ImmutableSet.of(0))
            .withPassthroughMethodAllFromName("observeOn")
            .withUseAndPassthroughMethodAllFromName("doOnNext", "accept", ImmutableSet.of(0))
            .addStreamType(new DescendantOf(Suppliers.typeFromString("io.reactivex.Single")))
            .withFilterMethodFromSignature("filter(io.reactivex.functions.Predicate<? super T>)")
            .withMapMethodFromSignature(
                "<R>map(io.reactivex.functions.Function<? super T,? extends R>)",
                "apply",
                ImmutableSet.of(0))
            .withMapMethodAllFromName("flatMap", "apply", ImmutableSet.of(0))
            .withMapMethodAllFromName("flatMapSingle", "apply", ImmutableSet.of(0))
            .withPassthroughMethodAllFromName("observeOn")
            .withUseAndPassthroughMethodAllFromName("doOnNext", "accept", ImmutableSet.of(0))
            .end();

    return new StreamNullabilityPropagator(rxModels);
  }
}
