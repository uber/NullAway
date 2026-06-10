/*
 * Copyright (c) 2026 Uber Technologies, Inc.
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
package com.uber.nullaway.testdata;

import javax.annotation.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class NullAwayReactorSupportNegativeCases {

    static class NullableContainer<T> {
        @Nullable private T ref;

        public NullableContainer() {
            ref = null;
        }

        @Nullable
        public T get() {
            return ref;
        }

        public void set(T o) {
            ref = o;
        }
    }

    // filter then map on Flux with lambda
    private Flux<Integer> fluxFilterThenMapLambdas(Flux<String> flux) {
        return flux.filter(s -> s != null).map(s -> s.length());
    }

    // filter then map on Flux with NullableContainer
    private Flux<Integer> fluxFilterThenMapNullableContainer(
            Flux<NullableContainer<String>> flux) {
        return flux.filter(c -> c.get() != null).map(c -> c.get().length());
    }

    // filter + distinct (passthrough) + map
    private Flux<Integer> fluxFilterDistinctThenMap(Flux<String> flux) {
        return flux.filter(s -> s != null).distinct().map(s -> s.length());
    }

    // filter + distinctUntilChanged (passthrough) + map
    private Flux<Integer> fluxFilterDistinctUntilChangedThenMap(
            Flux<NullableContainer<String>> flux) {
        return flux
                .filter(c -> c.get() != null)
                .distinctUntilChanged()
                .map(c -> c.get().length());
    }

    // filter + take (passthrough) + map
    private Flux<Integer> fluxFilterTakeThenMap(Flux<String> flux) {
        return flux.filter(s -> s != null).take(10).map(s -> s.length());
    }

    // filter + skip (passthrough) + map
    private Flux<Integer> fluxFilterSkipThenMap(Flux<String> flux) {
        return flux.filter(s -> s != null).skip(1).map(s -> s.length());
    }

    // filter + doOnNext (use-and-passthrough) + map
    private Flux<Integer> fluxFilterDoOnNextThenMap(
            Flux<NullableContainer<String>> flux) {
        return flux
                .filter(c -> c.get() != null)
                .doOnNext(
                        c -> {
                            if (c.get().length() == 0) {
                                throw new RuntimeException();
                            }
                        })
                .map(c -> c.get().length());
    }

    // filter + flatMap
    private Flux<Integer> fluxFilterThenFlatMap(
            Flux<NullableContainer<String>> flux) {
        return flux
                .filter(c -> c.get() != null)
                .flatMap(c -> Flux.just(c.get().length()));
    }

    // filter + concatMap
    private Flux<Integer> fluxFilterThenConcatMap(
            Flux<NullableContainer<String>> flux) {
        return flux
                .filter(c -> c.get() != null)
                .concatMap(c -> Flux.just(c.get().length()));
    }

    // Mono: filter then map
    private Mono<Integer> monoFilterThenMap(Mono<NullableContainer<String>> mono) {
        return mono.filter(c -> c.get() != null).map(c -> c.get().length());
    }

    // Mono: filter then flatMap
    private Mono<Integer> monoFilterThenFlatMap(Mono<NullableContainer<String>> mono) {
        return mono
                .filter(c -> c.get() != null)
                .flatMap(c -> Mono.just(c.get().length()));
    }
}
