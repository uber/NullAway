package com.uber.nullaway.handlers;

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
